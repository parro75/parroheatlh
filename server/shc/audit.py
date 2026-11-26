"""Journal des accès (traçabilité, esprit HDS) : qui a lu ou écrit quoi, quand, d'où, avec quel résultat.

- Une ligne par accès : lecture de l'app, outil MCP appelé par Claude, job de rapport, Home Assistant,
  écriture du téléphone, appairage, connexion (réussie ou non), émission et révocation de jetons.
- Jamais de donnée de santé : seulement la route ou l'outil, les paramètres de période, un compteur.
- **Chaîné** : chaque ligne porte l'empreinte SHA-256 de la précédente. Modifier ou supprimer une ligne
  casse la chaîne, que `verify()` (et `python -m shc.admin journal-verifier`) détecte. La copie nocturne
  chiffrée de la base fige l'état du journal hors du serveur.
- Conservation : AUDIT_RETENTION_DAYS (365 j par défaut) ; la purge garde la chaîne vérifiable à partir de
  la plus ancienne ligne restante.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
import time
from datetime import datetime, timezone
from typing import Any

GENESIS = "0" * 64
# Événements qui méritent une alerte (relayée par le job de surveillance, sans donnée de santé).
ALERT_ACTIONS = {"appairage", "connexion", "connexion-verrouillee", "jeton-rejoue", "client-enregistre",
                 "ecriture-refusee"}


def _digest(prev: str, row: tuple) -> str:
    return hashlib.sha256((prev + json.dumps(row, ensure_ascii=False, separators=(",", ":"))).encode()).hexdigest()


class AuditLog:
    SCHEMA = """CREATE TABLE IF NOT EXISTS access_log (
        seq INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL,
        detail TEXT, ip TEXT, status INTEGER, prev TEXT NOT NULL, hash TEXT NOT NULL)"""

    def __init__(self, db_path: str, retention_days: int = 365):
        self._conn = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.execute(self.SCHEMA)
        self._conn.execute("CREATE TABLE IF NOT EXISTS audit_ack (key TEXT PRIMARY KEY, seq INTEGER NOT NULL)")
        self._lock = threading.Lock()
        self.retention_days = retention_days

    def record(self, actor: str, action: str, detail: str = "", ip: str | None = None, status: int = 200) -> None:
        ts = datetime.now(timezone.utc).isoformat(timespec="seconds")
        row = (ts, actor[:80], action[:60], (detail or "")[:300], (ip or "")[:45], int(status))
        with self._lock:
            self._conn.execute("BEGIN IMMEDIATE")
            try:
                last = self._conn.execute("SELECT hash FROM access_log ORDER BY seq DESC LIMIT 1").fetchone()
                prev = last[0] if last else GENESIS
                self._conn.execute(
                    "INSERT INTO access_log (ts, actor, action, detail, ip, status, prev, hash) VALUES (?,?,?,?,?,?,?,?)",
                    (*row, prev, _digest(prev, row)))
                self._conn.execute("COMMIT")
            except BaseException:
                self._conn.execute("ROLLBACK")
                raise

    def entries(self, limit: int = 100, after: int = 0) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT seq, ts, actor, action, detail, ip, status FROM access_log WHERE seq > ? "
                "ORDER BY seq DESC LIMIT ?", (after, max(1, min(limit, 1000)))).fetchall()
        return [dict(zip(("seq", "ts", "actor", "action", "detail", "ip", "status"), r)) for r in rows]

    def alerts_after(self, seq: int) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                f"SELECT seq, ts, actor, action, detail, ip, status FROM access_log WHERE seq > ? AND action IN "
                f"({','.join('?' * len(ALERT_ACTIONS))}) ORDER BY seq", (seq, *sorted(ALERT_ACTIONS))).fetchall()
        return [dict(zip(("seq", "ts", "actor", "action", "detail", "ip", "status"), r)) for r in rows]

    def acked(self) -> int:
        with self._lock:
            r = self._conn.execute("SELECT seq FROM audit_ack WHERE key = 'alertes'").fetchone()
        return r[0] if r else 0

    def ack(self, seq: int) -> None:
        with self._lock:
            self._conn.execute("INSERT INTO audit_ack (key, seq) VALUES ('alertes', ?) "
                               "ON CONFLICT (key) DO UPDATE SET seq = MAX(seq, excluded.seq)", (int(seq),))

    def last_seq(self) -> int:
        with self._lock:
            r = self._conn.execute("SELECT MAX(seq) FROM access_log").fetchone()
        return r[0] or 0

    def verify(self) -> dict[str, Any]:
        """Recalcule la chaîne. ok=False et la première ligne fautive si une ligne a été modifiée ou retirée."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT seq, ts, actor, action, detail, ip, status, prev, hash FROM access_log ORDER BY seq").fetchall()
        prev = rows[0][7] if rows else GENESIS
        for seq, ts, actor, action, detail, ip, status, p, h in rows:
            if p != prev or _digest(p, (ts, actor, action, detail or "", ip or "", status)) != h:
                return {"ok": False, "entries": len(rows), "broken_at": seq}
            prev = h
        return {"ok": True, "entries": len(rows), "head": prev, "first": rows[0][1] if rows else None}

    def purge(self) -> int:
        cutoff = datetime.fromtimestamp(time.time() - self.retention_days * 86400, timezone.utc).isoformat(timespec="seconds")
        with self._lock:
            return self._conn.execute("DELETE FROM access_log WHERE ts < ?", (cutoff,)).rowcount

    def close(self) -> None:
        self._conn.close()
