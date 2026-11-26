"""Clés d'accès WebAuthn : enregistrement, connexion, rejeu, clé clonée, politique « clé seulement ».

Un authentificateur LOGICIEL (clé P-256, attestation « none ») joue le rôle d'une YubiKey ou d'une passkey :
mêmes messages que le navigateur, mêmes vérifications côté serveur.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import struct
from urllib.parse import parse_qs, urlparse

import cbor2
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from webauthn.helpers import base64url_to_bytes, bytes_to_base64url

from conftest import PASSWORD, fresh_totp, pkce
from shc.app import create_app

ORIGIN = "http://localhost"
RP_ID = "localhost"
APP_ID = "parro-health-android"
APP_CB = "fr.parro.health://oauth/callback"


class SoftKey:
    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.cred_id = hashlib.sha256(str(id(self)).encode()).digest()[:16]
        self.count = 0

    def _cose(self) -> bytes:
        n = self.key.public_key().public_numbers()
        return cbor2.dumps({1: 2, 3: -7, -1: 1, -2: n.x.to_bytes(32, "big"), -3: n.y.to_bytes(32, "big")})

    def create(self, options: dict) -> dict:
        cdata = json.dumps({"type": "webauthn.create", "challenge": options["challenge"], "origin": ORIGIN}).encode()
        auth = (hashlib.sha256(RP_ID.encode()).digest() + bytes([0x45]) + struct.pack(">I", 0) + b"\0" * 16
                + struct.pack(">H", len(self.cred_id)) + self.cred_id + self._cose())
        att = cbor2.dumps({"fmt": "none", "attStmt": {}, "authData": auth})
        cid = bytes_to_base64url(self.cred_id)
        return {"id": cid, "rawId": cid, "type": "public-key", "clientExtensionResults": {},
                "response": {"clientDataJSON": bytes_to_base64url(cdata), "attestationObject": bytes_to_base64url(att),
                             "transports": ["usb", "nfc"]}}

    def get(self, options: dict, count: int | None = None) -> dict:
        self.count = self.count + 1 if count is None else count
        cdata = json.dumps({"type": "webauthn.get", "challenge": options["challenge"], "origin": ORIGIN}).encode()
        auth = hashlib.sha256(RP_ID.encode()).digest() + bytes([0x05]) + struct.pack(">I", self.count)
        sig = self.key.sign(auth + hashlib.sha256(cdata).digest(), ec.ECDSA(hashes.SHA256()))
        cid = bytes_to_base64url(self.cred_id)
        return {"id": cid, "rawId": cid, "type": "public-key", "clientExtensionResults": {},
                "response": {"clientDataJSON": bytes_to_base64url(cdata), "authenticatorData": bytes_to_base64url(auth),
                             "signature": bytes_to_base64url(sig), "userHandle": None}}


def open_keys_session(client):
    page = client.get("/passkeys").text
    gate = page.split('name="gate" value="')[1].split('"')[0]
    r = client.post("/passkeys", data={"gate": gate, "password": PASSWORD, "code": fresh_totp(client.app)},
                    headers={"Origin": ORIGIN})
    assert r.status_code == 303, r.text
    client.cookies.set("shc_cles", r.cookies["shc_cles"], path="/passkeys")


def enroll(client, key: SoftKey, name="YubiKey test"):
    o = client.post("/passkeys/options", headers={"Origin": ORIGIN})
    assert o.status_code == 200, o.text
    r = client.post("/passkeys/enregistrer", json={"name": name, "credential": key.create(o.json())},
                    headers={"Origin": ORIGIN})
    assert r.status_code == 200, r.text


def login_req(client) -> str:
    _, challenge = pkce()
    r = client.get("/authorize", params={"response_type": "code", "client_id": APP_ID, "redirect_uri": APP_CB,
                                         "code_challenge": challenge, "code_challenge_method": "S256", "state": "s"})
    return parse_qs(urlparse(r.headers["location"]).query)["req"][0]


def passkey_login(client, key: SoftKey, password=PASSWORD, count=None):
    req = login_req(client)
    o = client.post("/login/cle-options", data={"req": req})
    assert o.status_code == 200, o.text
    return client.post("/login", data={"req": req, "password": password,
                                       "webauthn": json.dumps(key.get(o.json(), count))})


def test_enroll_and_login_with_key(client):
    open_keys_session(client)
    key = SoftKey()
    enroll(client, key)
    assert client.app.state.oauth.passkeys.count() == 1
    page = client.get(f"/login?req={login_req(client)}")
    assert "Continuer avec ma clé" in page.text and "script-src 'self'" in page.headers["content-security-policy"]
    r = passkey_login(client, key)
    assert r.status_code == 302 and r.headers["location"].startswith(APP_CB)
    actions = [(e["action"], e["detail"]) for e in client.app.state.audit.entries(50)]
    assert ("cle-ajoutee", "« YubiKey test »") in actions
    assert any(a == "connexion" and "clé « YubiKey test »" in d for a, d in actions)


def test_key_refusals(client):
    open_keys_session(client)
    key = SoftKey()
    enroll(client, key)
    assert passkey_login(client, key, password="mauvais mot de passe").status_code == 200  # formulaire + erreur
    other = SoftKey()  # clé jamais enregistrée
    other.cred_id = key.cred_id
    assert passkey_login(client, other).status_code == 200
    assert passkey_login(client, key).status_code == 302
    assert passkey_login(client, key, count=1).status_code == 200  # compteur qui recule : clé clonée
    key.count = 5  # la vraie clé reprend au-delà du compteur connu du serveur
    # rejeu d'une assertion : le défi est consommé
    req = login_req(client)
    o = client.post("/login/cle-options", data={"req": req}).json()
    a = json.dumps(key.get(o))
    assert client.post("/login", data={"req": req, "password": PASSWORD, "webauthn": a}).status_code == 302
    req2 = login_req(client)
    assert client.post("/login", data={"req": req2, "password": PASSWORD, "webauthn": a}).status_code == 200


def test_keys_page_requires_same_origin_and_session(client):
    assert client.post("/passkeys/options").status_code == 401
    page = client.get("/passkeys").text
    gate = page.split('name="gate" value="')[1].split('"')[0]
    r = client.post("/passkeys", data={"gate": gate, "password": PASSWORD, "code": fresh_totp(client.app)})
    assert r.status_code == 400  # pas d'en-tête Origin : refusé (CSRF)


def test_firefox_origin_null_with_fetch_metadata(client):
    """Firefox envoie « Origin: null » (ancienne politique no-referrer) : accepté si Sec-Fetch-Site = same-origin."""
    page = client.get("/passkeys")
    assert page.headers["referrer-policy"] == "same-origin"
    gate = page.text.split('name="gate" value="')[1].split('"')[0]
    data = {"gate": gate, "password": PASSWORD, "code": fresh_totp(client.app)}
    assert client.post("/passkeys", data=data, headers={"Origin": "null", "Sec-Fetch-Site": "cross-site"}).status_code == 400
    assert client.post("/passkeys", data=data, headers={"Origin": "https://evil.example"}).status_code == 400
    r = client.post("/passkeys", data=data, headers={"Origin": "null", "Sec-Fetch-Site": "same-origin"})
    assert r.status_code == 303


def test_passkey_only_policy(settings):
    with TestClient(create_app(dataclasses.replace(settings, second_factor="passkey")),
                    base_url="http://localhost", follow_redirects=False) as c:
        open_keys_session(c)  # aucune clé encore : le TOTP reste accepté (pas d'enfermement)
        key = SoftKey()
        enroll(c, key)
        req = login_req(c)
        assert "Code TOTP" not in c.get(f"/login?req={req}").text
        assert c.post("/login", data={"req": req, "password": PASSWORD, "code": fresh_totp(c.app)}).status_code == 200
        assert passkey_login(c, key).status_code == 302
