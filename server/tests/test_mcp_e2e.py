"""De bout en bout : vrai serveur uvicorn sur un port libre, vrai client MCP streamable HTTP."""

from __future__ import annotations

import asyncio
import json
import socket
import threading
import time

import httpx
import pytest
import uvicorn
from mcp import Client

from conftest import AUTH, MCP_SECRET, sample_records
from shc.app import create_app

EXPECTED_TOOLS = {
    "get_daily_steps", "get_sleep", "get_heart_rate", "get_hrv", "get_body_measurements",
    "get_spo2", "get_workouts", "get_sync_status",
}


@pytest.fixture
def live_server(settings):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(create_app(settings), host="127.0.0.1", port=port, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{port}"
    for _ in range(100):
        try:
            if httpx.get(f"{base}/healthz").status_code == 200:
                break
        except httpx.HTTPError:
            time.sleep(0.05)
    yield base
    server.should_exit = True
    thread.join(timeout=5)


def _payload(result) -> dict:
    assert not result.is_error, result.content
    if result.structured_content is not None:
        return result.structured_content
    return json.loads(result.content[0].text)


async def _session(url: str):
    async with Client(url) as client:
        tools = await client.list_tools()
        names = {t.name for t in tools.tools}
        out = {"tools": names, "ro": all(t.annotations and t.annotations.read_only_hint for t in tools.tools)}
        w = {"start": "2026-11-23", "end": "2026-11-24"}
        out["sleep"] = _payload(await client.call_tool("get_sleep", w))
        out["steps"] = _payload(await client.call_tool("get_daily_steps", w))
        out["hr"] = _payload(await client.call_tool("get_heart_rate", w))
        out["hrv"] = _payload(await client.call_tool("get_hrv", w))
        out["body"] = _payload(await client.call_tool("get_body_measurements", w))
        out["spo2"] = _payload(await client.call_tool("get_spo2", w))
        out["workouts"] = _payload(await client.call_tool("get_workouts", w))
        out["status"] = _payload(await client.call_tool("get_sync_status", {}))
        out["bad"] = await client.call_tool("get_sleep", {"start": "2026-11-24", "end": "2026-11-01"})
        out["toolong"] = await client.call_tool("get_sleep", {"start": "2024-01-01", "end": "2026-11-24"})
        return out


def test_mcp_end_to_end(live_server):
    r = httpx.post(f"{live_server}/ingest", json={"records": sample_records()}, headers=AUTH)
    assert r.status_code == 200, r.text

    out = asyncio.run(_session(f"{live_server}/mcp/{MCP_SECRET}"))

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
    assert out["hr"]["days"] == [{"date": "2026-11-24", "min": 48, "avg": 66, "max": 162, "resting": 52}]
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


def test_mcp_wrong_secret_is_rejected(live_server):
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
    hdr = {"Accept": "application/json, text/event-stream"}
    assert httpx.post(f"{live_server}/mcp/{'x' * 40}", json=body, headers=hdr).status_code == 404
    assert httpx.post(f"{live_server}/mcp", json=body, headers=hdr).status_code == 404
