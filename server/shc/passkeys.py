"""Clés d'accès WebAuthn (passkeys, clés de sécurité FIDO2) pour la connexion du propriétaire.

Tout authentificateur WebAuthn convient : clé physique (YubiKey… en USB ou NFC), passkey d'un gestionnaire
(1Password…), passkey du téléphone (Samsung Pass…). Plusieurs clés peuvent être enregistrées (une de
secours conseillée). La connexion devient : mot de passe + clé (ou TOTP en secours, selon AUTH_SECOND_FACTOR).

- Défis à usage unique, valables 5 min, liés à la demande de connexion (ou à la session de gestion).
- Compteur de signatures vérifié : un compteur qui recule trahit une clé clonée (refus).
- Gestion des clés (/passkeys) : session de 10 min ouverte par mot de passe + second facteur, cookie
  HttpOnly / Secure / SameSite=Strict, et contrôle de l'en-tête Origin sur chaque POST.
"""

from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import threading
import time
from typing import Any

from webauthn import (generate_authentication_options, generate_registration_options, options_to_json,
                      verify_authentication_response, verify_registration_response)
from webauthn.helpers import base64url_to_bytes, bytes_to_base64url
from webauthn.helpers.structs import (AuthenticatorSelectionCriteria, AuthenticatorTransport,
                                      PublicKeyCredentialDescriptor, ResidentKeyRequirement,
                                      UserVerificationRequirement)

USER_ID = b"parro-health-proprietaire"
CHALLENGE_TTL = 300
SESSION_TTL = 600
MAX_KEYS = 10


class PasskeyError(ValueError):
    pass


class Passkeys:
    SCHEMA = """
    CREATE TABLE IF NOT EXISTS webauthn_credentials (id TEXT PRIMARY KEY, public_key BLOB NOT NULL,
        sign_count INTEGER NOT NULL, name TEXT, transports TEXT, created REAL NOT NULL, last_used REAL);
    CREATE TABLE IF NOT EXISTS webauthn_challenges (key TEXT PRIMARY KEY, challenge BLOB NOT NULL, expires REAL NOT NULL);
    CREATE TABLE IF NOT EXISTS passkey_sessions (hash TEXT PRIMARY KEY, expires REAL NOT NULL);
    """

    def __init__(self, db_path: str, public_url: str):
        self._conn = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.executescript(self.SCHEMA)
        self._lock = threading.Lock()
        self.origin = public_url.rstrip("/")
        self.rp_id = self.origin.split("//", 1)[-1].split("/")[0].split(":")[0]

    def _q(self, sql: str, args: tuple = ()) -> list[tuple]:
        with self._lock:
            return self._conn.execute(sql, args).fetchall()

    def _x(self, sql: str, args: tuple = ()) -> int:
        with self._lock:
            return self._conn.execute(sql, args).rowcount

    # ---- clés enregistrées ----------------------------------------------------------------------------
    def list(self) -> list[dict[str, Any]]:
        rows = self._q("SELECT id, name, created, last_used FROM webauthn_credentials ORDER BY created")
        return [dict(zip(("id", "name", "created", "last_used"), r)) for r in rows]

    def count(self) -> int:
        return self._q("SELECT COUNT(*) FROM webauthn_credentials")[0][0]

    def delete(self, cred_id: str) -> bool:
        return self._x("DELETE FROM webauthn_credentials WHERE id = ?", (cred_id,)) == 1

    # ---- défis ----------------------------------------------------------------------------------------
    def _put_challenge(self, key: str, challenge: bytes) -> None:
        now = time.time()
        self._x("DELETE FROM webauthn_challenges WHERE expires < ?", (now,))
        self._x("INSERT OR REPLACE INTO webauthn_challenges (key, challenge, expires) VALUES (?, ?, ?)",
                (key, challenge, now + CHALLENGE_TTL))

    def _take_challenge(self, key: str) -> bytes:
        rows = self._q("SELECT challenge FROM webauthn_challenges WHERE key = ? AND expires > ?", (key, time.time()))
        self._x("DELETE FROM webauthn_challenges WHERE key = ?", (key,))  # usage unique, même en cas d'échec
        if not rows:
            raise PasskeyError("défi expiré ou inconnu : recommence")
        return rows[0][0]

    # ---- enregistrement ----------------------------------------------------------------------------
    def registration_options(self, session: str) -> str:
        if self.count() >= MAX_KEYS:
            raise PasskeyError(f"{MAX_KEYS} clés au plus")
        opts = generate_registration_options(
            rp_id=self.rp_id, rp_name="Parro Health", user_id=USER_ID, user_name="proprietaire",
            user_display_name="Propriétaire",
            authenticator_selection=AuthenticatorSelectionCriteria(
                resident_key=ResidentKeyRequirement.PREFERRED, user_verification=UserVerificationRequirement.PREFERRED),
            exclude_credentials=[PublicKeyCredentialDescriptor(id=base64url_to_bytes(k["id"])) for k in self.list()],
        )
        self._put_challenge("reg:" + hashlib.sha256(session.encode()).hexdigest(), opts.challenge)
        return options_to_json(opts)

    def register(self, session: str, credential: dict[str, Any], name: str) -> str:
        challenge = self._take_challenge("reg:" + hashlib.sha256(session.encode()).hexdigest())
        try:
            v = verify_registration_response(credential=credential, expected_challenge=challenge,
                                             expected_rp_id=self.rp_id, expected_origin=self.origin)
        except Exception as exc:  # la bibliothèque lève des types variés : tout échec = refus
            raise PasskeyError(f"enregistrement refusé : {exc}") from None
        cid = bytes_to_base64url(v.credential_id)
        transports = credential.get("response", {}).get("transports") or []
        self._x("INSERT INTO webauthn_credentials (id, public_key, sign_count, name, transports, created) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (cid, v.credential_public_key, v.sign_count, (name or "clé")[:60], json.dumps(transports)[:200], time.time()))
        return cid

    # ---- connexion ------------------------------------------------------------------------------------
    def authentication_options(self, req_id: str) -> str:
        creds = self._q("SELECT id, transports FROM webauthn_credentials")
        if not creds:
            raise PasskeyError("aucune clé enregistrée")
        allow = []
        for cid, tr in creds:
            ts = [AuthenticatorTransport(t) for t in json.loads(tr or "[]") if t in {x.value for x in AuthenticatorTransport}]
            allow.append(PublicKeyCredentialDescriptor(id=base64url_to_bytes(cid), transports=ts or None))
        opts = generate_authentication_options(rp_id=self.rp_id, allow_credentials=allow,
                                               user_verification=UserVerificationRequirement.PREFERRED)
        self._put_challenge("auth:" + req_id, opts.challenge)
        return options_to_json(opts)

    def verify(self, req_id: str, credential: dict[str, Any]) -> str:
        """Nom de la clé si l'assertion est valide ; lève PasskeyError sinon."""
        challenge = self._take_challenge("auth:" + req_id)
        cid = str(credential.get("id", ""))
        rows = self._q("SELECT public_key, sign_count, name FROM webauthn_credentials WHERE id = ?", (cid,))
        if not rows:
            raise PasskeyError("clé inconnue ou retirée")
        pub, count, name = rows[0]
        try:
            v = verify_authentication_response(credential=credential, expected_challenge=challenge,
                                               expected_rp_id=self.rp_id, expected_origin=self.origin,
                                               credential_public_key=pub, credential_current_sign_count=count)
        except Exception as exc:
            raise PasskeyError(f"clé refusée : {exc}") from None
        self._x("UPDATE webauthn_credentials SET sign_count = ?, last_used = ? WHERE id = ?",
                (v.new_sign_count, time.time(), cid))
        return name

    # ---- session de gestion (/passkeys) ---------------------------------------------------------------
    def new_session(self) -> str:
        tok = secrets.token_urlsafe(32)
        now = time.time()
        self._x("DELETE FROM passkey_sessions WHERE expires < ?", (now,))
        self._x("INSERT INTO passkey_sessions (hash, expires) VALUES (?, ?)",
                (hashlib.sha256(tok.encode()).hexdigest(), now + SESSION_TTL))
        return tok

    def session_ok(self, tok: str | None) -> bool:
        if not tok:
            return False
        return bool(self._q("SELECT 1 FROM passkey_sessions WHERE hash = ? AND expires > ?",
                            (hashlib.sha256(tok.encode()).hexdigest(), time.time())))

    def end_session(self, tok: str | None) -> None:
        if tok:
            self._x("DELETE FROM passkey_sessions WHERE hash = ?", (hashlib.sha256(tok.encode()).hexdigest(),))

    def close(self) -> None:
        self._conn.close()
