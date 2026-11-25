from datetime import date, timedelta

from conftest import REPORT_TOKEN
from shc import report
from shc.db import ALL_TYPES
from test_report import daily, night

SUNDAY = date(2026, 11, 29)
JOB = {"Authorization": f"Bearer {REPORT_TOKEN}"}


def seed(store, week_sleep=340, base_sleep=450, week_rhr=60, steps_week=3000):
    recs = []
    for k in range(0, 35):
        d = SUNDAY - timedelta(days=k)
        in_week = k < 7
        recs.append(night(d, week_sleep if in_week else base_sleep))
        recs.append(daily("resting_heart_rate", d, {"bpm": week_rhr if in_week else 55}, f"r-{d}"))
        recs.append(daily("steps_daily", d, {"count": steps_week if in_week else 9000}, f"steps:{d}"))
    recs.append({"type": "journal", "id": "j1", "start": "2026-11-27T22:00:00-05:00", "zone_offset": "-05:00",
                 "data": {"tags": ["alcool"]}})
    store.upsert(recs, "test", allowed=ALL_TYPES)


def test_weekly_flags(client):
    store = client.app.state.store
    seed(store)
    f = report.weekly_facts(store, SUNDAY)
    assert f["week"] == "S2026-W48" and f["start"] == "2026-11-23" and f["end"] == "2026-11-29"
    w, b = f["this_week"], f["previous_4_weeks"]
    assert w["nights"] == 7 and w["asleep_avg_min"] == 340 and b["asleep_avg_min"] == 450
    texts = [x["text"] for x in f["flags"]]
    assert "Sommeil moyen court : 5 h 40 par nuit" in texts
    assert any(t.startswith("FC au repos en hausse : 60") for t in texts)
    assert any(t.startswith("Semaine moins active : 3000") for t in texts)
    assert "Aucune séance cette semaine" in texts
    assert f["journal_tags"] == {"alcool": 1}
    assert "Semaine du 2026-11-23 au 2026-11-29 : 7 nuits" in report.weekly_fallback(f)


def test_weekly_job_route_and_key(client):
    r = client.get("/api/v1/report-job/weekly/pending", headers=JOB).json()
    assert r["date"].startswith("S") and "fallback" in r and r["has_narrative"] is False
    ok = client.post("/api/v1/report-job/narrative", json={"date": r["date"], "narrative": "Bonne semaine.", "model": "m"},
                     headers=JOB)
    assert ok.status_code == 200
    assert client.get("/api/v1/report-job/weekly/pending", headers=JOB).json()["narrative"] == "Bonne semaine."
    bad = client.post("/api/v1/report-job/narrative", json={"date": "S26-W1", "narrative": "x"}, headers=JOB)
    assert bad.status_code == 422


def test_goals_used_by_rules(client):
    from conftest import AUTH
    from test_report import D, seed as seed_day
    store = client.app.state.store
    seed_day(store, today_minutes=400, rhr_today=55, steps_yesterday=12000)   # 6 h 40 : pas « courte »
    f = report.facts(store, D)
    texts = [x["text"] for x in f["flags"]]
    assert "Sous ton objectif de sommeil : 6 h 40 (objectif 7 h 30)" in texts
    assert "Objectif de pas atteint hier : 12000 (objectif 8000)" in texts
    goals = {"type": "goals", "id": "goals", "start": "2026-11-25T12:00:00-05:00", "zone_offset": "-05:00",
             "data": {"sleep_min": 390, "steps": 15000}}
    assert client.post("/ingest", json={"records": [goals]}, headers=AUTH).status_code == 200
    texts = [x["text"] for x in report.facts(store, D)["flags"]]
    assert not any("objectif" in t for t in texts)                              # 6 h 40 ≥ 6 h 30 ; 12000 < 15000
    w = report.weekly_facts(store, D)
    assert w["goals"] == {"sleep_min": 390, "steps": 15000} and "nights_at_sleep_goal" in w["this_week"]
