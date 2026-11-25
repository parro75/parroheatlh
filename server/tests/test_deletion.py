from datetime import date

from conftest import AUTH, sample_records


def test_deletion_markers(client):
    recs = sample_records() + [{"type": "resting_heart_rate", "id": "calc:rhr:sleep-1", "start": "2026-11-24T06:40:00-05:00",
                                "zone_offset": "-05:00", "data": {"bpm": 52}}]
    assert client.post("/ingest", json={"records": recs}, headers=AUTH).status_code == 200
    store = client.app.state.store
    d = date(2026, 11, 24)
    assert len(store.fetch("sleep_session", d, d)) == 1
    r = client.post("/ingest", json={"records": [{"type": "deletion", "id": "sleep-1"}, {"type": "deletion", "id": "w-1"}]},
                    headers=AUTH)
    assert r.status_code == 200
    assert store.fetch("sleep_session", d, d) == []
    assert [x.id for x in store.fetch("resting_heart_rate", d, d)] == ["rhr-1"]   # la FC calculée de la nuit est partie
    assert store.fetch("weight", d, d) == []
    assert len(store.fetch("body_fat", d, d)) == 1                                # le reste est intact
    bad = client.post("/ingest", json={"records": [{"type": "deletion"}]}, headers=AUTH)
    assert bad.status_code == 422
