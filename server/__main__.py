"""Start the dev server, reachable from other devices in the same network:
    python -m server            (from the project root)
"""
import os
import socket

from .app import create_app


def lan_ip():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.connect(("10.255.255.255", 1))
            return sock.getsockname()[0]
        except OSError:
            return "127.0.0.1"


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8000))
    print(f"\n  Momentum läuft:  http://localhost:{port}")
    print(f"  Am Handy (gleiches WLAN):  http://{lan_ip()}:{port}")
    print(f"  Android-App laden:         http://{lan_ip()}:{port}/app.apk\n")
    create_app().run(host="0.0.0.0", port=port, debug=False)
