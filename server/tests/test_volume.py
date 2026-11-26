"""Garde du volume chiffré : sans le fichier marqueur, le service refuse de démarrer."""

import pytest

from shc import config


def _env(monkeypatch, tmp_path, marker):
    monkeypatch.setenv("INGEST_TOKEN", "i" * 40)
    monkeypatch.setenv("PUBLIC_URL", "https://sante.example.org")
    monkeypatch.setenv("OWNER_PASSWORD_HASH", "scrypt$x")
    monkeypatch.setenv("TOTP_SECRET", "A" * 32)
    monkeypatch.setenv("DB_PATH", str(tmp_path / "health.db"))
    if marker:
        monkeypatch.setenv("DATA_MARKER", marker)
    else:
        monkeypatch.delenv("DATA_MARKER", raising=False)


def test_without_marker_setting_starts(monkeypatch, tmp_path):
    _env(monkeypatch, tmp_path, None)
    assert config.load().db_path.endswith("health.db")


def test_marker_missing_refuses(monkeypatch, tmp_path):
    _env(monkeypatch, tmp_path, ".volume-chiffre")
    with pytest.raises(SystemExit, match="non monté"):
        config.load()


def test_marker_present_starts(monkeypatch, tmp_path):
    _env(monkeypatch, tmp_path, ".volume-chiffre")
    (tmp_path / ".volume-chiffre").write_text("")
    assert config.load().db_path == str(tmp_path / "health.db")
