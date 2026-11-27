"""Calories et macronutriments des recettes Cooklang (collection de recettes facultative, montée en lecture
seule), d'après la table Ciqual 2025 de l'ANSES (licence Etalab 2.0, fichier data/ciqual2025.csv).

Les recettes ne sont jamais modifiées : la correspondance ingrédient → aliment Ciqual vit à côté, dans
config/nutrition.conf de la collection. Un ingrédient sans correspondance (ou dans une unité inconnue)
rend le total « partiel » et il est listé : l'estimation ne cache jamais ce qu'elle ignore.
"""

from __future__ import annotations

import csv
import re
import threading
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

CIQUAL_CSV = Path(__file__).parent / "data" / "ciqual2025.csv"
NUTRIENTS = ("kcal", "proteines", "glucides", "lipides", "fibres", "sucres", "sel")
SOURCE = "Table de composition nutritionnelle Ciqual 2025, ANSES (licence Etalab 2.0)"

# Unités de volume converties en ml, puis en grammes par la masse volumique de l'ingrédient (1 par défaut).
VOLUME_ML = {
    "ml": 1, "cl": 10, "dl": 100, "l": 1000, "litre": 1000, "tasse": 250, "verre": 200,
    "cuil. à soupe": 15, "c. à soupe": 15, "cuillère à soupe": 15, "cas": 15, "tbsp": 15,
    "cuil. à thé": 5, "c. à thé": 5, "cuillère à thé": 5, "cuillère à café": 5, "cac": 5, "tsp": 5,
}
MASS_G = {"g": 1, "gr": 1, "kg": 1000, "mg": 0.001, "pincée": 0.5, "goutte": 0.05, "tour": 0.1}
SERVING_UNITS = {"servings", "serving", "portion", "portions", "part", "parts"}
PIECE = "pièce"
MAX_DEPTH = 3


def fold(s: str) -> str:
    """Minuscules sans accents ni espaces superflus (recherche et comparaison de noms)."""
    s = s.lower().replace("œ", "oe").replace("æ", "ae")  # ligatures : NFKD ne les décompose pas
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    return " ".join(s.split())


def _unit(u: str) -> str:
    u = " ".join(u.strip().lower().split())
    if u in VOLUME_ML or u in MASS_G or u in SERVING_UNITS:
        return u
    if u.endswith("s") and (u[:-1] in VOLUME_ML or u[:-1] in MASS_G or len(u) > 3):
        return u[:-1]  # gousses → gousse, tranches → tranche, tours → tour
    return u


def parse_quantity(q: str) -> float | None:
    """« 3 », « 0.5 », « 1/2 », « 1 1/2 », « 40-50 » (moyenne) ; « quelques » ou vide → None."""
    q = q.strip().replace(",", ".")
    if not q:
        return None
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)", q)
    if m:
        return (float(m.group(1)) + float(m.group(2))) / 2
    m = re.fullmatch(r"(?:(\d+)\s+)?(\d+)\s*/\s*(\d+)", q)
    if m:
        return float(m.group(1) or 0) + float(m.group(2)) / float(m.group(3))
    try:
        return float(q)
    except ValueError:
        return None


# ---- table Ciqual et correspondances ----------------------------------------------------------------

@dataclass(frozen=True)
class Food:
    code: str
    name: str
    per100: dict[str, float | None]


def load_ciqual(path: Path = CIQUAL_CSV) -> dict[str, Food]:
    out = {}
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            out[r["code"]] = Food(r["code"], r["nom"], {k: float(r[k]) if r[k] else None for k in NUTRIENTS})
    return out


@dataclass
class Mapping:
    code: str
    grams: dict[str, float] = field(default_factory=dict)  # unité → grammes (« ml » = masse volumique)


def load_mapping(path: Path) -> dict[str, Mapping]:
    """config/nutrition.conf : « nom = code ; unité grammes ; … » (voir l'en-tête du fichier)."""
    out: dict[str, Mapping] = {}
    if not path.is_file():
        return out
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if "=" not in line:
            continue
        name, rest = (x.strip() for x in line.split("=", 1))
        parts = [p.strip() for p in rest.split(";")]
        m = Mapping(parts[0])
        for p in parts[1:]:
            unit, _, val = p.rpartition(" ")
            try:
                m.grams[_unit(unit)] = float(val.replace(",", "."))
            except ValueError:
                continue
        out[fold(name)] = m
    return out


# ---- lecture des recettes Cooklang ------------------------------------------------------------------

# @nom{quantité%unité}, @nom en un mot ; modificateurs facultatifs (@? optionnel, @& référence, @- caché…).
_INGREDIENT = re.compile(r"@[?&+\-]*(?:([^@#~{}\n]+?)\{([^}]*)\}|([^\s@#~{}.,;:!?()\[\]]+))")
_COMMENT = re.compile(r"\[-.*?-\]", re.S)


@dataclass
class Ingredient:
    name: str
    quantity: float | None
    unit: str


@dataclass
class Recipe:
    id: str
    title: str
    servings: float | None
    course: str
    tags: list[str]
    ingredients: list[Ingredient]


def _frontmatter(text: str) -> tuple[dict[str, str], str]:
    if not text.startswith("---"):
        return {}, text
    end = text.find("\n---", 3)
    if end < 0:
        return {}, text
    meta = {}
    for line in text[3:end].splitlines():
        k, sep, v = line.partition(":")
        if sep:
            meta[k.strip().lower()] = v.strip().strip('"')
    return meta, text[end + 4:]


def parse_recipe(text: str, rid: str) -> Recipe:
    meta, body = _frontmatter(text)
    body = _COMMENT.sub("", body)
    lines = [ln.split("--", 1)[0] for ln in body.splitlines() if not ln.lstrip().startswith(">")]
    ings = []
    for m in _INGREDIENT.finditer("\n".join(lines)):
        if m.group(1) is not None:
            qty, _, unit = m.group(2).partition("%")
            ings.append(Ingredient(m.group(1).strip(), parse_quantity(qty), _unit(unit) if unit else ""))
        else:
            ings.append(Ingredient(m.group(3).strip(), None, ""))
    s = re.match(r"\s*(\d+(?:[.,]\d+)?)", meta.get("servings", ""))
    tags = [t.strip() for t in meta.get("tags", "").strip("[]").split(",") if t.strip()]
    return Recipe(rid, meta.get("title") or rid.rsplit("/", 1)[-1], float(s.group(1).replace(",", ".")) if s else None,
                  meta.get("course", ""), tags, ings)


# ---- calcul --------------------------------------------------------------------------------------------

class RecipeBook:
    """Collection de recettes en lecture seule ; relue quand un fichier change (dates de modification)."""

    def __init__(self, root: str | None, ciqual: dict[str, Food] | None = None):
        self.root = Path(root) if root else None
        self.ciqual = ciqual if ciqual is not None else load_ciqual()
        self._lock = threading.Lock()
        self._stamp: tuple | None = None
        self._recipes: dict[str, Recipe] = {}
        self._mapping: dict[str, Mapping] = {}
        self._computed: dict[str, dict[str, Any]] = {}

    @property
    def available(self) -> bool:
        return self.root is not None and self.root.is_dir()

    def _refresh(self) -> None:
        if not self.available:
            self._recipes, self._computed = {}, {}
            return
        files = sorted(self.root.rglob("*.cook"))
        conf = self.root / "config" / "nutrition.conf"
        stamp = tuple((str(p), p.stat().st_mtime_ns) for p in [*files, conf] if p.exists())
        if stamp == self._stamp:
            return
        recipes = {}
        for p in files:
            rid = p.relative_to(self.root).with_suffix("").as_posix()
            recipes[rid] = parse_recipe(p.read_text(encoding="utf-8"), rid)
        self._recipes, self._mapping, self._computed, self._stamp = recipes, load_mapping(conf), {}, stamp

    def _grams(self, ing: Ingredient, m: Mapping) -> float | None:
        q = ing.quantity
        if q is None:
            return None
        u = ing.unit or PIECE
        if u in m.grams:  # réglage propre à l'ingrédient (pièce, gousse, boîte, « g » partie comestible…)
            return q * m.grams[u]
        if u in MASS_G:
            return q * MASS_G[u]
        if u in VOLUME_ML:
            return q * VOLUME_ML[u] * m.grams.get("ml", 1.0)
        return None

    def _compute(self, rid: str, depth: int = 0) -> dict[str, Any]:
        if rid in self._computed:
            return self._computed[rid]
        r = self._recipes[rid]
        total = dict.fromkeys(NUTRIENTS, 0.0)
        lines, missing, unquantified = [], [], []
        quantified = {fold(i.name) for i in r.ingredients if i.quantity is not None}
        for ing in r.ingredients:
            key = fold(ing.name)
            line: dict[str, Any] = {"name": ing.name, "quantity": ing.quantity, "unit": ing.unit}
            if ing.name.startswith("./") or ing.name.startswith("../"):  # renvoi vers une autre recette
                ref = (Path(rid).parent / ing.name).as_posix() if ing.name.startswith("../") else ing.name[2:]
                ref = next((k for k in self._recipes if fold(k) == fold(ref)), None)
                sub = self._compute(ref, depth + 1) if ref and depth < MAX_DEPTH else None
                if sub is None or ing.quantity is None or ing.unit not in SERVING_UNITS or not sub["servings"]:
                    missing.append(ing.name)
                    line["status"] = "recette introuvable ou quantité inconnue"
                else:
                    f = ing.quantity / sub["servings"]
                    for k in NUTRIENTS:
                        total[k] += (sub["total"][k] or 0) * f
                    if not sub["complete"]:
                        missing.extend(f"{ing.name} : {x}" for x in sub["missing"])
                    line.update(status="recette", kcal=round(sub["total"]["kcal"] * f))
                lines.append(line)
                continue
            if ing.quantity is None:
                if key not in quantified and ing.name not in unquantified:
                    unquantified.append(ing.name)
                continue
            m = self._mapping.get(key)
            if m is None:
                missing.append(ing.name)
                lines.append({**line, "status": "sans correspondance"})
                continue
            if m.code == "0":
                lines.append({**line, "status": "sans calories", "kcal": 0})
                continue
            food = self.ciqual.get(m.code)
            grams = self._grams(ing, m)
            if food is None or grams is None or food.per100["kcal"] is None:
                missing.append(ing.name)
                lines.append({**line, "status": "code inconnu" if food is None else
                              "unité inconnue" if grams is None else "valeur absente de Ciqual"})
                continue
            for k in NUTRIENTS:
                total[k] += grams * (food.per100[k] or 0) / 100
            lines.append({**line, "status": "ok", "grams": round(grams, 1), "food": food.name, "ciqual": food.code,
                          "kcal": round(grams * food.per100["kcal"] / 100)})
        servings = r.servings
        out = {
            "id": rid, "title": r.title, "servings": servings, "course": r.course, "tags": r.tags,
            "total": {k: round(v, 1) for k, v in total.items()},
            "per_serving": {k: round(v / servings, 1) for k, v in total.items()} if servings else None,
            "complete": not missing, "missing": missing, "unquantified": unquantified, "ingredients": lines,
        }
        self._computed[rid] = out
        return out

    def list(self, q: str = "") -> list[dict[str, Any]]:
        with self._lock:
            self._refresh()
            needle = fold(q)
            out = []
            for rid in sorted(self._recipes, key=lambda k: fold(self._recipes[k].title)):
                r = self._recipes[rid]
                if needle and needle not in fold(r.title) and not any(needle in fold(t) for t in r.tags):
                    continue
                c = self._compute(rid)
                ps = c["per_serving"]
                out.append({"id": rid, "title": r.title, "servings": r.servings, "course": r.course,
                            "per_serving": ps, "complete": c["complete"]})
            return out

    def get(self, rid: str) -> dict[str, Any] | None:
        with self._lock:
            self._refresh()
            return self._compute(rid) if rid in self._recipes else None

    def titles(self) -> list[tuple[str, str]]:
        """(id, titre) de toutes les recettes : sert à rapprocher une photo d'une recette (incrément suivant)."""
        with self._lock:
            self._refresh()
            return [(k, r.title) for k, r in self._recipes.items()]


# ---- rapprochement d'un nom d'aliment libre (photo analysée) avec la table Ciqual ------------------------

_STOP = {"de", "du", "des", "la", "le", "les", "a", "au", "aux", "et", "en", "d", "l", "ou", "sans", "avec", "pour",
         "type", "similaire", "frais", "fraiche", "fraiches", "maison", "fondu", "fondue", "rape", "rapee"}
# Plats composés : écartés de la recherche approximative (un ingrédient n'est pas un plat préparé).
_PREPARED = {"preemballe", "preemballee", "cuisine", "cuisinee", "salade", "flan", "tortilla", "choucroute",
             "tartiflette", "sandwich", "pizza", "gateau", "patisserie", "plat", "garni", "garnie", "omelette",
             "quiche", "tarte", "haricot", "farci", "farcie", "soupe", "veloute", "gratin", "biscuit", "bonbon"}


def _sing(t: str) -> str:
    return t[:-1] if len(t) > 3 and t[-1] in "sx" else t


def _toks(s: str) -> set[str]:
    return {_sing(t) for t in re.findall(r"[a-z]+", fold(s)) if t not in _STOP and len(t) > 1}


class FoodMatcher:
    """Nom libre (« lardons fumés », « beurre fondu ») → aliment Ciqual. D'abord la correspondance soignée des
    recettes (config/nutrition.conf), puis une recherche approximative qui préfère les aliments simples et crus."""

    def __init__(self, ciqual: dict[str, Food], mapping: dict[str, Mapping] | None = None):
        self.ciqual = ciqual
        self.curated = {}
        for name, m in (mapping or {}).items():
            if m.code in ciqual:
                self.curated[" ".join(sorted(_toks(name)))] = m.code
        self._index = [(f, _toks(f.name.split(",")[0]), _toks(f.name)) for f in ciqual.values() if f.per100["kcal"] is not None]

    def match(self, name: str) -> Food | None:
        q = _toks(name)
        if not q:
            return None
        key = " ".join(sorted(q))
        if key in self.curated:
            return self.ciqual[self.curated[key]]
        best, best_s = None, 0.0
        for f, head, allt in self._index:
            common = q & allt
            if not common:
                continue
            s = (4 * len(q & head) + len(common) - 0.3 * len(head - q) - (3 if (allt & _PREPARED) - q else 0)
                 + (1 if "cru" in allt or "moyen" in allt else 0))
            if s > best_s:
                best, best_s = f, s
        return best if best_s >= 4 else None  # au moins le nom principal de l'aliment


def book_matcher(book: "RecipeBook") -> FoodMatcher:
    with book._lock:
        book._refresh()
        return FoodMatcher(book.ciqual, book._mapping)
