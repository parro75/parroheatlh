"""Application unique : POST /ingest (téléphone) + MCP streamable HTTP sur /mcp/<secret> (Claude)."""

from __future__ import annotations

import hmac
import json
import logging
from collections.abc import Callable
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Annotated, Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings
from mcp_types import ToolAnnotations
from pydantic import Field

from . import __version__, config, queries
from .db import RecordError, Store

log = logging.getLogger("shc")

MAX_BODY = 20 * 1024 * 1024
MAX_RECORDS = 20_000

INSTRUCTIONS = (
    "Données de santé personnelles de l'utilisateur, issues de Samsung Health (Galaxy Watch) "
    "via Health Connect sur son téléphone. Lecture seule. Dates au format AAAA-MM-JJ ; "
    "sans dates, les 7 derniers jours. Une nuit de sommeil est rattachée au jour du réveil. "
    "Appeler get_sync_status pour savoir jusqu'où les données sont à jour avant de conclure "
    "à une absence de données."
)

DateArg = Annotated[str | None, Field(description="AAAA-MM-JJ (inclus)", pattern=r"^\d{4}-\d{2}-\d{2}$")]
READ_ONLY = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)


def build_mcp(store: Store, settings: config.Settings) -> MCPServer:
    mcp = MCPServer(name="samsung-health", version=__version__, instructions=INSTRUCTIONS)

    def run(fn: Callable[..., dict[str, Any]], start: str | None, end: str | None) -> dict[str, Any]:
        today = datetime.now(settings.tz).date()
        try:
            d0, d1 = queries.date_range(start, end, today)
        except queries.QueryError as exc:
            raise ToolError(str(exc)) from exc
        return fn(store, d0, d1)

    @mcp.tool(annotations=READ_ONLY)
    def get_daily_steps(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Pas par jour (total quotidien dédoublonné par Health Connect), total et moyenne de la période."""
        return run(queries.daily_steps, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_sleep(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Sessions de sommeil : coucher, réveil, temps au lit, temps endormi et minutes par stade
        (profond, paradoxal/REM, léger, éveil). Date = jour du réveil."""
        return run(queries.sleep, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_heart_rate(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Fréquence cardiaque par jour : min, moyenne, max et FC au repos (bpm)."""
        return run(queries.heart_rate, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_hrv(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Variabilité cardiaque (HRV, RMSSD en ms) par jour : moyenne, min, max, nombre de mesures."""
        return run(queries.hrv, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_body_measurements(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Poids (kg) et masse grasse (%) : dernière mesure de chaque jour."""
        return run(queries.body, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_spo2(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Saturation en oxygène (SpO2, %) par jour : moyenne, min, max."""
        return run(queries.spo2, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_workouts(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Séances d'exercice : type, durée, distance, calories, FC moyenne/max, application source."""
        return run(queries.workouts, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_sync_status() -> dict[str, Any]:
        """Dernière synchronisation reçue du téléphone et, par type de donnée, nombre d'enregistrements
        et premier/dernier jour couverts."""
        return {"timezone": str(settings.tz), **store.status()}

    return mcp


def create_app(settings: config.Settings | None = None) -> FastAPI:
    settings = settings or config.load()
    store = Store(settings.db_path, settings.tz)
    mcp = build_mcp(store, settings)
    security = TransportSecuritySettings(
        enable_dns_rebinding_protection=bool(settings.allowed_hosts),
        allowed_hosts=settings.allowed_hosts,
        allowed_origins=settings.allowed_origins,
    )
    mcp_app = mcp.streamable_http_app(
        streamable_http_path=settings.mcp_path,
        stateless_http=True,
        json_response=True,
        transport_security=security,
        host="0.0.0.0",
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        async with mcp_app.router.lifespan_context(mcp_app):
            yield
        store.close()

    app = FastAPI(title="samsung-health-connector", version=__version__, lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store = store
    expected = f"Bearer {settings.ingest_token}".encode()

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/ingest")
    async def ingest(request: Request) -> JSONResponse:
        auth = request.headers.get("authorization", "").encode()
        if not hmac.compare_digest(auth, expected):
            raise HTTPException(status_code=401, detail="unauthorized")
        if int(request.headers.get("content-length") or 0) > MAX_BODY:
            raise HTTPException(status_code=413, detail="payload too large")
        body = await request.body()
        if len(body) > MAX_BODY:
            raise HTTPException(status_code=413, detail="payload too large")
        try:
            payload = json.loads(body)
        except ValueError:
            raise HTTPException(status_code=400, detail="invalid JSON") from None
        records = payload.get("records") if isinstance(payload, dict) else None
        if not isinstance(records, list):
            raise HTTPException(status_code=422, detail="records: liste attendue")
        if len(records) > MAX_RECORDS:
            raise HTTPException(status_code=413, detail=f"au plus {MAX_RECORDS} enregistrements par lot")
        client = (request.headers.get("user-agent") or "")[:120] or None
        try:
            n = await run_in_threadpool(store.upsert, records, client)
        except RecordError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from None
        log.info("ingest: %d enregistrements", n)
        return JSONResponse({"accepted": n})

    # Tout le reste (dont /mcp/<secret>) part vers l'application MCP ; un mauvais chemin = 404.
    app.mount("/", mcp_app)
    return app
