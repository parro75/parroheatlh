"""API de lecture de l'app Android : client OAuth public (PKCE, sans secret) + GET /api/v1/…"""

from __future__ import annotations

from urllib.parse import parse_qs, urlparse

from conftest import AUTH, PASSWORD, fresh_totp, pkce, register, sample_records

APP_ID = "parro-health-android"
APP_CB = "fr.parro.health://oauth/callback"


def app_login(client) -> dict:
    verifier, challenge = pkce()
    r = client.get("/authorize", params={
        "response_type": "code", "client_id": APP_ID, "redirect_uri": APP_CB, "code_challenge": challenge,
        "code_challenge_method": "S256", "state": "s1", "scope": "health:read"})
    assert r.status_code == 302, r.text
    req = parse_qs(urlparse(r.headers["location"]).query)["req"][0]
    page = client.get("/login", params={"req": req})
    assert "Parro Health (Android)" in page.text
    assert "fr.parro.health:" in page.headers["content-security-policy"]
    r = client.post("/login", data={"req": req, "password": PASSWORD, "code": fresh_totp(client.app)})
    assert r.status_code == 302 and r.headers["location"].startswith(APP_CB)
    code = parse_qs(urlparse(r.headers["location"]).query)["code"][0]
    tok = client.post("/token", data={"grant_type": "authorization_code", "code": code, "redirect_uri": APP_CB,
                                      "client_id": APP_ID, "code_verifier": verifier})
    assert tok.status_code == 200, tok.text
    return tok.json()


def test_app_public_client_and_api(client):
    assert client.post("/ingest", json={"records": sample_records()},
                       headers={**AUTH, "User-Agent": "ParroHealth/0.2.0 (Android 17; manuel)"}).status_code == 200
    t = app_login(client)
    h = {"Authorization": f"Bearer {t['access_token']}"}

    today = client.get("/api/v1/today", headers=h)
    assert today.status_code == 200 and today.headers["cache-control"] == "no-store"
    assert "steps_today" in today.json() and today.json()["last_phone_sync"]

    sl = client.get("/api/v1/sleep", params={"start": "2026-11-24", "end": "2026-11-24"}, headers=h).json()
    assert sl["sessions"][0]["asleep_min"] == 435
    tr = client.get("/api/v1/trends", params={"metric": "steps", "period": "month",
                                              "start": "2026-11-01", "end": "2026-11-30"}, headers=h).json()
    assert tr["rows"][0]["period"] == "2026-11"
    assert client.get("/api/v1/status", headers=h).json()["types"]["sleep_session"]["records"] == 1

    assert client.get("/api/v1/inconnu", headers=h).status_code == 404
    assert client.get("/api/v1/sleep", params={"start": "x"}, headers=h).status_code == 422

    # Rafraîchissement sans secret (client public)
    r = client.post("/token", data={"grant_type": "refresh_token", "refresh_token": t["refresh_token"],
                                    "client_id": APP_ID})
    assert r.status_code == 200, r.text


def test_api_rejects_missing_or_wrong_tokens(client):
    for headers in ({}, {"Authorization": "Bearer faux"}, AUTH):  # AUTH = jeton d'ingestion : écriture seule
        r = client.get("/api/v1/today", headers=headers)
        assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"


def test_app_redirect_not_registrable_by_dcr(client):
    # Le retour de l'app est réservé au client fixe : impossible de l'enregistrer via /register.
    assert register(client, APP_CB).status_code == 400


def test_app_client_requires_pkce_verifier(client):
    verifier, challenge = pkce()
    r = client.get("/authorize", params={
        "response_type": "code", "client_id": APP_ID, "redirect_uri": APP_CB, "code_challenge": challenge,
        "code_challenge_method": "S256", "state": "s", "scope": "health:read"})
    req = parse_qs(urlparse(r.headers["location"]).query)["req"][0]
    r = client.post("/login", data={"req": req, "password": PASSWORD, "code": fresh_totp(client.app)})
    code = parse_qs(urlparse(r.headers["location"]).query)["code"][0]
    bad = client.post("/token", data={"grant_type": "authorization_code", "code": code, "redirect_uri": APP_CB,
                                      "client_id": APP_ID, "code_verifier": "x" * 50})
    assert bad.status_code == 400
