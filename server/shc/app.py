"""Application unique : POST /ingest (téléphone, jeton fixe) + MCP streamable HTTP sur /mcp (Claude, OAuth)."""

from __future__ import annotations

import hmac
import os
import re
import json
import logging
from collections.abc import Callable
from contextlib import asynccontextmanager
from datetime import date, datetime, timedelta
from typing import Annotated, Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse, Response
from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings
from mcp.shared.auth import OAuthClientInformationFull
from mcp_types import ToolAnnotations
from pydantic import Field

from . import __version__, config, ha, pdf, queries, report, samsung_export, syncwatch, uploads
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
                                                 "sleeping_hr, resting_hr, heart_rate_avg, weight, body_fat, stress, "
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
    def get_weekly_report(day: DateArg = None) -> dict[str, Any]:
        """Bilan de la semaine (lundi → dimanche) contenant ce jour, comparé aux 4 semaines précédentes :
        sommeil, régularité du réveil, FC au repos, pas, séances, stress, journal ; texte du dimanche soir."""
        d = date.fromisoformat(day) if day else datetime.now(settings.tz).date()
        f = report.weekly_facts(store, d)
        return {"facts": f, **(reports.get(f["week"]) or {})}

    @mcp.tool(annotations=READ_ONLY)
    def get_journal(start: DateArg = None, end: DateArg = None) -> dict[str, Any]:
        """Journal d'événements noté par l'utilisateur dans Parro Health (alcool, café tardif, malade, voyage,
        séance intense, stress, repas tardif, écrans tard, médicament, autre + note). Utile pour chercher ce
        qui précède une mauvaise nuit ou une FC au repos élevée."""
        return run(queries.journal, start, end)

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
    watch = syncwatch.SyncWatch(settings.db_path)

    def run_import(path: str) -> dict[str, Any]:
        res = samsung_export.run(store, path)
        log.info("import d'export (téléphone) : %d enregistrements écrits", sum(w for _, w in res.values()))
        return {k: {"candidates": c, "written": w} for k, (c, w) in res.items()}

    exports = uploads.Uploads(settings.db_path, os.path.join(os.path.dirname(settings.db_path), "uploads"), run_import)
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
        watch.close()
        exports.close()

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
        "spo2": queries.spo2, "journal": queries.journal,
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
            elif name == "weekly":
                d = queries.date_range(q.get("date"), q.get("date"), today)[0] if q.get("date") else today
                f = await run_in_threadpool(report.weekly_facts, store, d)
                saved = reports.get(f["week"])
                if not saved:  # en semaine : le dernier rapport rédigé (semaine précédente)
                    prev = d - timedelta(days=7)
                    pf = await run_in_threadpool(report.weekly_facts, store, prev)
                    if reports.get(pf["week"]):
                        f, saved = pf, reports.get(pf["week"])
                data = {"facts": f, **(saved or {})}
            elif name == "status":
                data = {"timezone": str(settings.tz), **await run_in_threadpool(store.status)}
            elif name == "trends":
                d0, d1 = queries.trend_range(q.get("start"), q.get("end"), today)
                data = await run_in_threadpool(queries.trends, store, q.get("metric", "steps"),
                                               q.get("period", "month"), d0, d1)
            elif name in api_ranges:
                # poids et masse grasse : quelques mesures par mois → tout l'historique (20 ans) est permis
                rng = queries.trend_range if name == "body" else queries.date_range
                d0, d1 = rng(q.get("start"), q.get("end"), today)
                data = await run_in_threadpool(api_ranges[name], store, d0, d1)
            else:
                raise HTTPException(status_code=404, detail="not found")
        except queries.QueryError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from None
        return JSONResponse(data, headers={"Cache-Control": "no-store"})

    # ---- fiche PDF pour un rendez-vous médical : générée en mémoire, jamais écrite sur le disque ----------
    @app.get("/api/v1/pdf/summary")
    async def pdf_summary(request: Request) -> Response:
        await require_token(request)
        today = datetime.now(settings.tz).date()
        try:
            months = int(request.query_params.get("months", "3"))
            data = await run_in_threadpool(pdf.summary, store, today, months, datetime.now(settings.tz))
        except (ValueError, queries.QueryError):
            raise HTTPException(status_code=422, detail="months : 3, 6 ou 12") from None
        return Response(data, media_type="application/pdf", headers={
            "Cache-Control": "no-store",
            "Content-Disposition": f'attachment; filename="synthese-sante-{today.isoformat()}-{months}-mois.pdf"'})

    # ---- Home Assistant : jeton dédié, UNE route en lecture (tableau de bord visible par Parro seul) ----
    ha_auth = f"Bearer {settings.ha_token}".encode() if settings.ha_token else None

    @app.get("/api/v1/ha/summary")
    async def ha_summary(request: Request) -> JSONResponse:
        if ha_auth is None:
            raise HTTPException(status_code=404, detail="not found")
        if not hmac.compare_digest(request.headers.get("authorization", "").encode(), ha_auth):
            raise HTTPException(status_code=401, detail="unauthorized")
        data = await run_in_threadpool(ha.summary, store, reports, datetime.now(settings.tz).date())
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
        if not (re.fullmatch(r"\d{4}-\d{2}-\d{2}", d) or re.fullmatch(r"S\d{4}-W\d{2}", d)):
            raise HTTPException(status_code=422, detail="date AAAA-MM-JJ ou semaine SAAAA-Wnn attendue")
        if not text or len(text) > 4000:
            raise HTTPException(status_code=422, detail="texte vide ou trop long (4000 car. max)")
        reports.save_narrative(d, text, model)
        log.info("rapport du %s rédigé (%s, %d car.)", d, model, len(text))
        return JSONResponse({"saved": d})

    def _phone_last_sync() -> str | None:
        t = [x for x in (store.last_ingest_from("ParroHealth"), store.last_ingest_from("SanteSync")) if x]
        return max(t) if t else None

    @app.get("/api/v1/report-job/sync-status")
    async def sync_status(request: Request) -> JSONResponse:
        require_report_token(request)
        return JSONResponse(watch.status(_phone_last_sync(), syncwatch.now_utc(), settings.tz),
                            headers={"Cache-Control": "no-store"})

    @app.post("/api/v1/report-job/sync-status/ack")
    async def sync_ack(request: Request) -> JSONResponse:
        require_report_token(request)
        body = await request.json()
        action, last = str(body.get("action", "")), str(body.get("last_phone_sync", ""))
        if action not in ("alert", "recovered") or not last:
            raise HTTPException(status_code=422, detail="action alert|recovered et last_phone_sync attendus")
        watch.ack(action, last)
        return JSONResponse({"ack": action})

    @app.get("/api/v1/report-job/export-reminder")
    async def export_reminder(request: Request) -> JSONResponse:
        require_report_token(request)
        last = store.last_ingest_from(samsung_export.CLIENT)
        return JSONResponse(watch.export_reminder(last, syncwatch.now_utc(), settings.tz), headers={"Cache-Control": "no-store"})

    @app.post("/api/v1/report-job/export-reminder/ack")
    async def export_reminder_ack(request: Request) -> JSONResponse:
        require_report_token(request)
        watch.ack_reminder(syncwatch.now_utc())
        return JSONResponse({"ack": "export_reminder"})

    @app.get("/api/v1/report-job/weekly/pending")
    async def weekly_pending(request: Request) -> JSONResponse:
        require_report_token(request)
        now = datetime.now(settings.tz)
        f = await run_in_threadpool(report.weekly_facts, store, now.date())
        saved = reports.get(f["week"]) or {}
        return JSONResponse({
            "date": f["week"],  # clé de stockage du texte
            "ready": now.weekday() == 6 and now.hour >= 18,  # dimanche à partir de 18 h
            "has_narrative": bool(saved.get("narrative")), "notified": bool(saved.get("notified_at")),
            "narrative": saved.get("narrative"), "fallback": report.weekly_fallback(f), "facts": f,
        }, headers={"Cache-Control": "no-store"})

    @app.post("/api/v1/report-job/notified")
    async def report_notified(request: Request) -> JSONResponse:
        require_report_token(request)
        d = str((await request.json()).get("date", ""))
        reports.mark_notified(d)
        return JSONResponse({"notified": d})

    # ---- envoi d'un export Samsung Health par le téléphone (jeton d'ingestion, par morceaux) ---------
    def require_ingest(request: Request) -> None:
        if not hmac.compare_digest(request.headers.get("authorization", "").encode(), expected):
            raise HTTPException(status_code=401, detail="unauthorized")

    def upload_call(fn, *args):
        try:
            return JSONResponse(fn(*args), headers={"Cache-Control": "no-store"})
        except uploads.UploadError as exc:
            raise HTTPException(status_code=409 if "déjà" in str(exc) else 422, detail=str(exc)) from None

    @app.post("/ingest-export/start")
    async def export_start(request: Request) -> JSONResponse:
        require_ingest(request)
        b = await request.json()
        return await run_in_threadpool(upload_call, exports.start, int(b.get("size", 0)), str(b.get("sha256", "")).lower())

    @app.put("/ingest-export/{uid}/{index}")
    async def export_chunk(uid: str, index: int, request: Request) -> JSONResponse:
        require_ingest(request)
        data = await request.body()
        return await run_in_threadpool(upload_call, exports.put, uid, index, data)

    @app.post("/ingest-export/{uid}/finish")
    async def export_finish(uid: str, request: Request) -> JSONResponse:
        require_ingest(request)
        return await run_in_threadpool(upload_call, exports.finish, uid)

    @app.get("/ingest-export/{uid}")
    async def export_status(uid: str, request: Request) -> JSONResponse:
        require_ingest(request)
        return await run_in_threadpool(upload_call, exports.status, uid)

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
