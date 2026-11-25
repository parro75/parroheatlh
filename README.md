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
                                        │  /mcp  (streamable HTTP, sans état, jeton OAuth)
                                        ▼
                         Claude (connecteur personnalisé)
```

## Serveur (`server/`)

Python 3.13, SDK MCP officiel **v2** (`mcp==2.3.0`, `MCPServer`), FastAPI, SQLite (WAL).

| Route | Rôle | Protection |
|---|---|---|
| `POST /ingest` | reçoit les lots du téléphone | `Authorization: Bearer <INGEST_TOKEN>` |
| `/mcp` | MCP streamable HTTP (réponses JSON, sans état) | jeton OAuth (portée `health:read`) + `Host`/`Origin` vérifiés |
| `/.well-known/…`, `/register`, `/token`, `/revoke` | serveur d'autorisation OAuth 2.1 (SDK MCP) | PKCE S256 obligatoire |
| `/authorize`, `/login` | connexion du propriétaire | mot de passe (scrypt) + TOTP |
| `GET /api/v1/<nom>` | lecture pour l'app Android (today, sleep, steps, heart_rate, hrv, recovery, stress, training_load, workouts, body, spo2, trends, status) | jeton OAuth du client public `parro-health-android` (PKCE) |
| `GET /healthz` | sonde | aucune donnée |
| tout le reste | 404 | — |

### Sécurité

- **Un seul utilisateur** : le propriétaire des données. `/login` exige le mot de passe **et** un code
  TOTP ; un code déjà utilisé est refusé (anti-rejeu) ; 5 échecs = connexion verrouillée 15 min.
- **Clients** : enregistrement dynamique (DCR) accepté **seulement** avec une URL de retour de la liste
  blanche (`claude.ai` / `claude.com`) ; au plus 50 clients.
- **Jetons** : accès 1 h, rafraîchissement 30 j à rotation ; rejouer un jeton de rafraîchissement déjà
  utilisé révoque toute la famille. Codes et jetons ne sont stockés que **hachés** (SHA-256).
  `python -m shc.admin revoke-all` coupe tout.
- **Jeton d'ingestion** distinct : il n'ouvre pas le MCP, et un jeton OAuth n'ouvre pas `/ingest`.
- **Page de connexion** : CSP stricte (`frame-ancestors 'none'`, `form-action` limité), `no-store`.
- **Déploiement** (voir `deploy/`) : conteneur en lecture seule, sans capability, non root, sur un réseau
  Docker `internal` (aucun accès sortant) ; le reverse proxy n'ouvre les routes serveur à serveur
  qu'aux IP sortantes publiées par Anthropic et répond 404 à tout le reste.
- Aucun journal d'accès applicatif.

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
grep -v '=a-remplacer' .env.example > .env && chmod 600 .env   # puis régler PUBLIC_URL
docker compose build
docker compose run --rm -T shc python -m shc.admin init >> .env   # mot de passe lu sur stdin
install -d -o 10001 -g 10001 -m 700 /var/lib/sante
docker compose up -d
```

Le conteneur n'a **aucun réseau** (`network_mode: none`) : il écoute sur un socket Unix dans
`/var/lib/sante-run` (`install -d -o 10001 -m 750`). Monter ce dossier sur `/run/sante` dans le conteneur
du reverse proxy, puis ajouter le bloc `deploy/Caddyfile.sante`. Aucune règle de pare-feu nécessaire. ⚠️ `OWNER_PASSWORD_HASH` doit rester **entre apostrophes** dans `.env` : sans elles,
`docker compose` interprète les `$` du hachage scrypt et le tronque en silence. L'URI TOTP à enregistrer dans l'appli
d'authentification : `docker compose run --rm shc python -m shc.admin totp-uri`.

### Brancher dans Claude

Réglages → Connecteurs → *Ajouter un connecteur personnalisé* → URL `https://sante.example.org/mcp`.
Claude ouvre la page de connexion : mot de passe + code TOTP, puis *Autoriser*.

### Tests

```sh
pip install -r requirements-dev.txt
python -m pytest -q     # ingestion, OAuth (chaque garde-fou), MCP de bout en bout avec jeton
```

## App Android « Parro Health » (`android/`)

Kotlin, Jetpack Compose (Material 3, couleurs dynamiques), AGP 9.5, `compileSdk` 37.1, `targetSdk` 36,
`minSdk` 29. Identifiant `fr.parro.santesync` conservé depuis « Santé Sync » (le changer ferait perdre
les permissions Health Connect).

- **Synchro** (inchangée sur le fond) : Health Connect → `POST /ingest`, toutes les heures (WorkManager)
  ou à la demande ; la ligne de statut et l'en-tête `User-Agent` indiquent **auto** ou **manuel** ; l'écran
  Réglages affiche la prochaine exécution prévue et propose l'exemption d'optimisation de batterie.
- **FC au repos calculée** sur le téléphone (Samsung ne la publie pas) : plus basse moyenne glissante sur
  30 min des mesures de FC (~1/min) pendant chaque nuit de 3 h et plus, et moyenne de la nuit ; seuls ces
  deux chiffres sont envoyés. Rattrapage d'un an au premier lancement de la version.
- **Graphiques** : Accueil, Sommeil (phases, score), Cœur (FC au repos/sommeil, HRV, stress, FC jour),
  Activité (pas, séances), Tendances (semaine/mois/année depuis 2015). Données relues sur le serveur par
  `GET /api/v1/…` après une connexion OAuth (mot de passe + TOTP dans un onglet de navigateur, PKCE,
  client public) ; jetons chiffrés par une clé AES du Keystore Android.
- La HRV n'est pas calculable depuis Health Connect (pas d'intervalles entre battements) : elle vient des
  exports Samsung Health importés sur le serveur.

### Compiler et signer

```sh
cd android
./gradlew assembleDebug lintDebug          # ce que fait la CI (sans aucune clé)
```

La **clé de signature stable** (« Parro Health — clé de signature Android » dans le coffre gestionnaire de secrets) ne
quitte pas l'infrastructure : l'APK de version est signé dans le bac à sable, clé tirée du coffre puis
détruite, avec les variables `PARRO_SIGNING_STORE_FILE`, `PARRO_SIGNING_STORE_PASSWORD`,
`PARRO_SIGNING_KEY_ALIAS`, `PARRO_SIGNING_KEY_PASSWORD` et `./gradlew assembleRelease`.
Certificat : `CN=Parro Health`, SHA-256 `40:09:F2:CB:…:25:5B`.

### Installation

Samsung Health → Réglages → Health Connect : synchronisation active. Installer l'APK ; Réglages de l'app :
URL + jeton d'ingestion, **Autoriser** (tout cocher, arrière-plan et historique compris), **Exempter de
l'optimisation de batterie**, **Se connecter** (graphiques).

## Limites connues

- Seuls les types que Samsung Health écrit réellement dans Health Connect arrivent ; `get_sync_status`
  dit lesquels. Le score de sommeil, le stress, l'âge vasculaire, etc. ne passent pas par Health Connect.
- Une donnée supprimée dans Health Connect n'est pas supprimée côté serveur (pas encore d'API Changes).
- Le code de lecture Health Connect n'a pas de test automatique (la bibliothèque de faux client
  `connect-testing` n'existe qu'en alpha liée à d'autres versions) : à valider sur l'appareil.
