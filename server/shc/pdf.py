"""Fiche de synthèse PDF pour un rendez-vous médical (3, 6 ou 12 mois).

Générée EN MÉMOIRE à la demande de l'app (jeton OAuth) : rien n'est écrit sur le disque du serveur.
Contenu volontairement limité aux mesures ; le journal n'apparaît qu'en nombre de jours par étiquette
(les notes libres restent privées). Aucun nom ni identifiant dans le document ni ses métadonnées.
"""

from __future__ import annotations

import os
from collections import defaultdict
from collections.abc import Callable
from datetime import date, datetime, timedelta
from statistics import mean
from typing import Any

from fpdf import FPDF

from . import __version__, queries
from .db import Store

FONT_DIR = os.environ.get("SHC_FONT_DIR", "/usr/share/fonts/truetype/dejavu")
MONTHS = (3, 6, 12)
MONTH_ABBR = ("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
INK = (33, 33, 33)
MUTED = (110, 110, 110)
GRID = (215, 215, 215)


# ---- mise en forme française ---------------------------------------------------------------------------

def _num(v: float, nd: int = 0) -> str:
    return f"{v:,.{nd}f}".replace(",", "\N{NO-BREAK SPACE}").replace(".", ",")


def _hm(minutes: float) -> str:
    m = round(minutes)
    return f"{m // 60} h {m % 60:02d}"


def _clock(minutes: float) -> str:
    m = round(minutes) % (24 * 60)
    return f"{m // 60:02d}:{m % 60:02d}"


def _d(d: date) -> str:
    return d.strftime("%d/%m/%Y")


FMT: dict[str, Callable[[float], str]] = {
    "hm": _hm,
    "int": lambda v: _num(v),
    "bpm": lambda v: f"{_num(v)} bpm",
    "ms": lambda v: f"{_num(v)} ms",
    "kg": lambda v: f"{_num(v, 1)} kg",
    "pct": lambda v: f"{_num(v, 1)} %",
}


def months_back(end: date, months: int) -> date:
    """Premier jour de la période : même quantième `months` mois plus tôt, + 1 jour."""
    y, m = divmod(end.month - 1 - months, 12)
    y, m = end.year + y, m + 1
    day = min(end.day, [31, 29 if y % 4 == 0 and (y % 100 or y % 400 == 0) else 28,
                        31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1])
    return date(y, m, day) + timedelta(days=1)


# ---- calculs -----------------------------------------------------------------------------------------------

def _main_nights(store: Store, start: date, end: date) -> dict[str, dict[str, Any]]:
    """Une nuit par jour de réveil (la plus longue, siestes de moins de 3 h écartées)."""
    out: dict[str, dict[str, Any]] = {}
    for n in queries.sleep(store, start, end)["sessions"]:
        a = n.get("asleep_min") or n.get("in_bed_min") or 0
        if a >= 180 and a > (out.get(n["date"], {}).get("_a") or 0):
            out[n["date"]] = {**n, "_a": a}
    return out


def _stats(values: dict[str, float], start: date, end: date) -> dict[str, Any] | None:
    if not values:
        return None
    v = list(values.values())
    first = [x for k, x in values.items() if k <= (start + timedelta(days=27)).isoformat()]
    last = [x for k, x in values.items() if k >= (end - timedelta(days=27)).isoformat()]
    return {"avg": mean(v), "min": min(v), "max": max(v), "n": len(v),
            "first": mean(first) if len(first) >= 2 else None, "last": mean(last) if len(last) >= 2 else None}


def _weekly(values: dict[str, float]) -> list[tuple[date, float]]:
    """Moyenne par semaine (lundi), pour des courbes lisibles sur 3 à 12 mois."""
    acc: dict[date, list[float]] = defaultdict(list)
    for k, v in values.items():
        d = date.fromisoformat(k)
        acc[d - timedelta(days=d.weekday())].append(v)
    return [(d + timedelta(days=3), mean(v)) for d, v in sorted(acc.items())]


def collect(store: Store, start: date, end: date) -> dict[str, Any]:
    nights = _main_nights(store, start, end)
    sleep_min = {k: n["_a"] for k, n in nights.items()}
    scores = {k: float(n["score"]) for k, n in nights.items() if n.get("score") is not None}
    bed, wake = [], []
    for n in nights.values():
        if n.get("bedtime"):
            h, m = map(int, n["bedtime"].split(":"))
            bed.append(h * 60 + m + (24 * 60 if h < 12 else 0))  # 00:30 → 24:30, pour moyenner autour de minuit
        if n.get("wake"):
            h, m = map(int, n["wake"].split(":"))
            wake.append(h * 60 + m)

    def dv(metric: str) -> dict[str, float]:
        return queries._daily_values(store, metric, start, end)

    series = {
        "sleep": sleep_min, "sleep_score": scores,
        "resting_hr": dv("resting_hr"), "sleeping_hr": dv("sleeping_hr"), "heart_rate_avg": dv("heart_rate_avg"),
        "hrv": dv("hrv_nightly"), "weight": dv("weight"), "body_fat": dv("body_fat"),
        "steps": dv("steps"), "stress": dv("stress"),
    }
    spo2 = queries.spo2(store, start, end)["days"]
    ws = queries.workouts(store, start, end)["workouts"]
    weeks = max(1.0, ((end - start).days + 1) / 7)
    tags: dict[str, set[str]] = defaultdict(set)
    for e in queries.journal(store, start, end)["events"]:
        for t in e["tags"]:
            tags[t].add(e["date"])
    return {
        "series": series,
        "stats": {k: _stats(v, start, end) for k, v in series.items()},
        "short_nights": sum(1 for a in sleep_min.values() if a < 6 * 60),
        "bed_avg": mean(bed) if bed else None, "wake_avg": mean(wake) if wake else None,
        "spo2": {"avg": mean(d["avg"] for d in spo2), "min": min(d["min"] for d in spo2), "n": len(spo2),
                 "low_nights": sum(1 for d in spo2 if d["min"] < 90)} if spo2 else None,
        "workouts": {"n": len(ws), "min_per_week": sum(w.get("duration_min") or 0 for w in ws) / weeks,
                     "types": _top_types(ws)} if ws else None,
        "journal": sorted(((t, len(d)) for t, d in tags.items()), key=lambda x: -x[1]),
    }


TYPE_FR = {
    "other_workout": "autre", "walking": "marche", "running": "course", "running_treadmill": "course sur tapis",
    "biking": "vélo", "biking_stationary": "vélo d'appartement", "hiking": "randonnée", "swimming_pool": "natation",
    "swimming_open_water": "nage en eau libre", "strength_training": "musculation", "weightlifting": "haltérophilie",
    "elliptical": "elliptique", "rowing_machine": "rameur", "rowing": "aviron", "yoga": "yoga", "pilates": "pilates",
    "stretching": "étirements", "high_intensity_interval_training": "fractionné (HIIT)", "dancing": "danse",
    "skiing": "ski", "snowshoeing": "raquettes", "skating": "patin", "ice_skating": "patin à glace",
    "tennis": "tennis", "badminton": "badminton", "golf": "golf", "soccer": "football", "calisthenics": "gymnastique",
    "stair_climbing": "escaliers", "stair_climbing_machine": "simulateur d'escaliers", "exercise_class": "cours collectif",
    "martial_arts": "arts martiaux", "boxing": "boxe", "paddling": "pagaie", "rock_climbing": "escalade",
}


def _top_types(ws: list[dict[str, Any]]) -> str:
    c: dict[str, int] = defaultdict(int)
    for w in ws:
        t = str(w.get("type") or "other_workout")
        c[TYPE_FR.get(t, t.replace("_", " "))] += 1
    return ", ".join(f"{t} ({n})" for t, n in sorted(c.items(), key=lambda x: -x[1])[:4])


# ---- document ------------------------------------------------------------------------------------------------

class _Doc(FPDF):
    def footer(self) -> None:
        self.set_y(-12)
        self.set_font("DejaVu", "", 7)
        self.set_text_color(*MUTED)
        self.cell(0, 5, f"Parro Health {__version__} — document personnel — page {self.page_no()}/{{nb}}", align="C")


ROWS = (  # clé, libellé, format
    ("sleep", "Sommeil par nuit (temps endormi)", "hm"),
    ("sleep_score", "Score de sommeil (0-100)", "int"),
    ("resting_hr", "FC au repos", "bpm"),
    ("sleeping_hr", "FC pendant le sommeil", "bpm"),
    ("heart_rate_avg", "FC moyenne de la journée", "bpm"),
    ("hrv", "Variabilité cardiaque nocturne (HRV)", "ms"),
    ("weight", "Poids", "kg"),
    ("body_fat", "Masse grasse (estimée par la balance)", "pct"),
    ("steps", "Pas par jour", "int"),
    ("stress", "Stress (0-100, Samsung)", "int"),
)

CHARTS = (  # clé, titre, format, couleur
    ("sleep", "Sommeil par nuit — moyenne par semaine", "hm", (63, 81, 181)),
    ("resting_hr", "FC au repos — moyenne par semaine", "bpm", (211, 47, 47)),
    ("sleeping_hr", "FC pendant le sommeil — moyenne par semaine", "bpm", (211, 47, 47)),
    ("hrv", "HRV nocturne — moyenne par semaine", "ms", (0, 137, 123)),
    ("weight", "Poids — moyenne des pesées par semaine", "kg", (109, 76, 65)),
    ("steps", "Pas par jour — moyenne par semaine", "int", (56, 142, 60)),
)


def _h2(pdf: FPDF, text: str) -> None:
    if pdf.get_y() > pdf.page_break_trigger - 30:
        pdf.add_page()
    pdf.ln(3)
    pdf.set_font("DejaVu", "B", 11)
    pdf.set_text_color(*INK)
    pdf.cell(0, 7, text, new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("DejaVu", "", 9)


def _chart(pdf: FPDF, title: str, points: list[tuple[date, float]], fmt: Callable[[float], str],
           color: tuple[int, int, int], start: date, end: date) -> None:
    h, label_w = 34, 18
    if pdf.get_y() + h + 16 > pdf.page_break_trigger:
        pdf.add_page()
    pdf.set_font("DejaVu", "B", 9)
    pdf.set_text_color(*INK)
    pdf.cell(0, 6, title, new_x="LMARGIN", new_y="NEXT")
    x0, y0 = pdf.l_margin + label_w, pdf.get_y() + 1
    w = pdf.w - pdf.r_margin - x0
    vals = [v for _, v in points]
    lo, hi = min(vals), max(vals)
    pad = (hi - lo) * 0.15 or max(abs(hi) * 0.05, 1)
    lo, hi = lo - pad, hi + pad
    span = max(1, (end - start).days)

    def px(d: date) -> float:
        return x0 + w * min(max((d - start).days / span, 0), 1)

    def py(v: float) -> float:
        return y0 + h - h * (v - lo) / (hi - lo)

    pdf.set_font("DejaVu", "", 6.5)
    pdf.set_text_color(*MUTED)
    pdf.set_line_width(0.15)
    pdf.set_draw_color(*GRID)
    for v in (lo + (hi - lo) * f for f in (0.1, 0.5, 0.9)):
        pdf.line(x0, py(v), x0 + w, py(v))
        pdf.set_xy(pdf.l_margin, py(v) - 2)
        pdf.cell(label_w - 1.5, 4, fmt(v), align="R")
    d = date(start.year, start.month, 1)
    while d <= end:
        nxt = date(d.year + (d.month == 12), d.month % 12 + 1, 1)
        if d >= start:
            pdf.line(px(d), y0, px(d), y0 + h)
        if d >= start or (nxt - start).days > 10:  # pas d'étiquette pour un bout de mois trop court
            pdf.set_xy(px(max(d, start)), y0 + h + 0.5)
            pdf.cell(12, 3.5, MONTH_ABBR[d.month - 1] + (f" {d.year % 100:02d}" if d.month == 1 else ""))
        d = nxt
    pdf.rect(x0, y0, w, h)
    pdf.set_draw_color(*color)
    pdf.set_fill_color(*color)
    pdf.set_line_width(0.5)
    seg: list[tuple[float, float]] = []
    prev: date | None = None
    for day, v in points:
        if prev is not None and (day - prev).days > 15:  # trou de plus de 2 semaines : pas de trait
            if len(seg) > 1:
                pdf.polyline(seg)
            seg = []
        seg.append((px(day), py(v)))
        pdf.rect(px(day) - 0.6, py(v) - 0.6, 1.2, 1.2, style="F")
        prev = day
    if len(seg) > 1:
        pdf.polyline(seg)
    pdf.set_line_width(0.2)
    pdf.set_y(y0 + h + 6)


def render(c: dict[str, Any], start: date, end: date, months: int, generated: datetime) -> bytes:
    pdf = _Doc(format="A4")
    pdf.set_margins(15, 15, 15)
    pdf.set_auto_page_break(True, 16)
    pdf.add_font("DejaVu", "", os.path.join(FONT_DIR, "DejaVuSans.ttf"))
    pdf.add_font("DejaVu", "B", os.path.join(FONT_DIR, "DejaVuSans-Bold.ttf"))
    pdf.set_title("Synthèse santé")
    pdf.set_creator(f"Parro Health {__version__}")
    pdf.add_page()

    pdf.set_font("DejaVu", "B", 17)
    pdf.set_text_color(*INK)
    pdf.cell(0, 9, "Synthèse santé", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("DejaVu", "", 10)
    pdf.cell(0, 6, f"Période : du {_d(start)} au {_d(end)} ({months} mois)", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("DejaVu", "", 8)
    pdf.set_text_color(*MUTED)
    pdf.cell(0, 5, f"Générée le {generated.strftime('%d/%m/%Y à %H:%M')}", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(2)
    pdf.multi_cell(0, 4, "Mesures d'objets connectés grand public (montre Samsung Galaxy Watch, balance connectée), "
                         "recueillies automatiquement. Ce sont des estimations, pas des mesures médicales. "
                         "« Début » et « fin » : moyennes des 4 premières et des 4 dernières semaines de la période.",
                   new_x="LMARGIN", new_y="NEXT")
    pdf.set_text_color(*INK)

    _h2(pdf, "Résumé")
    rows = [(label, fmt, c["stats"][k]) for k, label, fmt in ROWS if c["stats"].get(k)]
    if rows:
        with pdf.table(col_widths=(62, 24, 34, 40, 20), text_align=("LEFT", "RIGHT", "RIGHT", "RIGHT", "RIGHT"),
                       line_height=5.2, padding=1, borders_layout="HORIZONTAL_LINES",
                       first_row_as_headings=True) as t:
            head = t.row()
            for h in ("Mesure", "Moyenne", "Min – max", "Début → fin", "Jours"):
                head.cell(h)
            for label, fmt, s in rows:
                f = FMT[fmt]
                r = t.row()
                r.cell(label)
                r.cell(f(s["avg"]))
                r.cell(f"{f(s['min'])} – {f(s['max'])}")
                r.cell(f"{f(s['first'])} → {f(s['last'])}" if s["first"] is not None and s["last"] is not None else "—")
                r.cell(str(s["n"]))
    else:
        pdf.cell(0, 6, "Aucune mesure sur la période.", new_x="LMARGIN", new_y="NEXT")

    lines: list[tuple[str, str]] = []
    if c["stats"].get("sleep"):
        n = c["stats"]["sleep"]["n"]
        lines.append(("Nuits de moins de 6 h", f"{c['short_nights']} sur {n} ({round(100 * c['short_nights'] / n)} %)"))
    if c["bed_avg"] is not None and c["wake_avg"] is not None:
        lines.append(("Coucher / lever moyens", f"{_clock(c['bed_avg'])} / {_clock(c['wake_avg'])}"))
    if (s := c["spo2"]):
        lines.append(("SpO2 nocturne", f"moyenne {_num(s['avg'], 1)} %, minimum {_num(s['min'], 1)} % "
                                        f"({s['n']} nuits mesurées)"))
        lines.append(("Nuits avec SpO2 minimale < 90 %", f"{s['low_nights']} sur {s['n']}"))
    if (w := c["workouts"]):
        lines.append(("Activité physique", f"{w['n']} séances, {_num(w['min_per_week'])} min par semaine en moyenne"))
        lines.append(("Types de séances", w["types"]))
    if lines:
        _h2(pdf, "Sommeil, oxygénation, activité")
        for k, v in lines:
            pdf.set_font("DejaVu", "B", 9)
            pdf.cell(62, 5.5, k)
            pdf.set_font("DejaVu", "", 9)
            pdf.multi_cell(0, 5.5, v, new_x="LMARGIN", new_y="NEXT")

    if c["journal"]:
        _h2(pdf, "Journal (jours où l'événement a été noté)")
        pdf.multi_cell(0, 5.5, " ; ".join(f"{t} : {n}" for t, n in c["journal"]), new_x="LMARGIN", new_y="NEXT")

    charts = []
    have_rhr = len(_weekly(c["series"]["resting_hr"])) >= 4
    for k, title, fmt, color in CHARTS:
        if k == "sleeping_hr" and have_rhr or k == "resting_hr" and not have_rhr:
            continue  # une seule courbe de FC : la FC au repos si assez de recul, sinon la FC de sommeil
        pts = _weekly(c["series"][k])
        if len(pts) >= 2:
            charts.append((title, pts, FMT[fmt], color))
    if charts:
        pdf.add_page()
        _h2(pdf, "Évolution")
        for title, pts, fmt, color in charts:
            _chart(pdf, title, pts, fmt, color, start, end)
    return bytes(pdf.output())


def summary(store: Store, end: date, months: int, generated: datetime) -> bytes:
    if months not in MONTHS:
        raise queries.QueryError(f"months : {', '.join(map(str, MONTHS))}")
    start = months_back(end, months)
    return render(collect(store, start, end), start, end, months, generated)
