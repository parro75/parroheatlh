"""Bilan quotidien : faits et signaux CALCULÉS ici (règles testables), jamais par le modèle de langage.

Le modèle local (machine Ollama) ne fait que rédiger le texte à partir de ces faits — règle de la maison :
« l'IA décrit et résume, les règles décident ».
"""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import date, datetime, timedelta, timezone
from statistics import mean, pstdev
from typing import Any

from . import queries
from .db import Store

SHORT_NIGHT_MIN = 6 * 60
RHR_HIGH_DELTA = 5  # bpm au-dessus de la moyenne 28 j
RHR_LOW_DELTA = -4
HRV_LOW_RATIO = 0.8
HRV_HIGH_RATIO = 1.2
SPO2_LOW = 90
MIN_BASELINE = 7  # nuits/jours de référence minimum pour juger un écart


def _r(x: float | None, nd: int = 0):
    return None if x is None else (round(x) if nd == 0 else round(x, nd))


def _main_night(store: Store, d: date) -> dict[str, Any] | None:
    nights = queries.sleep(store, d, d)["sessions"]
    if not nights:
        return None
    return max(nights, key=lambda n: n.get("asleep_min") or n.get("in_bed_min") or 0)


def _asleep(n: dict[str, Any] | None) -> float | None:
    if not n:
        return None
    return n.get("asleep_min") if n.get("asleep_min") is not None else n.get("in_bed_min")


def _per_day(store: Store, rtype: str, field: str, start: date, end: date) -> dict[str, float]:
    out: dict[str, float] = {}
    for r in store.fetch(rtype, start, end):
        v = r.data.get(field)
        if isinstance(v, (int, float)):
            out[r.local_date] = float(v)  # le dernier du jour l'emporte
    return out


def facts(store: Store, d: date, now: datetime | None = None) -> dict[str, Any]:
    """Faits du bilan du jour d (jour du réveil) et signaux, comparés aux moyennes personnelles."""
    flags: list[dict[str, str]] = []
    out: dict[str, Any] = {"date": d.isoformat()}
    yesterday = d - timedelta(days=1)
    b28 = d - timedelta(days=28)

    # ---- nuit -----------------------------------------------------------------------------------
    night = _main_night(store, d)
    hist = [_asleep(_main_night(store, d - timedelta(days=k))) for k in range(1, 29)]
    hist = [h for h in hist if h]
    if night:
        a = _asleep(night)
        out["night"] = {k: night.get(k) for k in ("bedtime", "wake", "asleep_min", "in_bed_min", "stages_min",
                                                   "score", "mental_recovery", "physical_recovery") if night.get(k) is not None}
        out["night"]["avg7_asleep_min"] = _r(mean(hist[:7])) if hist[:7] else None
        out["night"]["avg28_asleep_min"] = _r(mean(hist)) if hist else None
        if a is not None and a < SHORT_NIGHT_MIN:
            prev = [_asleep(_main_night(store, d - timedelta(days=k))) for k in (1, 2)]
            if all(p is not None and p < SHORT_NIGHT_MIN for p in prev):
                flags.append({"level": "attention", "text": f"3e nuit courte d'affilée ({queries.hm_text(a)})"})
            else:
                flags.append({"level": "attention", "text": f"Nuit courte : {queries.hm_text(a)}"
                              + (f" (moyenne 28 j : {queries.hm_text(mean(hist))})" if hist else "")})
        elif a is not None and len(hist) >= MIN_BASELINE and a >= mean(hist) + 60:
            flags.append({"level": "info", "text": f"Nuit plus longue que d'habitude : {queries.hm_text(a)}"})
        if night.get("score") is not None and night["score"] < 60:
            flags.append({"level": "attention", "text": f"Score de sommeil bas : {night['score']}"})

    # ---- FC au repos (calculée sur le téléphone) --------------------------------------------------
    rhr_today = _per_day(store, "resting_heart_rate", "bpm", d, d)
    rhr_hist = list(_per_day(store, "resting_heart_rate", "bpm", b28, yesterday).values())
    if rhr_today:
        v = rhr_today[d.isoformat()]
        sleeping = _per_day(store, "resting_heart_rate", "sleeping_avg", d, d).get(d.isoformat())
        rec: dict[str, Any] = {"bpm": _r(v), "sleeping_avg": sleeping}
        if len(rhr_hist) >= MIN_BASELINE:
            m = mean(rhr_hist)
            rec.update(avg28=_r(m, 1), sd28=_r(pstdev(rhr_hist), 1), delta=_r(v - m, 1))
            if v - m >= RHR_HIGH_DELTA:
                flags.append({"level": "attention",
                              "text": f"FC au repos élevée : {_r(v)} bpm (+{_r(v - m, 1)} vs moyenne 28 j)"})
            elif v - m <= RHR_LOW_DELTA:
                flags.append({"level": "info", "text": f"FC au repos basse : {_r(v)} bpm ({_r(v - m, 1)} vs moyenne 28 j)"})
        out["resting_hr"] = rec

    # ---- HRV (exports Samsung : souvent en retard) -------------------------------------------------
    hrv_all = {**_per_day(store, "energy_score", "hrv_ms", d - timedelta(days=60), d),
               **_per_day(store, "vitals_daily", "hrv_ms", d - timedelta(days=60), d)}
    if hrv_all:
        last_day = max(hrv_all)
        fresh = date.fromisoformat(last_day) >= yesterday
        rec = {"value": hrv_all[last_day], "date": last_day, "fresh": fresh}
        base = [v for k, v in hrv_all.items() if b28.isoformat() <= k < last_day]
        if base:
            rec["avg28"] = _r(mean(base), 1)
        if fresh and len(base) >= MIN_BASELINE:
            ratio = hrv_all[last_day] / mean(base)
            if ratio < HRV_LOW_RATIO:
                flags.append({"level": "attention", "text": f"HRV nocturne basse : {_r(hrv_all[last_day])} ms ({_r(ratio * 100)} % de la moyenne)"})
            elif ratio > HRV_HIGH_RATIO:
                flags.append({"level": "info", "text": f"HRV nocturne haute : {_r(hrv_all[last_day])} ms"})
        out["hrv"] = rec

    # ---- activité d'hier --------------------------------------------------------------------------
    steps = _per_day(store, "steps_daily", "count", b28, yesterday)
    if yesterday.isoformat() in steps:
        v = steps[yesterday.isoformat()]
        base = [s for k, s in steps.items() if k != yesterday.isoformat()]
        rec = {"count": int(v)}
        if len(base) >= MIN_BASELINE:
            m = mean(base)
            rec["avg28"] = _r(m)
            if v < 0.5 * m:
                flags.append({"level": "info", "text": f"Journée peu active hier : {int(v)} pas (moyenne {_r(m)})"})
            elif v > 1.5 * m:
                flags.append({"level": "info", "text": f"Journée très active hier : {int(v)} pas (moyenne {_r(m)})"})
        out["steps_yesterday"] = rec
    ws = queries.workouts(store, yesterday, yesterday)["workouts"]
    if ws:
        out["workouts_yesterday"] = ws

    # ---- SpO2 pendant la nuit ---------------------------------------------------------------------
    sp = [r.data["percent"] for r in store.fetch("oxygen_saturation", d, d) if isinstance(r.data.get("percent"), (int, float))]
    if sp:
        out["spo2_night"] = {"avg": _r(mean(sp), 1), "min": _r(min(sp), 1), "n": len(sp)}
        if min(sp) < SPO2_LOW:
            flags.append({"level": "attention", "text": f"SpO2 minimale à {_r(min(sp), 1)} %"})

    # ---- poids récent ---------------------------------------------------------------------------
    w = _per_day(store, "weight", "kg", d - timedelta(days=7), d)
    if w:
        k = max(w)
        out["weight"] = {"kg": round(w[k], 1), "date": k}

    out["flags"] = flags
    out["ready"] = night is not None
    out["last_phone_sync"] = store.last_ingest_from("ParroHealth") or store.last_ingest_from("SanteSync")
    return out


def fallback_text(f: dict[str, Any]) -> str:
    """Texte de secours sans modèle de langage (si Ollama est indisponible)."""
    lines = []
    n = f.get("night")
    if n:
        a = n.get("asleep_min") or n.get("in_bed_min")
        lines.append(f"Nuit : {queries.hm_text(a) if a else '?'} ({n.get('bedtime', '?')} → {n.get('wake', '?')})"
                     + (f", score {n['score']}" if n.get("score") is not None else "") + ".")
    else:
        lines.append("Pas de nuit reçue pour l'instant.")
    if (r := f.get("resting_hr")):
        lines.append(f"FC au repos : {r['bpm']} bpm" + (f" (moyenne 28 j : {r['avg28']})" if r.get("avg28") else "") + ".")
    if (s := f.get("steps_yesterday")):
        lines.append(f"Hier : {s['count']} pas" + (f" (moyenne {s['avg28']})" if s.get("avg28") else "") + ".")
    if f.get("flags"):
        lines.append("À noter : " + " ; ".join(x["text"] for x in f["flags"]) + ".")
    else:
        lines.append("Rien d'inhabituel.")
    return "\n".join(lines)


class ReportStore:
    """Textes rédigés et état de notification, par jour."""

    SCHEMA = """CREATE TABLE IF NOT EXISTS reports (
        date TEXT PRIMARY KEY, narrative TEXT, model TEXT, generated_at TEXT, notified_at TEXT)"""

    def __init__(self, path: str):
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.execute(self.SCHEMA)
        self._lock = threading.Lock()

    def get(self, d: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute("SELECT narrative, model, generated_at, notified_at FROM reports WHERE date = ?",
                                     (d,)).fetchone()
        return dict(zip(("narrative", "model", "generated_at", "notified_at"), row)) if row else None

    def save_narrative(self, d: str, narrative: str, model: str) -> None:
        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        with self._lock:
            self._conn.execute(
                """INSERT INTO reports (date, narrative, model, generated_at) VALUES (?, ?, ?, ?)
                   ON CONFLICT (date) DO UPDATE SET narrative=excluded.narrative, model=excluded.model,
                   generated_at=excluded.generated_at""", (d, narrative, model, now))

    def mark_notified(self, d: str) -> None:
        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        with self._lock:
            self._conn.execute("UPDATE reports SET notified_at = ? WHERE date = ?", (now, d))

    def close(self) -> None:
        self._conn.close()


def as_json(f: dict[str, Any]) -> str:
    return json.dumps(f, ensure_ascii=False, separators=(",", ":"))
