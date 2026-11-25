#!/usr/bin/env python3
"""Rapport santé du matin — job de la machine Ollama (Ollama, GPU). Bibliothèque standard seulement.

Toutes les 10 min entre 4 h et 13 h (timer systemd) :
  1. GET  {SANTE_URL}/api/v1/report-job/pending   (jeton dédié REPORT_TOKEN)
  2. si la nuit est arrivée (ou midi passé) et qu'aucun texte n'existe :
     le modèle local RÉDIGE à partir des faits ; il ne calcule ni ne juge rien (signaux déjà calculés
     par le serveur). Échec du modèle ou texte suspect → texte de secours fourni par le serveur.
     POST {SANTE_URL}/api/v1/report-job/narrative
  3. POST {SANTE_URL}/api/v1/report-job/notified (rapport prêt). Depuis le 25/11/2026, c'est l'appli
     Parro Health qui l'annonce (notification locale après sa synchro) : ni Home Assistant ni Google.
     Le webhook HA ne sert plus qu'aux alertes de synchro et au rappel d'export (--surveillance).

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
    "Tu rédiges le bilan santé du matin de Parro, en français. "
    "Adresse-toi DIRECTEMENT à Parro, à la deuxième personne (« tu », « ton », « ta », « tes ») ; "
    "n'écris jamais « Parro a » ni de pronom à la troisième personne pour le désigner. "
    "Évite tout accord au masculin ou au féminin qui te désignerait (préfère « ton activité a été plus faible » à « tu as été moins actif »). "
    "3 à 5 phrases courtes, ton factuel et bienveillant, sans titre, sans liste, sans emoji. "
    "Utilise UNIQUEMENT les valeurs fournies, recopiées telles quelles : ne calcule rien, ne compare que ce "
    "qui est donné côte à côte (valeur / moyenne_28j), ne pose aucun diagnostic, ne donne aucun conseil. "
    "Ne parle PAS d'alertes ni de points à surveiller : ils sont ajoutés automatiquement après ton texte. "
    "Ordre : la nuit, la fréquence cardiaque au repos, l'activité d'hier, puis ce qui a été noté dans le "
    "journal s'il y en a (sans en tirer de lien). Si « nuit_reçue » est faux, dis que la nuit n'est pas encore arrivée."
)


SYSTEM_WEEK = (
    "Tu rédiges le bilan santé de la semaine de Parro, en français. "
    "Adresse-toi DIRECTEMENT à Parro, à la deuxième personne (« tu », « ton », « ta », « tes ») ; "
    "n'écris jamais « Parro a » ni de pronom à la troisième personne pour le désigner. "
    "Évite tout accord au masculin ou au féminin qui te désignerait (préfère « ton activité a été plus faible » à « tu as été moins actif »). "
    "4 à 6 phrases, ton factuel et bienveillant, sans titre, sans liste, sans emoji. "
    "Utilise UNIQUEMENT les valeurs fournies, recopiées telles quelles : ne calcule rien, compare seulement "
    "« semaine » et « 4_semaines_précédentes » quand les deux sont donnés, aucun diagnostic ni conseil. "
    "Ne parle PAS d'alertes : elles sont ajoutées automatiquement après ton texte. "
    "Couvre le sommeil, la fréquence cardiaque au repos, l'activité, puis le journal s'il y en a."
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


def hm(minutes) -> str | None:
    if minutes is None:
        return None
    m = round(minutes)
    return f"{m // 60} h {m % 60:02d}"


def compact_facts(f: dict) -> dict:
    """Faits du matin, clés explicites et valeurs déjà mises en forme : rien à calculer ni à interpréter."""
    out: dict = {"date": f.get("date"), "nuit_reçue": bool(f.get("ready"))}
    if (n := f.get("night")):
        out["nuit"] = {k: v for k, v in {
            "durée_endormi": hm(n.get("asleep_min") or n.get("in_bed_min")), "coucher": n.get("bedtime"),
            "lever": n.get("wake"), "score_sommeil": n.get("score"),
            "moyenne_28j_durée_endormi": hm(n.get("avg28_asleep_min"))}.items() if v is not None}
    if (r := f.get("resting_hr")):
        out["fc_repos"] = {k: v for k, v in {"valeur_bpm": r.get("bpm"), "moyenne_28j_bpm": r.get("avg28")}.items() if v is not None}
    if (h := f.get("hrv")) and h.get("fresh"):
        out["hrv_nocturne"] = {k: v for k, v in {"valeur_ms": h.get("value"), "moyenne_28j_ms": h.get("avg28")}.items() if v is not None}
    if (st := f.get("steps_yesterday")):
        out["pas_hier"] = {k: v for k, v in {"valeur": st.get("count"), "moyenne_28j": st.get("avg28")}.items() if v is not None}
    if (ws := f.get("workouts_yesterday")):
        # Totaux calculés ICI : le modèle n'additionne rien.
        dist = sum(w.get("distance_km") or 0 for w in ws)
        out["séances_hier"] = {k: v for k, v in {
            "nombre": len(ws), "types": ", ".join(sorted({str(w.get("type")) for w in ws if w.get("type")})),
            "durée_totale_min": round(sum(w.get("duration_min") or 0 for w in ws)),
            "distance_totale_km": round(dist, 1) if dist else None}.items() if v is not None}
    if (j := f.get("journal")):
        out["journal"] = [{"moment": e["time"], "noté": ", ".join(e["tags"]), **({"note": e["note"]} if e.get("note") else {})} for e in j]
    return out


def compact_week(f: dict) -> dict:
    def block(w: dict) -> dict:
        return {k: v for k, v in {
            "nuits_enregistrées": w.get("nights"), "durée_moyenne_endormi": hm(w.get("asleep_avg_min")),
            "heure_moyenne_de_réveil": w.get("wake_avg"), "écart_type_réveil_min": w.get("wake_sd_min"),
            "score_sommeil_moyen": w.get("sleep_score_avg"), "fc_repos_moyenne_bpm": w.get("resting_hr_avg"),
            "pas_par_jour": w.get("steps_avg"), "séances": w.get("workouts"), "minutes_de_séance_au_total": w.get("workout_min"),
            "stress_moyen": w.get("stress_avg")}.items() if v is not None}
    return {"du": f.get("start"), "au": f.get("end"), "semaine": block(f["this_week"]),
            "4_semaines_précédentes": block(f["previous_4_weeks"]),
            **({"journal_nombre_de_fois": f["journal_tags"]} if f.get("journal_tags") else {})}


def with_flags(text: str, flags: list) -> str:
    """Les signaux (calculés par règles sur le serveur) sont ajoutés tels quels : jamais confiés au modèle."""
    if not flags:
        return text + "\n\nRien d'inhabituel."
    return text + "\n\nÀ noter :\n" + "\n".join(("⚠ " if x["level"] == "attention" else "• ") + x["text"] for x in flags)


def write_with_model(env: dict, facts: dict, system: str = SYSTEM) -> str | None:
    body = {
        "model": env.get("MODEL", "mistral-nemo:12b"),
        "stream": False,
        "think": False,
        "keep_alive": "2m",  # libère vite la VRAM pour l'agent Assist de Home Assistant
        "options": {"temperature": 0.3, "num_ctx": 4096},
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": json.dumps(facts, ensure_ascii=False)},
        ],
    }
    try:
        r = http("POST", env.get("OLLAMA_URL", "http://127.0.0.1:11434") + "/api/chat", body, timeout=240)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"modèle injoignable : {e.__class__.__name__}", file=sys.stderr)
        return None
    text = (r.get("message") or {}).get("content", "").strip()
    # Garde-fous : texte vide, trop long, raisonnement verbalisé, balises → texte de secours.
    if not text or len(text) > 1500 or "<think" in text or text.count("\n- ") > 2 or "Parro a " in text:
        print(f"texte du modèle rejeté ({len(text)} car.)", file=sys.stderr)
        return None
    return text


def weekly(env: dict, base: str, auth: dict) -> None:
    """Rapport de la semaine : dimanche à partir de 18 h (décidé par le serveur), une seule fois."""
    p = http("GET", f"{base}/api/v1/report-job/weekly/pending", headers=auth)
    if not p["ready"] or (p["has_narrative"] and p["notified"]):
        return
    key, text = p["date"], p.get("narrative")
    if not p["has_narrative"]:
        text = write_with_model(env, compact_week(p["facts"]), SYSTEM_WEEK)
        model = env.get("MODEL", "mistral-nemo:12b")
        if text is None:
            text, model = p["fallback"], "secours (sans IA)"
        http("POST", f"{base}/api/v1/report-job/narrative", {"date": key, "narrative": text, "model": model}, auth)
    if not p["notified"]:
        # annonce : par l'appli Parro Health elle-même (notification locale, 25/11/2026)
        http("POST", f"{base}/api/v1/report-job/notified", {"date": key}, auth)
    print(f"{key} : rapport de la semaine traité ({len(text)} car.)")


def notify(env: dict, title: str, message: str) -> None:
    """Alerte par Home Assistant — SANS donnée de santé (25/11/2026) : seulement l'alerte « plus de synchro »
    et le rappel d'export, qui doivent justement arriver quand l'appli ne synchronise plus. Les rapports,
    eux, sont annoncés par l'appli (notification locale : ni HA, ni Google)."""
    http("POST", env["HA_WEBHOOK_URL"], {"title": title, "message": message})


def watch(env: dict) -> int:
    """--surveillance : relaie l'alerte « plus de synchro » (seuils décidés par le serveur)."""
    base = env["SANTE_URL"].rstrip("/")
    auth = {"Authorization": f"Bearer {env['REPORT_TOKEN']}"}
    s = http("GET", f"{base}/api/v1/report-job/sync-status", headers=auth)
    if s["action"] == "none":
        print(f"synchro : âge {s.get('age_hours')} h, rien à signaler")
    else:
        notify(env, s["title"], s["message"])
        http("POST", f"{base}/api/v1/report-job/sync-status/ack",
             {"action": s["action"], "last_phone_sync": s["last_phone_sync"]}, auth)
        print(f"synchro : {s['action']} relayée (âge {s['age_hours']} h)")
    weekly(env, base, auth)
    r = http("GET", f"{base}/api/v1/report-job/export-reminder", headers=auth)
    if r["action"] == "export_reminder":
        notify(env, r["title"], r["message"])
        http("POST", f"{base}/api/v1/report-job/export-reminder/ack", {}, auth)
        print(f"rappel d'export relayé (dernier import il y a {r.get('export_age_days')} j)")
    return 0


def main() -> int:
    env = load_env()
    if "--surveillance" in sys.argv:
        return watch(env)
    if "--apercu-semaine" in sys.argv:
        base = env["SANTE_URL"].rstrip("/")
        p = http("GET", f"{base}/api/v1/report-job/weekly/pending", headers={"Authorization": f"Bearer {env['REPORT_TOKEN']}"})
        t = write_with_model(env, compact_week(p["facts"]), SYSTEM_WEEK)
        print(with_flags(t, p["facts"]["flags"]) if t else "(rejeté → secours)\n" + p["fallback"])
        return 0
    if "--apercu" in sys.argv:
        # Rédige le texte du jour SANS l'enregistrer ni notifier (choix du modèle, réglage du prompt).
        if len(sys.argv) > 2:
            env["MODEL"] = sys.argv[sys.argv.index("--apercu") + 1]
        base = env["SANTE_URL"].rstrip("/")
        p = http("GET", f"{base}/api/v1/report-job/pending", headers={"Authorization": f"Bearer {env['REPORT_TOKEN']}"})
        t = write_with_model(env, compact_facts(p["facts"]))
        print(with_flags(t, p["facts"]["flags"]) if t else "(rejeté → texte de secours)\n" + p["fallback"])
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
        text = write_with_model(env, compact_facts(p["facts"]))
        model = env.get("MODEL", "mistral-nemo:12b")
        if text is None:
            text, model = p["fallback"], "secours (sans IA)"
        http("POST", f"{base}/api/v1/report-job/narrative", {"date": day, "narrative": text, "model": model}, auth)
        print(f"{day} : texte enregistré ({model}, {len(text)} car.)")
    if not p["notified"]:
        # annonce : par l'appli Parro Health elle-même (notification locale, 25/11/2026)
        http("POST", f"{base}/api/v1/report-job/notified", {"date": day}, auth)
        print(f"{day} : rapport marqué prêt (l'appli l'annonce à sa prochaine synchro)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} sur {e.url.split('?')[0]}", file=sys.stderr)
        sys.exit(1)
