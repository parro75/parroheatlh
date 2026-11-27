"""De bout en bout : vrai serveur, parcours OAuth complet, vrai client MCP streamable HTTP avec jeton."""

from __future__ import annotations

import asyncio
import json

import httpx
import httpx2
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from conftest import AUTH, full_login, sample_records

EXPECTED_TOOLS = {
    "get_daily_steps", "get_sleep", "get_heart_rate", "get_hrv", "get_body_measurements",
    "get_spo2", "get_workouts", "get_sync_status", "get_recovery", "get_stress", "get_training_load", "get_trends", "get_daily_report", "get_journal", "get_weekly_report",
    "get_nutrition", "find_recipes",
}


def _payload(result) -> dict:
    assert not result.is_error, result.content
    if result.structured_content is not None:
        return result.structured_content
    return json.loads(result.content[0].text)


async def _session(url: str, token: str):
    http = httpx2.AsyncClient(headers={"Authorization": f"Bearer {token}"}, timeout=30)
    async with http, Client(streamable_http_client(url, http_client=http)) as client:
        tools = await client.list_tools()
        out = {"tools": {t.name for t in tools.tools},
               "ro": all(t.annotations and t.annotations.read_only_hint for t in tools.tools)}
        w = {"start": "2026-11-23", "end": "2026-11-24"}
        for key, tool in (("sleep", "get_sleep"), ("steps", "get_daily_steps"), ("hr", "get_heart_rate"),
                          ("hrv", "get_hrv"), ("body", "get_body_measurements"), ("spo2", "get_spo2"),
                          ("workouts", "get_workouts")):
            out[key] = _payload(await client.call_tool(tool, w))
        out["status"] = _payload(await client.call_tool("get_sync_status", {}))
        out["bad"] = await client.call_tool("get_sleep", {"start": "2026-11-24", "end": "2026-11-01"})
        out["toolong"] = await client.call_tool("get_sleep", {"start": "2024-01-01", "end": "2026-11-24"})
        return out


def test_mcp_end_to_end(live_server):
    base, app = live_server
    r = httpx.post(f"{base}/ingest", json={"records": sample_records()}, headers=AUTH)
    assert r.status_code == 200, r.text

    with httpx.Client(base_url=base) as http:
        tokens = full_login(http, app, base)
    out = asyncio.run(_session(f"{base}/mcp", tokens["access_token"]))

    assert out["tools"] == EXPECTED_TOOLS
    assert out["ro"]

    (night,) = out["sleep"]["sessions"]
    assert night["date"] == "2026-11-24"
    assert (night["bedtime"], night["wake"]) == ("23:10", "06:40")
    assert night["in_bed_min"] == 450
    assert night["asleep_min"] == 435
    assert night["stages_min"] == {"deep": 90, "rem": 105, "light": 240, "awake": 15}

    assert out["steps"]["days"] == [{"date": "2026-11-23", "steps": 8412}, {"date": "2026-11-24", "steps": 10231}]
    assert out["steps"]["total"] == 18643
    assert out["hr"]["days"] == [{"date": "2026-11-24", "min": 48, "avg": 66, "max": 162, "resting": 52,
                                  "sleeping": None}]
    assert out["hrv"]["days"][0] == {"date": "2026-11-24", "rmssd_avg": 44.5, "rmssd_min": 41.2,
                                     "rmssd_max": 47.8, "n": 2}
    assert out["body"]["days"] == [{"date": "2026-11-24", "weight_kg": 78.43, "body_fat_pct": 18.3}]
    assert out["spo2"]["days"][0]["avg"] == 96.0
    (run,) = out["workouts"]["workouts"]
    assert run["type"] == "running" and run["duration_min"] == 46 and run["distance_km"] == 8.12
    assert run["avg_hr"] == 151 and run["calories_kcal"] == 613

    assert out["status"]["types"]["sleep_session"]["last_day"] == "2026-11-24"
    assert out["status"]["last_ingest"]["records"] == len(sample_records())

    assert out["bad"].is_error and "start est après end" in out["bad"].content[0].text
    assert out["toolong"].is_error


def test_mcp_requires_valid_token(live_server):
    base, _ = live_server
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
    hdr = {"Accept": "application/json, text/event-stream"}
    r = httpx.post(f"{base}/mcp", json=body, headers=hdr)
    assert r.status_code == 401
    assert "resource_metadata" in r.headers.get("www-authenticate", "")
    r = httpx.post(f"{base}/mcp", json=body, headers={**hdr, "Authorization": "Bearer faux"})
    assert r.status_code == 401
    # Le jeton d'ingestion du téléphone n'ouvre PAS le MCP.
    r = httpx.post(f"{base}/mcp", json=body, headers={**hdr, **AUTH})
    assert r.status_code == 401
