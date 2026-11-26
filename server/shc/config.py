"""Configuration lue dans l'environnement — rien n'est écrit en dur."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from urllib.parse import urlparse
from zoneinfo import ZoneInfo

MIN_SECRET_LEN = 32
DEFAULT_REDIRECTS = "https://claude.ai/api/mcp/auth_callback,https://claude.com/api/mcp/auth_callback"


@dataclass(frozen=True)
class Settings:
    db_path: str
    ingest_token: str
    public_url: str  # https://sante.example.org — émetteur OAuth et base de la ressource /mcp
    owner_password_hash: str
    totp_secret: str
    tz: ZoneInfo
    redirect_uris: list[str] = field(default_factory=lambda: DEFAULT_REDIRECTS.split(","))
    allowed_hosts: list[str] = field(default_factory=list)
    allowed_origins: list[str] = field(default_factory=list)
    app_client_id: str = "parro-health-android"
    app_redirect_uri: str = "fr.parro.health://oauth/callback"
    report_token: str = ""  # job de rédaction (Ollama) : lit le bilan du jour, dépose le texte ; rien d'autre
    ha_token: str = ""  # Home Assistant : lit /api/v1/ha/summary et RIEN d'autre (tableau de bord)
    report_deadline_hour: int = 12  # sans nuit reçue à cette heure, le rapport part quand même
    # Écritures signées par le téléphone (shc.devices) : off | optional (vérifiée si présente) | required
    device_signature: str = "optional"
    audit_retention_days: int = 365  # journal des accès (traçabilité)

    @property
    def mcp_path(self) -> str:
        return "/mcp"

    @property
    def resource_url(self) -> str:
        return f"{self.public_url.rstrip('/')}{self.mcp_path}"


def _csv(name: str, default: str = "") -> list[str]:
    return [v.strip() for v in os.environ.get(name, default).split(",") if v.strip()]


def _require_data_volume(db_path: str) -> None:
    """DATA_MARKER (facultatif) : nom d'un fichier qui n'existe QUE dans le volume chiffré. S'il manque, le
    volume n'est pas monté : refuser de démarrer plutôt que de créer une base vide en clair en dessous."""
    marker = os.environ.get("DATA_MARKER", "")
    if marker and not os.path.exists(os.path.join(os.path.dirname(db_path), marker)):
        raise SystemExit(f"volume de données non monté ({marker} absent) : démarrage refusé")


def load() -> Settings:
    ingest = os.environ.get("INGEST_TOKEN", "")
    if len(ingest) < MIN_SECRET_LEN:
        raise SystemExit(f"INGEST_TOKEN absent ou trop court ({MIN_SECRET_LEN} caractères minimum)")
    public = os.environ.get("PUBLIC_URL", "").rstrip("/")
    if not public.startswith("https://"):
        raise SystemExit("PUBLIC_URL doit être une URL https://")
    pw_hash = os.environ.get("OWNER_PASSWORD_HASH", "")
    if not pw_hash.startswith("scrypt$"):
        raise SystemExit("OWNER_PASSWORD_HASH absent : python -m shc.admin init")
    totp = os.environ.get("TOTP_SECRET", "")
    if len(totp) < 16:
        raise SystemExit("TOTP_SECRET absent ou trop court : python -m shc.admin init")
    db_path = os.environ.get("DB_PATH", "/data/health.db")
    _require_data_volume(db_path)
    return Settings(
        db_path=db_path,
        ingest_token=ingest,
        public_url=public,
        owner_password_hash=pw_hash,
        totp_secret=totp,
        tz=ZoneInfo(os.environ.get("TZ_LOCAL", "America/Toronto")),
        redirect_uris=_csv("OAUTH_REDIRECT_URIS", DEFAULT_REDIRECTS),
        allowed_hosts=_csv("MCP_ALLOWED_HOSTS", urlparse(public).netloc),
        allowed_origins=_csv("MCP_ALLOWED_ORIGINS", "https://claude.ai,https://claude.com"),
        app_client_id=os.environ.get("APP_CLIENT_ID", "parro-health-android"),
        app_redirect_uri=os.environ.get("APP_REDIRECT_URI", "fr.parro.health://oauth/callback"),
        report_token=_report_token(ingest),
        ha_token=_ha_token(ingest),
        report_deadline_hour=int(os.environ.get("REPORT_DEADLINE_HOUR", "12")),
        device_signature=_device_signature(),
        audit_retention_days=int(os.environ.get("AUDIT_RETENTION_DAYS", "365")),
    )


def _device_signature() -> str:
    mode = os.environ.get("DEVICE_SIGNATURE", "optional").strip().lower()
    if mode not in ("off", "optional", "required"):
        raise SystemExit("DEVICE_SIGNATURE : off, optional ou required")
    return mode


def _ha_token(ingest: str) -> str:
    tok = os.environ.get("HA_TOKEN", "")
    if tok and (len(tok) < MIN_SECRET_LEN or tok in (ingest, os.environ.get("REPORT_TOKEN", ""))):
        raise SystemExit("HA_TOKEN trop court ou identique à un autre jeton")
    return tok


def _report_token(ingest: str) -> str:
    tok = os.environ.get("REPORT_TOKEN", "")
    if tok and (len(tok) < MIN_SECRET_LEN or tok == ingest):
        raise SystemExit("REPORT_TOKEN trop court ou identique à INGEST_TOKEN")
    return tok
