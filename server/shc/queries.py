"""Mise en forme des réponses des outils MCP — compactes, une ligne par jour ou par séance."""

from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta
from statistics import mean
from typing import Any

from .db import Row, Store
from .exercise_types import EXERCISE_TYPES

MAX_DAYS = 366
DEFAULT_DAYS = 7
ASLEEP_STAGES = ("light", "deep", "rem", "sleeping")


class QueryError(ValueError):
    pass


def date_range(start: str | None, end: str | None, today: date) -> tuple[date, date]:
    try:
        d_end = date.fromisoformat(end) if end else today
        d_start = date.fromisoformat(start) if start else d_end - timedelta(days=DEFAULT_DAYS - 1)
    except ValueError as exc:
        raise QueryError("dates attendues au format AAAA-MM-JJ") from exc
    if d_start > d_end:
        raise QueryError("start est après end")
    if (d_end - d_start).days + 1 > MAX_DAYS:
        raise QueryError(f"plage limitée à {MAX_DAYS} jours")
    return d_start, d_end


def _hhmm(iso: str | None) -> str | None:
    return datetime.fromisoformat(iso).strftime("%H:%M") if iso else None


def _minutes(a: str, b: str) -> float:
    return (datetime.fromisoformat(b) - datetime.fromisoformat(a)).total_seconds() / 60


def _r(x: float | None, nd: int = 0) -> float | int | None:
    if x is None:
        return None
    return round(x) if nd == 0 else round(x, nd)


def _last_per_day(rows: list[Row]) -> dict[str, Row]:
    # rows est trié par (local_date, start_ts) : la dernière valeur du jour gagne.
    return {r.local_date: r for r in rows}


# ---- outils ---------------------------------------------------------------


def daily_steps(store: Store, start: date, end: date) -> dict[str, Any]:
    rows = store.fetch("steps_daily", start, end)
    days = [{"date": r.local_date, "steps": r.data.get("count")} for r in _last_per_day(rows).values()]
    counts = [d["steps"] for d in days if isinstance(d["steps"], (int, float))]
    return {
        "start": start.isoformat(),
        "end": end.isoformat(),
        "days": days,
        "total": sum(counts),
        "daily_avg": _r(mean(counts)) if counts else None,
    }


def summarise_sleep(row: Row) -> dict[str, Any]:
    totals: dict[str, float] = defaultdict(float)
    for s in row.data.get("stages") or []:
        try:
            totals[str(s.get("stage", "unknown"))] += _minutes(s["start"], s["end"])
        except (KeyError, TypeError, ValueError):
            continue
    in_bed = _minutes(row.start, row.end) if row.end else None
    asleep = sum(totals[k] for k in ASLEEP_STAGES) if totals else None
    out: dict[str, Any] = {
        "date": row.local_date,
        "bedtime": _hhmm(row.start),
        "wake": _hhmm(row.end),
        "in_bed_min": _r(in_bed),
        "asleep_min": _r(asleep),
    }
    if totals:
        out["stages_min"] = {
            k: _r(totals[k]) for k in ("deep", "rem", "light", "awake", "sleeping", "awake_in_bed") if totals.get(k)
        }
    if row.data.get("source"):
        out["source"] = row.data["source"]
    return out


def sleep(store: Store, start: date, end: date) -> dict[str, Any]:
    nights = [summarise_sleep(r) for r in store.fetch("sleep_session", start, end)]
    asleep = [n["asleep_min"] for n in nights if n["asleep_min"] is not None]
    return {
        "start": start.isoformat(),
        "end": end.isoformat(),
        "note": "date = jour du réveil ; heures en heure locale de l'enregistrement",
        "sessions": nights,
        "avg_asleep_min": _r(mean(asleep)) if asleep else None,
    }


def heart_rate(store: Store, start: date, end: date) -> dict[str, Any]:
    daily = _last_per_day(store.fetch("heart_rate_daily", start, end))
    resting = _last_per_day(store.fetch("resting_heart_rate", start, end))
    days = []
    for d in sorted(set(daily) | set(resting)):
        hr = daily[d].data if d in daily else {}
        days.append(
            {
                "date": d,
                "min": hr.get("min"),
                "avg": _r(hr.get("avg")),
                "max": hr.get("max"),
                "resting": resting[d].data.get("bpm") if d in resting else None,
            }
        )
    return {"start": start.isoformat(), "end": end.isoformat(), "unit": "bpm", "days": days}


def hrv(store: Store, start: date, end: date) -> dict[str, Any]:
    per_day: dict[str, list[float]] = defaultdict(list)
    for r in store.fetch("hrv_rmssd", start, end):
        v = r.data.get("rmssd_ms")
        if isinstance(v, (int, float)):
            per_day[r.local_date].append(float(v))
    days = [
        {"date": d, "rmssd_avg": _r(mean(v), 1), "rmssd_min": _r(min(v), 1), "rmssd_max": _r(max(v), 1), "n": len(v)}
        for d, v in sorted(per_day.items())
    ]
    return {"start": start.isoformat(), "end": end.isoformat(), "unit": "ms (RMSSD)", "days": days}


def body(store: Store, start: date, end: date) -> dict[str, Any]:
    weight = _last_per_day(store.fetch("weight", start, end))
    fat = _last_per_day(store.fetch("body_fat", start, end))
    days = [
        {
            "date": d,
            "weight_kg": _r(weight[d].data.get("kg"), 2) if d in weight else None,
            "body_fat_pct": _r(fat[d].data.get("percent"), 1) if d in fat else None,
        }
        for d in sorted(set(weight) | set(fat))
    ]
    return {"start": start.isoformat(), "end": end.isoformat(), "days": days}


def spo2(store: Store, start: date, end: date) -> dict[str, Any]:
    per_day: dict[str, list[float]] = defaultdict(list)
    for r in store.fetch("oxygen_saturation", start, end):
        v = r.data.get("percent")
        if isinstance(v, (int, float)):
            per_day[r.local_date].append(float(v))
    days = [
        {"date": d, "avg": _r(mean(v), 1), "min": _r(min(v), 1), "max": _r(max(v), 1), "n": len(v)}
        for d, v in sorted(per_day.items())
    ]
    return {"start": start.isoformat(), "end": end.isoformat(), "unit": "%", "days": days}


def _exercise_name(value: Any) -> Any:
    # Le téléphone envoie le code Health Connect (int) ; un nom déjà textuel passe tel quel.
    if isinstance(value, int) and not isinstance(value, bool):
        return EXERCISE_TYPES.get(value, f"type_{value}")
    return value


def workouts(store: Store, start: date, end: date) -> dict[str, Any]:
    out = []
    for r in store.fetch("exercise_session", start, end):
        d = r.data
        w: dict[str, Any] = {
            "date": r.local_date,
            "start": _hhmm(r.start),
            "duration_min": _r(_minutes(r.start, r.end)) if r.end else None,
            "type": _exercise_name(d.get("exercise_type")),
        }
        for key, src, nd in (
            ("title", "title", None),
            ("distance_km", "distance_m", 2),
            ("calories_kcal", "calories_kcal", 0),
            ("avg_hr", "avg_hr", 0),
            ("max_hr", "max_hr", 0),
            ("steps", "steps", 0),
            ("source", "source", None),
        ):
            v = d.get(src)
            if v is None or v == "":
                continue
            if key == "distance_km":
                v = v / 1000
            w[key] = _r(v, nd) if nd is not None else v
        out.append(w)
    return {"start": start.isoformat(), "end": end.isoformat(), "workouts": out}
