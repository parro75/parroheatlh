"""Réception d'un export Samsung Health envoyé par le téléphone, par morceaux (≤ 16 Mio : Caddy garde sa
limite de 20 Mo), puis import automatique (shc.samsung_export, mêmes garde-fous qu'un import manuel).

  POST /ingest-export/start              {"size": n, "sha256": "…"}  → {"id": …, "chunk_size": …}
  PUT  /ingest-export/{id}/{index}       corps = morceau n° index (dans l'ordre)
  POST /ingest-export/{id}/finish        → vérifie taille + SHA-256, lance l'import en arrière-plan
  GET  /ingest-export/{id}               → état : receiving | importing | done | error (+ résultat)

Authentification : jeton d'ingestion du téléphone (opération d'écriture, comme /ingest).
Un seul envoi à la fois ; le fichier est écrasé puis supprimé après l'import.
"""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import sqlite3
import threading
import time
from collections.abc import Callable
from typing import Any

CHUNK_SIZE = 15 * 1024 * 1024
MAX_SIZE = 600 * 1024 * 1024
STALE_SECONDS = 3600


class UploadError(ValueError):
    pass


class Uploads:
    SCHEMA = """CREATE TABLE IF NOT EXISTS export_uploads (
        id TEXT PRIMARY KEY, size INTEGER, sha256 TEXT, received INTEGER, next_index INTEGER,
        state TEXT, result TEXT, error TEXT, created REAL, updated REAL)"""

    def __init__(self, db_path: str, directory: str, run_import: Callable[[str], dict[str, Any]]):
        self.dir = directory
        os.makedirs(directory, mode=0o700, exist_ok=True)
        self._conn = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.execute(self.SCHEMA)
        self._lock = threading.Lock()
        self._run_import = run_import

    def _path(self, uid: str) -> str:
        return os.path.join(self.dir, f"export-{uid}.zip")

    def _row(self, uid: str) -> dict[str, Any] | None:
        with self._lock:
            r = self._conn.execute("SELECT id, size, sha256, received, next_index, state, result, error, updated "
                                   "FROM export_uploads WHERE id = ?", (uid,)).fetchone()
        if not r:
            return None
        keys = ("id", "size", "sha256", "received", "next_index", "state", "result", "error", "updated")
        d = dict(zip(keys, r))
        d["result"] = json.loads(d["result"]) if d["result"] else None
        return d

    def _update(self, uid: str, **fields: Any) -> None:
        fields["updated"] = time.time()
        cols = ", ".join(f"{k} = ?" for k in fields)
        with self._lock:
            self._conn.execute(f"UPDATE export_uploads SET {cols} WHERE id = ?", (*fields.values(), uid))

    @staticmethod
    def _wipe(path: str) -> None:
        """Écrase puis supprime (au mieux : pas de garantie sur SSD/ext4, mais rien ne traîne en clair)."""
        try:
            size = os.path.getsize(path)
            with open(path, "r+b") as f:
                for _ in range(0, size, 1 << 20):
                    f.write(b"\0" * (1 << 20))
            os.remove(path)
        except FileNotFoundError:
            pass

    def purge_stale(self) -> None:
        cutoff = time.time() - STALE_SECONDS
        with self._lock:
            rows = self._conn.execute("SELECT id FROM export_uploads WHERE state = 'receiving' AND updated < ?",
                                      (cutoff,)).fetchall()
        for (uid,) in rows:
            self._wipe(self._path(uid))
            self._update(uid, state="error", error="abandonné (plus d'une heure sans morceau)")

    def start(self, size: int, sha256: str) -> dict[str, Any]:
        self.purge_stale()
        if not 0 < size <= MAX_SIZE:
            raise UploadError(f"taille hors limites (1 octet à {MAX_SIZE // 2**20} Mio)")
        if len(sha256) != 64 or any(c not in "0123456789abcdef" for c in sha256):
            raise UploadError("sha256 hexadécimal attendu")
        with self._lock:
            busy = self._conn.execute("SELECT COUNT(*) FROM export_uploads WHERE state IN ('receiving','importing')").fetchone()[0]
        if busy:
            raise UploadError("un envoi est déjà en cours")
        uid = secrets.token_hex(16)
        now = time.time()
        with self._lock:
            self._conn.execute("INSERT INTO export_uploads VALUES (?, ?, ?, 0, 0, 'receiving', NULL, NULL, ?, ?)",
                               (uid, size, sha256, now, now))
        open(self._path(uid), "wb").close()
        os.chmod(self._path(uid), 0o600)
        return {"id": uid, "chunk_size": CHUNK_SIZE}

    def put(self, uid: str, index: int, data: bytes) -> dict[str, Any]:
        row = self._row(uid)
        if row is None or row["state"] != "receiving":
            raise UploadError("envoi inconnu ou terminé")
        if index == row["next_index"] - 1 and len(data) <= CHUNK_SIZE:
            return {"received": row["received"], "next_index": row["next_index"]}  # morceau rejoué : idempotent
        if index != row["next_index"]:
            raise UploadError(f"morceau {index} reçu, {row['next_index']} attendu")
        if not data or len(data) > CHUNK_SIZE or row["received"] + len(data) > row["size"]:
            raise UploadError("taille de morceau invalide")
        with open(self._path(uid), "ab") as f:
            f.write(data)
        self._update(uid, received=row["received"] + len(data), next_index=index + 1)
        return {"received": row["received"] + len(data), "next_index": index + 1}

    def finish(self, uid: str) -> dict[str, Any]:
        row = self._row(uid)
        if row is None or row["state"] != "receiving":
            raise UploadError("envoi inconnu ou terminé")
        path = self._path(uid)
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for block in iter(lambda: f.read(1 << 20), b""):
                h.update(block)
        if row["received"] != row["size"] or h.hexdigest() != row["sha256"]:
            self._wipe(path)
            self._update(uid, state="error", error="fichier incomplet ou altéré (taille ou SHA-256)")
            raise UploadError("fichier incomplet ou altéré")
        self._update(uid, state="importing")
        threading.Thread(target=self._import, args=(uid, path), daemon=True, name=f"import-{uid[:6]}").start()
        return {"state": "importing"}

    def _import(self, uid: str, path: str) -> None:
        try:
            result = self._run_import(path)
            self._update(uid, state="done", result=json.dumps(result))
        except Exception as e:  # zip illisible, format inattendu… : l'état le dit ; les lots déjà écrits
            # restent (import idempotent : un nouvel envoi complétera)
            self._update(uid, state="error", error=f"{e.__class__.__name__} : {e}"[:300])
        finally:
            self._wipe(path)

    def status(self, uid: str) -> dict[str, Any]:
        row = self._row(uid)
        if row is None:
            raise UploadError("envoi inconnu")
        return {k: row[k] for k in ("id", "size", "received", "next_index", "state", "result", "error")}

    def last_done(self) -> float | None:
        with self._lock:
            r = self._conn.execute("SELECT MAX(updated) FROM export_uploads WHERE state = 'done'").fetchone()
        return r[0] if r and r[0] else None

    def close(self) -> None:
        self._conn.close()
