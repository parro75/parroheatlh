"""Bilan quotidien : règles de signaux, texte de secours, routes du job de rédaction."""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

from conftest import REPORT_TOKEN
from shc import report
from shc.db import ALL_TYPES

D = date(2026, 11, 25)
JOB = {"Authorization": f"Bearer {REPORT_TOKEN}"}


def night(d: date, minutes: int) -> dict:
    wake = datetime(d.year, d.month, d.day, 7, 0, tzinfo=timezone(timedelta(hours=-5)))
    start = wake - timedelta(minutes=minutes)
    return {"type": "sleep_session", "id": f"n-{d}", "start": start.isoformat(), "end": wake.isoformat(),
            "zone_offset": "-05:00", "data": {"stages": [{"start": start.isoformat(), "end": wake.isoformat(),
                                                         "stage": "light"}]}}


def daily(rtype: str, d: date, data: dict, rid: str) -> dict:
    return {"type": rtype, "id": rid, "start": f"{d}T07:00:00-05:00", "zone_offset": "-05:00", "data": data}


def seed(store, today_minutes=300, prev_minutes=450, rhr_today=62, steps_yesterday=2000):
    recs = []
    for k in range(1, 29):
        d = D - timedelta(days=k)
        recs.append(night(d, prev_minutes if k > 2 else prev_minutes))
        recs.append(daily("resting_heart_rate", d, {"bpm": 55 + (k % 3) - 1, "sleeping_avg": 60}, f"r-{d}"))
        recs.append(daily("steps_daily", d, {"count": 9000}, f"steps:{d}"))
    recs.append(night(D, today_minutes))
    recs.append(daily("resting_heart_rate", D, {"bpm": rhr_today, "sleeping_avg": 66.5}, f"r-{D}"))
    recs = [r for r in recs if not (r["type"] == "steps_daily" and r["id"] == f"steps:{D - timedelta(days=1)}")]
    recs.append(daily("steps_daily", D - timedelta(days=1), {"count": steps_yesterday}, f"steps:{D - timedelta(days=1)}"))
    store.upsert(recs, "test", allowed=ALL_TYPES)


def texts(f):
    return [x["text"] for x in f["flags"]]


def test_flags_short_night_high_rhr_low_activity(client):
    store = client.app.state.store
    seed(store)
    f = report.facts(store, D)
    assert f["ready"] and f["night"]["asleep_min"] == 300 and f["night"]["avg28_asleep_min"] == 450
    assert f["resting_hr"]["bpm"] == 62 and f["resting_hr"]["avg28"] == 55.0 and f["resting_hr"]["delta"] == 7.0
    t = texts(f)
    assert "Nuit courte : 5 h 00 (moyenne 28 j : 7 h 30)" in t
    assert any(x.startswith("FC au repos élevée : 62 bpm (+7.0") for x in t)
    assert any(x.startswith("Journée peu active hier : 2000 pas") for x in t)
    fb = report.fallback_text(f)
    assert "Nuit : 5 h 00" in fb and "À noter" in fb


def test_three_short_nights_and_quiet_day(client):
    store = client.app.state.store
    seed(store, today_minutes=320, rhr_today=55, steps_yesterday=9000)
    store.upsert([night(D - timedelta(days=1), 330), night(D - timedelta(days=2), 340)], "test", allowed=ALL_TYPES)
    f = report.facts(store, D)
    assert texts(f) == ["3e nuit courte d'affilée (5 h 20)"]


def test_no_flag_and_no_night(client):
    store = client.app.state.store
    seed(store, today_minutes=460, rhr_today=55, steps_yesterday=9000)
    assert texts(report.facts(store, D)) == []
    assert "Rien d'inhabituel" in report.fallback_text(report.facts(store, D))
    empty = report.facts(store, D + timedelta(days=10))
    assert empty["ready"] is False and "Pas de nuit reçue" in report.fallback_text(empty)


def test_report_job_routes(client):
    assert client.get("/api/v1/report-job/pending").status_code == 401
    assert client.get("/api/v1/report-job/pending", headers={"Authorization": "Bearer faux"}).status_code == 401
    p = client.get("/api/v1/report-job/pending", headers=JOB)
    assert p.status_code == 200
    j = p.json()
    assert set(j) >= {"date", "ready", "has_narrative", "notified", "fallback", "facts"} and not j["has_narrative"]
    bad = client.post("/api/v1/report-job/narrative", json={"date": "x", "narrative": "t"}, headers=JOB)
    assert bad.status_code == 422
    ok = client.post("/api/v1/report-job/narrative", json={"date": j["date"], "narrative": "Bonne nuit.", "model": "m"},
                     headers=JOB)
    assert ok.status_code == 200
    assert client.post("/api/v1/report-job/notified", json={"date": j["date"]}, headers=JOB).status_code == 200
    j2 = client.get("/api/v1/report-job/pending", headers=JOB).json()
    assert j2["has_narrative"] and j2["notified"] and j2["narrative"] == "Bonne nuit."
    # le jeton du job n'ouvre NI l'API de l'app NI l'ingestion
    assert client.get("/api/v1/today", headers=JOB).status_code == 401
    assert client.post("/ingest", json={"records": []}, headers=JOB).status_code == 401


def test_app_reads_report(client):
    from test_app_api import app_login
    j = client.get("/api/v1/report-job/pending", headers=JOB).json()
    client.post("/api/v1/report-job/narrative", json={"date": j["date"], "narrative": "Texte du jour.", "model": "m"},
                headers=JOB)
    t = app_login(client)
    r = client.get("/api/v1/report", headers={"Authorization": f"Bearer {t['access_token']}"})
    assert r.status_code == 200 and r.json()["narrative"] == "Texte du jour." and "facts" in r.json()


def test_report_job_disabled_without_token(tmp_path):
    from fastapi.testclient import TestClient
    from conftest import make_settings
    from shc.app import create_app
    from dataclasses import replace
    s = replace(make_settings(tmp_path, "http://localhost"), report_token="")
    with TestClient(create_app(s), base_url="http://localhost") as c:
        assert c.get("/api/v1/report-job/pending", headers=JOB).status_code == 404
