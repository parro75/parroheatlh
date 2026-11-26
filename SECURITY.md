# Sécurité

Parro Health manipule des **données de santé réelles**. Ce document résume le modèle de menace et les
protections en place ; le détail est dans le [README](README.md#données-et-confidentialité).

## Signaler une faille

Merci de **ne pas** ouvrir d'issue publique : utilise *Security → Report a vulnerability* (signalement privé
GitHub) sur ce dépôt. Réponse sous quelques jours.

## Ce qui est protégé, et contre quoi

| Menace | Protection |
|---|---|
| Écoute du réseau | HTTPS partout ; service sans réseau, joint par socket Unix ; trafic en clair interdit dans l'app |
| Accès non autorisé à l'API | 4 jetons distincts (écriture, lecture OAuth, job, Home Assistant), comparés en temps constant ; OAuth 2.1 + PKCE, mot de passe (scrypt) + TOTP anti-rejeu, verrouillage après 5 échecs ; jetons stockés hachés, rotation et révocation de famille sur rejeu |
| Exposition des routes | proxy qui n'ouvre que les routes listées ; MCP limité aux IP d'Anthropic ; routes du job et de Home Assistant sur un nom interne seulement |
| Vol ou recyclage du disque du serveur | base dans un volume LUKS2 dont la clé n'est jamais sur le serveur (`deploy/volume-chiffre/`) ; refus de démarrer si le volume n'est pas monté ; aucun swap pour le service |
| Fuite par les sauvegardes | sauvegardes chiffrées (`age`), clé privée hors du stockage des sauvegardes |
| Fuite par les journaux ou les notifications | aucune donnée de santé dans les journaux ; notifications locales, neutres si l'app est verrouillée ; rien ne passe par Google ni Home Assistant |
| Vol du téléphone | jetons chiffrés par le Keystore ; sauvegarde et transfert d'appareil exclus ; verrouillage biométrique facultatif (masque aussi le widget et la miniature des applis récentes) |
| Appli malveillante sur le téléphone | composants exportés réduits au nécessaire ; import d'un zip partagé seulement après confirmation ; FileProvider non exporté ; PKCE contre le détournement du retour OAuth |
| Injection de consignes (prompt injection) | textes libres plafonnés (note 500, titre 200 caractères) et déclarés comme des **données** à Claude (MCP) et au modèle local ; les signaux des rapports sont calculés par règles, jamais par le modèle |
| Fichiers piégés | export reçu par morceaux, taille et SHA-256 vérifiés, lu en flux sans extraction, JSON géants ignorés ; écrasé puis supprimé après import |
| Secrets dans le dépôt | aucun (`.env`, bases, APK ignorés) ; historique vérifié par gitleaks |

## Limites connues (assumées)

- Un administrateur du serveur peut lire la base **pendant** qu'elle est ouverte : aucun chiffrement au repos
  ne l'empêche tant que le service doit lire les données.
- Le verrouillage de la page de connexion est global : un tiers peut bloquer les **nouvelles** connexions
  pendant 15 min (sans pouvoir entrer, et sans couper l'app ni Claude déjà connectés).
- Le jeton d'écriture permet d'ajouter et de supprimer des mesures : sa fuite compromettrait l'intégrité
  (pas la confidentialité) ; les sauvegardes quotidiennes permettent de revenir en arrière.
- Pas d'épinglage de certificat dans l'app (TLS standard ; Android n'accepte pas les autorités ajoutées par
  l'utilisateur).

## Dépendances

Versions épinglées (`server/requirements.txt`, `android/app/build.gradle.kts`), vérifiées contre les bases de
vulnérabilités publiques (`pip-audit`, OSV) ; image Docker à reconstruire régulièrement pour les correctifs
du système de base.
