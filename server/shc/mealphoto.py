"""Photos de repas (hors collection de recettes) analysées par l'IA locale : file d'attente sur le volume chiffré.

Le téléphone envoie la photo (écriture signée) ; le job de la machine IA la récupère par la route interne,
renvoie le plat et la recette type de la portion (ingrédients et grammes) ; le serveur rapproche chaque
ingrédient de la table Ciqual. La photo est EFFACÉE dès l'analyse rendue (ou en échec) ; le résultat, sans
image, est purgé au bout de 24 h. L'utilisateur corrige puis valide dans l'app : seul le repas validé reste.
"""

from __future__ import annotations

import base64
import json
import sqlite3
import threading
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from .nutrition import NUTRIENTS, FoodMatcher

MAX_IMAGE = 3 * 1024 * 1024
KEEP = timedelta(hours=24)
LEASE = timedelta(minutes=10)  # analyse sans réponse au-delà : la photo repart dans la file
MAX_TRIES = 2
MAX_INGREDIENTS, MAX_GRAMS, MAX_NAME = 25, 3000, 60


class PhotoError(ValueError):
    pass


def _now() -> datetime:
    return datetime.now(timezone.utc)


class MealPhotos:
    SCHEMA = """CREATE TABLE IF NOT EXISTS meal_photos (
        id TEXT PRIMARY KEY, created_at TEXT NOT NULL, status TEXT NOT NULL, image BLOB,
        leased_at TEXT, tries INTEGER NOT NULL DEFAULT 0, result TEXT, error TEXT)"""

    def __init__(self, path: str):
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.execute(self.SCHEMA)
        self._lock = threading.Lock()

    def add(self, image: bytes) -> str:
        if not image.startswith(b"\xff\xd8") or len(image) > MAX_IMAGE:
            raise PhotoError("photo JPEG de 3 Mo au plus attendue")
        pid = uuid.uuid4().hex
        with self._lock:
            self._purge()
            if self._conn.execute("SELECT COUNT(*) FROM meal_photos WHERE status IN ('pending','processing')").fetchone()[0] >= 10:
                raise PhotoError("trop de photos en attente d'analyse")
            self._conn.execute("INSERT INTO meal_photos (id, created_at, status, image) VALUES (?, ?, 'pending', ?)",
                               (pid, _now().isoformat(), image))
        return pid

    def next(self) -> dict[str, Any] | None:
        """Pour le job : la plus ancienne photo en attente (ou dont l'analyse a expiré), prêtée 10 min."""
        now = _now()
        with self._lock:
            self._purge()
            row = self._conn.execute(
                """SELECT id, image, tries FROM meal_photos WHERE status = 'pending'
                   OR (status = 'processing' AND leased_at < ?) ORDER BY created_at LIMIT 1""",
                ((now - LEASE).isoformat(),)).fetchone()
            if row is None:
                return None
            pid, image, tries = row
            if tries >= MAX_TRIES:
                self._conn.execute("UPDATE meal_photos SET status='error', image=NULL, error=? WHERE id=?",
                                   ("analyse sans réponse", pid))
                return None
            self._conn.execute("UPDATE meal_photos SET status='processing', leased_at=?, tries=tries+1 WHERE id=?",
                               (now.isoformat(), pid))
        return {"id": pid, "image_b64": base64.b64encode(image).decode()}

    def complete(self, pid: str, analysis: dict[str, Any], matcher: FoodMatcher, model: str) -> None:
        result = estimate(analysis, matcher)
        result["model"] = model[:60]
        with self._lock:
            n = self._conn.execute("UPDATE meal_photos SET status='done', image=NULL, result=? WHERE id=? AND status='processing'",
                                   (json.dumps(result, ensure_ascii=False), pid)).rowcount
        if not n:
            raise PhotoError("photo inconnue ou pas en cours d'analyse")

    def fail(self, pid: str, error: str) -> None:
        with self._lock:
            self._conn.execute("UPDATE meal_photos SET status='error', image=NULL, error=? WHERE id=?", (error[:200], pid))

    def get(self, pid: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute("SELECT status, result, error, created_at FROM meal_photos WHERE id=?", (pid,)).fetchone()
        if row is None:
            return None
        status, result, error, created = row
        out: dict[str, Any] = {"id": pid, "status": status, "created_at": created}
        if result:
            out.update(json.loads(result))
        if error:
            out["error"] = error
        return out

    def forget(self, pid: str) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM meal_photos WHERE id=?", (pid,))

    def _purge(self) -> None:
        self._conn.execute("DELETE FROM meal_photos WHERE created_at < ?", ((_now() - KEEP).isoformat(),))


def _num(v: Any, top: float) -> float | None:
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not 0 <= v <= top:
        return None
    return float(v)


def estimate(analysis: dict[str, Any], matcher: FoodMatcher) -> dict[str, Any]:
    """Réponse du modèle (validée, bornée) → ingrédients rapprochés de Ciqual, valeurs pour 100 g et total."""
    if not isinstance(analysis, dict):
        raise PhotoError("analyse : objet attendu")
    raw = analysis.get("ingredients")
    if not isinstance(raw, list) or not raw:
        raise PhotoError("analyse : liste d'ingrédients attendue")
    total = dict.fromkeys(NUTRIENTS, 0.0)
    out = []
    for ing in raw[:MAX_INGREDIENTS]:
        if not isinstance(ing, dict) or not isinstance(ing.get("nom"), str):
            continue
        name, grams = ing["nom"].strip()[:MAX_NAME], _num(ing.get("grammes"), MAX_GRAMS)
        if not name or grams is None:
            continue
        food = matcher.match(name)
        line: dict[str, Any] = {"nom": name, "grammes": round(grams)}
        if food is not None:
            per100 = {k: food.per100[k] or 0.0 for k in NUTRIENTS}
            line.update(ciqual=food.code, aliment=food.name, per100=per100)
            for k in NUTRIENTS:
                total[k] += grams * per100[k] / 100
        out.append(line)
    if not out:
        raise PhotoError("analyse : aucun ingrédient exploitable")
    text = lambda k, n: analysis[k].strip()[:n] if isinstance(analysis.get(k), str) else ""  # noqa: E731
    model_kcal = _num(analysis.get("kcal_estime"), 10000)
    res = {"plat": text("plat", 120) or "Repas", "portion": text("portion", 160), "ingredients": out,
           "total": {k: round(v, 1) for k, v in total.items()},
           "model_kcal": round(model_kcal) if model_kcal is not None else None,
           "unmatched": [x["nom"] for x in out if "ciqual" not in x]}
    # Signal d'incohérence : calcul Ciqual et estimation directe du modèle du simple au double ou plus.
    k = res["total"]["kcal"]
    res["coherent"] = not (model_kcal and k and (k / model_kcal > 2 or model_kcal / k > 2))
    return res
