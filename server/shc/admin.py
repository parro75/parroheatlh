"""Administration : python -m shc.admin <commande>

  init            génère INGEST_TOKEN et TOTP_SECRET, demande le mot de passe, écrit les lignes .env
  hash-password   demande un mot de passe et affiche son hachage (OWNER_PASSWORD_HASH)
  totp-uri        affiche l'URI otpauth:// de TOTP_SECRET (à scanner ou saisir dans l'appli TOTP)
  revoke-all      révoque tous les jetons OAuth émis (Claude devra se reconnecter)
"""

from __future__ import annotations

import getpass
import os
import secrets
import sqlite3
import sys

from .security import hash_password, new_totp_secret, otpauth_uri

MIN_PASSWORD = 16


def _ask_password() -> str:
    if not sys.stdin.isatty():
        pw = sys.stdin.readline().rstrip("\n")
    else:
        pw = getpass.getpass("Mot de passe du propriétaire : ")
        if getpass.getpass("Encore une fois : ") != pw:
            raise SystemExit("Les deux saisies diffèrent.")
    if len(pw) < MIN_PASSWORD:
        raise SystemExit(f"Mot de passe trop court ({MIN_PASSWORD} caractères minimum).")
    return pw


def main(argv: list[str]) -> None:
    cmd = argv[0] if argv else ""
    if cmd == "init":
        totp = new_totp_secret()
        print(f"INGEST_TOKEN={secrets.token_urlsafe(32)}")
        print(f"OWNER_PASSWORD_HASH={hash_password(_ask_password())}")
        print(f"TOTP_SECRET={totp}")
    elif cmd == "hash-password":
        print(hash_password(_ask_password()))
    elif cmd == "totp-uri":
        secret = os.environ.get("TOTP_SECRET") or raise_(SystemExit("TOTP_SECRET absent"))
        host = os.environ.get("PUBLIC_URL", "sante").split("//")[-1]
        print(otpauth_uri(secret, account="proprietaire", issuer=f"Santé {host}"))
    elif cmd == "revoke-all":
        conn = sqlite3.connect(os.environ.get("DB_PATH", "/data/health.db"), isolation_level=None)
        n = conn.execute("DELETE FROM oauth_tokens").rowcount
        print(f"{n} jeton(s) révoqué(s).")
    else:
        print(__doc__)
        raise SystemExit(2)


def raise_(exc: BaseException):
    raise exc


if __name__ == "__main__":
    main(sys.argv[1:])
