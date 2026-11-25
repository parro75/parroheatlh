"""Serveur d'autorisation : chaque garde-fou a son test."""

from __future__ import annotations

from urllib.parse import parse_qs, urlparse

import pytest

from conftest import CLAUDE_CALLBACK, PASSWORD, TOTP_SECRET, fresh_totp, full_login, pkce, register, start_authorize
from shc.auth import MAX_FAILURES
from shc.security import current_step, hash_password, match_totp, totp_at, verify_password

BASE = "http://localhost"


def test_metadata(client):
    m = client.get("/.well-known/oauth-authorization-server").json()
    assert m["issuer"].rstrip("/") == BASE
    assert m["code_challenge_methods_supported"] == ["S256"]
    assert m["registration_endpoint"].endswith("/register")
    pr = client.get("/.well-known/oauth-protected-resource/mcp").json()
    assert pr["resource"] == f"{BASE}/mcp"


def test_full_flow_refresh_and_reuse(client):
    t = full_login(client, client.app, BASE)
    assert t["token_type"] == "Bearer" and t["expires_in"] == 3600 and t["scope"] == "health:read"

    # Un code ne sert qu'une fois.
    again = client.post("/token", data={
        "grant_type": "authorization_code", "code": t["code"], "redirect_uri": CLAUDE_CALLBACK,
        "client_id": t["client_id"], "client_secret": t["client_secret"], "code_verifier": t["verifier"],
    })
    assert again.status_code == 400

    # Rotation du jeton de rafraîchissement.
    r = client.post("/token", data={"grant_type": "refresh_token", "refresh_token": t["refresh_token"],
                                    "client_id": t["client_id"], "client_secret": t["client_secret"]})
    assert r.status_code == 200, r.text
    new = r.json()
    assert new["refresh_token"] != t["refresh_token"]
    oauth = client.app.state.oauth
    import asyncio
    assert asyncio.run(oauth.load_access_token(t["access_token"])) is None  # l'ancien accès est retiré
    assert asyncio.run(oauth.load_access_token(new["access_token"])) is not None

    # Rejeu de l'ancien jeton de rafraîchissement = vol présumé : toute la famille saute.
    r = client.post("/token", data={"grant_type": "refresh_token", "refresh_token": t["refresh_token"],
                                    "client_id": t["client_id"], "client_secret": t["client_secret"]})
    assert r.status_code == 400
    assert asyncio.run(oauth.load_access_token(new["access_token"])) is None


def test_tokens_stored_hashed(client):
    t = full_login(client, client.app, BASE)
    rows = client.app.state.oauth._q("SELECT hash FROM oauth_tokens")
    assert rows and all(t["access_token"] not in h and t["refresh_token"] not in h for (h,) in rows)


def test_registration_rejects_foreign_redirect(client):
    r = register(client, "https://evil.example/callback")
    assert r.status_code == 400
    assert r.json()["error"] == "invalid_redirect_uri"


def test_wrong_password_or_code_gives_no_code(client):
    reg = register(client).json()
    _, challenge = pkce()
    req = start_authorize(client, reg["client_id"], challenge, BASE)
    page = client.get("/login", params={"req": req})
    assert page.status_code == 200 and "Claude" in page.text
    assert "frame-ancestors 'none'" in page.headers["content-security-policy"]
    for data in ({"password": "faux", "code": fresh_totp(client.app)},
                 {"password": PASSWORD, "code": "000000"}):
        r = client.post("/login", data={"req": req, **data})
        assert r.status_code == 200 and "incorrect" in r.text and "location" not in r.headers
    # La demande reste utilisable après un échec (jusqu'à expiration).
    ok = client.post("/login", data={"req": req, "password": PASSWORD, "code": fresh_totp(client.app)})
    assert ok.status_code == 302
    # ... mais une seule fois.
    assert client.post("/login", data={"req": req, "password": PASSWORD, "code": "123456"}).status_code == 400


def test_totp_replay_rejected(client):
    oauth = client.app.state.oauth
    code = fresh_totp(client.app)
    assert oauth.check_owner(PASSWORD, code)
    assert not oauth.check_owner(PASSWORD, code)


def test_lockout_after_failures(client):
    reg = register(client).json()
    _, challenge = pkce()
    req = start_authorize(client, reg["client_id"], challenge, BASE)
    for _ in range(MAX_FAILURES):
        client.post("/login", data={"req": req, "password": "faux", "code": "000000"})
    r = client.post("/login", data={"req": req, "password": PASSWORD, "code": fresh_totp(client.app)})
    assert r.status_code == 429


def test_unknown_request_and_scope(client):
    assert client.get("/login", params={"req": "inconnu"}).status_code == 400
    reg = register(client).json()
    _, challenge = pkce()
    r = client.get("/authorize", params={
        "response_type": "code", "client_id": reg["client_id"], "redirect_uri": CLAUDE_CALLBACK,
        "code_challenge": challenge, "code_challenge_method": "S256", "state": "s", "scope": "admin",
    })
    # Portée inconnue : erreur renvoyée au client, jamais de page de connexion.
    assert r.status_code in (302, 400)
    if r.status_code == 302:
        assert "error" in parse_qs(urlparse(r.headers["location"]).query)
        assert not r.headers["location"].startswith(f"{BASE}/login")


def test_password_and_totp_primitives():
    h = hash_password("une phrase de passe assez longue")
    assert verify_password("une phrase de passe assez longue", h)
    assert not verify_password("autre", h)
    assert not verify_password("x", "n'importe quoi")
    # Vecteur RFC 6238 (SHA1, T=59 s) : 94287082 sur 8 chiffres -> 287082 sur 6.
    rfc_secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"  # gitleaks:allow (vecteur de test public RFC 6238)
    assert totp_at(rfc_secret, 1) == "287082"
    s = current_step()
    assert match_totp(TOTP_SECRET, totp_at(TOTP_SECRET, s - 1)) == s - 1
    assert match_totp(TOTP_SECRET, totp_at(TOTP_SECRET, s - 3)) is None
    assert match_totp(TOTP_SECRET, "abc") is None


@pytest.mark.parametrize("path", ["/mcp/quelquechose", "/docs", "/openapi.json", "/admin"])
def test_unknown_paths_404(client, path):
    assert client.get(path).status_code == 404
