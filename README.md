# Connecteur Samsung Health → Claude

Expose à Claude, par un serveur MCP auto-hébergé, les données Samsung Health (Galaxy Watch) qui ne
remontent pas dans Strava : sommeil, pas, FC, FC au repos, HRV, poids, masse grasse, SpO2, séances.

```
Galaxy Watch ─► Samsung Health ─► Health Connect (téléphone)
                                        │  lecture seule, toutes les heures (WorkManager)
                                        ▼
                         Santé Sync (app Android, android/)
                                        │  POST /ingest  (HTTPS + Bearer INGEST_TOKEN)
                                        ▼
                  serveur shc (server/) : FastAPI + SQLite + MCP
                                        │  /mcp/<MCP_PATH_SECRET>  (streamable HTTP, sans état)
                                        ▼
                         Claude (connecteur personnalisé)
```

## Serveur (`server/`)

Python 3.13, SDK MCP officiel **v2** (`mcp==2.3.0`, `MCPServer`), FastAPI, SQLite (WAL).

| Route | Rôle | Protection |
|---|---|---|
| `POST /ingest` | reçoit les lots du téléphone | `Authorization: Bearer <INGEST_TOKEN>` |
| `/mcp/<MCP_PATH_SECRET>` | MCP streamable HTTP (réponses JSON, sans état) | chemin secret + `Host`/`Origin` vérifiés |
| `GET /healthz` | sonde | aucune donnée |
| tout le reste | 404 | — |

Aucun journal d'accès (le chemin MCP contient le secret).

### Outils MCP (lecture seule)

`get_daily_steps`, `get_sleep`, `get_heart_rate`, `get_hrv`, `get_body_measurements`, `get_spo2`,
`get_workouts` — paramètres optionnels `start` / `end` (`AAAA-MM-JJ`, inclus ; 7 derniers jours par
défaut ; 366 jours au plus) — et `get_sync_status` (dernière synchro, couverture par type).
Une nuit est rattachée au **jour du réveil**.

### Contrat d'ingestion

```json
{"records": [
  {"type": "sleep_session", "id": "<metadata.id Health Connect>",
   "start": "2026-11-23T23:10:00-05:00", "end": "2026-11-24T06:40:00-05:00",
   "zone_offset": "-05:00", "data": {"stages": [{"start": "…", "end": "…", "stage": "deep"}]}}
]}
```

Types : `steps_daily`, `heart_rate_daily` (agrégats par jour local, id `steps:AAAA-MM-JJ` / `hr:…`),
`resting_heart_rate`, `hrv_rmssd`, `sleep_session`, `weight`, `body_fat`, `oxygen_saturation`,
`exercise_session` (`exercise_type` = code Health Connect, traduit côté serveur).
Upsert idempotent sur `(type, id)` ; un lot invalide est refusé **en bloc** (422, index fautif dans le
message). Horodatage sans décalage = refusé.

### Déploiement

```sh
cd server
cp .env.example .env    # remplir les deux secrets (différents, 32+ caractères) et MCP_ALLOWED_HOSTS
mkdir -p data && chown 10001 data
docker compose up -d --build
```

Le conteneur écoute sur `127.0.0.1:8080` ; il faut un reverse proxy HTTPS devant, par exemple Caddy :

```
sante.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

Claude (claude.ai) appelle le connecteur **depuis Internet** : le nom doit être public. Le téléphone,
lui, doit pouvoir joindre `/ingest` (en 5G aussi si l'on veut des synchros hors de la maison).

### Brancher dans Claude

Réglages → Connecteurs → *Ajouter un connecteur personnalisé* → URL
`https://sante.example.org/mcp/<MCP_PATH_SECRET>`. Pas d'OAuth en v1 : le chemin est le secret.

### Tests

```sh
pip install -r requirements-dev.txt
python -m pytest -q     # ingestion, idempotence, rejet atomique, dates locales, MCP de bout en bout
```

## App Android (`android/`)

Kotlin, AGP 9.5 (Kotlin intégré), Gradle 9.9, `compileSdk`/`targetSdk` 36, `minSdk` 29.
Dépendances : `connect-client` 1.1.0, `work-runtime-ktx`, `activity-ktx`, coroutines. Réseau en
`HttpURLConnection` + `org.json`. HTTPS imposé.

- **Fenêtre** : depuis la dernière synchro réussie moins 2 jours ; premier import 30 jours, ou 365
  si la permission d'historique est accordée. Lecture paginée (`pageToken`), envoi par lots de 1000.
- **Pas et FC journaliers** par `aggregateGroupByPeriod` (dédoublonnage par Health Connect) ;
  distance, calories, FC et pas de chaque séance par `aggregate` sur sa plage.
- **Synchro horaire** en arrière-plan (réseau requis) ; « Synchroniser maintenant » tourne au premier
  plan, sans besoin de la permission d'arrière-plan.
- Jeton dans le stockage privé ; sauvegarde cloud et transfert d'appareil exclus.

```sh
cd android
./gradlew assembleDebug    # → app/build/outputs/apk/debug/app-debug.apk
```

La CI (`.github/workflows/build-apk.yml`) lance les tests serveur et publie l'APK en artefact.
⚠️ Un APK de debug est signé par la clé de debug **de la machine qui compile** : passer d'un APK
compilé localement à un APK de la CI impose de désinstaller d'abord.

### Installation sur le téléphone

1. Samsung Health → Réglages → **Health Connect** : vérifier que la synchronisation est active
   (sinon rien n'arrive dans Health Connect).
2. Installer l'APK (autoriser l'installation depuis la source utilisée).
3. Ouvrir *Santé Sync* : URL `https://sante.example.org`, jeton = `INGEST_TOKEN`, **Enregistrer**.
4. **Autoriser Health Connect** : tout cocher, y compris *arrière-plan* et *historique*.
5. **Synchroniser maintenant**, puis `get_sync_status` depuis Claude.
6. Réglages Android → Applis → Santé Sync → Batterie : **Non restreinte**, sinon One UI peut
   espacer ou suspendre la synchro horaire.

## Limites connues

- Seuls les types que Samsung Health écrit réellement dans Health Connect arrivent ; `get_sync_status`
  dit lesquels. Le score de sommeil, le stress, l'âge vasculaire, etc. ne passent pas par Health Connect.
- Une donnée supprimée dans Health Connect n'est pas supprimée côté serveur (pas encore d'API Changes).
- Le code de lecture Health Connect n'a pas de test automatique (la bibliothèque de faux client
  `connect-testing` n'existe qu'en alpha liée à d'autres versions) : à valider sur l'appareil.
