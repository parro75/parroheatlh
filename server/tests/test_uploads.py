"""Envoi d'un export par le téléphone : morceaux, vérification SHA-256, import automatique."""

from __future__ import annotations

import hashlib
import time
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from conftest import AUTH
from shc import uploads
from shc.syncwatch import SyncWatch
from test_samsung_export import export_zip  # noqa: F401  (fixture : export synthétique au format réel)


def send(client, data: bytes, chunk: int, sha: str | None = None):
    r = client.post("/ingest-export/start", json={"size": len(data), "sha256": sha or hashlib.sha256(data).hexdigest()},
                    headers=AUTH)
    assert r.status_code == 200, r.text
    uid = r.json()["id"]
    for i in range(0, len(data), chunk):
        assert client.put(f"/ingest-export/{uid}/{i // chunk}", content=data[i:i + chunk], headers=AUTH).status_code == 200
    return uid


def wait(client, uid):
    for _ in range(100):
        s = client.get(f"/ingest-export/{uid}", headers=AUTH).json()
        if s["state"] in ("done", "error"):
            return s
        time.sleep(0.05)
    raise AssertionError("import trop long")


def test_upload_then_import(client, export_zip, monkeypatch):  # noqa: F811
    monkeypatch.setattr(uploads, "CHUNK_SIZE", 2048)
    data = open(export_zip, "rb").read()
    uid = send(client, data, 2048)
    # un morceau rejoué (perte de réponse en 5G) est accepté sans être réécrit
    last = (len(data) - 1) // 2048
    assert client.put(f"/ingest-export/{uid}/{last}", content=data[last * 2048:], headers=AUTH).status_code == 200
    assert client.post(f"/ingest-export/{uid}/finish", headers=AUTH).json() == {"state": "importing"}
    s = wait(client, uid)
    assert s["state"] == "done", s
    assert s["result"]["steps_daily"]["written"] == 2
    assert client.app.state.store.last_ingest_from("import-export-samsung")
    # le fichier reçu ne traîne pas sur le disque
    import os
    d = os.path.join(os.path.dirname(client.app.state.store._conn.execute("PRAGMA database_list").fetchone()[2]), "uploads")
    assert os.listdir(d) == []


def test_upload_errors(client, monkeypatch):
    monkeypatch.setattr(uploads, "CHUNK_SIZE", 10)
    assert client.post("/ingest-export/start", json={"size": 5, "sha256": "0" * 64}).status_code == 401
    data = b"0123456789abcdefghij"
    r = client.post("/ingest-export/start", json={"size": len(data), "sha256": "0" * 64}, headers=AUTH).json()
    # un seul envoi à la fois
    assert client.post("/ingest-export/start", json={"size": 1, "sha256": "0" * 64}, headers=AUTH).status_code == 409
    uid = r["id"]
    assert client.put(f"/ingest-export/{uid}/1", content=data[10:], headers=AUTH).status_code == 422  # désordre
    client.put(f"/ingest-export/{uid}/0", content=data[:10], headers=AUTH)
    client.put(f"/ingest-export/{uid}/1", content=data[10:], headers=AUTH)
    assert client.post(f"/ingest-export/{uid}/finish", headers=AUTH).status_code == 422          # SHA-256 faux
    assert client.get(f"/ingest-export/{uid}", headers=AUTH).json()["state"] == "error"
    assert client.post("/ingest-export/start", json={"size": 10**12, "sha256": "0" * 64}, headers=AUTH).status_code == 422


def test_bad_zip_reports_error(client):
    data = b"pas un zip" * 10
    uid = send(client, data, 1024 * 1024)
    client.post(f"/ingest-export/{uid}/finish", headers=AUTH)
    s = wait(client, uid)
    assert s["state"] == "error" and "BadZipFile" in s["error"]


def test_export_reminder(tmp_path):
    tz = ZoneInfo("America/Toronto")
    w = SyncWatch(str(tmp_path / "w.db"))
    noon = datetime(2026, 11, 26, 12, 0, tzinfo=tz).astimezone(timezone.utc)
    assert w.export_reminder(None, noon, tz)["action"] == "export_reminder"                  # jamais importé
    w.ack_reminder(noon)
    assert w.export_reminder(None, noon + timedelta(days=5), tz)["action"] == "none"           # déjà rappelé
    later = noon + timedelta(days=40)
    recent = (later - timedelta(days=3)).isoformat()
    assert w.export_reminder(recent, later, tz)["action"] == "none"                          # import récent
    old = (noon - timedelta(days=45)).isoformat()
    assert w.export_reminder(old, noon + timedelta(days=31), tz)["action"] == "export_reminder"
    night = datetime(2026, 12, 30, 2, 0, tzinfo=tz).astimezone(timezone.utc)
    assert w.export_reminder(old, night, tz)["action"] == "none"                              # pas la nuit
