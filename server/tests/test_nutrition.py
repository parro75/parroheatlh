"""Calories des recettes (Cooklang + Ciqual) et repas notés dans l'app."""

from __future__ import annotations

import pytest
from conftest import AUTH
from test_app_api import app_login

from shc import nutrition
from shc.nutrition import RecipeBook, parse_quantity, parse_recipe

CONF = """
# commentaire
œufs = 22000 ; pièce 50
farine = 9435 ; ml 0.5
beurre = 16400
lait = 19024 ; ml 1.03
moules = 10014 ; g 0.3
sel = 0
ail = 11000 ; gousse 5
"""

CREPES = """---
title: Crêpes
servings: 4
tags: [dessert, rapide]
---
Mélanger @farine{250%g}, @œufs{3} et @lait{1/2%l}. -- commentaire ignoré
Ajouter @beurre{40-50%g} fondu et @sel{1%pincée}.
> Astuce : @sucre{100%g} ici n'est qu'une note, ignorée.
Servir avec @confiture (à volonté).
"""

DINER = """---
title: Dîner complet
servings: 2
---
Faire les @./Desserts/Crêpes{2%servings} et des @moules{1000%g} à l'@ail{2%gousses}.
Assaisonner avec @poivre{}.
"""


@pytest.fixture
def book(tmp_path):
    (tmp_path / "config").mkdir()
    (tmp_path / "config" / "nutrition.conf").write_text(CONF, encoding="utf-8")
    (tmp_path / "Desserts").mkdir()
    (tmp_path / "Desserts" / "Crêpes.cook").write_text(CREPES, encoding="utf-8")
    (tmp_path / "Dîner.cook").write_text(DINER, encoding="utf-8")
    return RecipeBook(str(tmp_path))


def test_quantities():
    assert parse_quantity("3") == 3 and parse_quantity("1/2") == 0.5 and parse_quantity("1 1/2") == 1.5
    assert parse_quantity("40-50") == 45 and parse_quantity("0,5") == 0.5
    assert parse_quantity("quelques") is None and parse_quantity("") is None


def test_parse_recipe():
    r = parse_recipe(CREPES, "Desserts/Crêpes")
    assert r.title == "Crêpes" and r.servings == 4 and r.tags == ["dessert", "rapide"]
    names = [i.name for i in r.ingredients]
    assert names == ["farine", "œufs", "lait", "beurre", "sel", "confiture"]  # la note (>) est ignorée
    lait = r.ingredients[2]
    assert lait.quantity == 0.5 and lait.unit == "l"


def test_recipe_calories(book):
    c = book.get("Desserts/Crêpes")
    ciq = nutrition.load_ciqual()
    expected = (250 * ciq["9435"].per100["kcal"] + 150 * ciq["22000"].per100["kcal"]
                + 515 * ciq["19024"].per100["kcal"] + 45 * ciq["16400"].per100["kcal"]) / 100
    assert c["total"]["kcal"] == pytest.approx(expected, abs=1)
    assert c["per_serving"]["kcal"] == pytest.approx(expected / 4, abs=1)
    assert c["complete"] and c["unquantified"] == ["confiture"]  # sans quantité : signalé, pas manquant
    lait = next(i for i in c["ingredients"] if i["name"] == "lait")
    assert lait["grams"] == 515.0 and lait["ciqual"] == "19024"


def test_sub_recipe_edible_share_and_missing(book):
    d = book.get("Dîner")
    crepes = book.get("Desserts/Crêpes")
    ciq = nutrition.load_ciqual()
    expected = crepes["total"]["kcal"] / 2 + 300 * ciq["10014"].per100["kcal"] / 100 + 10 * ciq["11000"].per100["kcal"] / 100
    assert d["total"]["kcal"] == pytest.approx(expected, abs=1)
    assert d["complete"] and d["unquantified"] == ["poivre"]


def test_unmapped_makes_partial(book, tmp_path):
    (tmp_path / "Soupe.cook").write_text("---\ntitle: Soupe\nservings: 2\n---\n@navet{3} et @farine{1%tasse}\n",
                                         encoding="utf-8")
    s = book.get("Soupe")
    assert not s["complete"] and s["missing"] == ["navet"]
    farine = next(i for i in s["ingredients"] if i["name"] == "farine")
    assert farine["grams"] == 125.0  # 1 tasse = 250 ml × 0,5 g/ml
    listed = {r["title"]: r for r in book.list()}
    assert listed["Soupe"]["complete"] is False and listed["Crêpes"]["per_serving"]["kcal"] > 0
    assert [r["title"] for r in book.list("creme")] == []
    assert [r["title"] for r in book.list("crepe")] == ["Crêpes"]      # sans accents
    assert [r["title"] for r in book.list("rapide")] == ["Crêpes"]     # par tag


def test_no_collection():
    b = RecipeBook(None)
    assert not b.available and b.list() == [] and b.get("x") is None


def meal(mid: str, day: str, kcal, **extra):
    return {"type": "meal", "id": f"meal:{mid}", "start": f"{day}T12:30:00-05:00", "zone_offset": "-05:00",
            "data": {"slot": "midi", "source": "recette", "kcal": kcal, "proteines": 30, **extra}}


def test_meals_validation(client):
    bad = [meal("a", "2026-11-20", -5), meal("b", "2026-11-20", "800"),
           {**meal("c", "2026-11-20", 500), "data": {"slot": "brunch", "source": "recette", "kcal": 500}}]
    for rec in bad:
        assert client.post("/ingest", json={"records": [rec]}, headers=AUTH).status_code == 422
    deleted = {"type": "meal", "id": "meal:z", "start": "2026-11-20T12:00:00-05:00", "data": {"deleted": True}}
    assert client.post("/ingest", json={"records": [deleted]}, headers=AUTH).status_code == 200


def test_nutrition_api(client):
    recs = [meal("1", "2026-11-20", 650, recipe="Plats/Chili", portions=1.5, label="Chili"),
            meal("2", "2026-11-20", 350.5, label="Sandwich", source="saisie"),
            meal("3", "2026-11-21", 400),
            {**meal("4", "2026-11-21", 900), "data": {"deleted": True}},
            {"type": "calories_daily", "id": "cal:2026-11-20", "start": "2026-11-20T00:00:00-05:00",
             "end": "2026-11-21T00:00:00-05:00", "zone_offset": "-05:00", "data": {"total_kcal": 2400.4, "active_kcal": 520}}]
    assert client.post("/ingest", json={"records": recs}, headers=AUTH).status_code == 200
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}"}
    r = client.get("/api/v1/nutrition", params={"start": "2026-11-20", "end": "2026-11-21"}, headers=h).json()
    d20, d21 = r["days"]
    assert d20["intake"]["kcal"] == 1000.5 and len(d20["meals"]) == 2 and d20["burned"]["total_kcal"] == 2400
    assert d20["balance_kcal"] == round(1000.5 - 2400)
    assert d21["intake"]["kcal"] == 400 and len(d21["meals"]) == 1  # la suppression est respectée
    assert r["days_logged"] == 2 and r["average_intake_kcal"] == 700
    assert client.get("/api/v1/recipes", headers=h).json() == {"available": False, "source": nutrition.SOURCE,
                                                               "recipes": []}
    assert client.get("/api/v1/recipe", params={"id": "x"}, headers=h).status_code == 404


def _rec(rtype, rid, day, data):
    return {"type": rtype, "id": rid, "start": f"{day}T08:00:00-05:00", "end": f"{day}T08:00:00-05:00",
            "zone_offset": "-05:00", "data": data}


def test_weight_goal(client):
    from datetime import date, timedelta
    recs = [_rec("weight", "w1", "2026-11-26", {"kg": 102.0}), _rec("weight", "w2", "2027-01-26", {"kg": 98.0}),
            _rec("goals", "goals", "2026-11-27", {"sleep_min": 450, "steps": 8000, "weight_low": 80, "weight_high": 90,
                                                  "weight_date": "2027-09-26", "weight_since": "2026-11-27"})]
    d0 = date(2027, 1, 13)
    recs += [_rec("calories_daily", f"cal:{d0 + timedelta(days=i)}", (d0 + timedelta(days=i)).isoformat(),
                  {"total_kcal": 2200}) for i in range(14)]
    assert client.post("/ingest", json={"records": recs}, headers=AUTH).status_code == 200
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}"}
    g = client.get("/api/v1/body", params={"start": "2026-11-01", "end": "2027-01-27"}, headers=h).json()["weight_goal"]
    assert g["target_kg"] == 85 and g["start"] == {"date": "2026-11-26", "kg": 102.0}
    # 61 j sur 304 : trajectoire attendue ≈ 102 - 17 × 61/304 ≈ 98,6 ; pesée 98 → en avance de 0,6 kg
    assert g["expected_kg"] == 98.6 and g["gap_kg"] == -0.6 and g["status"] == "en avance"
    assert g["to_go_kg"] == -13.0 and g["rate_kg_per_week"] == -0.38
    assert g["burned_avg_kcal"] == 2200 and g["kcal_target"] == 1790  # 2200 − 13/34,6 sem × 7700 / 7 ≈ 1786
    n = client.get("/api/v1/nutrition", params={"start": "2027-01-27", "end": "2027-01-27"}, headers=h).json()
    assert n["weight_goal"]["kcal_target"] == 1790
    # pas d'objectif de poids réglé : rien
    assert client.post("/ingest", json={"records": [_rec("goals", "goals", "2027-01-27", {"sleep_min": 450, "steps": 8000})]},
                       headers=AUTH).status_code == 200
    assert "weight_goal" not in client.get("/api/v1/body", params={"start": "2027-01-01", "end": "2027-01-27"}, headers=h).json()


def test_weight_goal_floor_and_bad_config():
    from datetime import date
    from shc.queries import FLOOR_KCAL, _weight_goal_config
    assert FLOOR_KCAL == 1500

    class S:
        def __init__(self, data):
            self.data = data

        def fetch(self, *_):
            return [type("R", (), {"data": self.data})()]
    assert _weight_goal_config(S({"weight_low": 90, "weight_high": 80, "weight_date": "2027-09-26", "weight_since": "2026-11-27"})) is None
    assert _weight_goal_config(S({"weight_low": 80, "weight_high": 90, "weight_date": "pas une date", "weight_since": "2026-11-27"})) is None
    assert _weight_goal_config(S({"weight_low": 80, "weight_high": 90, "weight_date": "2026-01-01", "weight_since": "2026-11-27"})) is None
    assert _weight_goal_config(S({"weight_low": True, "weight_high": 90, "weight_date": "2027-09-26", "weight_since": "2026-11-27"})) is None
    assert date(2027, 9, 26).isoformat() == _weight_goal_config(
        S({"weight_low": 80, "weight_high": 90, "weight_date": "2027-09-26", "weight_since": "2026-11-27"}))["date"]
