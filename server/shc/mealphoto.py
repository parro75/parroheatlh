"""Photos de repas (hors collection de recettes) analysées par l'IA locale : file d'attente sur le volume chiffré.

Le téléphone envoie la photo (écriture signée) ; le job de la machine IA la récupère par la route interne,
renvoie le plat et la recette type de la portion (ingrédients et grammes) ; le serveur rapproche chaque
ingrédient de la table Ciqual. La photo est EFFACÉE dès l'analyse rendue (ou en échec) ; le résultat, sans
image, est purgé au bout de 24 h. L'utilisateur corrige puis valide dans l'app : seul le repas validé reste.
"""

from __future__ import annotations

import base64
import json
import re
import sqlite3
import unicodedata
import threading
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from .nutrition import NUTRIENTS, FoodMatcher

MAX_IMAGE = 3 * 1024 * 1024
MAX_PHOTOS = 4  # plusieurs angles ou plusieurs assiettes d'un même repas
MAX_UPLOAD = 8 * 1024 * 1024  # envoi JSON : photos en base64 + description
MAX_DESCRIPTION = 300
MIN_TEXT_ONLY = 3  # repas décrit sans photo (saisie libre sans calories) : une description lisible au moins
KEEP = timedelta(hours=24)
LEASE = timedelta(minutes=10)  # analyse sans réponse au-delà : la photo repart dans la file
MAX_TRIES = 2
MAX_INGREDIENTS, MAX_GRAMS, MAX_NAME = 25, 3000, 60


_ERASE = "image=NULL, more_images=NULL, description=NULL"  # tout ce que l'utilisateur a envoyé disparaît avec l'analyse


class PhotoError(ValueError):
    pass


# ---- Barrières contre l'injection de consignes (description saisie, et réponse du modèle) ----
# 1. Liste blanche de caractères : lettres, chiffres, espace et une ponctuation de cuisine. Pas de < > { } [ ] ` " # \ ni
#    de caractère invisible (largeur nulle, inversion bidi) : impossible de fermer une balise ou d'imiter un format.
# 2. Formes compatibles repliées (NFKC) : les lettres « pleine chasse » ou stylisées ne contournent pas le filtre.
# 3. Refus explicite d'un texte qui parle au modèle au lieu de décrire un repas, ou qui contient une adresse web.
# Le job ajoute ses propres barrières (consignes en rôle système, balises à jeton aléatoire, sortie au schéma JSON),
# et le serveur repasse la RÉPONSE du modèle au même filtre avant de l'afficher ou de l'enregistrer.
_PUNCT = set(" .,;:!?'’()-/+%&°½¼¾×")
_URL = re.compile(r"https?:|www\.|\b[a-z0-9-]+\.(?:com|fr|net|org|io|ly|me|co|info|ai|app|xyz)\b|@", re.I)
_INSTRUCTION = re.compile(
    r"\b(?:(?:ignor|oubli)\w*\s+(?:\w+\s+){0,2}?(?:tout|pr[ée]c[ée]d\w*|ci dessus|ci-dessus|r[eè]gles?)|instructions?|consignes?|prompts?|syst[eè]me|system|assistant|r[ée]pond\w*|r[ée]ponse|"
    r"json|sch[ée]ma|mod[eè]les?|ia|llm|gpt|gemma|claude|d[ée]veloppeur|developer|admin\w*|jailbreak|"
    r"pr[ée]tend\w*|fais semblant|tu es|you are|act as|r[oô]le|balises?|tags?|token|secret|mot de passe|password)\b",
    re.I)


def clean_text(text: Any, limit: int) -> str:
    """Texte d'une ligne, réduit à la liste blanche, espaces resserrés, borné."""
    if not isinstance(text, str):
        return ""
    text = unicodedata.normalize("NFKC", text)
    kept = "".join(ch if (unicodedata.category(ch)[0] in "LN" or ch in _PUNCT) else " " for ch in text)
    return " ".join(kept.split())[:limit].strip()


def looks_like_instruction(text: str) -> bool:
    return bool(_URL.search(text) or _INSTRUCTION.search(text))


def clean_description(text: Any) -> str:
    """Description saisie : nettoyée ; refusée si elle s'adresse au modèle (le message d'erreur le dit à l'app)."""
    desc = clean_text(text, MAX_DESCRIPTION)
    if desc and looks_like_instruction(desc):
        raise PhotoError("description refusée : décris seulement le repas (plat, ingrédients, quantité), "
                         "sans adresse web ni consigne adressée à l'IA")
    return desc


def parse_upload(body: bytes, content_type: str) -> tuple[list[bytes], str]:
    """Envoi de l'app : JPEG brut (une photo, app ≤ 0.19.0) ou JSON {description, images: [base64…]}."""
    if not content_type.startswith("application/json"):
        return [body], ""
    try:
        data = json.loads(body)
        raw = data["images"]
        if not isinstance(raw, list) or not all(isinstance(x, str) for x in raw):
            raise ValueError
        images = [base64.b64decode(x, validate=True) for x in raw[:MAX_PHOTOS + 1]]
    except (ValueError, KeyError, TypeError):
        raise PhotoError("envoi illisible : {description, images: [JPEG en base64]} attendu") from None
    return images, clean_description(data.get("description"))


def _now() -> datetime:
    return datetime.now(timezone.utc)


class MealPhotos:
    SCHEMA = """CREATE TABLE IF NOT EXISTS meal_photos (
        id TEXT PRIMARY KEY, created_at TEXT NOT NULL, status TEXT NOT NULL, image BLOB,
        leased_at TEXT, tries INTEGER NOT NULL DEFAULT 0, result TEXT, error TEXT)"""
    # Ajoutées en 0.19.0 : photos supplémentaires (JSON de base64) et description saisie, effacées avec la photo.
    COLUMNS = {"more_images": "TEXT", "description": "TEXT"}

    def __init__(self, path: str):
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.execute(self.SCHEMA)
        have = {r[1] for r in self._conn.execute("PRAGMA table_info(meal_photos)")}
        for col, typ in self.COLUMNS.items():
            if col not in have:
                self._conn.execute(f"ALTER TABLE meal_photos ADD COLUMN {col} {typ}")
        self._lock = threading.Lock()

    def add(self, image: bytes | list[bytes], description: str = "") -> str:
        """1 à 4 photos et une description facultative, ou une description seule (saisie libre sans calories)."""
        images = [image] if isinstance(image, bytes) else list(image)
        if len(images) > MAX_PHOTOS:
            raise PhotoError(f"de 1 à {MAX_PHOTOS} photos attendues")
        for img in images:
            if not isinstance(img, bytes) or not img.startswith(b"\xff\xd8") or len(img) > MAX_IMAGE:
                raise PhotoError("photo JPEG de 3 Mo au plus attendue")
        desc = clean_description(description)
        if not images and len(desc) < MIN_TEXT_ONLY:
            raise PhotoError("décris le repas (plat, ingrédients, quantité) ou ajoute une photo")
        more = json.dumps([base64.b64encode(x).decode() for x in images[1:]]) if len(images) > 1 else None
        pid = uuid.uuid4().hex
        with self._lock:
            self._purge()
            if self._conn.execute("SELECT COUNT(*) FROM meal_photos WHERE status IN ('pending','processing')").fetchone()[0] >= 10:
                raise PhotoError("trop de photos en attente d'analyse")
            self._conn.execute(
                "INSERT INTO meal_photos (id, created_at, status, image, more_images, description) VALUES (?, ?, 'pending', ?, ?, ?)",
                (pid, _now().isoformat(), images[0] if images else None, more, desc or None))
        return pid

    def next(self) -> dict[str, Any] | None:
        """Pour le job : la plus ancienne photo en attente (ou dont l'analyse a expiré), prêtée 10 min."""
        now = _now()
        with self._lock:
            self._purge()
            row = self._conn.execute(
                """SELECT id, image, more_images, description, tries FROM meal_photos WHERE status = 'pending'
                   OR (status = 'processing' AND leased_at < ?) ORDER BY created_at LIMIT 1""",
                ((now - LEASE).isoformat(),)).fetchone()
            if row is None:
                return None
            pid, image, more, desc, tries = row
            if tries >= MAX_TRIES:
                self._conn.execute(f"UPDATE meal_photos SET status='error', {_ERASE}, error=? WHERE id=?",
                                   ("analyse sans réponse", pid))
                return None
            self._conn.execute("UPDATE meal_photos SET status='processing', leased_at=?, tries=tries+1 WHERE id=?",
                               (now.isoformat(), pid))
        images = ([base64.b64encode(image).decode()] if image else []) + (json.loads(more) if more else [])
        # image_b64 : pour un job resté en version précédente (une seule photo) ; absent pour un repas sans photo.
        item = {"id": pid, "images_b64": images, "description": desc or ""}
        if images:
            item["image_b64"] = images[0]
        return item

    def complete(self, pid: str, analysis: dict[str, Any], matcher: FoodMatcher, model: str) -> None:
        result = estimate(analysis, matcher)
        result["model"] = model[:60]
        with self._lock:
            row = self._conn.execute("SELECT image IS NULL FROM meal_photos WHERE id=?", (pid,)).fetchone()
            result["source"] = "texte" if row and row[0] else "photo"  # l'app enregistre « saisie » ou « photo »
            n = self._conn.execute(f"UPDATE meal_photos SET status='done', {_ERASE}, result=? WHERE id=? AND status='processing'",
                                   (json.dumps(result, ensure_ascii=False), pid)).rowcount
        if not n:
            raise PhotoError("photo inconnue ou pas en cours d'analyse")

    def fail(self, pid: str, error: str) -> None:
        with self._lock:
            self._conn.execute(f"UPDATE meal_photos SET status='error', {_ERASE}, error=? WHERE id=?", (error[:200], pid))

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
        if not isinstance(ing, dict):
            continue
        name, grams = clean_text(ing.get("nom"), MAX_NAME), _num(ing.get("grammes"), MAX_GRAMS)
        if not name or grams is None or looks_like_instruction(name):
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
    def text(k: str, n: int) -> str:  # sortie du modèle : même filtre ; un texte suspect est simplement vidé
        t = clean_text(analysis.get(k), n)
        return "" if looks_like_instruction(t) else t

    model_kcal = _num(analysis.get("kcal_estime"), 10000)
    res = {"plat": text("plat", 120) or "Repas", "portion": text("portion", 160), "ingredients": out,
           "total": {k: round(v, 1) for k, v in total.items()},
           "model_kcal": round(model_kcal) if model_kcal is not None else None,
           "unmatched": [x["nom"] for x in out if "ciqual" not in x]}
    # Signal d'incohérence : calcul Ciqual et estimation directe du modèle du simple au double ou plus.
    k = res["total"]["kcal"]
    res["coherent"] = not (model_kcal and k and (k / model_kcal > 2 or model_kcal / k > 2))
    return res
