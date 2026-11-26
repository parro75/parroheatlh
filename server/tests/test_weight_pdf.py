"""Poids dans les rapports (signal lissé) et fiche PDF pour le médecin."""

from __future__ import annotations

from datetime import date, datetime, timedelta

from conftest import AUTH, sample_records
from shc import pdf, report
from shc.db import ALL_TYPES
from test_app_api import app_login
from test_report import D, daily, night, seed


def weigh(store, values: dict[date, float], fat: float | None = None):
    recs = [daily("weight", d, {"kg": v}, f"w-{d}") for d, v in values.items()]
    if fat is not None:
        recs += [daily("body_fat", d, {"percent": fat}, f"f-{d}") for d in values]
    store.upsert(recs, "test", allowed=ALL_TYPES)


def test_kg_text():
    assert report.kg_text(82.44) == "82,4 kg"
    assert report.kg_text(-1.25, signed=True) == "−1,2 kg" or report.kg_text(-1.25, signed=True) == "−1,3 kg"
    assert report.kg_text(0.8, signed=True) == "+0,8 kg"


def test_daily_weight_trend_and_rapid_change_flag(client):
    store = client.app.state.store
    seed(store)
    # 4 semaines avant : 90 kg ; semaine d'avant : 89 ; cette semaine : 86,8 → −2,2 kg en une semaine
    weigh(store, {D - timedelta(days=30): 90.0, D - timedelta(days=10): 89.0, D - timedelta(days=8): 89.0,
                  D - timedelta(days=3): 87.0, D: 86.6}, fat=24.0)
    f = report.facts(store, D)
    w = f["weight"]
    assert w["kg"] == 86.6 and w["date"] == D.isoformat() and w["fresh"] and w["body_fat_pct"] == 24.0
    assert w["change_1w_kg"] == -2.2 and w["change_4w_kg"] == -3.2
    assert "Poids : −2,2 kg en une semaine (moyenne des pesées)" in [x["text"] for x in f["flags"]]
    assert "Poids : 86,6 kg (−3,2 kg en 4 semaines)." in report.fallback_text(f)


def test_stale_weighing_not_flagged_nor_told(client):
    store = client.app.state.store
    seed(store)
    weigh(store, {D - timedelta(days=12): 90.0, D - timedelta(days=4): 87.0})
    f = report.facts(store, D)
    assert f["weight"]["fresh"] is False
    assert not any(x["text"].startswith("Poids") for x in f["flags"])
    assert "Poids" not in report.fallback_text(f)


def test_weekly_weight(client):
    store = client.app.state.store
    sunday = date(2026, 11, 29)
    store.upsert([night(sunday - timedelta(days=k), 450) for k in range(35)], "test", allowed=ALL_TYPES)
    weigh(store, {sunday - timedelta(days=k): (88.0 if k < 7 else 90.0) for k in range(0, 35, 3)})
    f = report.weekly_facts(store, sunday)
    assert f["this_week"]["weight_avg_kg"] == 88.0 and f["previous_4_weeks"]["weight_avg_kg"] == 90.0
    assert f["weight_change_kg"] == -2.0 and f["this_week"]["weighings"] == 3
    assert "Poids moyen en baisse : 88,0 kg (−2,0 kg vs 4 semaines précédentes)" in [x["text"] for x in f["flags"]]
    assert "Poids moyen : 88,0 kg (−2,0 kg vs 4 semaines précédentes)." in report.weekly_fallback(f)


def test_months_back():
    assert pdf.months_back(date(2026, 11, 25), 3) == date(2026, 8, 26)
    assert pdf.months_back(date(2026, 5, 31), 3) == date(2026, 3, 1)
    assert pdf.months_back(date(2026, 3, 31), 12) == date(2025, 4, 1)


def test_pdf_content(client):
    store = client.app.state.store
    seed(store)
    weigh(store, {D - timedelta(days=k): 88.0 + k / 20 for k in range(0, 80, 4)}, fat=24.0)
    store.upsert([{"type": "journal", "id": "j1", "start": f"{D - timedelta(days=3)}T22:00:00-05:00",
                   "zone_offset": "-05:00", "data": {"tags": ["malade"], "note": "note privée"}}], "test",
                 allowed=ALL_TYPES)
    c = pdf.collect(store, pdf.months_back(D, 3), D)
    assert c["stats"]["sleep"]["n"] == 29 and c["stats"]["weight"]["n"] == 20
    assert c["journal"] == [("malade", 1)]
    data = pdf.summary(store, D, 3, datetime(2026, 11, 25, 9, 0))
    assert data.startswith(b"%PDF-") and len(data) < 200_000


def test_pdf_route(client):
    assert client.post("/ingest", json={"records": sample_records()}, headers=AUTH).status_code == 200
    assert client.get("/api/v1/pdf/summary").status_code == 401
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}"}
    r = client.get("/api/v1/pdf/summary", params={"months": "12"}, headers=h)
    assert r.status_code == 200 and r.headers["content-type"] == "application/pdf"
    assert r.headers["cache-control"] == "no-store" and r.content.startswith(b"%PDF-")
    assert client.get("/api/v1/pdf/summary", params={"months": "7"}, headers=h).status_code == 422
    assert client.get("/api/v1/pdf/summary", params={"months": "x"}, headers=h).status_code == 422


def test_night_segments(client):
    store = client.app.state.store
    store.upsert([{"type": "sleep_session", "id": "n1", "start": "2026-11-24T23:30:00-05:00",
                   "end": "2026-11-25T07:00:00-05:00", "zone_offset": "-05:00", "data": {"stages": [
                       {"start": "2026-11-25T01:00:00-05:00", "end": "2026-11-25T07:00:00-05:00", "stage": "light"},
                       {"start": "2026-11-24T23:30:00-05:00", "end": "2026-11-25T01:00:00-05:00", "stage": "deep"}]}}],
                 "test", allowed=ALL_TYPES)
    from shc import queries
    n = queries.night(store, D)["night"]
    assert n["segments"] == [{"stage": "deep", "start_min": 0, "dur_min": 90}, {"stage": "light", "start_min": 90, "dur_min": 360}]
    assert n["asleep_min"] == 450 and n["span_min"] == 450
    assert queries.night(store, D - timedelta(days=5))["night"] is None
