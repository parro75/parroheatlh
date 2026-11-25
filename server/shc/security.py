"""Primitives sans dépendance : hachage de mot de passe (scrypt) et TOTP (RFC 6238)."""

from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import struct
import time

_SCRYPT_N, _SCRYPT_R, _SCRYPT_P = 2**15, 8, 1
TOTP_STEP = 30
TOTP_DIGITS = 6


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.scrypt(password.encode(), salt=salt, n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P, maxmem=64 * 1024 * 1024)
    b64 = lambda b: base64.b64encode(b).decode()  # noqa: E731
    return f"scrypt${_SCRYPT_N}${_SCRYPT_R}${_SCRYPT_P}${b64(salt)}${b64(dk)}"


def verify_password(password: str, encoded: str) -> bool:
    try:
        algo, n, r, p, salt, expected = encoded.split("$")
        if algo != "scrypt":
            return False
        dk = hashlib.scrypt(
            password.encode(), salt=base64.b64decode(salt), n=int(n), r=int(r), p=int(p), maxmem=64 * 1024 * 1024
        )
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(dk, base64.b64decode(expected))


def new_totp_secret() -> str:
    return base64.b32encode(secrets.token_bytes(20)).decode().rstrip("=")


def _b32(secret: str) -> bytes:
    s = secret.strip().replace(" ", "").upper()
    return base64.b32decode(s + "=" * (-len(s) % 8))


def totp_at(secret: str, step: int) -> str:
    digest = hmac.new(_b32(secret), struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF) % 10**TOTP_DIGITS
    return f"{code:0{TOTP_DIGITS}d}"


def current_step(now: float | None = None) -> int:
    return int((time.time() if now is None else now) // TOTP_STEP)


def match_totp(secret: str, code: str, now: float | None = None, window: int = 1) -> int | None:
    """Pas de temps correspondant au code (±window), ou None. L'appelant refuse les pas déjà utilisés."""
    code = code.strip().replace(" ", "")
    if len(code) != TOTP_DIGITS or not code.isdigit():
        return None
    base = current_step(now)
    found = None
    for step in range(base - window, base + window + 1):
        if hmac.compare_digest(totp_at(secret, step), code):
            found = step
    return found


def otpauth_uri(secret: str, account: str, issuer: str) -> str:
    from urllib.parse import quote

    return (
        f"otpauth://totp/{quote(issuer)}:{quote(account)}?secret={secret}&issuer={quote(issuer)}"
        f"&algorithm=SHA1&digits={TOTP_DIGITS}&period={TOTP_STEP}"
    )


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()
