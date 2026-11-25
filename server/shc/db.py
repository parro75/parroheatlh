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
        "journal",  # événement noté dans l'app (étiquettes + note) ; data.deleted = suppression
    }
)

# Types qui n'arrivent QUE par l'import d'un export Samsung Health (Health Connect ne les publie pas).
IMPORT_ONLY_TYPES = frozenset(
    {
        "sleep_score",  # score et récupérations d'une nuit (rattaché au jour du réveil)
        "vitals_daily",  # vitaux nocturnes : HRV, FC de sommeil, respiration, température cutanée, SpO2
        "energy_score",  # score d'énergie (vitality score)
        "stress_daily",  # agrégat quotidien des mesures de stress
        "training_load_daily",  # charge d'entraînement aiguë / chronique, temps de récupération
    }
)
ALL_TYPES = RECORD_TYPES | IMPORT_ONLY_TYPES

# La nuit appartient au jour du réveil.
_DATE_FROM_END = frozenset({"sleep_session", "sleep_score"})

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
    def _normalise(self, raw: Any, allowed: frozenset[str] = RECORD_TYPES) -> tuple:
        if not isinstance(raw, dict):
            raise RecordError("enregistrement non objet")
        rec_type = raw.get("type")
        if rec_type not in allowed:
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

    def upsert(self, records: Iterable[Any], client: str | None = None, *, mode: str = "upsert",
               allowed: frozenset[str] = RECORD_TYPES) -> int:
        """Valide TOUT puis écrit en une transaction : un lot est accepté ou refusé en bloc.

        mode="upsert" (téléphone) remplace un enregistrement existant ; mode="ignore" (import d'export)
        n'écrit que ce qui manque, si bien que Health Connect garde toujours la priorité.
        Renvoie le nombre de lignes réellement écrites."""
        if mode not in ("upsert", "ignore"):
            raise ValueError(mode)
        rows = []
        for i, raw in enumerate(records):
            try:
                rows.append(self._normalise(raw, allowed))
            except RecordError as exc:
                raise RecordError(f"records[{i}] : {exc}") from None
        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        with self._lock:
            self._conn.execute("BEGIN")
            try:
                conflict = ("DO NOTHING" if mode == "ignore" else
                            """DO UPDATE SET
                         start=excluded.start, end=excluded.end, start_ts=excluded.start_ts,
                         local_date=excluded.local_date, data=excluded.data, received_at=excluded.received_at""")
                before = self._conn.total_changes
                self._conn.executemany(
                    f"""INSERT INTO records (type, id, start, end, start_ts, local_date, data, received_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                       ON CONFLICT (type, id) {conflict}""",
                    [(*r, now) for r in rows],
                )
                written = self._conn.total_changes - before
                self._conn.execute(
                    "INSERT INTO ingests (received_at, count, client) VALUES (?, ?, ?)", (now, written, client)
                )
                self._conn.execute("COMMIT")
            except BaseException:
                self._conn.execute("ROLLBACK")
                raise
        return written

    def spans(self, rec_type: str) -> list[tuple[float, float]]:
        """(début, fin) en secondes epoch de tous les enregistrements d'un type — pour éviter les doublons
        à l'import (une nuit ou une séance déjà reçue de Health Connect n'est pas réimportée)."""
        with self._lock:
            rows = self._conn.execute("SELECT start_ts, end FROM records WHERE type = ?", (rec_type,)).fetchall()
        out = []
        for start_ts, end in rows:
            end_ts = datetime.fromisoformat(end.replace("Z", "+00:00")).timestamp() if end else start_ts
            out.append((start_ts, end_ts))
        return out

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

    def last_ingest_from(self, client_prefix: str) -> str | None:
        """Heure (UTC ISO) de la dernière ingestion d'un client donné, ex. « SanteSync » pour le téléphone."""
        with self._lock:
            row = self._conn.execute(
                "SELECT received_at FROM ingests WHERE client LIKE ? ORDER BY rowid DESC LIMIT 1",
                (client_prefix.replace("%", "") + "%",)).fetchone()
        return row[0] if row else None

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
