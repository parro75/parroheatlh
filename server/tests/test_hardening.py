"""Durcissement : textes libres plafonnés, JSON invalide = 400, JSON géant ignoré dans un export."""

from __future__ import annotations

import zipfile
from datetime import date

import pytest

from conftest import AUTH, REPORT_TOKEN
from shc.db import RecordError
from shc.samsung_export import MAX_JSON_BYTES, Export


def test_free_text_is_capped(client):
    store = client.app.state.store
    store.upsert([
        {"type": "journal", "id": "journal:1", "start": "2026-11-20T21:00:00-05:00", "zone_offset": "-05:00",
         "data": {"tags": ["alcool"] * 50 + ["x" * 100], "note": "n" * 5000}},
        {"type": "exercise_session", "id": "e1", "start": "2026-11-20T18:00:00-05:00", "end": "2026-11-20T18:30:00-05:00",
         "zone_offset": "-05:00", "data": {"exercise_type": 79, "title": "t" * 1000}},
    ])
    j = store.fetch("journal", date(2026, 11, 20), date(2026, 11, 20))[0].data
    assert len(j["note"]) == 500 and len(j["tags"]) == 12 and all(len(t) <= 40 for t in j["tags"])
    assert len(store.fetch("exercise_session", date(2026, 11, 20), date(2026, 11, 20))[0].data["title"]) == 200


def test_journal_tags_must_be_text(client):
    with pytest.raises(RecordError):
        client.app.state.store.upsert([{"type": "journal", "id": "journal:2", "start": "2026-11-20T21:00:00-05:00",
                                        "zone_offset": "-05:00", "data": {"tags": [{"x": 1}]}}])


def test_invalid_json_is_400_not_500(client):
    job = {"Authorization": f"Bearer {REPORT_TOKEN}", "Content-Type": "application/json"}
    assert client.post("/api/v1/report-job/narrative", content=b"{pas du json", headers=job).status_code == 400
    assert client.post("/api/v1/report-job/notified", content=b"[1, 2]", headers=job).status_code == 400
    assert client.post("/ingest-export/start", content=b"nope", headers={**AUTH, "Content-Type": "application/json"}).status_code == 400


def test_oversized_json_in_export_is_ignored(tmp_path):
    p = tmp_path / "export.zip"
    with zipfile.ZipFile(p, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("export/jsons/com.samsung.health.hrv/a/gros.json", b"[" + b"0," * (MAX_JSON_BYTES // 2) + b"0]")
        z.writestr("export/jsons/com.samsung.health.hrv/a/petit.json", b"[1, 2]")
    e = Export(str(p))
    assert e.binning("com.samsung.health.hrv", "gros.json") is None
    assert e.binning("com.samsung.health.hrv", "petit.json") == [1, 2]
