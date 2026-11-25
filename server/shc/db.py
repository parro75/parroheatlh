"""Stockage SQLite : une table d'enregistrements, upsert idempotent sur (type, id)."""

from __future__ import annotations

import json
import sqlite3
import threading
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone, tzinfo
from typing import Any

# Types acceptés à l'ingestion — le téléphone n'envoie que ceux-là.
RECORD_TYPES = frozenset(
    {
        "steps_daily",  # agrégat Health Connect par jour local
        "heart_rate_daily",  # agrégat min/moy/max par jour local
        "resting_heart_rate",
        "hrv_rmssd",
        "sleep_session",
        "weight",
        "body_fat",
        "oxygen_saturation",
        "exercise_session",
    }
)

# La nuit appartient au jour du réveil.
_DATE_FROM_END = frozenset({"sleep_session"})

SCHEMA = """
CREATE TABLE IF NOT EXISTS records (
    type        TEXT NOT NULL,
    id          TEXT NOT NULL,
    start       TEXT NOT NULL,
    end         TEXT,
    start_ts    REAL NOT NULL,
    local_date  TEXT NOT NULL,
    data        TEXT NOT NULL,
    received_at TEXT NOT NULL,
    PRIMARY KEY (type, id)
);
CREATE INDEX IF NOT EXISTS records_type_date ON records (type, local_date);
CREATE TABLE IF NOT EXISTS ingests (
    received_at TEXT NOT NULL,
    count       INTEGER NOT NULL,
    client      TEXT
);
"""


class RecordError(ValueError):
    """Enregistrement refusé à l'ingestion."""


def parse_offset(value: str | None) -> timezone | None:
    if not value:
        return None
    if value in ("Z", "z"):
        return timezone.utc
    try:
        sign = -1 if value[0] == "-" else 1
        hh, mm = value.lstrip("+-").split(":")[:2]
        return timezone(sign * timedelta(hours=int(hh), minutes=int(mm)))
    except (ValueError, IndexError) as exc:
        raise RecordError(f"zone_offset invalide : {value!r}") from exc


def parse_instant(value: Any, field: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise RecordError(f"{field} manquant")
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise RecordError(f"{field} n'est pas une date ISO 8601 : {value!r}") from exc
    if dt.tzinfo is None:
        raise RecordError(f"{field} sans décalage horaire : {value!r}")
    return dt


def local_date_of(rec_type: str, start: datetime, end: datetime | None, offset: tzinfo | None, fallback: tzinfo) -> date:
    ref = end if (rec_type in _DATE_FROM_END and end is not None) else start
    return ref.astimezone(offset or ref.tzinfo or fallback).date()


@dataclass(frozen=True)
class Row:
    type: str
    id: str
    start: str
    end: str | None
    local_date: str
    data: dict[str, Any]


class Store:
    def __init__(self, path: str, fallback_tz: tzinfo):
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.executescript(SCHEMA)
        self._lock = threading.Lock()
        self.fallback_tz = fallback_tz

    def close(self) -> None:
        self._conn.close()

    # ---- écriture -------------------------------------------------------
    def _normalise(self, raw: Any) -> tuple:
        if not isinstance(raw, dict):
            raise RecordError("enregistrement non objet")
        rec_type = raw.get("type")
        if rec_type not in RECORD_TYPES:
            raise RecordError(f"type inconnu : {rec_type!r}")
        rec_id = raw.get("id")
        if not isinstance(rec_id, str) or not rec_id or len(rec_id) > 200:
            raise RecordError("id manquant ou trop long")
        start = parse_instant(raw.get("start"), "start")
        end = parse_instant(raw["end"], "end") if raw.get("end") else None
        if end is not None and end < start:
            raise RecordError("end précède start")
        data = raw.get("data") or {}
        if not isinstance(data, dict):
            raise RecordError("data doit être un objet")
        offset = parse_offset(raw.get("zone_offset"))
        local = local_date_of(rec_type, start, end, offset, self.fallback_tz)
        return (
            rec_type,
            rec_id,
            raw["start"],
            raw.get("end"),
            start.timestamp(),
            local.isoformat(),
            json.dumps(data, separators=(",", ":"), ensure_ascii=False),
        )

    def upsert(self, records: Iterable[Any], client: str | None = None) -> int:
        """Valide TOUT puis écrit en une transaction : un lot est accepté ou refusé en bloc."""
        rows = []
        for i, raw in enumerate(records):
            try:
                rows.append(self._normalise(raw))
            except RecordError as exc:
                raise RecordError(f"records[{i}] : {exc}") from None
        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        with self._lock:
            self._conn.execute("BEGIN")
            try:
                self._conn.executemany(
                    """INSERT INTO records (type, id, start, end, start_ts, local_date, data, received_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                       ON CONFLICT (type, id) DO UPDATE SET
                         start=excluded.start, end=excluded.end, start_ts=excluded.start_ts,
                         local_date=excluded.local_date, data=excluded.data, received_at=excluded.received_at""",
                    [(*r, now) for r in rows],
                )
                self._conn.execute(
                    "INSERT INTO ingests (received_at, count, client) VALUES (?, ?, ?)", (now, len(rows), client)
                )
                self._conn.execute("COMMIT")
            except BaseException:
                self._conn.execute("ROLLBACK")
                raise
        return len(rows)

    # ---- lecture --------------------------------------------------------
    def fetch(self, rec_type: str, start: date, end: date) -> list[Row]:
        with self._lock:
            cur = self._conn.execute(
                """SELECT type, id, start, end, local_date, data FROM records
                   WHERE type = ? AND local_date BETWEEN ? AND ?
                   ORDER BY local_date, start_ts""",
                (rec_type, start.isoformat(), end.isoformat()),
            )
            return [Row(t, i, s, e, d, json.loads(js)) for t, i, s, e, d, js in cur.fetchall()]

    def status(self) -> dict[str, Any]:
        with self._lock:
            last = self._conn.execute(
                "SELECT received_at, count, client FROM ingests ORDER BY rowid DESC LIMIT 1"
            ).fetchone()
            per_type = self._conn.execute(
                """SELECT type, COUNT(*), MIN(local_date), MAX(local_date), MAX(received_at)
                   FROM records GROUP BY type ORDER BY type"""
            ).fetchall()
        return {
            "last_ingest": (
                {"received_at": last[0], "records": last[1], "client": last[2]} if last else None
            ),
            "types": {
                t: {"records": n, "first_day": a, "last_day": b, "last_received": r}
                for t, n, a, b, r in per_type
            },
        }
