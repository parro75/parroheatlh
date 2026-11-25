"""Sonde Docker : GET /healthz par le socket Unix (UNIX_SOCKET) ou par TCP. Code 0 si 200."""

import os
import socket
import sys

sock = os.environ.get("UNIX_SOCKET")
s = socket.socket(socket.AF_UNIX if sock else socket.AF_INET)
s.settimeout(3)
try:
    s.connect(sock if sock else ("127.0.0.1", int(os.environ.get("PORT", "8080"))))
    s.sendall(b"GET /healthz HTTP/1.0\r\nHost: localhost\r\n\r\n")
    sys.exit(0 if b" 200 " in s.recv(64) else 1)
except OSError:
    sys.exit(1)
