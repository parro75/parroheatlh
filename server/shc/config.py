"""Configuration lue dans l'environnement — rien n'est écrit en dur."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from zoneinfo import ZoneInfo

MIN_SECRET_LEN = 32


@dataclass(frozen=True)
class Settings:
    db_path: str
    ingest_token: str
    mcp_secret: str
    tz: ZoneInfo
    allowed_hosts: list[str] = field(default_factory=list)
    allowed_origins: list[str] = field(default_factory=list)

    @property
    def mcp_path(self) -> str:
        return f"/mcp/{self.mcp_secret}"


def _csv(name: str, default: str = "") -> list[str]:
    return [v.strip() for v in os.environ.get(name, default).split(",") if v.strip()]


def load() -> Settings:
    ingest = os.environ.get("INGEST_TOKEN", "")
    secret = os.environ.get("MCP_PATH_SECRET", "")
    for name, value in (("INGEST_TOKEN", ingest), ("MCP_PATH_SECRET", secret)):
        if len(value) < MIN_SECRET_LEN:
            raise SystemExit(f"{name} absent ou trop court ({MIN_SECRET_LEN} caractères minimum)")
    if ingest == secret:
        raise SystemExit("INGEST_TOKEN et MCP_PATH_SECRET doivent être différents")
    return Settings(
        db_path=os.environ.get("DB_PATH", "/data/health.db"),
        ingest_token=ingest,
        mcp_secret=secret,
        tz=ZoneInfo(os.environ.get("TZ_LOCAL", "America/Toronto")),
        allowed_hosts=_csv("MCP_ALLOWED_HOSTS"),
        allowed_origins=_csv("MCP_ALLOWED_ORIGINS", "https://claude.ai,https://claude.com"),
    )
