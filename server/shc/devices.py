"""Téléphones appairés : chaque écriture est SIGNÉE par une clé qui ne quitte jamais le téléphone.

Le jeton d'ingestion seul ne suffit plus (mode « required ») : il faut aussi une signature ECDSA P-256 faite
par une clé générée dans le Keystore matériel du téléphone (non exportable). Un jeton volé (fichier .env,
gestionnaire de secrets, copie d'écran…) ne permet donc ni d'écrire ni de supprimer des mesures.

Appairage : POST /api/v1/device avec un jeton OAuth (donc après mot de passe + TOTP) et la clé publique.

Signature d'une requête (en-têtes) :
  X-Parro-Device     identifiant de l'appareil (32 hex, empreinte de la clé publique)
  X-Parro-Timestamp  secondes epoch (± 5 min autour de l'heure du serveur)
  X-Parro-Signature  base64( ECDSA-SHA256( METHODE \\n CHEMIN \\n TIMESTAMP \\n sha256hex(CORPS) ) )
Une signature déjà vue est refusée (anti-rejeu, mémoire de 10 min).
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import sqlite3
import threading
import time
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_der_public_key

MODES = ("off", "optional", "required")
MAX_DEVICES = 5
CLOCK_SKEW = 300
REPLAY_WINDOW = 600


class DeviceError(ValueError):
    pass


def key_id(der: bytes) -> str:
    return hashlib.sha256(der).hexdigest()[:32]


def canonical(method: str, path: str, ts: str, body: bytes) -> bytes:
    return f"{method.upper()}\n{path}\n{ts}\n{hashlib.sha256(body).hexdigest()}".encode()


class Devices:
    SCHEMA = """CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY, public_key TEXT NOT NULL, label TEXT, created REAL NOT NULL, last_used REAL)"""

    def __init__(self, db_path: str):
        self._conn = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.execute(self.SCHEMA)
        self._lock = threading.Lock()
        self._seen: dict[str, float] = {}

    # ---- appairage --------------------------------------------------------------------------------
    def register(self, public_key_b64: str, label: str) -> str:
        try:
            der = base64.b64decode(public_key_b64, validate=True)
            key = load_der_public_key(der)
        except (binascii.Error, ValueError) as exc:
            raise DeviceError("clé publique illisible (DER X.509 en base64 attendu)") from exc
        if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
            raise DeviceError("clé ECDSA P-256 attendue")
        did = key_id(der)
        with self._lock:
            if self._conn.execute("SELECT 1 FROM devices WHERE id = ?", (did,)).fetchone():
                return did  # déjà appairé : idempotent
            if self._conn.execute("SELECT COUNT(*) FROM devices").fetchone()[0] >= MAX_DEVICES:
                raise DeviceError(f"{MAX_DEVICES} appareils au plus : révoquer un ancien (python -m shc.admin devices)")
            self._conn.execute("INSERT INTO devices (id, public_key, label, created) VALUES (?, ?, ?, ?)",
                               (did, base64.b64encode(der).decode(), label[:80], time.time()))
        return did

    def list(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute("SELECT id, label, created, last_used FROM devices ORDER BY created").fetchall()
        return [dict(zip(("id", "label", "created", "last_used"), r)) for r in rows]

    def revoke(self, did: str) -> bool:
        with self._lock:
            return self._conn.execute("DELETE FROM devices WHERE id = ?", (did,)).rowcount == 1

    def count(self) -> int:
        with self._lock:
            return self._conn.execute("SELECT COUNT(*) FROM devices").fetchone()[0]

    # ---- vérification -----------------------------------------------------------------------------
    def verify(self, method: str, path: str, headers: Any, body: bytes, now: float | None = None) -> str | None:
        """Identifiant de l'appareil si la requête est signée correctement ; None si elle n'est PAS signée.
        Lève DeviceError si elle est signée mais invalide (mauvaise clé, périmée, rejouée…)."""
        did, ts, sig = (headers.get("x-parro-device"), headers.get("x-parro-timestamp"),
                        headers.get("x-parro-signature"))
        if not (did or ts or sig):
            return None
        if not (did and ts and sig):
            raise DeviceError("signature incomplète")
        now = time.time() if now is None else now
        try:
            t = int(ts)
            raw_sig = base64.b64decode(sig, validate=True)
        except (ValueError, binascii.Error):
            raise DeviceError("signature mal formée") from None
        if abs(now - t) > CLOCK_SKEW:
            raise DeviceError("horodatage hors fenêtre (horloge du téléphone ?)")
        with self._lock:
            row = self._conn.execute("SELECT public_key FROM devices WHERE id = ?", (did,)).fetchone()
        if row is None:
            raise DeviceError("appareil inconnu ou révoqué")
        key = load_der_public_key(base64.b64decode(row[0]))
        try:
            key.verify(raw_sig, canonical(method, path, ts, body), ec.ECDSA(hashes.SHA256()))
        except InvalidSignature:
            raise DeviceError("signature invalide") from None
        fp = hashlib.sha256(raw_sig).hexdigest()
        with self._lock:
            self._seen = {k: v for k, v in self._seen.items() if v > now - REPLAY_WINDOW}
            if fp in self._seen:
                raise DeviceError("requête rejouée")
            self._seen[fp] = now
            self._conn.execute("UPDATE devices SET last_used = ? WHERE id = ?", (now, did))
        return did

    def close(self) -> None:
        self._conn.close()
