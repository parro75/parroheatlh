"""Lancement : python -m shc

- UNIX_SOCKET=/chemin/shc.sock : écoute sur un socket Unix (conteneur sans aucun réseau ; Caddy s'y
  connecte par un dossier partagé). Recommandé en production.
- sinon HOST:PORT (0.0.0.0:8080 par défaut).
"""

import logging
import os

import uvicorn

from .app import create_app


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    sock = os.environ.get("UNIX_SOCKET")
    common = dict(access_log=False)  # pas de journal d'accès : rien de sensible dans les journaux
    if sock:
        if os.path.exists(sock):
            os.remove(sock)  # socket résiduel d'un arrêt brutal
        # uvicorn crée le socket en 666 : la protection vient du DOSSIER (750, propriétaire 10001) et
        # du conteneur sans réseau.
        uvicorn.run(create_app(), uds=sock, **common)
    else:
        uvicorn.run(create_app(), host=os.environ.get("HOST", "0.0.0.0"), port=int(os.environ.get("PORT", "8080")),
                    proxy_headers=True, forwarded_allow_ips=os.environ.get("FORWARDED_ALLOW_IPS", "127.0.0.1"), **common)


if __name__ == "__main__":
    main()
