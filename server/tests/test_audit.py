"""Journal des accès : qui, quoi, quand, d'où ; chaîne d'empreintes ; alertes de sécurité ; réservé à l'app."""

from __future__ import annotations

import sqlite3

from conftest import AUTH, REPORT_TOKEN, sample_records
from test_app_api import app_login
from test_devices import new_key

JOB = {"Authorization": f"Bearer {REPORT_TOKEN}"}


def entries(client):
    return list(reversed(client.app.state.audit.entries(200)))


def test_reads_and_refusals_are_logged(client):
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}", "X-Forwarded-For": "203.0.113.7"}
    assert client.get("/api/v1/sleep", params={"start": "2026-11-01", "end": "2026-11-20"}, headers=h).status_code == 200
    assert client.get("/api/v1/today", headers={"X-Forwarded-For": "198.51.100.9"}).status_code == 401
    assert client.post("/ingest", json={"records": sample_records()}, headers=AUTH).status_code == 200
    e = entries(client)
    sleep = next(x for x in e if x["action"] == "GET /api/v1/sleep")
    assert sleep["actor"] == "app" and sleep["ip"] == "203.0.113.7" and "start=2026-11-01" in sleep["detail"]
    refused = next(x for x in e if x["action"] == "GET /api/v1/today" and x["status"] == 401)
    assert refused["actor"] == "inconnu" and refused["ip"] == "198.51.100.9"
    ing = next(x for x in e if x["action"] == "POST /ingest")
    assert ing["status"] == 200 and "écrits" in ing["detail"]
    assert any(x["action"] == "connexion" and x["actor"] == "proprietaire" for x in e)
    assert any(x["action"] == "jetons-emis" and x["actor"] == "app" for x in e)


def test_chain_detects_tampering(client, settings):
    client.get("/api/v1/today")
    client.get("/api/v1/today")
    log = client.app.state.audit
    assert log.verify()["ok"]
    c = sqlite3.connect(settings.db_path)
    c.execute("UPDATE access_log SET actor = 'quelquun' WHERE seq = (SELECT MIN(seq) FROM access_log)")
    c.commit()
    assert log.verify()["ok"] is False


def test_security_events_and_ack(client):
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}"}
    _, pub, did = new_key()
    assert client.post("/api/v1/device", json={"public_key": pub, "label": "Téléphone test"}, headers=h).status_code == 200
    r = client.get("/api/v1/report-job/security-events", headers=JOB).json()
    assert r["count"] >= 2 and "Nouvel appareil appairé" in r["message"] and "Connexion réussie" in r["message"]
    assert client.post("/api/v1/report-job/security-events/ack", json={"seq": r["last_seq"]}, headers=JOB).status_code == 200
    assert client.get("/api/v1/report-job/security-events", headers=JOB).json()["count"] == 0


def test_access_log_reserved_to_app(client):
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}"}
    r = client.get("/api/v1/access-log", headers=h)
    assert r.status_code == 200 and r.json()["chain"]["ok"] and r.json()["entries"]
    tok = client.app.state.oauth._issue("client-claude", ["health:read"], "f")
    assert client.get("/api/v1/access-log", headers={"Authorization": f"Bearer {tok.access_token}"}).status_code == 403


def test_monthly_access_review(client):
    from datetime import date, timedelta
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}"}
    client.get("/api/v1/today", headers=h)
    client.get("/api/v1/today")  # refus
    log = client.app.state.audit
    first = date.today().replace(day=1)
    s = log.summary(first.isoformat(), (first + timedelta(days=40)).isoformat())
    assert s["reads"].get("app", 0) >= 1 and s["refused"] >= 1 and s["totp_logins"] >= 1
    r = client.get("/api/v1/report-job/access-review", headers=JOB).json()
    assert r["due"] and "revue des accès" in r["title"] and "Journal : chaîne intacte" in r["message"]
    assert client.post("/api/v1/report-job/access-review/ack", json={"month": r["month"]}, headers=JOB).status_code == 200
    assert client.get("/api/v1/report-job/access-review", headers=JOB).json()["due"] is False
