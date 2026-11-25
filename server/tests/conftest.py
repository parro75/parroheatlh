from __future__ import annotations

import base64
import hashlib
import secrets
import socket
import threading
import time
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo

import httpx
import pytest
import uvicorn
from fastapi.testclient import TestClient

from shc.app import create_app
from shc.config import Settings
from shc.security import current_step, hash_password, new_totp_secret, totp_at

INGEST_TOKEN = "i" * 40
REPORT_TOKEN = "r" * 40
HA_TOKEN = "h" * 40
PASSWORD = "correct horse battery staple"
TOTP_SECRET = new_totp_secret()
PASSWORD_HASH = hash_password(PASSWORD)
CLAUDE_CALLBACK = "https://claude.ai/api/mcp/auth_callback"
AUTH = {"Authorization": f"Bearer {INGEST_TOKEN}"}


def make_settings(tmp_path, public_url: str) -> Settings:
    return Settings(
        db_path=str(tmp_path / "health.db"),
        ingest_token=INGEST_TOKEN,
        public_url=public_url,
        owner_password_hash=PASSWORD_HASH,
        totp_secret=TOTP_SECRET,
        tz=ZoneInfo("America/Toronto"),
        report_token=REPORT_TOKEN,
        ha_token=HA_TOKEN,
    )


@pytest.fixture
def settings(tmp_path) -> Settings:
    return make_settings(tmp_path, "http://localhost")


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings), base_url="http://localhost", follow_redirects=False) as c:
        yield c


@pytest.fixture
def live_server(tmp_path):
    """Vrai serveur uvicorn sur un port libre ; renvoie (base_url, app)."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    app = create_app(make_settings(tmp_path, base))
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    for _ in range(100):
        try:
            if httpx.get(f"{base}/healthz").status_code == 200:
                break
        except httpx.HTTPError:
            time.sleep(0.05)
    yield base, app
    server.should_exit = True
    thread.join(timeout=5)


def fresh_totp(app) -> str:
    """Un code TOTP pour un pas pas encore consommé (l'anti-rejeu refuse le même pas deux fois)."""
    last = app.state.oauth._state("totp_last_step", -1)
    step = max(current_step(), int(last) + 1)
    return totp_at(TOTP_SECRET, step)


def pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    return verifier, challenge


def register(http: httpx.Client, redirect: str = CLAUDE_CALLBACK) -> httpx.Response:
    return http.post("/register", json={
        "redirect_uris": [redirect],
        "client_name": "Claude",
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "client_secret_post",
    })


def start_authorize(http: httpx.Client, client_id: str, challenge: str, base: str) -> str:
    """Renvoie l'identifiant de demande en attente (paramètre req de /login)."""
    r = http.get("/authorize", params={
        "response_type": "code", "client_id": client_id, "redirect_uri": CLAUDE_CALLBACK,
        "code_challenge": challenge, "code_challenge_method": "S256", "state": "etat-123",
        "scope": "health:read", "resource": f"{base}/mcp",
    })
    assert r.status_code == 302, r.text
    loc = urlparse(r.headers["location"])
    assert loc.path == "/login"
    return parse_qs(loc.query)["req"][0]


def full_login(http: httpx.Client, app, base: str) -> dict:
    """Parcours OAuth complet comme le ferait claude.ai ; renvoie la réponse du point /token."""
    reg = register(http).json()
    verifier, challenge = pkce()
    req = start_authorize(http, reg["client_id"], challenge, base)
    r = http.post("/login", data={"req": req, "password": PASSWORD, "code": fresh_totp(app)})
    assert r.status_code == 302, r.text
    q = parse_qs(urlparse(r.headers["location"]).query)
    assert q["state"] == ["etat-123"]
    tok = http.post("/token", data={
        "grant_type": "authorization_code", "code": q["code"][0], "redirect_uri": CLAUDE_CALLBACK,
        "client_id": reg["client_id"], "client_secret": reg.get("client_secret", ""),
        "code_verifier": verifier, "resource": f"{base}/mcp",
    })
    assert tok.status_code == 200, tok.text
    return {**tok.json(), "client_id": reg["client_id"], "client_secret": reg.get("client_secret", ""),
            "code": q["code"][0], "verifier": verifier}


def sample_records() -> list[dict]:
    """Un jeu réaliste : une nuit à cheval sur deux jours, un footing, des agrégats."""
    return [
        {"type": "steps_daily", "id": "steps:2026-11-23", "start": "2026-11-23T00:00:00-05:00",
         "end": "2026-11-24T00:00:00-05:00", "zone_offset": "-05:00", "data": {"count": 8412}},
        {"type": "steps_daily", "id": "steps:2026-11-24", "start": "2026-11-24T00:00:00-05:00",
         "end": "2026-11-25T00:00:00-05:00", "zone_offset": "-05:00", "data": {"count": 10231}},
        {"type": "heart_rate_daily", "id": "hr:2026-11-24", "start": "2026-11-24T00:00:00-05:00",
         "end": "2026-11-25T00:00:00-05:00", "zone_offset": "-05:00", "data": {"min": 48, "avg": 66.4, "max": 162}},
        {"type": "resting_heart_rate", "id": "rhr-1", "start": "2026-11-24T07:05:00-05:00",
         "zone_offset": "-05:00", "data": {"bpm": 52, "source": "com.sec.android.app.shealth"}},
        {"type": "hrv_rmssd", "id": "hrv-1", "start": "2026-11-24T03:00:00-05:00", "zone_offset": "-05:00",
         "data": {"rmssd_ms": 41.2}},
        {"type": "hrv_rmssd", "id": "hrv-2", "start": "2026-11-24T05:00:00-05:00", "zone_offset": "-05:00",
         "data": {"rmssd_ms": 47.8}},
        {"type": "sleep_session", "id": "sleep-1", "start": "2026-11-23T23:10:00-05:00",
         "end": "2026-11-24T06:40:00-05:00", "zone_offset": "-05:00",
         "data": {"source": "com.sec.android.app.shealth", "stages": [
             {"start": "2026-11-23T23:10:00-05:00", "end": "2026-11-23T23:25:00-05:00", "stage": "awake"},
             {"start": "2026-11-23T23:25:00-05:00", "end": "2026-11-24T01:25:00-05:00", "stage": "light"},
             {"start": "2026-11-24T01:25:00-05:00", "end": "2026-11-24T02:55:00-05:00", "stage": "deep"},
             {"start": "2026-11-24T02:55:00-05:00", "end": "2026-11-24T04:40:00-05:00", "stage": "rem"},
             {"start": "2026-11-24T04:40:00-05:00", "end": "2026-11-24T06:40:00-05:00", "stage": "light"},
         ]}},
        {"type": "weight", "id": "w-1", "start": "2026-11-24T07:30:00-05:00", "zone_offset": "-05:00",
         "data": {"kg": 78.43}},
        {"type": "body_fat", "id": "bf-1", "start": "2026-11-24T07:30:00-05:00", "zone_offset": "-05:00",
         "data": {"percent": 18.27}},
        {"type": "oxygen_saturation", "id": "o2-1", "start": "2026-11-24T04:00:00-05:00",
         "zone_offset": "-05:00", "data": {"percent": 96.0}},
        {"type": "exercise_session", "id": "ex-1", "start": "2026-11-24T17:45:00-05:00",
         "end": "2026-11-24T18:31:30-05:00", "zone_offset": "-05:00",
         "data": {"exercise_type": 56, "title": "Footing", "distance_m": 8123.4,
                  "calories_kcal": 612.7, "avg_hr": 151.2, "max_hr": 174, "source": "com.sec.android.app.shealth"}},
    ]
