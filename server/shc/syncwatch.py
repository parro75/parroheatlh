"""Surveillance de l'âge de la dernière synchro du téléphone (règle de la maison : surveiller l'âge
des données, pas les codes de retour). Les seuils vivent ici, testables ; le job de la machine Ollama ne fait
qu'interroger et relayer la notification."""

from __future__ import annotations

import sqlite3
import threading
from datetime import datetime, timezone, tzinfo
from typing import Any

DAY_HOURS = range(9, 23)  # 9 h - 22 h 59, heure locale
DAY_LIMIT_H = 4.0
ANY_LIMIT_H = 9.0


class SyncWatch:
    SCHEMA = "CREATE TABLE IF NOT EXISTS sync_watch (k TEXT PRIMARY KEY, v TEXT)"

    def __init__(self, path: str):
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.execute(self.SCHEMA)
        self._lock = threading.Lock()

    def _get(self, k: str) -> str | None:
        with self._lock:
            r = self._conn.execute("SELECT v FROM sync_watch WHERE k = ?", (k,)).fetchone()
        return r[0] if r else None

    def _set(self, k: str, v: str | None) -> None:
        with self._lock:
            if v is None:
                self._conn.execute("DELETE FROM sync_watch WHERE k = ?", (k,))
            else:
                self._conn.execute("INSERT INTO sync_watch (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v",
                                   (k, v))

    def status(self, last_sync: str | None, now: datetime, tz: tzinfo) -> dict[str, Any]:
        """Action à relayer : « alert » (une fois par panne), « recovered » (une fois au retour) ou « none »."""
        alerted_for = self._get("alerted_for")  # last_sync au moment de l'alerte envoyée
        if last_sync is None:
            return {"last_phone_sync": None, "age_hours": None, "action": "none"}
        age = (now - datetime.fromisoformat(last_sync)).total_seconds() / 3600
        local_hour = now.astimezone(tz).hour
        late = age >= ANY_LIMIT_H or (age >= DAY_LIMIT_H and local_hour in DAY_HOURS)
        out = {"last_phone_sync": last_sync, "age_hours": round(age, 1)}
        if late and alerted_for != last_sync:
            when = datetime.fromisoformat(last_sync).astimezone(tz).strftime("%d/%m à %H h %M")
            return {**out, "action": "alert", "title": "Parro Health — plus de synchro",
                    "message": f"Le téléphone n'a rien envoyé depuis {round(age)} h (dernière synchro le {when}). "
                               "Ouvre Parro Health pour relancer la synchro et vérifier les réglages."}
        if alerted_for and alerted_for != last_sync and not late:
            return {**out, "action": "recovered", "title": "Parro Health — synchro revenue",
                    "message": "Le téléphone envoie de nouveau ses données."}
        return {**out, "action": "none"}

    def ack(self, action: str, last_sync: str) -> None:
        if action == "alert":
            self._set("alerted_for", last_sync)
        elif action == "recovered":
            self._set("alerted_for", None)

    def close(self) -> None:
        self._conn.close()


def now_utc() -> datetime:
    return datetime.now(timezone.utc)
