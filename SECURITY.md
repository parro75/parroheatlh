# Sécurité

Parro Health manipule des **données de santé réelles**. Ce document résume le modèle de menace et les
protections en place ; le détail est dans le [README](README.md#données-et-confidentialité), et la démarche
« au plus près de HDS » (analyse de risques, registre, incidents, continuité, revue des accès) dans
[`docs/conformite/`](docs/conformite/README.md).

## Signaler une faille

Merci de **ne pas** ouvrir d'issue publique : utilise *Security → Report a vulnerability* (signalement privé
GitHub) sur ce dépôt. Réponse sous quelques jours.

## Ce qui est protégé, et contre quoi

| Menace | Protection |
|---|---|
| Écoute du réseau | HTTPS partout ; service sans réseau, joint par socket Unix ; trafic en clair interdit dans l'app |
| Écriture avec un jeton volé | chaque écriture est **signée** par une clé ECDSA P-256 du Keystore matériel du téléphone appairé (appairage par l'app seulement, après mot de passe + TOTP) ; horodatage ± 5 min, anti-rejeu ; le jeton seul est refusé |
| Usurpation de la connexion | mot de passe + **clé d'accès WebAuthn** (passkey, clé FIDO2 ; résiste à l'hameçonnage), TOTP en secours tracé et signalé |
| Accès non autorisé à l'API | 4 jetons distincts (écriture, lecture OAuth, job, Home Assistant), comparés en temps constant ; OAuth 2.1 + PKCE, mot de passe (scrypt) + TOTP anti-rejeu, verrouillage après 5 échecs ; jetons stockés hachés, rotation et révocation de famille sur rejeu |
| Exposition des routes | proxy qui n'ouvre que les routes listées ; MCP limité aux IP d'Anthropic ; routes du job et de Home Assistant sur un nom interne seulement |
| Vol ou recyclage du disque du serveur | base dans un volume LUKS2 dont la clé n'est jamais sur le serveur (`deploy/volume-chiffre/`) ; refus de démarrer si le volume n'est pas monté ; aucun swap pour le service |
| Fuite par les sauvegardes | sauvegardes chiffrées (`age`), clé privée hors du stockage des sauvegardes |
| Fuite par les journaux ou les notifications | aucune donnée de santé dans les journaux ; notifications locales, neutres si l'app est verrouillée ; rien ne passe par Google ni Home Assistant |
| Vol du téléphone | jetons chiffrés par le Keystore ; sauvegarde et transfert d'appareil exclus ; verrouillage biométrique facultatif (masque aussi le widget et la miniature des applis récentes) |
| Appli malveillante sur le téléphone | composants exportés réduits au nécessaire ; import d'un zip partagé seulement après confirmation ; FileProvider non exporté ; PKCE contre le détournement du retour OAuth |
| Injection de consignes (prompt injection) | textes libres plafonnés (note 500, titre 200 caractères) et déclarés comme des **données** à Claude (MCP) et au modèle local ; les signaux des rapports sont calculés par règles, jamais par le modèle |
| Fichiers piégés | export reçu par morceaux, taille et SHA-256 vérifiés, lu en flux sans extraction, JSON géants ignorés ; écrasé puis supprimé après import |
| Accès non tracé | **journal des accès** chaîné (SHA-256, 1 an) : qui, quoi, quand, d'où ; consultable dans l'app ; alertes et revue mensuelle automatique |
| Secrets dans le dépôt | aucun (`.env`, bases, APK ignorés) ; historique vérifié par gitleaks |

## Limites connues (assumées)

- Un administrateur du serveur peut lire la base **pendant** qu'elle est ouverte : aucun chiffrement au repos
  ne l'empêche tant que le service doit lire les données.
- Le verrouillage de la page de connexion est global : un tiers peut bloquer les **nouvelles** connexions
  pendant 15 min (sans pouvoir entrer, et sans couper l'app ni Claude déjà connectés).
- Un téléphone appairé ET déverrouillé par un tiers peut écrire : révoquer l'appareil
  (`python -m shc.admin revoke-device`) en cas de perte ou de vol.
- Pas d'épinglage de certificat dans l'app (TLS standard ; Android n'accepte pas les autorités ajoutées par
  l'utilisateur).

## Derniers contrôles

**26/11/2026 — test d'intrusion** (depuis Internet, sans test de charge ni tentative de connexion sur
`/login`) : TLS 1.2/1.3 seuls (1.0/1.1 et chiffrements CBC/SHA1 refusés), confidentialité persistante
(ECDHE + AES-GCM / ChaCha20), HSTS ; testssl.sh 3.2.1 : aucune vulnérabilité TLS connue (Heartbleed, ROBOT,
POODLE, BEAST, SWEET32, LOGJAM, DROWN…) ; 34 sondes HTTP (fichiers
sensibles, documentation d'API, traversées de chemin encodées, casse, méthodes, CORS, en-tête Host forgé,
redirections OAuth malveillantes, corps de 22 Mo, routes internes) → toutes refusées comme prévu ; clé SSH
de déverrouillage limitée à `etat|ouvrir` (ni shell, ni redirection, ni injection) ; APK publié : non
débogable, sans sauvegarde, sans trafic en clair, aucune adresse ni secret dans le code. **Corrigé** : le
filtre réseau du nom interne était ignoré (un `respond` de Caddy passe après les blocs `handle`) ; un
jeton OAuth d'un autre client que l'app pouvait appairer un appareil.

## Dépendances

Versions épinglées (`server/requirements.txt`, `android/app/build.gradle.kts`), vérifiées contre les bases de
vulnérabilités publiques (`pip-audit`, OSV) ; image Docker à reconstruire régulièrement pour les correctifs
du système de base.
