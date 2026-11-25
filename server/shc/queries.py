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


def hm_text(minutes: float) -> str:
    m = round(minutes)
    return f"{m // 60} h {m % 60:02d}"


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


def _attach_scores(rows: list[Row], nights: list[dict[str, Any]], scores: list[Row]) -> None:
    """Score Samsung (import d'export) rattaché à la nuit du même jour dont la fin est la plus proche (< 3 h)."""
    by_day: dict[str, list[Row]] = defaultdict(list)
    for sc in scores:
        by_day[sc.local_date].append(sc)
    for row, night in zip(rows, nights):
        if not row.end:
            continue
        end = datetime.fromisoformat(row.end)
        best, gap = None, timedelta(hours=3)
        for sc in by_day.get(row.local_date, []):
            if sc.end and abs(datetime.fromisoformat(sc.end) - end) < gap:
                best, gap = sc, abs(datetime.fromisoformat(sc.end) - end)
        if best:
            d = best.data
            night["score"] = d.get("score")
            for k in ("efficiency", "mental_recovery", "physical_recovery"):
                if d.get(k) is not None:
                    night[k] = d[k]


def sleep(store: Store, start: date, end: date) -> dict[str, Any]:
    rows = store.fetch("sleep_session", start, end)
    nights = [summarise_sleep(r) for r in rows]
    _attach_scores(rows, nights, store.fetch("sleep_score", start, end))
    asleep = [n["asleep_min"] for n in nights if n["asleep_min"] is not None]
    scores = [n["score"] for n in nights if n.get("score") is not None]
    return {
        "start": start.isoformat(),
        "end": end.isoformat(),
        "note": "date = jour du réveil ; heures en heure locale ; score = score de sommeil Samsung (0-100)",
        "sessions": nights,
        "avg_asleep_min": _r(mean(asleep)) if asleep else None,
        "avg_score": _r(mean(scores)) if scores else None,
    }


def heart_rate(store: Store, start: date, end: date) -> dict[str, Any]:
    daily = _last_per_day(store.fetch("heart_rate_daily", start, end))
    resting = _last_per_day(store.fetch("resting_heart_rate", start, end))
    sleeping = _sleeping_hr(store, start, end)
    days = []
    for d in sorted(set(daily) | set(resting) | set(sleeping)):
        hr = daily[d].data if d in daily else {}
        days.append(
            {
                "date": d,
                "min": hr.get("min"),
                "avg": _r(hr.get("avg")),
                "max": hr.get("max"),
                "resting": resting[d].data.get("bpm") if d in resting else None,
                "sleeping": sleeping.get(d),
            }
        )
    return {"start": start.isoformat(), "end": end.isoformat(), "unit": "bpm",
            "note": "sleeping = FC moyenne pendant le sommeil (Samsung), l'équivalent d'une FC au repos",
            "days": days}


def _sleeping_hr(store: Store, start: date, end: date) -> dict[str, float]:
    out: dict[str, float] = {}
    for rtype in ("energy_score", "vitals_daily"):  # vitals_daily, plus récent, l'emporte
        for r in store.fetch(rtype, start, end):
            if isinstance(r.data.get("sleeping_hr"), (int, float)):
                out[r.local_date] = r.data["sleeping_hr"]
    return out


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


# ---- données issues de l'export Samsung Health ------------------------------------------


def recovery(store: Store, start: date, end: date) -> dict[str, Any]:
    vitals = _last_per_day(store.fetch("vitals_daily", start, end))
    energy = _last_per_day(store.fetch("energy_score", start, end))
    days = []
    for d in sorted(set(vitals) | set(energy)):
        v = vitals[d].data if d in vitals else {}
        e = energy[d].data if d in energy else {}
        row: dict[str, Any] = {"date": d}
        row["hrv_ms"] = v.get("hrv_ms", e.get("hrv_ms"))
        row["sleeping_hr"] = v.get("sleeping_hr", e.get("sleeping_hr"))
        for k in ("respiratory_rate", "spo2"):
            if v.get(k) is not None:
                row[k] = v[k]
        if v.get("skin_temp_c") is not None and v.get("skin_temp_baseline_c") is not None:
            row["skin_temp_delta_c"] = round(v["skin_temp_c"] - v["skin_temp_baseline_c"], 2)
        if e.get("score") is not None:
            row["energy_score"] = e["score"]
        days.append({k: val for k, val in row.items() if val is not None})
    return {"start": start.isoformat(), "end": end.isoformat(),
            "note": "valeurs nocturnes Samsung (montre) : HRV en ms, FC de sommeil en bpm, respiration /min, "
                    "écart de température cutanée à la ligne de base ; energy_score 0-100. "
                    "Source : import d'export Samsung Health (pas de mise à jour continue).",
            "days": days}


def stress(store: Store, start: date, end: date) -> dict[str, Any]:
    days = [{"date": r.local_date, **{k: r.data.get(k) for k in ("avg", "max", "min", "n")}}
            for r in _last_per_day(store.fetch("stress_daily", start, end)).values()]
    return {"start": start.isoformat(), "end": end.isoformat(),
            "note": "score de stress Samsung 0-100 (plus haut = plus stressé), agrégé par jour", "days": days}


def training_load(store: Store, start: date, end: date) -> dict[str, Any]:
    days = [{"date": r.local_date, **{k: v for k, v in r.data.items() if k != "source" and v is not None}}
            for r in _last_per_day(store.fetch("training_load_daily", start, end)).values()]
    return {"start": start.isoformat(), "end": end.isoformat(),
            "note": "charge d'entraînement Samsung : acute = 7 jours, chronic = 28 jours", "days": days}


# ---- tendances longues ---------------------------------------------------------------------

TREND_MAX_DAYS = 366 * 20
PERIODS = ("week", "month", "year")


def _period_key(d: str, period: str) -> str:
    day = date.fromisoformat(d)
    if period == "year":
        return str(day.year)
    if period == "month":
        return d[:7]
    iso = day.isocalendar()
    return f"{iso.year}-W{iso.week:02d}"


def _daily_values(store: Store, metric: str, start: date, end: date) -> dict[str, float]:
    """Une valeur par jour local pour la métrique demandée."""
    def per_day(rtype: str, field: str, agg=mean) -> dict[str, float]:
        acc: dict[str, list[float]] = defaultdict(list)
        for r in store.fetch(rtype, start, end):
            v = r.data.get(field)
            if isinstance(v, (int, float)):
                acc[r.local_date].append(float(v))
        return {d: agg(v) for d, v in acc.items()}

    if metric == "steps":
        return per_day("steps_daily", "count", max)
    if metric == "sleep_minutes":
        out: dict[str, float] = defaultdict(float)
        for r in store.fetch("sleep_session", start, end):
            s = summarise_sleep(r)
            m = s["asleep_min"] if s["asleep_min"] is not None else s["in_bed_min"]
            if m:
                out[r.local_date] += m
        return dict(out)
    if metric == "sleep_score":
        return per_day("sleep_score", "score", max)
    if metric == "hrv_nightly":
        return {**per_day("energy_score", "hrv_ms"), **per_day("vitals_daily", "hrv_ms")}
    if metric == "hrv":
        return per_day("hrv_rmssd", "rmssd_ms")
    if metric == "sleeping_hr":
        return {**per_day("energy_score", "sleeping_hr"), **per_day("vitals_daily", "sleeping_hr")}
    if metric == "heart_rate_avg":
        return per_day("heart_rate_daily", "avg")
    if metric == "resting_hr":
        return per_day("resting_heart_rate", "bpm")
    if metric == "weight":
        return per_day("weight", "kg")
    if metric == "body_fat":
        return per_day("body_fat", "percent")
    if metric == "stress":
        return per_day("stress_daily", "avg")
    if metric == "energy":
        return per_day("energy_score", "score")
    if metric == "workout_minutes":
        out = defaultdict(float)
        for r in store.fetch("exercise_session", start, end):
            if r.end:
                out[r.local_date] += _minutes(r.start, r.end)
        return dict(out)
    raise QueryError(f"métrique inconnue : {metric}")


TREND_METRICS = ("steps", "sleep_minutes", "sleep_score", "hrv_nightly", "hrv", "sleeping_hr", "resting_hr",
                 "heart_rate_avg", "weight", "body_fat", "stress", "energy", "workout_minutes")


def trends(store: Store, metric: str, period: str, start: date, end: date) -> dict[str, Any]:
    if metric not in TREND_METRICS:
        raise QueryError(f"métrique inconnue ; au choix : {', '.join(TREND_METRICS)}")
    if period not in PERIODS:
        raise QueryError("period : week, month ou year")
    groups: dict[str, list[float]] = defaultdict(list)
    for d, v in _daily_values(store, metric, start, end).items():
        groups[_period_key(d, period)].append(v)
    rows = []
    for key in sorted(groups):
        vals = groups[key]
        total = metric in ("workout_minutes",)
        rows.append({"period": key, "days": len(vals), "avg": _r(mean(vals), 1),
                     "min": _r(min(vals), 1), "max": _r(max(vals), 1),
                     **({"total": _r(sum(vals))} if total else {})})
    return {"metric": metric, "period": period, "start": start.isoformat(), "end": end.isoformat(),
            "note": "moyenne des valeurs QUOTIDIENNES de chaque période ; days = jours avec donnée", "rows": rows}


def trend_range(start: str | None, end: str | None, today: date) -> tuple[date, date]:
    try:
        d_end = date.fromisoformat(end) if end else today
        d_start = date.fromisoformat(start) if start else d_end - timedelta(days=365)
    except ValueError as exc:
        raise QueryError("dates attendues au format AAAA-MM-JJ") from exc
    if d_start > d_end:
        raise QueryError("start est après end")
    if (d_end - d_start).days + 1 > TREND_MAX_DAYS:
        raise QueryError("plage limitée à 20 ans")
    return d_start, d_end


# ---- journal d'événements (noté dans l'app) ---------------------------------------------------------

JOURNAL_TAGS = {
    "alcool": "alcool", "cafe_tardif": "café tardif", "malade": "malade", "voyage": "voyage",
    "seance_intense": "séance intense", "stress": "stress", "repas_tardif": "repas tardif",
    "ecrans_tard": "écrans tard", "medicament": "médicament", "autre": "autre",
}


def journal(store: Store, start: date, end: date) -> dict[str, Any]:
    events = []
    for r in store.fetch("journal", start, end):
        if r.data.get("deleted"):
            continue
        events.append({"date": r.local_date, "time": _hhmm(r.start),
                       "tags": [JOURNAL_TAGS.get(t, t) for t in r.data.get("tags", [])],
                       **({"note": r.data["note"]} if r.data.get("note") else {})})
    return {"start": start.isoformat(), "end": end.isoformat(),
            "note": "événements notés par l'utilisateur dans Parro Health (heure locale)", "events": events}


DEFAULT_GOALS = {"sleep_min": 450, "steps": 8000}


def goals(store: Store) -> dict[str, Any]:
    """Objectifs réglés dans l'app (dernier enregistrement « goals »), sinon les valeurs par défaut."""
    rows = store.fetch("goals", date(2000, 1, 1), date(2100, 1, 1))
    g = dict(DEFAULT_GOALS)
    if rows:
        g.update({k: v for k, v in rows[-1].data.items() if k in DEFAULT_GOALS and isinstance(v, (int, float)) and v > 0})
    return g


# ---- résumé pour l'écran d'accueil de l'app --------------------------------------------------


def _latest(store: Store, rtype: str, today: date, days: int) -> Row | None:
    rows = store.fetch(rtype, today - timedelta(days=days), today)
    return rows[-1] if rows else None


def today(store: Store, day: date) -> dict[str, Any]:
    """Dernière nuit, pas du jour, FC au repos, HRV, énergie, stress, fraîcheur des données."""
    out: dict[str, Any] = {"date": day.isoformat()}
    nights = sleep(store, day - timedelta(days=1), day)["sessions"]
    if nights:
        # la nuit la PLUS RÉCENTE (pas la plus longue des deux jours), et parmi celles de ce jour la plus
        # longue, pour écarter une sieste. Bug corrigé le 25/11/2026 : on affichait la plus longue des deux.
        latest = max(n["date"] for n in nights)
        out["last_night"] = max((n for n in nights if n["date"] == latest),
                                key=lambda n: n.get("asleep_min") or n.get("in_bed_min") or 0)
    st = store.fetch("steps_daily", day, day)
    out["steps_today"] = st[-1].data.get("count") if st else 0
    week = daily_steps(store, day - timedelta(days=6), day)
    out["steps_7d_avg"] = week["daily_avg"]
    if (r := _latest(store, "resting_heart_rate", day, 7)) is not None:
        out["resting_hr"] = {"date": r.local_date, "bpm": r.data.get("bpm"), "source": r.data.get("source")}
    for rtype, key, field in (("vitals_daily", "hrv", "hrv_ms"), ("energy_score", "energy", "score"),
                              ("stress_daily", "stress", "avg")):
        if (r := _latest(store, rtype, day, 60)) is not None and r.data.get(field) is not None:
            out[key] = {"date": r.local_date, "value": r.data[field]}
    if "hrv" not in out and (r := _latest(store, "energy_score", day, 60)) is not None and r.data.get("hrv_ms"):
        out["hrv"] = {"date": r.local_date, "value": r.data["hrv_ms"]}
    out["goals"] = goals(store)
    status = store.status()
    phone = [t for t in (store.last_ingest_from("SanteSync"), store.last_ingest_from("ParroHealth")) if t]
    out["last_phone_sync"] = max(phone) if phone else None
    imported = status["types"].get("vitals_daily") or status["types"].get("energy_score")
    out["last_export_day"] = imported["last_day"] if imported else None
    return out
