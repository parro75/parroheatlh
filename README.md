# Parro Health

Tableau de bord de santé **personnel et auto-hébergé**. Une app Android lit Health Connect (montre Samsung
Galaxy Watch via Samsung Health, balance connectée via Google Health), envoie les mesures à un serveur
qu'on héberge soi-même, et les rend lisibles partout :

- dans l'**app** : tableau de bord, graphiques interactifs, hypnogramme, tendances sur dix ans, journal ;
- dans **Claude**, par un connecteur MCP en lecture seule (« comment évolue ma FC au repos depuis un an ? ») ;
- dans un **rapport du matin** et un **rapport de la semaine**, calculés par règles et rédigés par un modèle
  de langage **local** (Ollama) ;
- dans une **fiche PDF** pour un rendez-vous médical ;
- et, en option, dans un tableau de bord **Home Assistant** (chiffres seulement).

Aucun service tiers ne voit les données au-delà de ce que Samsung Health et Google Health voient déjà.
Seuls l'app et le serveur sont indispensables ; le job de rapport, Ollama et Home Assistant sont facultatifs.

```
Galaxy Watch ─► Samsung Health ─┐                                ┌─ export Samsung Health (zip, HRV, scores,
Balance ─────► Google Health ───┴─► Health Connect (téléphone)   │  historique depuis 2015) partagé vers l'app
                                          │                       │
                                          ▼                       ▼
                          ┌──────── Parro Health (android/) ──────────┐
                          │ synchro horaire, et toutes les 15 min de 5 h à 11 h ; journal, objectifs
                          ▼ POST /ingest (jeton d'ingestion)          ▲ GET /api/v1/… (OAuth, PKCE)
          ┌─────────────────── serveur shc (server/) : FastAPI + SQLite + MCP ───────────────────┐
          │ /mcp (OAuth)                 │ /api/v1/report-job/… (jeton dédié)  │ /api/v1/ha/summary (jeton dédié)
          ▼                              ▼                                     ▼
       Claude                 job de rapport (jobs/) + Ollama local     Home Assistant
                              (facultatif)                             (facultatif)
```

## Ce que fait le système

| Où | Quoi |
|---|---|
| **App — Aujourd'hui** | anneaux pas/sommeil vs objectifs, rapport du matin dépliable, 6 tuiles (nuit, FC au repos, HRV, pas, poids, stress) avec mini-courbe, semaine en cours ; tirer vers le bas = synchro |
| **App — détails** | Sommeil (hypnogramme de la nuit, durées, régularité coucher/lever, score), Cœur (FC au repos, HRV, stress, FC du jour, SpO2), Activité (pas, séances), Corps (poids, masse grasse) ; périodes 7 j → tout l'historique |
| **App — Tendances** | 10 mesures, 12 mois (par mois), 5 ans et tout (par année), même mois d'une année à l'autre |
| **App — Journal** | alcool, café tardif, maladie… noté hors ligne, envoyé à la synchro suivante, rappelé tel quel dans les rapports |
| **App — Profil** | objectifs, fiche PDF, rappel de pesée, verrouillage biométrique, synchro, export Samsung, diagnostic Health Connect, connexion |
| **Rapports** | faits et signaux calculés par règles sur le serveur ; le modèle local ne fait que rédiger ; annoncés par une notification **locale** de l'app, sans contenu de santé si l'app est verrouillée |
| **Claude** | 15 outils MCP en lecture seule (voir plus bas) |
| **Fiche médecin** | PDF de 3, 6 ou 12 mois généré en mémoire : tableau des mesures, courbes, nombre de jours par étiquette du journal (jamais les notes) |
| **Home Assistant** (facultatif) | `GET /api/v1/ha/summary` : chiffres du jour seulement, pour un tableau de bord réservé au propriétaire ; route inexistante (404) sans `HA_TOKEN` |

## Arborescence

```
android/   app Parro Health (Kotlin, Jetpack Compose)
server/    serveur shc : API d'ingestion, MCP, OAuth, API de lecture, rapports, PDF
jobs/      job de rédaction des rapports et de surveillance (systemd, Python sans dépendance)
deploy/    bloc Caddy (socket Unix, routes ouvertes une à une)
dist/      APK signés (non versionnés)
```

## Serveur (`server/`)

Python 3.13, SDK MCP officiel v2 (`mcp==2.3.0`), FastAPI, SQLite (WAL), fpdf2 pour la fiche PDF.

### Routes

| Route | Rôle | Protection |
|---|---|---|
| `POST /ingest` | lots du téléphone (mesures, journal, objectifs, suppressions) | `Bearer INGEST_TOKEN` |
| `POST/PUT/GET /ingest-export/…` | export Samsung Health envoyé par morceaux de 15 Mio, puis import automatique | `Bearer INGEST_TOKEN` |
| `/mcp` | MCP streamable HTTP, sans état, réponses JSON | jeton OAuth (`health:read`) + `Host`/`Origin` vérifiés |
| `/.well-known/…`, `/register`, `/token`, `/revoke` | serveur d'autorisation OAuth 2.1 | PKCE S256 obligatoire |
| `/authorize`, `/login` | connexion du propriétaire | mot de passe (scrypt) + TOTP |
| `GET /api/v1/<nom>` | lecture pour l'app : `today`, `report`, `weekly`, `night`, `sleep`, `steps`, `heart_rate`, `hrv`, `recovery`, `stress`, `training_load`, `workouts`, `body`, `spo2`, `journal`, `trends`, `status` | jeton OAuth du client public `parro-health-android` |
| `GET /api/v1/pdf/summary?months=3\|6\|12` | fiche PDF, générée en mémoire | jeton OAuth de l'app |
| `/api/v1/report-job/…` | faits du jour/de la semaine, dépôt du texte rédigé, état de la synchro, rappel d'export | `Bearer REPORT_TOKEN` |
| `GET /api/v1/ha/summary` | chiffres du jour pour Home Assistant | `Bearer HA_TOKEN` |
| `GET /healthz` | sonde | aucune donnée |
| tout le reste | 404 | — |

Plages : 366 jours au plus par appel, sauf `trends` et `body` (20 ans). Une nuit est rattachée au **jour du
réveil**. Les pesées en double (même source, même valeur à 10 min près) sont ignorées à la lecture.

### Outils MCP (lecture seule)

`get_daily_steps`, `get_sleep`, `get_heart_rate`, `get_hrv`, `get_body_measurements`, `get_spo2`,
`get_workouts`, `get_recovery`, `get_stress`, `get_training_load`, `get_journal` — `start`/`end` optionnels
(`AAAA-MM-JJ`, 7 derniers jours par défaut) — ainsi que `get_trends` (moyennes par semaine, mois ou année
sur 20 ans), `get_daily_report`, `get_weekly_report` et `get_sync_status` (couverture par type).

### Données

| Source | Types |
|---|---|
| Téléphone (Health Connect) | `steps_daily`, `heart_rate_daily` (agrégats par jour local), `resting_heart_rate` (calculée par l'app), `hrv_rmssd`, `sleep_session` (avec phases), `weight`, `body_fat`, `oxygen_saturation`, `exercise_session` |
| Téléphone (app) | `journal`, `goals`, et `deletion` (suppressions relevées par l'API Changes de Health Connect) |
| Export Samsung Health | `sleep_score`, `vitals_daily` (HRV, FC de sommeil, respiration, température cutanée), `energy_score`, `stress_daily`, `training_load_daily`, et l'historique des types ci-dessus |

Contrat d'ingestion : `{"records": [{"type", "id", "start", "end"?, "zone_offset", "data"}]}`, upsert
idempotent sur `(type, id)`, lot invalide refusé **en bloc** (422, index fautif dans le message), horodatage
sans décalage refusé.

### Rapports

`report.facts` (matin) et `report.weekly_facts` (semaine) calculent tout : nuit et moyennes 28 j, FC au
repos, HRV, pas, SpO2, poids (tendance lissée sur les moyennes de pesées), journal. Les **signaux** (nuit
courte, FC au repos élevée, HRV basse, poids qui varie de plus de 2 kg en une semaine…) sont des règles
testées, ajoutées **après** le texte : jamais confiés au modèle. Le job (`jobs/rapport_ia.py`, timers toutes
les 10 min de 4 h à 12 h) envoie des faits déjà mis en forme au modèle local, contrôle sa réponse, et retombe
sur un texte de secours si besoin. Sans job, l'app et Claude affichent quand même les faits et les signaux.
Le service `parro-surveillance` alerte si le téléphone ne synchronise plus et rappelle d'envoyer un export
Samsung chaque mois : vers un webhook **facultatif** qui reçoit `{"title", "message"}` (Home Assistant, ou
tout autre service), sinon dans le journal du service. Ces alertes ne contiennent aucune donnée de santé.

### Sécurité

- **Un seul utilisateur.** `/login` exige mot de passe **et** code TOTP ; code déjà utilisé refusé ; 5 échecs
  = 15 min de verrouillage. Enregistrement dynamique de clients limité aux URL de retour de `claude.ai` /
  `claude.com` (50 clients au plus).
- **Jetons.** Accès 1 h, rafraîchissement 30 j à rotation, rejeu = révocation de la famille ; stockés
  **hachés**. Quatre secrets distincts, refusés s'ils sont trop courts ou identiques : `INGEST_TOKEN`
  (écriture), OAuth (lecture), `REPORT_TOKEN` (job), `HA_TOKEN` (Home Assistant).
  `python -m shc.admin revoke-all` coupe tout.
- **Conteneur sans réseau** (`network_mode: none`), en lecture seule, non root, sans capability : il écoute
  sur un **socket Unix** que seul le reverse proxy monte. Le proxy n'ouvre que les routes listées, celles
  du MCP seulement aux IP sortantes publiées par Anthropic. Aucun journal d'accès applicatif.
- **Fiche PDF** générée en mémoire, jamais écrite sur le disque du serveur.
- **Notifications** : aucune donnée de santé ne passe par Home Assistant ni par Google ; le rapport est
  annoncé par l'app elle-même.
- **Export partagé** : un zip partagé vers l'app n'est envoyé qu'après confirmation (nom, taille, appli
  d'origine) — sans quoi une autre appli pourrait faire importer de fausses données.

### Configuration (`.env`)

| Variable | Rôle |
|---|---|
| `INGEST_TOKEN`, `OWNER_PASSWORD_HASH`, `TOTP_SECRET` | générés par `python -m shc.admin init` (le hachage reste **entre apostrophes** : sinon `docker compose` interprète les `$` et le tronque en silence) |
| `PUBLIC_URL` | URL publique (émetteur OAuth, ressource MCP) |
| `TZ_LOCAL`, `DB_PATH` | fuseau des jours locaux, base SQLite |
| `REPORT_TOKEN`, `REPORT_DEADLINE_HOUR` | job de rapport (facultatif : sans jeton, ses routes répondent 404) |
| `HA_TOKEN` | résumé pour Home Assistant (facultatif : sans jeton, la route répond 404) |
| `UNIX_SOCKET` | socket d'écoute (sinon TCP 8080) — fixé par `docker-compose.yml` |
| `MCP_ALLOWED_HOSTS`, `OAUTH_REDIRECT_URIS`, `APP_CLIENT_ID`, `APP_REDIRECT_URI` | valeurs par défaut adaptées |

### Déploiement

```sh
cd server
grep -v '=a-remplacer' .env.example > .env && chmod 600 .env    # puis régler PUBLIC_URL
docker compose build
docker compose run --rm -T shc python -m shc.admin init >> .env    # mot de passe lu sur stdin
install -d -o 10001 -g 10001 -m 700 /var/lib/sante                 # base
install -d -o 10001 -m 750 /var/lib/sante-run                      # socket
docker compose up -d
docker compose run --rm shc python -m shc.admin totp-uri           # à enregistrer dans l'appli TOTP
```

Monter `/var/lib/sante-run` sur `/run/sante` dans le conteneur du reverse proxy et ajouter le bloc
`deploy/Caddyfile.sante`. Dans Claude : Réglages → Connecteurs → *Ajouter un connecteur personnalisé* →
`https://sante.example.org/mcp`, puis mot de passe + code TOTP et *Autoriser*.

Job de rapport (facultatif) : copier `jobs/rapport_ia.py` dans `/opt/parro-rapport/`, les unités
`jobs/*.service|timer` dans systemd, et créer `/etc/parro-sante/rapport.env` (mode 600) avec `SANTE_URL` et
`REPORT_TOKEN` ; facultatifs : `OLLAMA_URL`, `MODEL` (défaut `mistral-nemo:12b`), `OWNER_NAME` (prénom par
lequel le modèle s'adresse à toi), `ALERT_WEBHOOK_URL` (ou `HA_WEBHOOK_URL`). `rapport_ia.py --apercu [modèle]`
rédige le rapport du jour sans l'enregistrer. Les routes du job et de Home Assistant ne passent jamais par le
nom public : `deploy/Caddyfile.sante` les sert sur un **nom interne** réservé à tes réseaux (VPN, LAN).

### Tests

```sh
pip install -r requirements-dev.txt
python -m pytest -q    # ingestion, OAuth (chaque garde-fou), MCP de bout en bout, rapports, PDF, doublons…
```

## App Android « Parro Health » (`android/`)

Kotlin, Jetpack Compose (Material 3), AGP 9.5, `compileSdk` 37.1, `targetSdk` 36, `minSdk` 29, R8 activé
(APK de ~6 Mio). Identifiant `fr.parro.santesync`, gardé depuis « Santé Sync » : le changer ferait perdre
les permissions Health Connect.

- **Interface** : thème « nuit » qui suit le mode clair/sombre du téléphone, une couleur fixe par domaine
  (sommeil indigo, cœur corail, HRV turquoise, activité vert, corps ambre), chiffres en Space Grotesk
  **embarquée** (licence SIL OFL, `android/FONT-LICENSE-SpaceGrotesk.txt`), graphiques maison en Compose
  (`ui/Charts.kt` : barres et courbes qu'on parcourt au doigt, anneaux, hypnogramme).
- **Synchro** : WorkManager toutes les heures, et toutes les 15 min de 5 h à 11 h pour que le rapport parte
  vite ; API Changes de Health Connect (seul le neuf part, les suppressions suivent) ; `User-Agent` et ligne
  d'état indiquent auto, matin ou manuel.
- **FC au repos calculée** (Samsung ne la publie pas) : plus basse moyenne glissante sur 30 min pendant
  chaque nuit de 3 h et plus ; seuls ce chiffre et la moyenne de la nuit sont envoyés.
- **Export Samsung Health** : partager le zip vers l'app (ou le choisir dans Profil) l'envoie au serveur par
  morceaux, qui l'importe seul. C'est la seule source de la HRV nocturne, des scores de sommeil et du stress.
- **Lecture** : OAuth (mot de passe + TOTP dans un onglet de navigateur, PKCE, client public), jetons chiffrés
  par une clé AES du Keystore.
- **Notifications locales** : rapport prêt (texte neutre si l'app est verrouillée), rappel de pesée
  (quotidien ou hebdomadaire, muet si une pesée existe déjà dans Health Connect).
- **Widget** d'écran d'accueil (masqué si l'app est verrouillée), **verrouillage biométrique**, écran
  **Diagnostic** de ce que Health Connect contient réellement. Aucune permission de santé reproductive.

### Compiler et signer

```sh
cd android
./gradlew assembleDebug lintDebug testDebugUnitTest    # ce que fait la CI (sans aucune clé)
```

La **clé de signature** n'est pas dans le dépôt : elle reste dans un gestionnaire de secrets, et l'APK de
version est signé dans un environnement isolé, clé tirée du coffre puis détruite, avec `PARRO_SIGNING_STORE_FILE`, `PARRO_SIGNING_STORE_PASSWORD`, `PARRO_SIGNING_KEY_ALIAS`,
`PARRO_SIGNING_KEY_PASSWORD` et `./gradlew assembleRelease`. Certificat : `CN=Parro Health`,
SHA-256 `40:09:F2:CB:…:25:5B`.

### Installation

1. Samsung Health → Réglages → Health Connect : synchronisation active (et Google Health pour la balance).
2. Installer l'APK, puis dans **Profil** : *Connexion au serveur* (URL + jeton d'ingestion), *Autorisations
   Health Connect* (tout cocher, arrière-plan et historique compris), *Exempter de l'optimisation de
   batterie*, *Se connecter* (lecture).
3. Facultatif : envoyer un premier export Samsung Health pour l'historique et la HRV.

## Données et confidentialité

| Où | Quoi | Protection |
|---|---|---|
| Téléphone | Health Connect (géré par Android) ; journal non encore envoyé, résumé du widget, réglages | stockage privé de l'app, **exclu** des sauvegardes cloud et des transferts d'appareil ; jetons (OAuth et ingestion) **chiffrés** par une clé AES du Keystore ; verrouillage biométrique facultatif, qui masque aussi le widget, le texte des notifications et la miniature des applis récentes |
| Réseau | toutes les requêtes | HTTPS obligatoire (URL en clair refusée, trafic en clair interdit par le manifeste) |
| Serveur | base SQLite | volume privé du conteneur (mode 700) ; à sauvegarder **chiffrée** (par exemple avec `age`) |
| Serveur | export Samsung reçu | écrit le temps de l'import (mode 600), puis écrasé et supprimé |
| Serveur | fiche PDF | générée en mémoire, jamais écrite |
| Claude | réponses des outils MCP | ce que Claude lit pour répondre, selon ta configuration de Claude |
| Machine Ollama | faits du jour (dont le journal) | ne quittent pas cette machine ; aucune donnée de santé dans ses journaux |
| Home Assistant | chiffres du jour | facultatif ; penser à exclure ces capteurs de l'historique et d'Assist |

Les tests n'utilisent que des valeurs fictives. Aucun secret n'est dans le dépôt : `.env`, bases et APK sont
ignorés par git.

## Limites connues

- Seul ce que les applis écrivent réellement dans Health Connect arrive ; l'écran Diagnostic et
  `get_sync_status` le montrent. HRV nocturne, scores de sommeil et stress n'existent que via les exports
  Samsung, donc à jour au dernier export.
- Google Health ne publie que les pesées récentes : l'historique plus ancien de la balance n'arrive pas.
- La FC au repos n'existe que depuis l'installation de la version qui la calcule (rattrapage d'un an).
- Le code de lecture Health Connect n'a pas de test automatique (pas de faux client stable) : il se valide
  sur l'appareil, avec l'écran Diagnostic.
