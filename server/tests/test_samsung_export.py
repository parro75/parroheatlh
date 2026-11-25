"""Import d'export Samsung Health : zip SYNTHÉTIQUE au format réel (aucune donnée réelle dans le dépôt)."""

from __future__ import annotations

import json
import zipfile
from datetime import date
from zoneinfo import ZoneInfo

import pytest

from conftest import AUTH, sample_records
from shc import queries, samsung_export
from shc.db import Store

ROOT = "samsunghealth_test_20261125102541"
U = "11111111-2222-3333-4444-"  # préfixe d'UUID factice


def table(z: zipfile.ZipFile, name: str, header: list[str], rows: list[list[str]], prefix: str = "") -> None:
    lines = [f"﻿{name},6312001,3", ",".join(prefix + h if prefix and h not in ("tag_id",) else h for h in header) + ","]
    lines += [",".join(r) + "," for r in rows]
    z.writestr(f"{ROOT}/{name}.20261125102541.csv", "\n".join(lines) + "\n")


@pytest.fixture
def export_zip(tmp_path):
    path = tmp_path / "export.zip"
    with zipfile.ZipFile(path, "w") as z:
        table(z, "com.samsung.shealth.step_daily_trend", ["day_time", "count", "distance", "source_type", "datauuid"], [
            ["2016-03-01 00:00:00.000", "9000", "6500.5", "-2", U + "000000000001"],
            ["2016-03-01 00:00:00.000", "7000", "5000", "0", U + "000000000002"],  # par appareil : ignoré
            ["2026-11-24 00:00:00.000", "1", "1", "-2", U + "000000000003"],  # jour déjà fourni par HC
            ["1970-01-01 00:00:00.000", "5", "1", "-2", U + "000000000004"],  # horodatage bidon
        ])
        table(z, "com.samsung.shealth.tracker.heart_rate",
              ["start_time", "end_time", "heart_rate", "min", "max", "time_offset", "datauuid"], [
                  ["2016-03-01 13:00:00.000", "2016-03-01 13:01:00.000", "70.0", "65.0", "80.0", "UTC-0500", U + "1"],
                  ["2016-03-01 18:00:00.000", "2016-03-01 18:01:00.000", "90.0", "", "", "UTC-0500", U + "2"],
                  # 02:00 UTC le 02/03 = 21:00 le 01/03 à Montréal
                  ["2016-03-02 02:00:00.000", "2016-03-02 02:01:00.000", "50.0", "48.0", "52.0", "UTC-0500", U + "3"],
              ], prefix="com.samsung.health.heart_rate.")
        table(z, "com.samsung.health.hrv", ["start_time", "end_time", "time_offset", "binning_data", "datauuid"], [
            ["2025-05-10 07:00:00.000", "2025-05-10 09:00:00.000", "UTC-0400", U + "hrv1.binning_data.json", U + "hrv1"],
        ])
        z.writestr(f"{ROOT}/jsons/com.samsung.health.hrv/1/{U}hrv1.binning_data.json", json.dumps([
            {"start_time": 1, "end_time": 2, "sdnn": 50.0, "rmssd": 40.0},
            {"start_time": 2, "end_time": 3, "sdnn": 60.0, "rmssd": 50.0},
        ]))
        sleep_hdr = ["start_time", "end_time", "time_offset", "sleep_score", "efficiency", "mental_recovery",
                     "physical_recovery", "sleep_cycle", "datauuid"]
        table(z, "com.samsung.shealth.sleep", sleep_hdr, [
            # nuit historique (23:00 → 07:00 heure de Montréal, réveil le 05/03/2016)
            ["2016-03-05 04:00:00.000", "2016-03-05 12:00:00.000", "UTC-0500", "81", "92.5", "70.0", "75.0", "4", U + "s1"],
            # même nuit que celle de Health Connect dans sample_records (23:10 → 06:40, -05:00)
            ["2026-11-24 04:12:00.000", "2026-11-24 11:39:00.000", "UTC-0500", "77", "90.0", "60.0", "65.0", "5", U + "s2"],
            # « nuit » de 20 h : enregistrement erroné, écarté
            ["2017-01-01 04:00:00.000", "2017-01-02 00:00:00.000", "UTC-0500", "50", "", "", "", "", U + "s3"],
        ])
        table(z, "com.samsung.health.sleep_stage", ["start_time", "end_time", "stage", "sleep_id", "time_offset"], [
            ["2016-03-05 04:00:00.000", "2016-03-05 06:00:00.000", "40002", U + "s1", "UTC-0500"],
            ["2016-03-05 06:00:00.000", "2016-03-05 07:30:00.000", "40003", U + "s1", "UTC-0500"],
            ["2016-03-05 07:30:00.000", "2016-03-05 09:00:00.000", "40004", U + "s1", "UTC-0500"],
            ["2016-03-05 09:00:00.000", "2016-03-05 12:00:00.000", "40001", U + "s1", "UTC-0500"],
        ])
        table(z, "com.samsung.shealth.exercise",
              ["start_time", "end_time", "duration", "exercise_type", "distance", "calorie", "mean_heart_rate",
               "max_heart_rate", "vo2_max", "time_offset", "pkg_name", "datauuid"], [
                  ["2016-04-01 22:00:00.000", "2016-04-01 22:30:00.000", "1800000", "1002", "5000.0", "350.0",
                   "150.0", "170.0", "", "UTC-0400", "com.sec.android.app.shealth", U + "e1"],
                  ["2019-06-01 12:00:00.000", "", "3600000", "15003", "", "400.0", "", "", "", "UTC-0400",
                   "com.strava", U + "e2"],
                  # chevauche la séance Health Connect de sample_records (17:45 → 18:31:30, -05:00)
                  ["2026-11-24 22:45:00.000", "2026-11-24 23:31:30.000", "2790000", "1002", "8123.4", "612.7",
                   "151.2", "174.0", "", "UTC-0500", "com.sec.android.app.shealth", U + "e3"],
              ])
        table(z, "com.samsung.health.weight", ["start_time", "weight", "body_fat", "time_offset", "datauuid"], [
            ["2016-01-10 12:00:00.000", "82.4", "21.5", "UTC-0500", U + "w1"],
            ["2026-11-24 12:31:00.000", "78.4", "18.3", "UTC-0500", U + "w2"],  # ±2 min de la pesée HC
        ])
        table(z, "com.samsung.shealth.tracker.oxygen_saturation", ["start_time", "spo2", "time_offset", "datauuid"], [
            ["2023-01-01 09:00:00.000", "97.0", "UTC-0500", U + "o1"],
            ["2023-01-02 09:00:00.000", "41.0", "UTC-0500", U + "o2"],  # artefact de capteur
        ])
        table(z, "com.samsung.shealth.vitals.daily",
              ["day_time", "shrv_average", "shrv_baseline_min", "shrv_baseline_max", "shr_average",
               "respiratory_rate_average", "skin_temp_average", "skin_temp_baseline", "spo2_average"], [
                  ["2026-11-20 00:00:00.000", "45.5", "38.0", "52.0", "52.0", "14.2", "34.30", "33.90", "-1.0"],
              ])
        table(z, "com.samsung.shealth.vitality_score",
              ["day_time", "total_score", "sleep_score", "activity_score", "shr_value", "shrv_value"], [
                  ["2025-01-15 00:00:00.000", "78", "80", "70", "55.0", "41.0"],
                  ["2026-11-20 00:00:00.000", "82", "85", "75", "53.0", "44.0"],
              ])
        table(z, "com.samsung.shealth.stress", ["start_time", "score", "time_offset", "datauuid"], [
            ["2024-02-01 15:00:00.000", "20.0", "UTC-0500", U + "st1"],
            ["2024-02-01 20:00:00.000", "60.0", "UTC-0500", U + "st2"],
        ])
        table(z, "com.samsung.shealth.training_load.daily",
              ["time", "acute_training_load", "chronic_training_load", "recovery_time",
               "recommend_lower_bound", "recommend_upper_bound"], [
                  ["2026-09-01 00:00:00.000", "250.5", "300.25", "720", "10.0", "30.0"],
              ])
    return str(path)


@pytest.fixture
def store_with_hc(client):
    """Le magasin du serveur, avec les données « téléphone » déjà reçues par /ingest."""
    assert client.post("/ingest", json={"records": sample_records()}, headers=AUTH).status_code == 200
    return client.app.state.store


def test_import_counts_and_hc_priority(store_with_hc, export_zip):
    store = store_with_hc
    res = samsung_export.run(store, export_zip)
    # pas : 2016-03-01 seulement (par appareil ignoré, 1970 ignoré, 24/11 déjà fourni par HC)
    assert res["steps_daily"] == (2, 1)
    assert [r.data["count"] for r in store.fetch("steps_daily", date(2026, 11, 24), date(2026, 11, 24))] == [10231]
    # nuit chevauchant celle de HC : score importé, session NON dupliquée
    assert len(store.fetch("sleep_session", date(2026, 11, 24), date(2026, 11, 24))) == 1
    assert res["exercise_session"] == (2, 2)  # la séance de 2026 chevauche HC : écartée
    assert res["oxygen_saturation"] == (1, 1)  # SpO2 41 % écartée
    assert store.fetch("sleep_session", date(2017, 1, 1), date(2017, 1, 2)) == []
    body = store.fetch("weight", date(2016, 1, 10), date(2016, 1, 10))
    assert [b.data["kg"] for b in body] == [82.4]
    assert store.fetch("weight", date(2026, 11, 24), date(2026, 11, 24))[0].id == "w-1"  # HC conservé seul
    # 2e import : rien de nouveau
    again = samsung_export.run(store, export_zip)
    assert sum(w for _, w in again.values()) == 0


def test_dry_run_writes_nothing(store_with_hc, export_zip):
    before = store_with_hc.status()["types"]
    res = samsung_export.run(store_with_hc, export_zip, dry_run=True)
    assert sum(w for _, w in res.values()) == 0 and sum(c for c, _ in res.values()) > 0
    assert store_with_hc.status()["types"] == before


def test_imported_values(store_with_hc, export_zip):
    store = store_with_hc
    samsung_export.run(store, export_zip)
    d = date(2016, 3, 1)
    (hr,) = store.fetch("heart_rate_daily", d, d)
    assert hr.data == {"min": 48, "avg": 70.0, "max": 90, "samples": 3, "source": "samsung_export"}

    (h,) = store.fetch("hrv_rmssd", date(2025, 5, 10), date(2025, 5, 10))
    assert h.data["rmssd_ms"] == 45.0 and h.data["sdnn_ms"] == 55.0

    night = queries.sleep(store, date(2016, 3, 5), date(2016, 3, 5))["sessions"][0]
    assert (night["bedtime"], night["wake"], night["score"]) == ("23:00", "07:00", 81)
    assert night["stages_min"] == {"deep": 90, "rem": 90, "light": 120, "awake": 180}

    # le score Samsung s'accroche aussi à la nuit venue de Health Connect
    hc_night = queries.sleep(store, date(2026, 11, 24), date(2026, 11, 24))["sessions"][0]
    assert hc_night["source"] == "com.sec.android.app.shealth" and hc_night["score"] == 77

    ws = queries.workouts(store, date(2016, 4, 1), date(2019, 6, 1))["workouts"]
    assert [(w["type"], w["duration_min"]) for w in ws] == [("running", 30), ("biking_stationary", 60)]
    assert "com.strava" in ws[1]["source"]


def test_recovery_stress_training_load(store_with_hc, export_zip):
    store = store_with_hc
    samsung_export.run(store, export_zip)
    rec = queries.recovery(store, date(2025, 1, 15), date(2026, 11, 20))["days"]
    assert rec[0] == {"date": "2025-01-15", "hrv_ms": 41.0, "sleeping_hr": 55.0, "energy_score": 78}
    assert rec[1] == {"date": "2026-11-20", "hrv_ms": 45.5, "sleeping_hr": 52.0, "respiratory_rate": 14.2,
                      "skin_temp_delta_c": 0.4, "energy_score": 82}  # SpO2 -1 écartée
    assert queries.stress(store, date(2024, 2, 1), date(2024, 2, 1))["days"] == [
        {"date": "2024-02-01", "avg": 40.0, "max": 60, "min": 20, "n": 2}]
    tl = queries.training_load(store, date(2026, 9, 1), date(2026, 9, 1))["days"][0]
    assert tl["acute"] == 250.5 and tl["recovery_time"] == 720
    hr = queries.heart_rate(store, date(2026, 11, 20), date(2026, 11, 20))["days"]
    assert hr == [{"date": "2026-11-20", "min": None, "avg": None, "max": None, "resting": None, "sleeping": 52.0}]


def test_trends(store_with_hc, export_zip):
    store = store_with_hc
    samsung_export.run(store, export_zip)
    t = queries.trends(store, "steps", "year", date(2015, 1, 1), date(2026, 12, 31))
    assert [(r["period"], r["avg"]) for r in t["rows"]] == [("2016", 9000.0), ("2026", 9321.5)]
    m = queries.trends(store, "hrv_nightly", "month", date(2025, 1, 1), date(2026, 12, 31))
    assert [(r["period"], r["avg"]) for r in m["rows"]] == [("2025-01", 41.0), ("2026-11", 45.5)]
    with pytest.raises(queries.QueryError):
        queries.trends(store, "inconnue", "month", date(2025, 1, 1), date(2025, 2, 1))
    with pytest.raises(queries.QueryError):
        queries.trend_range("2000-01-01", "2026-01-01", date(2026, 1, 1))


def test_ingest_still_refuses_import_only_types(client):
    rec = {"type": "vitals_daily", "id": "x", "start": "2026-11-20T12:00:00+00:00", "data": {}}
    assert client.post("/ingest", json={"records": [rec]}, headers=AUTH).status_code == 422


def test_cli_entry(tmp_path, export_zip, monkeypatch, capsys):
    monkeypatch.setenv("DB_PATH", str(tmp_path / "cli.db"))
    samsung_export.main([export_zip, "--dry-run"])
    assert "simulation" in capsys.readouterr().out
    s = Store(str(tmp_path / "cli.db"), ZoneInfo("America/Toronto"))
    assert s.status()["types"] == {}
