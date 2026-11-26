"""Serveur d'autorisation OAuth 2.1 mono-utilisateur (propriétaire des données).

Le SDK MCP fournit les points d'entrée standard (/authorize, /token, /register, /revoke,
métadonnées, PKCE). Ce module fournit le reste :
- les clients sont enregistrés dynamiquement (DCR) mais SEULEMENT avec des URL de retour
  de la liste blanche (claude.ai / claude.com) ;
- /authorize renvoie vers /login : mot de passe (scrypt) + code TOTP, anti-rejeu,
  verrouillage après 5 échecs ;
- codes, jetons d'accès (1 h) et de rafraîchissement (30 j, à rotation, réutilisation =
  révocation de toute la famille) ne sont stockés que hachés (SHA-256).
"""

from __future__ import annotations

import html
import json
import logging
import secrets
import sqlite3
import threading
import time
from dataclasses import dataclass
from urllib.parse import parse_qs, urlparse

from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    AuthorizeError,
    RefreshToken,
    RegistrationError,
    TokenError,
    construct_redirect_uri,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken
from starlette.requests import Request
from starlette.responses import HTMLResponse, RedirectResponse, Response

from .security import match_totp, token_hash, verify_password

log = logging.getLogger("shc.auth")

SCOPE = "health:read"
SUBJECT = "owner"
PENDING_TTL = 600
CODE_TTL = 300
ACCESS_TTL = 3600
REFRESH_TTL = 30 * 86400
MAX_CLIENTS = 50
MAX_FAILURES = 5
LOCK_SECONDS = 900

SCHEMA = """
CREATE TABLE IF NOT EXISTS oauth_clients (client_id TEXT PRIMARY KEY, info TEXT NOT NULL, created_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS oauth_pending (id TEXT PRIMARY KEY, client_id TEXT NOT NULL, params TEXT NOT NULL,
                                          expires_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS oauth_codes (hash TEXT PRIMARY KEY, data TEXT NOT NULL, expires_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS oauth_tokens (hash TEXT PRIMARY KEY, kind TEXT NOT NULL, family TEXT NOT NULL,
                                         client_id TEXT NOT NULL, scopes TEXT NOT NULL, expires_at REAL NOT NULL,
                                         used INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS oauth_tokens_family ON oauth_tokens (family);
CREATE TABLE IF NOT EXISTS auth_state (key TEXT PRIMARY KEY, value REAL NOT NULL);
"""


@dataclass(frozen=True)
class OwnerAuth:
    password_hash: str
    totp_secret: str


class HealthOAuthProvider:
    """Implémente OAuthAuthorizationServerProvider (protocole du SDK MCP)."""

    def __init__(self, db_path: str, public_url: str, resource_url: str, owner: OwnerAuth,
                 redirect_allowlist: list[str], static_clients: list[OAuthClientInformationFull] | None = None):
        self._conn = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.executescript(SCHEMA)
        self._lock = threading.Lock()
        self.public_url = public_url.rstrip("/")
        self.resource_url = resource_url
        self.owner = owner
        # Liste blanche de l'enregistrement dynamique (DCR) : claude.ai / claude.com seulement.
        self.redirect_allowlist = set(redirect_allowlist)
        # Clients déclarés par configuration (l'app Android) : jamais créés par /register.
        self.static_clients = {c.client_id: c for c in (static_clients or [])}
        self.static_redirects = {str(u) for c in self.static_clients.values() for u in (c.redirect_uris or [])}
        self.audit = None  # shc.audit.AuditLog, posé par create_app (journal des accès)

    def _a(self, *args, **kw) -> None:
        if self.audit is not None:
            self.audit.record(*args, **kw)

    def actor_label(self, client_id: str) -> str:
        """« app » pour le client de l'app, « claude:<nom> » pour un client enregistré (Claude)."""
        if client_id in self.static_clients:
            return "app"
        rows = self._q("SELECT info FROM oauth_clients WHERE client_id = ?", (client_id,))
        name = json.loads(rows[0][0]).get("client_name") if rows else None
        return f"claude:{(name or client_id)[:40]}"

    # ---- outils --------------------------------------------------------
    def _q(self, sql: str, args: tuple = ()) -> list[tuple]:
        with self._lock:
            return self._conn.execute(sql, args).fetchall()

    def _x(self, sql: str, args: tuple = ()) -> int:
        with self._lock:
            return self._conn.execute(sql, args).rowcount

    def _purge(self) -> None:
        now = time.time()
        for table in ("oauth_pending", "oauth_codes", "oauth_tokens"):
            self._x(f"DELETE FROM {table} WHERE expires_at < ?", (now,))

    def close(self) -> None:
        self._conn.close()

    # ---- clients (DCR) -------------------------------------------------
    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        if client_id in self.static_clients:
            return self.static_clients[client_id]
        rows = self._q("SELECT info FROM oauth_clients WHERE client_id = ?", (client_id,))
        return OAuthClientInformationFull.model_validate_json(rows[0][0]) if rows else None

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        uris = [str(u) for u in (client_info.redirect_uris or [])]
        if not uris or any(u not in self.redirect_allowlist for u in uris):
            log.warning("enregistrement refusé : URL de retour hors liste blanche %s", uris)
            raise RegistrationError("invalid_redirect_uri", "redirect_uri non autorisée sur ce serveur")
        if self._q("SELECT COUNT(*) FROM oauth_clients")[0][0] >= MAX_CLIENTS:
            raise RegistrationError("invalid_client_metadata", "trop de clients enregistrés")
        self._x("INSERT INTO oauth_clients (client_id, info, created_at) VALUES (?, ?, ?)",
                (client_info.client_id, client_info.model_dump_json(), time.time()))
        log.info("client enregistré : %s (%s)", client_info.client_id, client_info.client_name)
        self._a("claude", "client-enregistre", f"{client_info.client_name or '?'} ({client_info.client_id[:12]})")

    # ---- autorisation --------------------------------------------------
    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        if str(params.redirect_uri) not in self.redirect_allowlist | self.static_redirects:
            raise AuthorizeError("invalid_request", "redirect_uri non autorisée")
        if params.scopes and any(s != SCOPE for s in params.scopes):
            raise AuthorizeError("invalid_scope", f"seule la portée {SCOPE} existe")
        self._purge()
        req_id = secrets.token_urlsafe(32)
        self._x("INSERT INTO oauth_pending (id, client_id, params, expires_at) VALUES (?, ?, ?, ?)",
                (req_id, client.client_id, params.model_dump_json(), time.time() + PENDING_TTL))
        return f"{self.public_url}/login?req={req_id}"

    def _pending(self, req_id: str) -> tuple[str, AuthorizationParams] | None:
        rows = self._q("SELECT client_id, params FROM oauth_pending WHERE id = ? AND expires_at > ?",
                       (req_id, time.time()))
        if not rows:
            return None
        return rows[0][0], AuthorizationParams.model_validate_json(rows[0][1])

    # ---- verrouillage --------------------------------------------------
    def _state(self, key: str, default: float = 0) -> float:
        rows = self._q("SELECT value FROM auth_state WHERE key = ?", (key,))
        return rows[0][0] if rows else default

    def _set_state(self, key: str, value: float) -> None:
        self._x("INSERT INTO auth_state (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                (key, value))

    def locked_for(self) -> int:
        return max(0, int(self._state("lock_until") - time.time()))

    def _fail(self) -> None:
        failures = self._state("failures") + 1
        if failures >= MAX_FAILURES:
            self._set_state("lock_until", time.time() + LOCK_SECONDS)
            failures = 0
            log.warning("connexion verrouillée %d s après %d échecs", LOCK_SECONDS, MAX_FAILURES)
            self._a("systeme", "connexion-verrouillee", f"{MAX_FAILURES} échecs : connexion bloquée {LOCK_SECONDS // 60} min", status=429)
        self._set_state("failures", failures)

    def check_owner(self, password: str, code: str, now: float | None = None) -> bool:
        """Mot de passe ET TOTP ; un pas TOTP déjà utilisé est refusé (anti-rejeu)."""
        ok_pw = verify_password(password, self.owner.password_hash)
        step = match_totp(self.owner.totp_secret, code, now=now)
        if not ok_pw or step is None or step <= self._state("totp_last_step", -1):
            self._fail()
            return False
        self._set_state("totp_last_step", step)
        self._set_state("failures", 0)
        return True

    def issue_code(self, req_id: str) -> str | None:
        """Consomme la demande en attente et renvoie l'URL de retour portant le code."""
        pending = self._pending(req_id)
        if pending is None or self._x("DELETE FROM oauth_pending WHERE id = ?", (req_id,)) != 1:
            return None
        client_id, params = pending
        code = secrets.token_urlsafe(32)
        ac = AuthorizationCode(
            code=code,
            scopes=params.scopes or [SCOPE],
            expires_at=time.time() + CODE_TTL,
            client_id=client_id,
            code_challenge=params.code_challenge,
            redirect_uri=params.redirect_uri,
            redirect_uri_provided_explicitly=params.redirect_uri_provided_explicitly,
            resource=params.resource,
            subject=SUBJECT,
        )
        self._x("INSERT INTO oauth_codes (hash, data, expires_at) VALUES (?, ?, ?)",
                (token_hash(code), ac.model_dump_json(), ac.expires_at))
        return construct_redirect_uri(str(params.redirect_uri), code=code, state=params.state)

    async def load_authorization_code(self, client: OAuthClientInformationFull, authorization_code: str
                                      ) -> AuthorizationCode | None:
        rows = self._q("SELECT data FROM oauth_codes WHERE hash = ? AND expires_at > ?",
                       (token_hash(authorization_code), time.time()))
        if not rows:
            return None
        ac = AuthorizationCode.model_validate_json(rows[0][0]).model_copy(update={"code": authorization_code})
        return ac if ac.client_id == client.client_id else None

    # ---- jetons ----------------------------------------------------------
    def _issue(self, client_id: str, scopes: list[str], family: str) -> OAuthToken:
        access, refresh = secrets.token_urlsafe(32), secrets.token_urlsafe(48)
        now = time.time()
        rows = [
            (token_hash(access), "access", family, client_id, json.dumps(scopes), now + ACCESS_TTL),
            (token_hash(refresh), "refresh", family, client_id, json.dumps(scopes), now + REFRESH_TTL),
        ]
        with self._lock:
            self._conn.executemany(
                "INSERT INTO oauth_tokens (hash, kind, family, client_id, scopes, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                rows)
        return OAuthToken(access_token=access, expires_in=ACCESS_TTL, refresh_token=refresh, scope=" ".join(scopes))

    async def exchange_authorization_code(self, client: OAuthClientInformationFull,
                                          authorization_code: AuthorizationCode) -> OAuthToken:
        # Usage unique : seul l'appel qui supprime réellement le code obtient des jetons.
        if self._x("DELETE FROM oauth_codes WHERE hash = ?", (token_hash(authorization_code.code),)) != 1:
            raise TokenError("invalid_grant", "code déjà utilisé")
        self._purge()
        log.info("jetons émis pour le client %s", client.client_id)
        self._a(self.actor_label(client.client_id), "jetons-emis", "connexion OAuth terminée")
        return self._issue(client.client_id, authorization_code.scopes, secrets.token_hex(16))

    async def load_refresh_token(self, client: OAuthClientInformationFull, refresh_token: str) -> RefreshToken | None:
        rows = self._q(
            "SELECT family, scopes, expires_at, used FROM oauth_tokens WHERE hash = ? AND kind = 'refresh' AND client_id = ?",
            (token_hash(refresh_token), client.client_id))
        if not rows or rows[0][2] < time.time():
            return None
        family, scopes, expires_at, used = rows[0]
        if used:
            # Jeton de rafraîchissement rejoué après rotation : vol probable, toute la famille saute.
            self._x("DELETE FROM oauth_tokens WHERE family = ?", (family,))
            log.warning("réutilisation d'un jeton de rafraîchissement : famille %s révoquée", family)
            self._a(self.actor_label(client.client_id), "jeton-rejoue", "jeton de rafraîchissement réutilisé : session révoquée", status=401)
            return None
        return RefreshToken(token=refresh_token, client_id=client.client_id, scopes=json.loads(scopes),
                            expires_at=int(expires_at), subject=SUBJECT)

    async def exchange_refresh_token(self, client: OAuthClientInformationFull, refresh_token: RefreshToken,
                                     scopes: list[str]) -> OAuthToken:
        h = token_hash(refresh_token.token)
        rows = self._q("SELECT family FROM oauth_tokens WHERE hash = ? AND used = 0", (h,))
        if not rows or self._x("UPDATE oauth_tokens SET used = 1 WHERE hash = ? AND used = 0", (h,)) != 1:
            raise TokenError("invalid_grant", "jeton de rafraîchissement invalide")
        family = rows[0][0]
        self._x("DELETE FROM oauth_tokens WHERE family = ? AND kind = 'access'", (family,))
        return self._issue(client.client_id, scopes or refresh_token.scopes, family)

    async def load_access_token(self, token: str) -> AccessToken | None:
        rows = self._q("SELECT client_id, scopes, expires_at FROM oauth_tokens WHERE hash = ? AND kind = 'access'",
                       (token_hash(token),))
        if not rows or rows[0][2] < time.time():
            return None
        client_id, scopes, expires_at = rows[0]
        return AccessToken(token=token, client_id=client_id, scopes=json.loads(scopes), expires_at=int(expires_at),
                           resource=self.resource_url, subject=SUBJECT)

    async def revoke_token(self, token: AccessToken | RefreshToken) -> None:
        rows = self._q("SELECT family FROM oauth_tokens WHERE hash = ?", (token_hash(token.token),))
        if rows:
            self._x("DELETE FROM oauth_tokens WHERE family = ?", (rows[0][0],))
            self._a(self.actor_label(token.client_id), "jetons-revoques", "déconnexion")

    def revoke_all(self) -> int:
        return self._x("DELETE FROM oauth_tokens")

    # ---- page de connexion ----------------------------------------------
    # Second facteur : clé d'accès WebAuthn (passkey, clé FIDO2) ou TOTP en secours, selon
    # self.second_factor : "any" (clé OU TOTP) | "passkey" (TOTP refusé dès qu'une clé existe).
    passkeys = None  # shc.passkeys.Passkeys, posé par create_app
    second_factor = "any"

    def _csp(self) -> str:
        origins = sorted({f"{urlparse(u).scheme}://{urlparse(u).netloc}" for u in self.redirect_allowlist}
                         | {f"{urlparse(u).scheme}:" for u in self.static_redirects})
        # form-action couvre aussi la redirection qui suit le POST (vers claude.ai).
        return ("default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; "
                "frame-ancestors 'none'; base-uri 'none'; "
                f"form-action 'self' {' '.join(origins)}")

    def _page(self, body: str, status: int = 200, headers: dict | None = None) -> HTMLResponse:
        doc = f"""<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>Parro Health — connexion</title>
<style>body{{font-family:system-ui,sans-serif;max-width:26rem;margin:3rem auto;padding:0 1rem;line-height:1.5}}
input,button{{font-size:1.1rem;width:100%;box-sizing:border-box;padding:.6rem;margin:.3rem 0 .9rem}}
.err{{color:#b00020}} small{{color:#555}} .ou{{text-align:center;color:#555;margin:.2rem 0}}
table{{width:100%;border-collapse:collapse}} td{{padding:.3rem 0;border-bottom:1px solid #ddd}}
td button{{width:auto;margin:0;padding:.3rem .6rem;font-size:.9rem}}</style></head><body>{body}</body></html>"""
        return HTMLResponse(doc, status_code=status, headers={
            "Content-Security-Policy": self._csp(),
            "Cache-Control": "no-store",
            "Referrer-Policy": "no-referrer",
            "X-Frame-Options": "DENY",
            **(headers or {}),
        })

    def _has_keys(self) -> bool:
        return self.passkeys is not None and self.passkeys.count() > 0

    def _second_factor_html(self, scope_field: str, scope_value: str) -> str:
        """Bouton clé d'accès (si au moins une clé) + champ TOTP (sauf si refusé par la politique)."""
        key_btn = (f'<input type="hidden" name="webauthn" value="">'
                   f'<button type="button" id="pk" data-{scope_field}="{html.escape(scope_value)}">'
                   f'Continuer avec ma clé d\'accès</button>') if self._has_keys() else ""
        totp_allowed = not (self.second_factor == "passkey" and self._has_keys())
        totp = ('<p class="ou">ou, en secours</p>' if key_btn else "") + (
            '<label>Code TOTP<input name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" '
            'autocomplete="one-time-code"' + ("" if key_btn else " required") + '></label>'
            '<button type="submit">Autoriser avec le code</button>') if totp_allowed else ""
        return key_btn + totp + ('<p class="err" id="pkerr"></p><script src="/login.js"></script>' if key_btn else "")

    def _form(self, req_id: str, client_name: str, redirect_host: str, error: str = "") -> HTMLResponse:
        err = f'<p class="err">{html.escape(error)}</p>' if error else ""
        return self._page(f"""<h1>Parro Health</h1>
<p><b>{html.escape(client_name)}</b> demande l'accès en <b>lecture</b> à tes données de santé.<br>
<small>Retour vers : {html.escape(redirect_host)}</small></p>{err}
<form method="post" action="/login" autocomplete="off" id="f">
<input type="hidden" name="req" value="{html.escape(req_id)}">
<label>Mot de passe<input type="password" name="password" autocomplete="current-password" required autofocus></label>
{self._second_factor_html("req", req_id)}</form>""")

    def _check(self, form: dict[str, str], challenge_key: str) -> tuple[bool, str]:
        """Mot de passe + second facteur. (succès, méthode). Tout échec compte pour le verrouillage."""
        from .passkeys import PasskeyError
        password = form.get("password", "")
        if form.get("webauthn"):
            if not self._has_keys():
                self._fail()
                return False, "clé"
            # Mot de passe d'abord : un mauvais mot de passe ne consomme ni le défi ni le compteur de la clé.
            if not verify_password(password, self.owner.password_hash):
                self._fail()
                return False, "clé"
            try:
                name = self.passkeys.verify(challenge_key, json.loads(form["webauthn"]))
            except (ValueError, PasskeyError):
                self._fail()
                return False, "clé"
            self._set_state("failures", 0)
            return True, f"clé « {name} »"
        if self.second_factor == "passkey" and self._has_keys():
            self._fail()
            return False, "TOTP refusé (clé exigée)"
        return self.check_owner(password, form.get("code", "")), "TOTP"

    async def _client_label(self, client_id: str) -> str:
        client = await self.get_client(client_id)
        return (client.client_name if client and client.client_name else client_id)

    @staticmethod
    def _ip(request: Request) -> str:
        return (request.headers.get("x-forwarded-for") or (request.client.host if request.client else "")).split(",")[0].strip()

    @staticmethod
    async def _form_body(request: Request, limit: int = 16384) -> dict[str, str] | None:
        body = await request.body()
        if len(body) > limit:
            return None
        return {k: v[0] for k, v in parse_qs(body.decode("utf-8", "replace")).items()}

    async def login_get(self, request: Request) -> Response:
        req_id = request.query_params.get("req", "")
        pending = self._pending(req_id)
        if pending is None:
            return self._page("<p>Demande expirée ou inconnue. Relance la connexion depuis Claude.</p>", 400)
        client_id, params = pending
        return self._form(req_id, await self._client_label(client_id), urlparse(str(params.redirect_uri)).netloc)

    async def login_post(self, request: Request) -> Response:
        form = await self._form_body(request)
        if form is None:
            return self._page("<p>Requête trop grande.</p>", 413)
        req_id = form.get("req", "")
        pending = self._pending(req_id)
        if pending is None:
            return self._page("<p>Demande expirée ou inconnue. Relance la connexion depuis Claude.</p>", 400)
        client_id, params = pending
        label, host = await self._client_label(client_id), urlparse(str(params.redirect_uri)).netloc
        if (wait := self.locked_for()) > 0:
            return self._page(f"<p class='err'>Trop d'essais. Réessaie dans {wait // 60 + 1} min.</p>", 429)
        ip = self._ip(request)
        ok, method = self._check(form, "req:" + req_id)
        if not ok:
            log.warning("échec de connexion (client %s, %s)", client_id, method)
            self._a("inconnu", "connexion-echec", f"pour {label} ({method})", ip=ip, status=401)
            return self._form(req_id, label, host, "Mot de passe, clé ou code incorrect.")
        target = self.issue_code(req_id)
        if target is None:
            return self._page("<p>Demande déjà utilisée.</p>", 400)
        log.info("connexion réussie (%s), code émis pour le client %s", method, client_id)
        self._a("proprietaire", "connexion", f"pour {label} ({method})", ip=ip)
        return RedirectResponse(target, status_code=302, headers={"Cache-Control": "no-store"})

    async def passkey_options(self, request: Request) -> Response:
        """Options WebAuthn d'authentification pour une demande de connexion (req) ou la page de gestion (gate)."""
        from starlette.responses import JSONResponse
        from .passkeys import PasskeyError
        form = await self._form_body(request, 2048) or {}
        if form.get("req"):
            if self._pending(form["req"]) is None:
                return JSONResponse({"error": "demande expirée"}, status_code=400)
            key = "req:" + form["req"]
        elif form.get("gate") and len(form["gate"]) >= 20:
            key = "gate:" + form["gate"]
        else:
            return JSONResponse({"error": "requête invalide"}, status_code=400)
        try:
            return Response(self.passkeys.authentication_options(key), media_type="application/json",
                            headers={"Cache-Control": "no-store"})
        except PasskeyError as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)

    # ---- gestion des clés d'accès (/passkeys) ---------------------------------------------------------
    COOKIE = "shc_cles"

    def _same_origin(self, request: Request) -> bool:
        return request.headers.get("origin", "") == self.public_url

    def _keys_gate(self, error: str = "") -> HTMLResponse:
        gate = secrets.token_urlsafe(24)
        err = f'<p class="err">{html.escape(error)}</p>' if error else ""
        return self._page(f"""<h1>Clés d'accès</h1>
<p>Gestion des clés de connexion (clé physique, passkey). Identifie-toi d'abord.</p>{err}
<form method="post" action="/passkeys" autocomplete="off" id="f">
<input type="hidden" name="gate" value="{gate}">
<label>Mot de passe<input type="password" name="password" autocomplete="current-password" required autofocus></label>
{self._second_factor_html("gate", gate)}</form>""")

    def _keys_page(self, msg: str = "") -> HTMLResponse:
        rows = "".join(
            f"<tr><td><b>{html.escape(k['name'] or 'clé')}</b><br><small>ajoutée le "
            f"{time.strftime('%d/%m/%Y', time.localtime(k['created']))} · dernière connexion "
            f"{time.strftime('%d/%m/%Y', time.localtime(k['last_used'])) if k['last_used'] else 'jamais'}</small></td>"
            f"<td><form method='post' action='/passkeys/supprimer'><input type='hidden' name='id' value='{html.escape(k['id'])}'>"
            f"<button type='submit'>Retirer</button></form></td></tr>" for k in self.passkeys.list())
        note = ("TOTP refusé dès qu'une clé existe." if self.second_factor == "passkey"
                else "Le code TOTP reste accepté en secours.")
        return self._page(f"""<h1>Clés d'accès</h1>{f"<p>{html.escape(msg)}</p>" if msg else ""}
<table>{rows or "<tr><td>Aucune clé enregistrée.</td></tr>"}</table>
<p><small>Enregistre au moins deux clés (une de secours). {note}</small></p>
<label>Nom de la nouvelle clé<input id="nom" maxlength="60" placeholder="YubiKey, 1Password, Samsung Pass…"></label>
<button type="button" id="ajouter">Ajouter une clé d'accès</button><p class="err" id="pkerr"></p>
<form method="post" action="/passkeys/fin"><button type="submit">Terminer</button></form>
<script src="/passkeys.js"></script>""")

    async def keys_get(self, request: Request) -> Response:
        if self.passkeys.session_ok(request.cookies.get(self.COOKIE)):
            return self._keys_page()
        return self._keys_gate()

    async def keys_post(self, request: Request) -> Response:
        form = await self._form_body(request)
        if form is None or not self._same_origin(request):
            return self._page("<p>Requête refusée.</p>", 400)
        if (wait := self.locked_for()) > 0:
            return self._page(f"<p class='err'>Trop d'essais. Réessaie dans {wait // 60 + 1} min.</p>", 429)
        ok, method = self._check(form, "gate:" + form.get("gate", ""))
        if not ok:
            self._a("inconnu", "connexion-echec", f"gestion des clés ({method})", ip=self._ip(request), status=401)
            return self._keys_gate("Mot de passe, clé ou code incorrect.")
        tok = self.passkeys.new_session()
        self._a("proprietaire", "connexion", f"gestion des clés ({method})", ip=self._ip(request))
        resp = RedirectResponse("/passkeys", status_code=303)
        resp.set_cookie(self.COOKIE, tok, max_age=600, httponly=True, secure=True, samesite="strict", path="/passkeys")
        return resp

    def _session_or_none(self, request: Request) -> str | None:
        tok = request.cookies.get(self.COOKIE)
        return tok if self.passkeys.session_ok(tok) and self._same_origin(request) else None

    async def keys_options(self, request: Request) -> Response:
        from starlette.responses import JSONResponse
        from .passkeys import PasskeyError
        tok = self._session_or_none(request)
        if tok is None:
            return JSONResponse({"error": "session expirée"}, status_code=401)
        try:
            return Response(self.passkeys.registration_options(tok), media_type="application/json",
                            headers={"Cache-Control": "no-store"})
        except PasskeyError as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)

    async def keys_register(self, request: Request) -> Response:
        from starlette.responses import JSONResponse
        from .passkeys import PasskeyError
        tok = self._session_or_none(request)
        if tok is None:
            return JSONResponse({"error": "session expirée"}, status_code=401)
        try:
            body = json.loads(await request.body())
            name = str(body.get("name", ""))[:60] or "clé"
            self.passkeys.register(tok, body["credential"], name)
        except (ValueError, KeyError, TypeError, PasskeyError) as exc:
            return JSONResponse({"error": str(exc)}, status_code=400)
        self._a("proprietaire", "cle-ajoutee", f"« {name} »", ip=self._ip(request))
        return JSONResponse({"ok": True})

    async def keys_delete(self, request: Request) -> Response:
        tok = self._session_or_none(request)
        form = await self._form_body(request, 2048)
        if tok is None or form is None:
            return RedirectResponse("/passkeys", status_code=303)
        names = {k["id"]: k["name"] for k in self.passkeys.list()}
        if self.passkeys.delete(form.get("id", "")):
            self._a("proprietaire", "cle-supprimee", f"« {names.get(form.get('id', ''), '?')} »", ip=self._ip(request))
        return self._keys_page("Clé retirée.")

    async def keys_end(self, request: Request) -> Response:
        self.passkeys.end_session(request.cookies.get(self.COOKIE))
        resp = self._page("<h1>Clés d'accès</h1><p>Session terminée. Tu peux fermer cette page.</p>")
        resp.delete_cookie(self.COOKIE, path="/passkeys")
        return resp

    async def static_js(self, request: Request) -> Response:
        from pathlib import Path
        name = request.url.path.lstrip("/")
        if name not in ("login.js", "passkeys.js"):
            return Response(status_code=404)
        return Response((Path(__file__).parent / "static" / name).read_text(), media_type="text/javascript",
                        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
