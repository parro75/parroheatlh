"""Import d'un export Samsung Health (zip « Télécharger les données personnelles »).

    python -m shc.samsung_export /data/import.zip [--dry-run]

Principes :
- Health Connect garde TOUJOURS la priorité : les identifiants journaliers sont ceux du téléphone
  (steps:AAAA-MM-JJ, hr:AAAA-MM-JJ) et l'écriture se fait en mode « ignore » ; une nuit, une séance ou
  une mesure qui chevauche un enregistrement déjà reçu de Health Connect n'est pas importée.
- L'export apporte l'historique (depuis 2015) et ce que Samsung ne publie pas dans Health Connect :
  HRV, scores de sommeil, vitaux nocturnes, score d'énergie, stress, charge d'entraînement.
- Bibliothèque standard seulement ; lecture en flux depuis le zip, rien n'est extrait sur disque.

Format de l'export : un CSV par table (ligne 1 = métadonnées, ligne 2 = en-têtes, parfois préfixés par
le nom de la table), horodatages en UTC « AAAA-MM-JJ HH:MM:SS.mmm » + colonne time_offset « UTC-0500 » ;
les valeurs fines (HRV minute par minute…) sont dans jsons/<table>/<x>/<binning_data>.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
import zipfile
from collections import defaultdict
from collections.abc import Iterator
from datetime import datetime, timedelta, timezone
from statistics import mean
from typing import Any

from .db import ALL_TYPES, Store

CLIENT = "import-export-samsung"
PKG_SHEALTH = "com.sec.android.app.shealth"

# Codes Samsung (« Predefined Exercise Type ») → noms Health Connect quand ils existent.
EXERCISE_CODES = {
    1001: "walking",
    1002: "running",
    11007: "biking",
    15003: "biking_stationary",
    15005: "running_treadmill",
}
# Phases de sommeil Samsung → noms utilisés par le serveur.
SLEEP_STAGES = {40001: "awake", 40002: "light", 40003: "deep", 40004: "rem"}
MAX_SLEEP = timedelta(hours=16)
# Bornes de plausibilité des mesures ponctuelles (artefacts de capteur écartés).
MAX_JSON_BYTES = 20 * 1024 * 1024  # une courbe HRV d'une nuit pèse quelques Ko
PLAUSIBLE = {"weight": (25, 300), "body_fat": (2, 70), "oxygen_saturation": (70, 100)}


# ---- lecture de l'export ------------------------------------------------------


class Export:
    def __init__(self, path: str):
        self.z = zipfile.ZipFile(path)
        self.tables: dict[str, zipfile.ZipInfo] = {}
        self.jsons: dict[tuple[str, str], zipfile.ZipInfo] = {}
        for info in self.z.infolist():
            parts = info.filename.split("/")
            if len(parts) == 2 and parts[1].endswith(".csv"):
                self.tables[re.sub(r"\.\d{10,}\.csv$", "", parts[1])] = info
            elif len(parts) >= 4 and parts[1] == "jsons":
                self.jsons[(parts[2], parts[-1])] = info

    def rows(self, table: str) -> Iterator[dict[str, str]]:
        """Lignes d'une table, colonnes renommées par leur dernier segment (« …heart_rate.max » → « max »)."""
        info = self.tables.get(table)
        if info is None:
            return
        with self.z.open(info) as f:
            text = io.TextIOWrapper(f, encoding="utf-8-sig", errors="replace")
            text.readline()  # ligne de métadonnées
            reader = csv.reader(text)
            header = next(reader, [])
            names: list[str] = []
            for h in header:
                short = h.rsplit(".", 1)[-1]
                names.append(short if short not in names else h)
            for row in reader:
                yield {k: v for k, v in zip(names, row) if v != ""}

    def binning(self, table: str, filename: str) -> Any:
        info = self.jsons.get((table, filename))
        if info is None or info.file_size > MAX_JSON_BYTES:  # fichier anormal (ou bombe de décompression)
            return None
        with self.z.open(info) as f:
            return json.load(f)


# ---- conversions ----------------------------------------------------------------


def parse_offset(value: str | None) -> timezone:
    m = re.fullmatch(r"UTC([+-])(\d{2})(\d{2})", value or "")
    if not m:
        return timezone.utc
    delta = timedelta(hours=int(m.group(2)), minutes=int(m.group(3)))
    return timezone(delta if m.group(1) == "+" else -delta)


def parse_utc(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        dt = datetime.strptime(value[:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
    except ValueError:
        return None
    return dt if dt.year >= 2000 else None  # l'export contient quelques horodatages 1970


def iso_local(dt: datetime, tz: timezone) -> str:
    return dt.astimezone(tz).isoformat(timespec="seconds")


def offset_str(tz: timezone) -> str:
    total = int(tz.utcoffset(None).total_seconds() // 60)
    sign = "-" if total < 0 else "+"
    return f"{sign}{abs(total) // 60:02d}:{abs(total) % 60:02d}"


def num(row: dict[str, str], key: str) -> float | None:
    try:
        v = float(row[key])
    except (KeyError, ValueError):
        return None
    return v


def day_of(row: dict[str, str], key: str = "day_time") -> str | None:
    """Les colonnes day_time/time portent le jour local à minuit : on garde la date telle quelle."""
    v = row.get(key, "")
    return v[:10] if re.match(r"\d{4}-\d{2}-\d{2}", v) and not v.startswith("1970") else None


def record(rtype: str, rid: str, start: datetime, end: datetime | None, tz: timezone,
           data: dict[str, Any]) -> dict[str, Any]:
    data = {k: v for k, v in data.items() if v is not None}
    data.setdefault("source", "samsung_export")
    rec = {"type": rtype, "id": rid, "start": iso_local(start, tz), "zone_offset": offset_str(tz), "data": data}
    if end is not None:
        rec["end"] = iso_local(end, tz)
    return rec


def daily(rtype: str, rid: str, day: str, data: dict[str, Any]) -> dict[str, Any]:
    """Enregistrement journalier : midi UTC du jour, sans décalage, pour que la date locale soit stable."""
    start = datetime.fromisoformat(day).replace(hour=12, tzinfo=timezone.utc)
    return record(rtype, rid, start, None, timezone.utc, data)


def _round(v: float | None, nd: int = 1) -> float | None:
    return None if v is None else round(v, nd)


# ---- extracteurs : un par table ----------------------------------------------------


def steps(exp: Export) -> Iterator[dict]:
    # source_type -2 = total quotidien déjà dédoublonné par Samsung entre téléphone et montre.
    for r in exp.rows("com.samsung.shealth.step_daily_trend"):
        if r.get("source_type") != "-2" or (day := day_of(r)) is None or (n := num(r, "count")) is None:
            continue
        yield daily("steps_daily", f"steps:{day}", day,
                    {"count": int(n), "distance_m": _round(num(r, "distance"), 0), "source": "samsung_export"})


def heart_rate(exp: Export) -> Iterator[dict]:
    per_day: dict[str, list[tuple[float, float, float]]] = defaultdict(list)
    for r in exp.rows("com.samsung.shealth.tracker.heart_rate"):
        start, hr = parse_utc(r.get("start_time")), num(r, "heart_rate")
        if start is None or hr is None or hr <= 0:
            continue
        day = start.astimezone(parse_offset(r.get("time_offset"))).date().isoformat()
        per_day[day].append((hr, num(r, "min") or hr, num(r, "max") or hr))
    for day, vals in per_day.items():
        yield daily("heart_rate_daily", f"hr:{day}", day, {
            "min": round(min(v[1] for v in vals)), "avg": round(mean(v[0] for v in vals), 1),
            "max": round(max(v[2] for v in vals)), "samples": len(vals), "source": "samsung_export"})


def hrv(exp: Export) -> Iterator[dict]:
    for r in exp.rows("com.samsung.health.hrv"):
        start, tz = parse_utc(r.get("start_time")), parse_offset(r.get("time_offset"))
        if start is None or not r.get("binning_data"):
            continue
        points = exp.binning("com.samsung.health.hrv", r["binning_data"]) or []
        rmssd = [p["rmssd"] for p in points if isinstance(p, dict) and isinstance(p.get("rmssd"), (int, float))
                 and p["rmssd"] > 0]
        if not rmssd:
            continue
        sdnn = [p["sdnn"] for p in points if isinstance(p.get("sdnn"), (int, float)) and p["sdnn"] > 0]
        yield record("hrv_rmssd", f"sh:hrv:{r['datauuid']}", start, parse_utc(r.get("end_time")), tz, {
            "rmssd_ms": round(mean(rmssd), 1), "sdnn_ms": _round(mean(sdnn)) if sdnn else None,
            "points": len(rmssd)})


def sleep(exp: Export, existing: list[tuple[float, float]]) -> Iterator[dict]:
    stages: dict[str, list[dict]] = defaultdict(list)
    for r in exp.rows("com.samsung.health.sleep_stage"):
        s, e = parse_utc(r.get("start_time")), parse_utc(r.get("end_time"))
        code = int(num(r, "stage") or 0)
        if s and e and r.get("sleep_id"):
            stages[r["sleep_id"]].append({"s": s, "e": e, "stage": SLEEP_STAGES.get(code, "unknown")})
    for r in exp.rows("com.samsung.shealth.sleep"):
        start, end, tz = parse_utc(r.get("start_time")), parse_utc(r.get("end_time")), parse_offset(r.get("time_offset"))
        uid = r.get("datauuid")
        if not (start and end and uid) or end <= start or end - start > MAX_SLEEP:
            continue  # incohérent, ou « nuit » de plus de 16 h (enregistrement erroné)
        score = {k: num(r, k) for k in ("sleep_score", "efficiency", "mental_recovery", "physical_recovery",
                                        "movement_awakening", "sleep_cycle")}
        if score["sleep_score"] is not None:
            yield record("sleep_score", f"sh:sleepscore:{uid}", start, end, tz, {
                "score": round(score["sleep_score"]), "efficiency": _round(score["efficiency"]),
                "mental_recovery": _round(score["mental_recovery"]),
                "physical_recovery": _round(score["physical_recovery"]),
                "movement_awakening": _round(score["movement_awakening"]),
                "cycles": None if score["sleep_cycle"] is None else int(score["sleep_cycle"])})
        if overlaps(start, end, existing):
            continue  # nuit déjà reçue de Health Connect
        st = [{"start": iso_local(x["s"], tz), "end": iso_local(x["e"], tz), "stage": x["stage"]}
              for x in sorted(stages.get(uid, []), key=lambda x: x["s"])]
        yield record("sleep_session", f"sh:sleep:{uid}", start, end, tz, {"stages": st})


def overlaps(start: datetime, end: datetime, spans: list[tuple[float, float]], slack: float = 0) -> bool:
    s, e = start.timestamp() - slack, end.timestamp() + slack
    return any(a < e and b > s for a, b in spans)


def exercise(exp: Export, existing: list[tuple[float, float]]) -> Iterator[dict]:
    for r in exp.rows("com.samsung.shealth.exercise"):
        start, end, tz = parse_utc(r.get("start_time")), parse_utc(r.get("end_time")), parse_offset(r.get("time_offset"))
        if start is None or not r.get("datauuid"):
            continue
        if end is None or end <= start:
            dur = num(r, "duration")
            end = start + timedelta(milliseconds=dur) if dur else None
        if end is None or overlaps(start, end, existing):
            continue
        code = int(num(r, "exercise_type") or 0)
        pkg = r.get("pkg_name") or PKG_SHEALTH
        yield record("exercise_session", f"sh:ex:{r['datauuid']}", start, end, tz, {
            "exercise_type": EXERCISE_CODES.get(code, f"samsung_{code}"),
            "distance_m": _round(num(r, "distance")), "calories_kcal": _round(num(r, "calorie")),
            "avg_hr": _round(num(r, "mean_heart_rate")), "max_hr": _round(num(r, "max_heart_rate")),
            "vo2_max": _round(num(r, "vo2_max")),
            "source": "samsung_export" if pkg == PKG_SHEALTH else f"samsung_export ({pkg})"})


def instants(exp: Export, table: str, mapping: dict[str, tuple[str, str]],
             existing: dict[str, list[tuple[float, float]]]) -> Iterator[dict]:
    """Mesures ponctuelles ; ignorées si Health Connect a déjà une mesure du même type à ±2 min."""
    for r in exp.rows(table):
        start, tz = parse_utc(r.get("start_time")), parse_offset(r.get("time_offset"))
        if start is None or not r.get("datauuid"):
            continue
        for column, (rtype, field) in mapping.items():
            v = num(r, column)
            lo, hi = PLAUSIBLE[rtype]
            if v is None or not lo <= v <= hi or overlaps(start, start, existing[rtype], slack=120):
                continue
            yield record(rtype, f"sh:{rtype}:{r['datauuid']}", start, None, tz, {field: round(v, 2)})


def vitals(exp: Export) -> Iterator[dict]:
    for r in exp.rows("com.samsung.shealth.vitals.daily"):
        if (day := day_of(r)) is None:
            continue
        spo2 = num(r, "spo2_average")
        yield daily("vitals_daily", f"vitals:{day}", day, {
            "hrv_ms": _round(num(r, "shrv_average")),
            "hrv_baseline_ms": [num(r, "shrv_baseline_min"), num(r, "shrv_baseline_max")],
            "sleeping_hr": _round(num(r, "shr_average")),
            "sleeping_hr_baseline": [num(r, "shr_baseline_min"), num(r, "shr_baseline_max")],
            "respiratory_rate": _round(num(r, "respiratory_rate_average")),
            "skin_temp_c": _round(num(r, "skin_temp_average"), 2),
            "skin_temp_baseline_c": _round(num(r, "skin_temp_baseline"), 2),
            "spo2": spo2 if spo2 and spo2 > 0 else None})


def energy(exp: Export) -> Iterator[dict]:
    for r in exp.rows("com.samsung.shealth.vitality_score"):
        if (day := day_of(r)) is None or (score := num(r, "total_score")) is None:
            continue
        yield daily("energy_score", f"energy:{day}", day, {
            "score": round(score), "sleep_score": _round(num(r, "sleep_score")),
            "activity_score": _round(num(r, "activity_score")),
            "sleeping_hr": _round(num(r, "shr_value")), "sleeping_hr_score": _round(num(r, "shr_score")),
            "hrv_ms": _round(num(r, "shrv_value")), "hrv_score": _round(num(r, "shrv_score"))})


def stress(exp: Export) -> Iterator[dict]:
    per_day: dict[str, list[float]] = defaultdict(list)
    for r in exp.rows("com.samsung.shealth.stress"):
        start, s = parse_utc(r.get("start_time")), num(r, "score")
        if start is None or s is None:
            continue
        per_day[start.astimezone(parse_offset(r.get("time_offset"))).date().isoformat()].append(s)
    for day, vals in per_day.items():
        yield daily("stress_daily", f"stress:{day}", day, {
            "avg": round(mean(vals), 1), "max": round(max(vals)), "min": round(min(vals)), "n": len(vals)})


def training_load(exp: Export) -> Iterator[dict]:
    for r in exp.rows("com.samsung.shealth.training_load.daily"):
        if (day := day_of(r, "time")) is None:
            continue
        yield daily("training_load_daily", f"tl:{day}", day, {
            "acute": _round(num(r, "acute_training_load")), "chronic": _round(num(r, "chronic_training_load")),
            "recovery_time": _round(num(r, "recovery_time"), 0),
            "recommended_range": [num(r, "recommend_lower_bound"), num(r, "recommend_upper_bound")]})


# ---- orchestration -----------------------------------------------------------------


def collect(exp: Export, store: Store) -> dict[str, list[dict]]:
    existing = {t: store.spans(t) for t in ("sleep_session", "exercise_session", "weight", "body_fat",
                                             "oxygen_saturation")}
    batches = {
        "steps_daily": list(steps(exp)),
        "heart_rate_daily": list(heart_rate(exp)),
        "hrv_rmssd": list(hrv(exp)),
        "sleep": list(sleep(exp, existing["sleep_session"])),
        "exercise_session": list(exercise(exp, existing["exercise_session"])),
        "body": list(instants(exp, "com.samsung.health.weight",
                              {"weight": ("weight", "kg"), "body_fat": ("body_fat", "percent")}, existing)),
        "oxygen_saturation": list(instants(exp, "com.samsung.shealth.tracker.oxygen_saturation",
                                           {"spo2": ("oxygen_saturation", "percent")}, existing)),
        "vitals_daily": list(vitals(exp)),
        "energy_score": list(energy(exp)),
        "stress_daily": list(stress(exp)),
        "training_load_daily": list(training_load(exp)),
    }
    return batches


def run(store: Store, path: str, dry_run: bool = False) -> dict[str, tuple[int, int]]:
    """Renvoie {lot: (candidats, écrits)}. En --dry-run, rien n'est écrit."""
    exp = Export(path)
    out = {}
    for name, recs in collect(exp, store).items():
        written = 0
        if recs and not dry_run:
            for i in range(0, len(recs), 5000):
                written += store.upsert(recs[i:i + 5000], CLIENT, mode="ignore", allowed=ALL_TYPES)
        out[name] = (len(recs), written)
    return out


def main(argv: list[str] | None = None) -> None:
    import os
    from zoneinfo import ZoneInfo

    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("zip")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    store = Store(os.environ.get("DB_PATH", "/data/health.db"), ZoneInfo(os.environ.get("TZ_LOCAL", "America/Toronto")))
    try:
        result = run(store, a.zip, a.dry_run)
    finally:
        store.close()
    for name, (cand, written) in result.items():
        print(f"{name:22s} candidats {cand:7d}  écrits {written:7d}")
    print("(simulation : rien n'a été écrit)" if a.dry_run else "import terminé")


if __name__ == "__main__":
    main(sys.argv[1:])
