"""Application unique : POST /ingest (téléphone, jeton fixe) + MCP streamable HTTP sur /mcp (Claude, OAuth)."""

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
from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings
from mcp.shared.auth import OAuthClientInformationFull
from mcp_types import ToolAnnotations
from pydantic import Field

from . import __version__, config, queries, report
from .auth import SCOPE, HealthOAuthProvider, OwnerAuth
from .db import RecordError, Store

log = logging.getLogger("shc")

MAX_BODY = 20 * 1024 * 1024
MAX_RECORDS = 20_000

INSTRUCTIONS = (
    "Données de santé personnelles de l'utilisateur, issues de Samsung Health (Galaxy Watch) : "
    "synchro continue via Health Connect (pas, FC, sommeil, SpO2, séances, poids) et imports d'exports "
    "Samsung Health (historique depuis 2015, HRV, scores de sommeil, vitaux nocturnes, score d'énergie, "
    "stress, charge d'entraînement — à jour seulement jusqu'au dernier import). Lecture seule. "
    "Dates AAAA-MM-JJ ; sans dates, les 7 derniers jours ; 366 jours au plus par appel, sauf get_trends "
    "(moyennes par semaine/mois/année sur 20 ans). Une nuit est rattachée au jour du réveil. "
    "Appeler get_sync_status pour connaître la couverture de chaque type avant de conclure à une absence."
)

DateArg = Annotated[str | None, Field(description="AAAA-MM-JJ (inclus)", pattern=r"^\d{4}-\d{2}-\d{2}$")]
READ_ONLY = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)


def build_mcp(store: Store, settings: config.Settings, provider: HealthOAuthProvider,
              reports: report.ReportStore) -> MCPServer:
    mcp = MCPServer(
        name="samsung-health",
        version=__version__,
        instructions=INSTRUCTIONS,
        auth_server_provider=provider,
        auth=AuthSettings(
            issuer_url=settings.public_url,
            resource_server_url=settings.resource_url,
            validate_token_resource=True,
            required_scopes=[SCOPE],
            client_registration_options=ClientRegistrationOptions(
                enabled=True, valid_scopes=[SCOPE], default_scopes=[SCOPE]
            ),
            revocation_options=RevocationOptions(enabled=True),
        ),
    )
    mcp.custom_route("/login", methods=["GET"], include_in_schema=False)(provider.login_get)
    mcp.custom_route("/login", methods=["POST"], include_in_schema=False)(provider.login_post)

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
    def get_recovery(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Récupération nocturne par jour : HRV (ms), FC de sommeil, fréquence respiratoire, écart de
        température cutanée, SpO2 et score d'énergie Samsung (0-100)."""
        return run(queries.recovery, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_stress(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Stress Samsung par jour : moyenne, max, min (0-100) et nombre de mesures."""
        return run(queries.stress, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_training_load(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Charge d'entraînement Samsung par jour : aiguë (7 j), chronique (28 j), temps de récupération,
        plage recommandée."""
        return run(queries.training_load, start, end)

    @mcp.tool(annotations=READ_ONLY)
    def get_trends(
        metric: Annotated[str, Field(description="steps, sleep_minutes, sleep_score, hrv_nightly, hrv, "
                                                 "sleeping_hr, heart_rate_avg, weight, body_fat, stress, "
                                                 "energy ou workout_minutes")],
        period: Annotated[str, Field(description="week, month ou year")] = "month",
        start: DateArg = None,
        end: DateArg = None,
    ) -> dict[str, Any]:
        """Tendance longue d'une métrique : moyenne, min et max des valeurs quotidiennes par semaine,
        mois ou année (jusqu'à 20 ans ; par défaut les 12 derniers mois)."""
        today = datetime.now(settings.tz).date()
        try:
            d0, d1 = queries.trend_range(start, end, today)
            return queries.trends(store, metric, period, d0, d1)
        except queries.QueryError as exc:
            raise ToolError(str(exc)) from exc

    @mcp.tool(annotations=READ_ONLY)
    def get_daily_report(day: DateArg = None) -> dict[str, Any]:
        """Bilan du jour (nuit, FC au repos, HRV, activité d'hier, SpO2) comparé aux moyennes personnelles,
        avec les signaux calculés par règles et le texte rédigé chaque matin par l'IA locale."""
        d = queries.date_range(day, day, datetime.now(settings.tz).date())[0] if day else datetime.now(settings.tz).date()
        return {"facts": report.facts(store, d), **(reports.get(d.isoformat()) or {})}

    @mcp.tool(annotations=READ_ONLY)
    def get_sync_status() -> dict[str, Any]:
        """Dernière synchronisation reçue du téléphone et, par type de donnée, nombre d'enregistrements
        et premier/dernier jour couverts."""
        return {"timezone": str(settings.tz), **store.status()}

    return mcp


def create_app(settings: config.Settings | None = None) -> FastAPI:
    settings = settings or config.load()
    store = Store(settings.db_path, settings.tz)
    provider = HealthOAuthProvider(
        settings.db_path,
        public_url=settings.public_url,
        resource_url=settings.resource_url,
        owner=OwnerAuth(settings.owner_password_hash, settings.totp_secret),
        redirect_allowlist=settings.redirect_uris,
        static_clients=[OAuthClientInformationFull(
            client_id=settings.app_client_id,
            client_name="Parro Health (Android)",
            redirect_uris=[settings.app_redirect_uri],
            token_endpoint_auth_method="none",  # client public : PKCE obligatoire, aucun secret
            grant_types=["authorization_code", "refresh_token"],
            response_types=["code"],
            scope=SCOPE,
        )],
    )
    reports = report.ReportStore(settings.db_path)
    mcp = build_mcp(store, settings, provider, reports)
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
        provider.close()
        reports.close()

    app = FastAPI(title="samsung-health-connector", version=__version__, lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store = store
    app.state.oauth = provider
    expected = f"Bearer {settings.ingest_token}".encode()

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    # ---- API de lecture de l'app (jeton OAuth, portée health:read) ------------------------------
    async def require_token(request: Request) -> None:
        auth = request.headers.get("authorization", "")
        token = auth[7:].strip() if auth[:7].lower() == "bearer " else ""
        access = await provider.load_access_token(token) if token else None
        if access is None or SCOPE not in access.scopes:
            raise HTTPException(status_code=401, detail="unauthorized", headers={"WWW-Authenticate": "Bearer"})

    api_ranges: dict[str, Callable[..., dict[str, Any]]] = {
        "steps": queries.daily_steps, "sleep": queries.sleep, "heart_rate": queries.heart_rate,
        "hrv": queries.hrv, "recovery": queries.recovery, "stress": queries.stress,
        "training_load": queries.training_load, "workouts": queries.workouts, "body": queries.body,
        "spo2": queries.spo2,
    }

    @app.get("/api/v1/{name}")
    async def api_read(name: str, request: Request) -> JSONResponse:
        await require_token(request)
        q = request.query_params
        today = datetime.now(settings.tz).date()
        try:
            if name == "today":
                data = await run_in_threadpool(queries.today, store, today)
            elif name == "report":
                d = queries.date_range(q.get("date"), q.get("date"), today)[0] if q.get("date") else today
                f = await run_in_threadpool(report.facts, store, d)
                data = {"facts": f, **(reports.get(d.isoformat()) or {})}
            elif name == "status":
                data = {"timezone": str(settings.tz), **await run_in_threadpool(store.status)}
            elif name == "trends":
                d0, d1 = queries.trend_range(q.get("start"), q.get("end"), today)
                data = await run_in_threadpool(queries.trends, store, q.get("metric", "steps"),
                                               q.get("period", "month"), d0, d1)
            elif name in api_ranges:
                d0, d1 = queries.date_range(q.get("start"), q.get("end"), today)
                data = await run_in_threadpool(api_ranges[name], store, d0, d1)
            else:
                raise HTTPException(status_code=404, detail="not found")
        except queries.QueryError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from None
        return JSONResponse(data, headers={"Cache-Control": "no-store"})

    # ---- job de rédaction du rapport (machine Ollama) : jeton dédié, 3 routes seulement ---------------------
    report_auth = f"Bearer {settings.report_token}".encode() if settings.report_token else None

    def require_report_token(request: Request) -> None:
        if report_auth is None:
            raise HTTPException(status_code=404, detail="not found")
        if not hmac.compare_digest(request.headers.get("authorization", "").encode(), report_auth):
            raise HTTPException(status_code=401, detail="unauthorized")

    @app.get("/api/v1/report-job/pending")
    async def report_pending(request: Request) -> JSONResponse:
        require_report_token(request)
        now = datetime.now(settings.tz)
        d = now.date()
        f = await run_in_threadpool(report.facts, store, d)
        saved = reports.get(d.isoformat()) or {}
        return JSONResponse({
            "date": d.isoformat(),
            "ready": f["ready"] or now.hour >= settings.report_deadline_hour,
            "has_narrative": bool(saved.get("narrative")),
            "notified": bool(saved.get("notified_at")),
            "narrative": saved.get("narrative"),
            "fallback": report.fallback_text(f),
            "facts": f,
        }, headers={"Cache-Control": "no-store"})

    @app.post("/api/v1/report-job/narrative")
    async def report_narrative(request: Request) -> JSONResponse:
        require_report_token(request)
        body = await request.json()
        d, text, model = str(body.get("date", "")), str(body.get("narrative", "")).strip(), str(body.get("model", ""))[:80]
        try:
            datetime.strptime(d, "%Y-%m-%d")
        except ValueError:
            raise HTTPException(status_code=422, detail="date AAAA-MM-JJ attendue") from None
        if not text or len(text) > 4000:
            raise HTTPException(status_code=422, detail="texte vide ou trop long (4000 car. max)")
        reports.save_narrative(d, text, model)
        log.info("rapport du %s rédigé (%s, %d car.)", d, model, len(text))
        return JSONResponse({"saved": d})

    @app.post("/api/v1/report-job/notified")
    async def report_notified(request: Request) -> JSONResponse:
        require_report_token(request)
        d = str((await request.json()).get("date", ""))
        reports.mark_notified(d)
        return JSONResponse({"notified": d})

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

    # Tout le reste (MCP, OAuth, /login) part vers l'application MCP ; un chemin inconnu = 404.
    app.mount("/", mcp_app)
    return app
