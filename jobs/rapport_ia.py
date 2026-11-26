#!/usr/bin/env python3
"""Rapports santé (matin et semaine) — job à lancer sur une machine qui a accès à Ollama (GPU conseillé).
Bibliothèque standard seulement.

Toutes les 10 min entre 4 h et 13 h (timer systemd) :
  1. GET  {SANTE_URL}/api/v1/report-job/pending   (jeton dédié REPORT_TOKEN)
  2. si la nuit est arrivée (ou midi passé) et qu'aucun texte n'existe :
     le modèle local RÉDIGE à partir des faits ; il ne calcule ni ne juge rien (signaux déjà calculés
     par le serveur). Échec du modèle ou texte suspect → texte de secours fourni par le serveur.
     POST {SANTE_URL}/api/v1/report-job/narrative
  3. POST {SANTE_URL}/api/v1/report-job/notified (rapport prêt). C'est l'appli Parro Health qui l'annonce
     (notification locale après sa synchro) : aucun service tiers ne voit le texte.

--surveillance (toutes les 30 min) : alerte si le téléphone ne synchronise plus, rappel mensuel d'export.
Ces alertes (sans donnée de santé) partent vers un webhook FACULTATIF qui reçoit {"title", "message"} en
JSON — par exemple un webhook Home Assistant. Sans webhook, elles sont seulement écrites dans le journal.

Configuration : /etc/parro-sante/rapport.env (600) — SANTE_URL, REPORT_TOKEN ; facultatifs : OLLAMA_URL,
MODEL, OWNER_NAME (prénom utilisé par le modèle pour s'adresser à toi), ALERT_WEBHOOK_URL (ou HA_WEBHOOK_URL).
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
    "Tu rédiges le bilan santé du matin destiné à {name}, en français. "
    "Adresse-toi DIRECTEMENT à {name}, à la deuxième personne (« tu », « ton », « ta », « tes ») ; "
    "n'écris jamais « {name} a » ni de pronom à la troisième personne pour le désigner. "
    "Évite tout accord au masculin ou au féminin qui te désignerait (préfère « ton activité a été plus faible » à « tu as été moins actif »). "
    "3 à 5 phrases courtes, ton factuel et bienveillant, sans titre, sans liste, sans emoji. "
    "Utilise UNIQUEMENT les valeurs fournies, recopiées telles quelles : ne calcule rien, ne compare que ce "
    "qui est donné côte à côte (valeur / moyenne_28j), ne pose aucun diagnostic, ne donne aucun conseil. "
    "Ne parle PAS d'alertes ni de points à surveiller : ils sont ajoutés automatiquement après ton texte. "
    "Ordre : la nuit, la fréquence cardiaque au repos, l'activité d'hier, le poids s'il est donné, puis ce qui "
    "a été noté dans le journal s'il y en a (sans en tirer de lien). Si « nuit_reçue » est faux, dis que la nuit n'est pas encore arrivée."
)


SYSTEM_WEEK = (
    "Tu rédiges le bilan santé de la semaine destiné à {name}, en français. "
    "Adresse-toi DIRECTEMENT à {name}, à la deuxième personne (« tu », « ton », « ta », « tes ») ; "
    "n'écris jamais « {name} a » ni de pronom à la troisième personne pour le désigner. "
    "Évite tout accord au masculin ou au féminin qui te désignerait (préfère « ton activité a été plus faible » à « tu as été moins actif »). "
    "4 à 6 phrases, ton factuel et bienveillant, sans titre, sans liste, sans emoji. "
    "Utilise UNIQUEMENT les valeurs fournies, recopiées telles quelles : ne calcule rien, compare seulement "
    "« semaine » et « 4_semaines_précédentes » quand les deux sont donnés, aucun diagnostic ni conseil. "
    "Ne parle PAS d'alertes : elles sont ajoutées automatiquement après ton texte. "
    "Couvre le sommeil, la fréquence cardiaque au repos, l'activité, le poids s'il est donné, puis le journal s'il y en a."
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
        "Content-Type": "application/json", "User-Agent": "ParroHealth-rapport/1.0", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
    return json.loads(raw) if raw else {}


def hm(minutes) -> str | None:
    if minutes is None:
        return None
    m = round(minutes)
    return f"{m // 60} h {m % 60:02d}"


def kg(v, signed: bool = False):
    """Même mise en forme que le serveur (« 82,4 kg », « −1,2 kg ») : le modèle recopie, il ne calcule pas."""
    if v is None:
        return None
    s = f"{abs(v):.1f}".replace(".", ",") + " kg"
    return (("+" if v > 0 else "\u2212" if v < 0 else "\u00b1") + s) if signed else s


def pct(v):
    return None if v is None else f"{v:.1f}".replace(".", ",") + " %"


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
    if (w := f.get("weight")) and w.get("fresh"):  # seulement le jour d'une pesée (ou le lendemain)
        out["poids"] = {k: v for k, v in {
            "dernière_pesée": kg(w.get("kg")), "masse_grasse": pct(w.get("body_fat_pct")),
            "variation_sur_4_semaines": kg(w.get("change_4w_kg"), signed=True)}.items() if v is not None}
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
            "stress_moyen": w.get("stress_avg"), "poids_moyen": kg(w.get("weight_avg_kg")),
            "pesées": w.get("weighings") or None}.items() if v is not None}
    return {"du": f.get("start"), "au": f.get("end"), "semaine": block(f["this_week"]),
            "4_semaines_précédentes": block(f["previous_4_weeks"]),
            **({"variation_poids_moyen": kg(f["weight_change_kg"], signed=True)} if f.get("weight_change_kg") is not None else {}),
            **({"journal_nombre_de_fois": f["journal_tags"]} if f.get("journal_tags") else {})}


def with_flags(text: str, flags: list) -> str:
    """Les signaux (calculés par règles sur le serveur) sont ajoutés tels quels : jamais confiés au modèle."""
    if not flags:
        return text + "\n\nRien d'inhabituel."
    return text + "\n\nÀ noter :\n" + "\n".join(("⚠ " if x["level"] == "attention" else "• ") + x["text"] for x in flags)


def write_with_model(env: dict, facts: dict, system: str = SYSTEM) -> str | None:
    name = env.get("OWNER_NAME", "").strip() or "la personne concernée"
    system = system.replace("{name}", name)
    body = {
        "model": env.get("MODEL", "mistral-nemo:12b"),
        "stream": False,
        "think": False,
        "keep_alive": "2m",  # libère vite la mémoire du GPU pour les autres usages d'Ollama
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
    if not text or len(text) > 1500 or "<think" in text or text.count("\n- ") > 2 or f"{name} a " in text:
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
    """Alerte SANS donnée de santé : « plus de synchro » et rappel d'export, qui doivent justement arriver
    quand l'appli ne synchronise plus. Webhook facultatif (Home Assistant ou tout service qui accepte
    {"title", "message"}) ; sans lui, l'alerte n'est qu'écrite dans le journal du service."""
    url = env.get("ALERT_WEBHOOK_URL") or env.get("HA_WEBHOOK_URL")
    if not url:
        print(f"alerte (aucun webhook configuré) : {title}")
        return
    http("POST", url, {"title": title, "message": message})


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
