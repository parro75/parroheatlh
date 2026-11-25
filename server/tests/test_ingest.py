from __future__ import annotations

from conftest import AUTH, sample_records


def test_healthz(client):
    assert client.get("/healthz").json() == {"status": "ok"}


def test_ingest_requires_token(client):
    body = {"records": sample_records()}
    assert client.post("/ingest", json=body).status_code == 401
    assert client.post("/ingest", json=body, headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.app.state.store.status()["last_ingest"] is None


def test_ingest_and_idempotent_upsert(client):
    recs = sample_records()
    r = client.post("/ingest", json={"records": recs}, headers=AUTH)
    assert r.status_code == 200, r.text
    assert r.json() == {"accepted": len(recs)}
    # Deuxième envoi (chevauchement de fenêtre) : aucun doublon, valeur mise à jour.
    recs[0]["data"]["count"] = 9000
    assert client.post("/ingest", json={"records": recs}, headers=AUTH).status_code == 200
    status = client.app.state.store.status()
    assert status["types"]["steps_daily"]["records"] == 2
    assert status["types"]["hrv_rmssd"]["records"] == 2
    assert status["last_ingest"]["records"] == len(recs)


def test_invalid_batch_is_rejected_atomically(client):
    recs = sample_records()
    recs.append({"type": "blood_glucose", "id": "x", "start": "2026-11-24T00:00:00-05:00"})
    r = client.post("/ingest", json={"records": recs}, headers=AUTH)
    assert r.status_code == 422
    assert f"records[{len(recs) - 1}]" in r.json()["detail"]
    assert client.app.state.store.status()["types"] == {}


def test_naive_datetime_is_rejected(client):
    rec = {"type": "weight", "id": "w", "start": "2026-11-24T07:30:00", "data": {"kg": 80}}
    r = client.post("/ingest", json={"records": [rec]}, headers=AUTH)
    assert r.status_code == 422
    assert "décalage" in r.json()["detail"]


def test_bad_payloads(client):
    assert client.post("/ingest", content=b"{", headers=AUTH).status_code == 400
    assert client.post("/ingest", json={"records": {}}, headers=AUTH).status_code == 422
    rec = {"type": "sleep_session", "id": "s", "start": "2026-11-24T07:00:00-05:00",
           "end": "2026-11-24T06:00:00-05:00"}
    assert client.post("/ingest", json={"records": [rec]}, headers=AUTH).status_code == 422


def test_local_date_rules(client):
    store = client.app.state.store
    recs = [
        # Nuit : rattachée au jour du réveil.
        {"type": "sleep_session", "id": "n", "start": "2026-11-20T23:30:00-05:00",
         "end": "2026-11-21T07:00:00-05:00", "zone_offset": "-05:00", "data": {}},
        # Séance tardive : 03:30 UTC le lendemain, mais le 20 en heure locale.
        {"type": "exercise_session", "id": "e", "start": "2026-11-21T03:30:00Z",
         "end": "2026-11-21T04:00:00Z", "zone_offset": "-05:00", "data": {}},
        # Sans zone_offset : on garde le décalage de l'horodatage.
        {"type": "weight", "id": "w", "start": "2026-11-20T23:59:00-05:00", "data": {"kg": 80}},
    ]
    assert client.post("/ingest", json={"records": recs}, headers=AUTH).status_code == 200
    from datetime import date
    d20, d21 = date(2026, 11, 20), date(2026, 11, 21)
    assert [r.id for r in store.fetch("sleep_session", d21, d21)] == ["n"]
    assert [r.id for r in store.fetch("exercise_session", d20, d20)] == ["e"]
    assert [r.id for r in store.fetch("weight", d20, d20)] == ["w"]


def test_mcp_without_token_is_401(client):
    assert client.post("/mcp", json={}).status_code == 401
