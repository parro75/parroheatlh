#!/usr/bin/env python3
"""Rapport santé du matin — job de la machine Ollama (Ollama, GPU). Bibliothèque standard seulement.

Toutes les 10 min entre 4 h et 13 h (timer systemd) :
  1. GET  {SANTE_URL}/api/v1/report-job/pending   (jeton dédié REPORT_TOKEN)
  2. si la nuit est arrivée (ou midi passé) et qu'aucun texte n'existe :
     le modèle local RÉDIGE à partir des faits ; il ne calcule ni ne juge rien (signaux déjà calculés
     par le serveur). Échec du modèle ou texte suspect → texte de secours fourni par le serveur.
     POST {SANTE_URL}/api/v1/report-job/narrative
  3. si pas encore notifié : POST au webhook Home Assistant (notification sur le téléphone de Parro),
     puis POST {SANTE_URL}/api/v1/report-job/notified

Configuration : /etc/parro-sante/rapport.env (600) — SANTE_URL, REPORT_TOKEN, OLLAMA_URL, MODEL, HA_WEBHOOK_URL.
Aucune donnée de santé n'est écrite dans les journaux (seulement des longueurs et des états).
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

# Fourni par systemd (LoadCredential) : le service tourne sous un utilisateur dynamique sans droits.
ENV_FILE = os.environ.get("PARRO_ENV_FILE", "/etc/parro-sante/rapport.env")

SYSTEM = (
    "Tu rédiges le bilan santé du matin de Parro, en français, en le tutoyant. "
    "4 à 6 phrases courtes, ton factuel et bienveillant, sans titre, sans liste à puces, sans emoji. "
    "Utilise UNIQUEMENT les faits fournis en JSON : n'invente rien, ne calcule rien de nouveau, "
    "ne pose aucun diagnostic et ne donne aucun conseil médical. "
    "Commence par la nuit (durée, heures de coucher et de lever, score s'il existe), puis la fréquence "
    "cardiaque au repos comparée à sa moyenne, puis l'activité d'hier (pas et, s'il y en a, les séances). "
    "Reprends chaque élément de la liste « flags » sans en changer le sens ; s'il n'y en a aucun, dis que "
    "rien d'inhabituel n'apparaît. Si « ready » est faux, dis que la nuit n'a pas encore été reçue. "
    "Si la HRV n'est pas « fresh », ne la commente pas. "
    "S'il y a un « journal », rappelle brièvement ce qui a été noté, sans en tirer de lien ni de conclusion."
)


def load_env() -> dict[str, str]:
    env = {}
    with open(ENV_FILE) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip("'\"")
    return env


def http(method: str, url: str, body: dict | None = None, headers: dict | None = None, timeout: int = 30) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Content-Type": "application/json", "User-Agent": "ParroHealth-rapport/1.0 (machine Ollama)", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
    return json.loads(raw) if raw else {}


def compact_facts(f: dict) -> dict:
    """Ce que le modèle a besoin de lire (petit contexte : modèle 12B sur 11 Go de VRAM)."""
    keep = ("date", "ready", "night", "resting_hr", "hrv", "steps_yesterday", "workouts_yesterday", "spo2_night",
            "weight", "journal", "flags")
    out = {k: f[k] for k in keep if k in f}
    for w in out.get("workouts_yesterday", []):
        w.pop("source", None)
    return out


def write_with_model(env: dict, facts: dict) -> str | None:
    body = {
        "model": env.get("MODEL", "mistral-nemo:12b"),
        "stream": False,
        "think": False,
        "keep_alive": "2m",  # libère vite la VRAM pour l'agent Assist de Home Assistant
        "options": {"temperature": 0.3, "num_ctx": 4096},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": json.dumps(compact_facts(facts), ensure_ascii=False)},
        ],
    }
    try:
        r = http("POST", env.get("OLLAMA_URL", "http://127.0.0.1:11434") + "/api/chat", body, timeout=240)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"modèle injoignable : {e.__class__.__name__}", file=sys.stderr)
        return None
    text = (r.get("message") or {}).get("content", "").strip()
    # Garde-fous : texte vide, trop long, raisonnement verbalisé, balises → texte de secours.
    if not text or len(text) > 1500 or "<think" in text or text.count("\n- ") > 2:
        print(f"texte du modèle rejeté ({len(text)} car.)", file=sys.stderr)
        return None
    return text


def watch(env: dict) -> int:
    """--surveillance : relaie l'alerte « plus de synchro » (seuils décidés par le serveur)."""
    base = env["SANTE_URL"].rstrip("/")
    auth = {"Authorization": f"Bearer {env['REPORT_TOKEN']}"}
    s = http("GET", f"{base}/api/v1/report-job/sync-status", headers=auth)
    if s["action"] == "none":
        print(f"synchro : âge {s.get('age_hours')} h, rien à signaler")
    else:
        http("POST", env["HA_WEBHOOK_URL"], {"title": s["title"], "message": s["message"]})
        http("POST", f"{base}/api/v1/report-job/sync-status/ack",
             {"action": s["action"], "last_phone_sync": s["last_phone_sync"]}, auth)
        print(f"synchro : {s['action']} relayée (âge {s['age_hours']} h)")
    r = http("GET", f"{base}/api/v1/report-job/export-reminder", headers=auth)
    if r["action"] == "export_reminder":
        http("POST", env["HA_WEBHOOK_URL"], {"title": r["title"], "message": r["message"]})
        http("POST", f"{base}/api/v1/report-job/export-reminder/ack", {}, auth)
        print(f"rappel d'export relayé (dernier import il y a {r.get('export_age_days')} j)")
    return 0


def main() -> int:
    env = load_env()
    if "--surveillance" in sys.argv:
        return watch(env)
    if "--apercu" in sys.argv:
        # Rédige le texte du jour SANS l'enregistrer ni notifier (choix du modèle, réglage du prompt).
        if len(sys.argv) > 2:
            env["MODEL"] = sys.argv[sys.argv.index("--apercu") + 1]
        base = env["SANTE_URL"].rstrip("/")
        p = http("GET", f"{base}/api/v1/report-job/pending", headers={"Authorization": f"Bearer {env['REPORT_TOKEN']}"})
        print(write_with_model(env, p["facts"]) or "(rejeté → texte de secours)\n" + p["fallback"])
        return 0
    base = env["SANTE_URL"].rstrip("/")
    auth = {"Authorization": f"Bearer {env['REPORT_TOKEN']}"}
    p = http("GET", f"{base}/api/v1/report-job/pending", headers=auth)
    day = p["date"]
    if not p["ready"]:
        print(f"{day} : nuit pas encore reçue, rien à faire")
        return 0
    text = p.get("narrative")
    if not p["has_narrative"]:
        text = write_with_model(env, p["facts"])
        model = env.get("MODEL", "mistral-nemo:12b")
        if text is None:
            text, model = p["fallback"], "secours (sans IA)"
        http("POST", f"{base}/api/v1/report-job/narrative", {"date": day, "narrative": text, "model": model}, auth)
        print(f"{day} : texte enregistré ({model}, {len(text)} car.)")
    if not p["notified"] and env.get("HA_WEBHOOK_URL"):
        flags = p["facts"].get("flags", [])
        title = "Rapport santé" + (" — à noter" if any(x["level"] == "attention" for x in flags) else "")
        http("POST", env["HA_WEBHOOK_URL"], {"title": title, "message": text})
        http("POST", f"{base}/api/v1/report-job/notified", {"date": day}, auth)
        print(f"{day} : notification envoyée")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} sur {e.url.split('?')[0]}", file=sys.stderr)
        sys.exit(1)
