"""Surveillance de l'âge de la dernière synchro du téléphone (règle de la maison : surveiller l'âge
des données, pas les codes de retour). Les seuils vivent ici, testables ; le job de rapport ne fait
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

    # ---- État du téléphone transmis avec chaque envoi signé (exemption de l'optimisation de batterie) ----

    def phone_state(self, battery_exempt: bool, now: datetime) -> None:
        """Mémorise l'état déclaré par l'app. Android retire l'exemption sans prévenir (réinstallation…) :
        sans elle, les synchros automatiques sont retenues des heures pendant la veille du téléphone."""
        self._set("battery_exempt", "1" if battery_exempt else "0")
        self._set("battery_seen_at", now.isoformat())

    def battery_status(self) -> dict[str, Any]:
        """« battery_alert » une fois quand l'exemption tombe, « battery_ok » une fois quand elle revient."""
        state, alerted = self._get("battery_exempt"), self._get("battery_alerted")
        out = {"battery_exempt": None if state is None else state == "1", "seen_at": self._get("battery_seen_at")}
        if state == "0" and not alerted:
            return {**out, "action": "battery_alert", "title": "Parro Health — synchro automatique freinée",
                    "message": "Android a retiré à Parro Health l'exemption de l'optimisation de batterie : les synchros "
                               "automatiques seront retardées de plusieurs heures quand le téléphone est en veille. "
                               "Ouvre l'app et touche « Rétablir » sur la bannière."}
        if state == "1" and alerted:
            return {**out, "action": "battery_ok", "title": "Parro Health — synchro automatique rétablie",
                    "message": "Parro Health est de nouveau exemptée de l'optimisation de batterie."}
        return {**out, "action": "none"}

    def ack_battery(self, action: str) -> None:
        if action == "battery_alert":
            self._set("battery_alerted", "1")
        elif action == "battery_ok":
            self._set("battery_alerted", None)

    REMINDER_DAYS = 30
    REMINDER_HOURS = range(10, 20)

    def export_reminder(self, last_export: str | None, now: datetime, tz: tzinfo) -> dict[str, Any]:
        """Rappel d'export Samsung Health : une fois tous les 30 jours si le dernier import a plus de 30 jours."""
        age_days = None if last_export is None else (now - datetime.fromisoformat(last_export)).total_seconds() / 86400
        last_sent = self._get("export_reminder_at")
        since_sent = None if last_sent is None else (now - datetime.fromisoformat(last_sent)).total_seconds() / 86400
        due = (age_days is None or age_days >= self.REMINDER_DAYS) and (since_sent is None or since_sent >= self.REMINDER_DAYS)
        if not due or now.astimezone(tz).hour not in self.REMINDER_HOURS:
            return {"action": "none", "export_age_days": None if age_days is None else round(age_days, 1)}
        return {"action": "export_reminder", "export_age_days": None if age_days is None else round(age_days),
                "title": "Parro Health — export Samsung à faire",
                "message": "Samsung Health → Réglages → Télécharger les données personnelles ; dans Mes fichiers, "
                           "appui long sur le dossier → Compresser, puis partager le zip vers Parro Health. "
                           "La HRV et les scores resteront à jour."}

    def ack_reminder(self, now: datetime) -> None:
        self._set("export_reminder_at", now.isoformat())

    def ack(self, action: str, last_sync: str) -> None:
        if action == "alert":
            self._set("alerted_for", last_sync)
        elif action == "recovered":
            self._set("alerted_for", None)

    def close(self) -> None:
        self._conn.close()


def now_utc() -> datetime:
    return datetime.now(timezone.utc)
