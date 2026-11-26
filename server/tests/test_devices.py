"""Écritures signées par le téléphone : appairage par OAuth, signature ECDSA P-256, anti-rejeu, mode required."""

from __future__ import annotations

import base64
import dataclasses
import hashlib
import json
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from conftest import AUTH, sample_records
from shc.app import create_app
from shc.devices import canonical
from test_app_api import app_login


def new_key():
    k = ec.generate_private_key(ec.SECP256R1())
    der = k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return k, base64.b64encode(der).decode(), hashlib.sha256(der).hexdigest()[:32]


def signed(key, did, method, path, body: bytes, ts=None):
    ts = str(int(time.time()) if ts is None else ts)
    sig = key.sign(canonical(method, path, ts, body), ec.ECDSA(hashes.SHA256()))
    return {**AUTH, "X-Parro-Device": did, "X-Parro-Timestamp": ts, "X-Parro-Signature": base64.b64encode(sig).decode(),
            "Content-Type": "application/json"}


def pair(client):
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}"}
    key, pub, did = new_key()
    r = client.post("/api/v1/device", json={"public_key": pub, "label": "Test"}, headers=h)
    assert r.status_code == 200 and r.json()["device_id"] == did
    return key, did, h


BODY = json.dumps({"records": sample_records()}).encode()


def test_pairing_requires_oauth_and_p256(client):
    _, pub, _ = new_key()
    assert client.post("/api/v1/device", json={"public_key": pub}, headers=AUTH).status_code == 401  # jeton d'écriture ≠ OAuth
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}"}
    k384 = ec.generate_private_key(ec.SECP384R1()).public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    assert client.post("/api/v1/device", json={"public_key": base64.b64encode(k384).decode()}, headers=h).status_code == 422
    assert client.post("/api/v1/device", json={"public_key": "pas une clé"}, headers=h).status_code == 422


def test_pairing_refused_with_claude_token(client):
    """Un jeton OAuth émis pour Claude (autre client) ne peut pas appairer un appareil."""
    tok = client.app.state.oauth._issue("client-claude", ["health:read"], "famille-test")
    _, pub, _ = new_key()
    h = {"Authorization": f"Bearer {tok.access_token}"}
    assert client.post("/api/v1/device", json={"public_key": pub}, headers=h).status_code == 403
    assert client.get("/api/v1/status", headers=h).status_code == 200  # la lecture, elle, reste permise


def test_optional_mode(client):
    key, did, _ = pair(client)
    assert client.post("/ingest", content=BODY, headers={**AUTH, "Content-Type": "application/json"}).status_code == 200
    assert client.post("/ingest", content=BODY, headers=signed(key, did, "POST", "/ingest", BODY)).status_code == 200
    other, _, _ = new_key()
    assert client.post("/ingest", content=BODY, headers=signed(other, did, "POST", "/ingest", BODY)).status_code == 401
    assert client.post("/ingest", content=BODY, headers=signed(key, "0" * 32, "POST", "/ingest", BODY)).status_code == 401
    assert client.post("/ingest", content=BODY, headers=signed(key, did, "POST", "/ingest", BODY,
                                                                ts=int(time.time()) - 900)).status_code == 401
    assert client.post("/ingest", content=BODY + b" ", headers=signed(key, did, "POST", "/ingest", BODY)).status_code == 401
    h = signed(key, did, "POST", "/ingest", BODY)
    assert client.post("/ingest", content=BODY, headers=h).status_code == 200
    assert client.post("/ingest", content=BODY, headers=h).status_code == 401  # rejeu


def test_required_mode(settings):
    with TestClient(create_app(dataclasses.replace(settings, device_signature="required")),
                    base_url="http://localhost", follow_redirects=False) as c:
        key, did, _ = pair(c)
        assert c.post("/ingest", content=BODY, headers={**AUTH, "Content-Type": "application/json"}).status_code == 401
        assert c.post("/ingest", content=BODY, headers=signed(key, did, "POST", "/ingest", BODY)).status_code == 200
        # le jeton reste exigé : une signature seule ne suffit pas
        h = signed(key, did, "POST", "/ingest", BODY)
        h["Authorization"] = "Bearer " + "x" * 40
        assert c.post("/ingest", content=BODY, headers=h).status_code == 401
        start = json.dumps({"size": 10, "sha256": "a" * 64}).encode()
        assert c.post("/ingest-export/start", content=start, headers={**AUTH, "Content-Type": "application/json"}).status_code == 401
        assert c.post("/ingest-export/start", content=start,
                      headers=signed(key, did, "POST", "/ingest-export/start", start)).status_code == 200
        # révocation : les écritures de ce téléphone sont refusées
        c.app.state.phones.revoke(did)
        assert c.post("/ingest", content=BODY, headers=signed(key, did, "POST", "/ingest", BODY)).status_code == 401
