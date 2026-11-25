from conftest import AUTH, HA_TOKEN, REPORT_TOKEN
from shc import report
from test_report import D, seed as seed_day

HA = {"Authorization": f"Bearer {HA_TOKEN}"}


def test_sleep_goal_tolerance_30_min(client):
    store = client.app.state.store
    seed_day(store, today_minutes=444, rhr_today=55, steps_yesterday=5000)   # 7 h 24 pour un objectif de 7 h 30
    texts = [x["text"] for x in report.facts(store, D)["flags"]]
    assert not any("objectif de sommeil" in t for t in texts)


def test_ha_summary_needs_its_own_token(client):
    assert client.get("/api/v1/ha/summary").status_code == 401
    for other in (AUTH, {"Authorization": f"Bearer {REPORT_TOKEN}"}):
        assert client.get("/api/v1/ha/summary", headers=other).status_code == 401
    r = client.get("/api/v1/ha/summary", headers=HA)
    assert r.status_code == 200 and r.headers["cache-control"] == "no-store"
    s = r.json()
    assert s["sleep_goal_min"] == 450 and s["steps_goal"] == 8000 and len(s["nights_7d"]) == 7
    assert s["report_ready"] is False
    # le jeton HA n'ouvre RIEN d'autre
    assert client.get("/api/v1/today", headers=HA).status_code == 401
    assert client.get("/api/v1/report-job/pending", headers=HA).status_code == 401
    assert client.post("/ingest", json={"records": []}, headers=HA).status_code == 401


def test_ha_summary_values(client, settings):
    from shc import ha
    store = client.app.state.store
    seed_day(store, today_minutes=420, rhr_today=57, steps_yesterday=9000)
    reports = report.ReportStore(settings.db_path)
    reports.save_narrative(D.isoformat(), "Texte.", "m")
    s = ha.summary(store, reports, D)
    assert s["sleep_last_min"] == 420 and s["resting_hr_bpm"] == 57 and s["report_ready"] is True
    assert s["nights_7d"][-1] == {"date": D.isoformat(), "asleep_min": 420}
    assert "narrative" not in s and all(not isinstance(v, str) or len(v) <= 32 for v in s.values())  # aucun texte rédigé
