from __future__ import annotations

from zoneinfo import ZoneInfo

import pytest
from fastapi.testclient import TestClient

from shc.app import create_app
from shc.config import Settings

INGEST_TOKEN = "i" * 40
MCP_SECRET = "s" * 40


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        db_path=str(tmp_path / "health.db"),
        ingest_token=INGEST_TOKEN,
        mcp_secret=MCP_SECRET,
        tz=ZoneInfo("America/Toronto"),
    )


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings)) as c:
        yield c


AUTH = {"Authorization": f"Bearer {INGEST_TOKEN}"}


def sample_records() -> list[dict]:
    """Un jeu réaliste : une nuit à cheval sur deux jours, un footing, des agrégats."""
    return [
        {"type": "steps_daily", "id": "steps:2026-11-23", "start": "2026-11-23T00:00:00-05:00",
         "end": "2026-11-24T00:00:00-05:00", "zone_offset": "-05:00", "data": {"count": 8412}},
        {"type": "steps_daily", "id": "steps:2026-11-24", "start": "2026-11-24T00:00:00-05:00",
         "end": "2026-11-25T00:00:00-05:00", "zone_offset": "-05:00", "data": {"count": 10231}},
        {"type": "heart_rate_daily", "id": "hr:2026-11-24", "start": "2026-11-24T00:00:00-05:00",
         "end": "2026-11-25T00:00:00-05:00", "zone_offset": "-05:00", "data": {"min": 48, "avg": 66.4, "max": 162}},
        {"type": "resting_heart_rate", "id": "rhr-1", "start": "2026-11-24T07:05:00-05:00",
         "zone_offset": "-05:00", "data": {"bpm": 52, "source": "com.sec.android.app.shealth"}},
        {"type": "hrv_rmssd", "id": "hrv-1", "start": "2026-11-24T03:00:00-05:00", "zone_offset": "-05:00",
         "data": {"rmssd_ms": 41.2}},
        {"type": "hrv_rmssd", "id": "hrv-2", "start": "2026-11-24T05:00:00-05:00", "zone_offset": "-05:00",
         "data": {"rmssd_ms": 47.8}},
        {"type": "sleep_session", "id": "sleep-1", "start": "2026-11-23T23:10:00-05:00",
         "end": "2026-11-24T06:40:00-05:00", "zone_offset": "-05:00",
         "data": {"source": "com.sec.android.app.shealth", "stages": [
             {"start": "2026-11-23T23:10:00-05:00", "end": "2026-11-23T23:25:00-05:00", "stage": "awake"},
             {"start": "2026-11-23T23:25:00-05:00", "end": "2026-11-24T01:25:00-05:00", "stage": "light"},
             {"start": "2026-11-24T01:25:00-05:00", "end": "2026-11-24T02:55:00-05:00", "stage": "deep"},
             {"start": "2026-11-24T02:55:00-05:00", "end": "2026-11-24T04:40:00-05:00", "stage": "rem"},
             {"start": "2026-11-24T04:40:00-05:00", "end": "2026-11-24T06:40:00-05:00", "stage": "light"},
         ]}},
        {"type": "weight", "id": "w-1", "start": "2026-11-24T07:30:00-05:00", "zone_offset": "-05:00",
         "data": {"kg": 78.43}},
        {"type": "body_fat", "id": "bf-1", "start": "2026-11-24T07:30:00-05:00", "zone_offset": "-05:00",
         "data": {"percent": 18.27}},
        {"type": "oxygen_saturation", "id": "o2-1", "start": "2026-11-24T04:00:00-05:00",
         "zone_offset": "-05:00", "data": {"percent": 96.0}},
        {"type": "exercise_session", "id": "ex-1", "start": "2026-11-24T17:45:00-05:00",
         "end": "2026-11-24T18:31:30-05:00", "zone_offset": "-05:00",
         "data": {"exercise_type": 56, "title": "Footing", "distance_m": 8123.4,
                  "calories_kcal": 612.7, "avg_hr": 151.2, "max_hr": 174, "source": "com.sec.android.app.shealth"}},
    ]
