from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from conftest import AUTH, REPORT_TOKEN
from shc.syncwatch import SyncWatch

TZ = ZoneInfo("America/Toronto")
JOB = {"Authorization": f"Bearer {REPORT_TOKEN}"}


def at(local_h: int, day: int = 25) -> datetime:
    return datetime(2026, 11, day, local_h, 0, tzinfo=TZ).astimezone(timezone.utc)


def test_thresholds_and_single_alert_then_recovery(tmp_path):
    w = SyncWatch(str(tmp_path / "w.db"))
    last = (at(10) - timedelta(hours=3)).isoformat()
    assert w.status(last, at(10), TZ)["action"] == "none"               # 3 h en journée : OK
    s = w.status(last, at(11, 25), TZ)                                   # 4 h en journée : alerte
    assert s["action"] == "alert" and "depuis 4 h" in s["message"]
    w.ack("alert", last)
    assert w.status(last, at(13), TZ)["action"] == "none"                # une seule alerte par panne
    new = at(13).isoformat()
    assert w.status(new, at(13), TZ)["action"] == "recovered"            # retour : message unique
    w.ack("recovered", new)
    assert w.status(new, at(14), TZ)["action"] == "none"


def test_night_tolerance(tmp_path):
    w = SyncWatch(str(tmp_path / "w.db"))
    last = (at(23, 24) ).isoformat()
    assert w.status(last, at(5), TZ)["action"] == "none"                 # 6 h la nuit : toléré
    assert w.status(last, at(8), TZ)["action"] == "alert"                # 9 h : alerte à toute heure


def test_routes(client):
    assert client.get("/api/v1/report-job/sync-status").status_code == 401
    r = client.get("/api/v1/report-job/sync-status", headers=JOB).json()
    assert r["action"] == "none" and r["last_phone_sync"] is None
    client.post("/ingest", json={"records": []}, headers={**AUTH, "User-Agent": "ParroHealth/0.3.0 (Android 17; auto)"})
    r = client.get("/api/v1/report-job/sync-status", headers=JOB).json()
    assert r["last_phone_sync"] and r["action"] == "none"
    assert client.post("/api/v1/report-job/sync-status/ack", json={"action": "x"}, headers=JOB).status_code == 422


def test_battery_alert_once_then_recovery(tmp_path):
    w = SyncWatch(str(tmp_path / "w.db"))
    assert w.battery_status()["action"] == "none"                        # app ancienne : rien de déclaré
    w.phone_state(True, at(10))
    assert w.battery_status() == {"battery_exempt": True, "seen_at": at(10).isoformat(), "action": "none"}
    w.phone_state(False, at(11))
    s = w.battery_status()
    assert s["action"] == "battery_alert" and "Rétablir" in s["message"]
    w.ack_battery("battery_alert")
    w.phone_state(False, at(12))
    assert w.battery_status()["action"] == "none"                        # une seule alerte
    w.phone_state(True, at(13))
    assert w.battery_status()["action"] == "battery_ok"
    w.ack_battery("battery_ok")
    assert w.battery_status()["action"] == "none"


def test_battery_state_from_ingest(client):
    assert client.get("/api/v1/report-job/phone-state").status_code == 401
    ua = {**AUTH, "User-Agent": "ParroHealth/0.17.1 (Android 17; auto)"}
    client.post("/ingest", json={"records": [], "device": {"battery_exempt": "non"}}, headers=ua)  # pas un booléen : ignoré
    assert client.get("/api/v1/report-job/phone-state", headers=JOB).json()["battery_exempt"] is None
    assert client.post("/ingest", json={"records": [], "device": {"battery_exempt": False}}, headers=ua).status_code == 200
    r = client.get("/api/v1/report-job/phone-state", headers=JOB).json()
    assert r["action"] == "battery_alert" and r["battery_exempt"] is False
    assert any("optimisation de batterie active" in (e["detail"] or "") for e in client.app.state.audit.entries(50))
    assert client.post("/api/v1/report-job/phone-state/ack", json={"action": "x"}, headers=JOB).status_code == 422
    assert client.post("/api/v1/report-job/phone-state/ack", json={"action": "battery_alert"}, headers=JOB).status_code == 200
    assert client.get("/api/v1/report-job/phone-state", headers=JOB).json()["action"] == "none"
