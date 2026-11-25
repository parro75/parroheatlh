"""Résumé pour Home Assistant (tableau de bord « Santé », visible par Parro seul).

Volontairement PLAT et MINIMAL : des nombres, aucun texte rédigé (le rapport se lit dans l'app). HA lit ce
résumé avec un jeton dédié (HA_TOKEN) qui n'ouvre que cette route ; ses capteurs sont exclus du recorder,
donc rien ne s'accumule dans la base ni dans les sauvegardes de HA.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Any

from . import queries, report
from .db import Store


def summary(store: Store, reports: report.ReportStore, day: date) -> dict[str, Any]:
    t = queries.today(store, day)
    night = t.get("last_night") or {}
    nights = []
    for k in range(6, -1, -1):
        d = day - timedelta(days=k)
        nights.append({"date": d.isoformat(), "asleep_min": report._asleep(report._main_night(store, d))})
    known = [n["asleep_min"] for n in nights if n["asleep_min"] is not None]
    steps = queries.daily_steps(store, day - timedelta(days=6), day)["days"]
    w = queries._latest(store, "weight", day, 90)
    rhr, hrv = t.get("resting_hr") or {}, t.get("hrv") or {}
    saved = reports.get(day.isoformat()) or {}
    return {
        "date": day.isoformat(),
        "sleep_last_min": report._asleep(night) if night else None,
        "sleep_last_score": night.get("score"),
        "sleep_goal_min": t["goals"]["sleep_min"],
        "sleep_7d_avg_min": round(sum(known) / len(known)) if known else None,
        "steps_today": t.get("steps_today"),
        "steps_goal": t["goals"]["steps"],
        "steps_7d_avg": t.get("steps_7d_avg"),
        "resting_hr_bpm": rhr.get("bpm"),
        "resting_hr_date": rhr.get("date"),
        "hrv_ms": hrv.get("value"),
        "hrv_date": hrv.get("date"),
        "weight_kg": round(w.data["kg"], 1) if w and w.data.get("kg") is not None else None,
        "weight_date": w.local_date if w else None,
        "last_phone_sync": t.get("last_phone_sync"),
        "report_ready": bool(saved.get("narrative")),
        "nights_7d": nights,
        "steps_7d": [{"date": s["date"], "steps": s["steps"]} for s in steps],
    }
