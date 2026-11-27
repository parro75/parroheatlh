"""Photos de repas : file d'attente, analyse par le job, rapprochement Ciqual, effacement de la photo."""

from __future__ import annotations

import base64
import json
import sqlite3

from conftest import AUTH, REPORT_TOKEN
from test_app_api import app_login

from shc.mealphoto import MealPhotos, PhotoError, clean_description, estimate, parse_upload
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


def test_several_photos_and_description_erased(tmp_path):
    q = MealPhotos(str(tmp_path / "p.db"))
    other = b"\xff\xd8\xff\xe1" + b"1" * 500
    pid = q.add([JPEG, other], "  Poké bowl\nsaumon, riz  \x07vinaigré ")
    item = q.next()
    assert [base64.b64decode(x) for x in item["images_b64"]] == [JPEG, other]
    assert item["image_b64"] == item["images_b64"][0]  # job resté en version précédente
    assert item["description"] == "Poké bowl saumon, riz vinaigré"  # une ligne, sans caractère de contrôle
    q.complete(pid, {"plat": "Poké bowl", "ingredients": [{"nom": "Riz", "grammes": 150}]}, FoodMatcher(CIQ), "m")
    row = sqlite3.connect(str(tmp_path / "p.db")).execute(
        "SELECT image, more_images, description FROM meal_photos WHERE id=?", (pid,)).fetchone()
    assert row == (None, None, None)  # photos ET description effacées avec l'analyse
    for bad in ([], [JPEG] * 5, [JPEG, b"pas un jpeg"]):
        try:
            q.add(bad)
            raise AssertionError(bad)
        except PhotoError:
            pass
    assert len(clean_description("x" * 500)) == 300 and clean_description(42) == ""


def test_parse_upload():
    assert parse_upload(JPEG, "image/jpeg") == ([JPEG], "")
    body = json.dumps({"description": "salade", "images": [base64.b64encode(JPEG).decode()]}).encode()
    assert parse_upload(body, "application/json; charset=utf-8") == ([JPEG], "salade")
    for bad in (b"{", b'{"images": "x"}', b'{"images": ["%%%"]}', b"{}"):
        try:
            parse_upload(bad, "application/json")
            raise AssertionError(bad)
        except PhotoError:
            pass


def test_route_json_upload(client):
    b64 = base64.b64encode(JPEG).decode()
    r = client.post("/api/v1/meal-photo", json={"description": "tacos al pastor", "images": [b64, b64]}, headers=AUTH)
    pid = r.json()["id"]
    item = client.get("/api/v1/report-job/meal-photos/next", headers=JOB).json()
    assert item["id"] == pid and len(item["images_b64"]) == 2 and item["description"] == "tacos al pastor"
    detail = [e for e in client.app.state.audit.entries(50) if "repas reçu" in str(e)]
    assert detail and "2 photos" in str(detail[0]) and "tacos" not in str(detail[0])  # la description n'est pas tracée
    assert client.post("/api/v1/meal-photo", json={"images": [b64] * 5}, headers=AUTH).status_code == 422
