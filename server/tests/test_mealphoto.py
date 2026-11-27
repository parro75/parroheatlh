"""Photos de repas : file d'attente, analyse par le job, rapprochement Ciqual, effacement de la photo."""

from __future__ import annotations

import sqlite3

from conftest import AUTH, REPORT_TOKEN
from test_app_api import app_login

from shc.mealphoto import MealPhotos, estimate
from shc.nutrition import FoodMatcher, Mapping, load_ciqual

JOB = {"Authorization": f"Bearer {REPORT_TOKEN}"}
JPEG = b"\xff\xd8\xff\xe0" + b"0" * 2000

CIQ = load_ciqual()


def test_matcher_prefers_simple_raw_foods():
    m = FoodMatcher(CIQ, {"lardons": Mapping("28720"), "œufs": Mapping("22000")})
    assert m.match("Lardons fumés").code == "28720"          # correspondance soignée, accents et pluriel repliés
    assert m.match("Oeufs").code == "22000"
    assert "cerise" in m.match("Tomates cerises").name.lower()  # pas une forêt noire
    assert m.match("Oignons").name.startswith("Oignon")        # pas une tortilla aux oignons
    assert m.match("Pommes de terre").name.startswith("Pomme de terre")
    assert m.match("Reblochon").name == "Reblochon"
    assert m.match("xyzzy") is None
    m2 = FoodMatcher(CIQ, {"sel": Mapping("0"), "crème fraîche": Mapping("19410"), "beurre": Mapping("16400")})
    assert m2.match("Sel").per100["kcal"] == 0                  # « sans calories » : pas du sel au céleri
    assert m2.match("Crème fraîche épaisse").code == "19410"     # qualificatif seul en plus
    assert m2.match("Beurre fondu").code == "16400"
    assert m2.match("Beurre de cacahuète").code != "16400"       # « cacahuète » n'est pas un qualificatif


def test_estimate_bounds_and_coherence():
    m = FoodMatcher(CIQ)
    a = {"plat": "Tartiflette", "portion": "un quart du plat", "kcal_estime": 650,
         "ingredients": [{"nom": "Reblochon", "grammes": 150}, {"nom": "Pommes de terre", "grammes": 300},
                         {"nom": "truc inconnu", "grammes": 10}, {"nom": "Beurre", "grammes": -5},
                         {"nom": 3, "grammes": 10}]}
    r = estimate(a, m)
    assert [x["nom"] for x in r["ingredients"]] == ["Reblochon", "Pommes de terre", "truc inconnu"]  # bornes
    assert r["unmatched"] == ["truc inconnu"]
    expected = (150 * CIQ[r["ingredients"][0]["ciqual"]].per100["kcal"] + 300 * CIQ[r["ingredients"][1]["ciqual"]].per100["kcal"]) / 100
    assert abs(r["total"]["kcal"] - expected) < 1 and r["coherent"] is True
    r2 = estimate({**a, "kcal_estime": 200}, m)
    assert r2["coherent"] is False  # Ciqual et estimation directe du simple au triple


def test_queue_lease_and_image_erased(tmp_path):
    q = MealPhotos(str(tmp_path / "p.db"))
    pid = q.add(JPEG)
    item = q.next()
    assert item["id"] == pid and q.next() is None  # prêtée : pas deux fois
    q.complete(pid, {"plat": "Crêpe", "ingredients": [{"nom": "Farine", "grammes": 70}]}, FoodMatcher(CIQ), "gemma3:12b")
    got = q.get(pid)
    assert got["status"] == "done" and got["plat"] == "Crêpe" and got["model"] == "gemma3:12b"
    blob = sqlite3.connect(str(tmp_path / "p.db")).execute("SELECT image FROM meal_photos WHERE id=?", (pid,)).fetchone()[0]
    assert blob is None  # la photo n'existe plus dès l'analyse rendue


def test_routes(client):
    assert client.post("/api/v1/meal-photo", content=b"pas une image", headers=AUTH).status_code == 422
    assert client.post("/api/v1/meal-photo", content=JPEG, headers={"Authorization": "Bearer faux"}).status_code == 401
    pid = client.post("/api/v1/meal-photo", content=JPEG, headers=AUTH).json()["id"]
    h = {"Authorization": f"Bearer {app_login(client)['access_token']}"}
    assert client.get("/api/v1/meal-photo", params={"id": pid}, headers=h).json()["status"] == "pending"
    tok = client.app.state.oauth._issue("client-claude", ["health:read"], "f")
    assert client.get("/api/v1/meal-photo", params={"id": pid},
                      headers={"Authorization": f"Bearer {tok.access_token}"}).status_code == 403
    assert client.get("/api/v1/report-job/meal-photos/next").status_code == 401
    item = client.get("/api/v1/report-job/meal-photos/next", headers=JOB).json()
    assert item["id"] == pid and item["image_b64"]
    before = len(client.app.state.audit.entries(500))
    assert client.get("/api/v1/report-job/meal-photos/next", headers=JOB).status_code == 204
    assert len(client.app.state.audit.entries(500)) == before  # attente vide : pas tracée
    analysis = {"plat": "Pizza", "portion": "deux parts", "kcal_estime": 700,
                "ingredients": [{"nom": "Mozzarella", "grammes": 120}, {"nom": "Farine de blé", "grammes": 100}]}
    assert client.post("/api/v1/report-job/meal-photos/result", json={"id": pid, "analysis": analysis, "model": "m"},
                       headers=JOB).json() == {"stored": "done"}
    r = client.get("/api/v1/meal-photo", params={"id": pid}, headers=h).json()
    assert r["status"] == "done" and r["total"]["kcal"] > 300 and all("per100" in x for x in r["ingredients"])
    pid2 = client.post("/api/v1/meal-photo", content=JPEG, headers=AUTH).json()["id"]
    client.get("/api/v1/report-job/meal-photos/next", headers=JOB)
    client.post("/api/v1/report-job/meal-photos/result", json={"id": pid2, "error": "modèle indisponible"}, headers=JOB)
    assert client.get("/api/v1/meal-photo", params={"id": pid2}, headers=h).json()["status"] == "error"
