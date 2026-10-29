#!/usr/bin/env python3
"""Choose QUIC first, or TCP after a proven initial registration failure.

The local frpc API is authenticated and loopback-only. Once a proxy has ever
registered during this service run, no transport switch is permitted. This
supervisor never reads, produces, buffers, or retries a vehicle command.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
import time
import urllib.request

from configuration import CONFIG_ROOT, FRP_VERSION, INSTALL_ROOT, admin_password


EXPECTED_PROXIES = {"board-ssh", "board-whep"}
STARTUP_QUIC_DEADLINE_SECONDS = 10.0


def registered_proxy_names(payload: object) -> set[str]:
    if not isinstance(payload, dict):
        raise ValueError("Invalid frpc status")
    if payload and "tcp" not in payload:
        raise ValueError("Unexpected frpc status schema")
    rows = payload.get("tcp", [])
    if not isinstance(rows, list):
        raise ValueError("Invalid frpc TCP status")
    return {row.get("name") for row in rows if isinstance(row, dict)
            and row.get("name") in EXPECTED_PROXIES and row.get("status") == "running"}


@dataclass
class StartupFallback:
    started: float
    protocol: str = "quic"
    ever_registered: bool = False

    def observe(self, now: float, registered: set[str] | None) -> bool:
        """Return true once to request fallback, never on an unreadable status."""
        if registered:
            self.ever_registered = True
        if (self.protocol == "quic" and not self.ever_registered
                and registered is not None and not registered
                and now - self.started >= STARTUP_QUIC_DEADLINE_SECONDS):
            self.protocol = "tcp"
            return True
        return False


def emit(event: str, **fields) -> None:
    print(json.dumps({"event": event, **fields}), flush=True)


def read_status(token: str) -> set[str]:
    credential = base64.b64encode(("followingcar:" + admin_password(token)).encode()).decode()
    request = urllib.request.Request("http://127.0.0.1:17400/api/status",
                                     headers={"Authorization": "Basic " + credential})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=0.5) as response:
        payload = response.read(65537)
    if len(payload) > 65536:
        raise ValueError("Oversized frpc status")
    return registered_proxy_names(json.loads(payload))


def stop_child(child: subprocess.Popen) -> None:
    if child.poll() is None:
        child.terminate()
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=3)


def main() -> None:
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    config = Path(CONFIG_ROOT)
    token = (config / "frp-token").read_text().strip()
    preferred = (config / "preferred-transport").read_text().strip()
    if preferred not in {"quic", "tcp"}:
        raise SystemExit("Unsupported preferred transport")
    boot_id = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    state_file = Path("/var/lib/followingcar-relay/transport-selection.json")
    if state_file.exists():
        stored = json.loads(state_file.read_text())
        if stored.get("bootId") == boot_id and stored.get("transport") == "tcp":
            preferred = "tcp"
    policy = StartupFallback(time.monotonic(), preferred)
    executable = str(Path(INSTALL_ROOT) / "frp" / FRP_VERSION / "frpc")
    child = None
    try:
        while not stop.is_set():
            child = subprocess.Popen([executable, "-c", str(config / f"frpc-{policy.protocol}.toml")])
            emit("relay.transport_started", transport=policy.protocol)
            previous = None
            while not stop.wait(0.5):
                if child.poll() is not None:
                    raise RuntimeError("Owned frpc process exited")
                try:
                    registered = read_status(token)
                except Exception:
                    registered = None
                if registered != previous:
                    emit("relay.registration", transport=policy.protocol,
                         registered=sorted(registered) if registered is not None else None)
                    previous = registered
                if policy.observe(time.monotonic(), registered):
                    stop_child(child)
                    child = None
                    state_file.write_text(json.dumps({"bootId": boot_id, "transport": "tcp"}) + "\n")
                    os.chmod(state_file, 0o600)
                    emit("relay.transport_fallback", transport="tcp", source="initial_quic_registration_timeout")
                    break
            else:
                continue
            if stop.is_set():
                break
    finally:
        if child is not None:
            stop_child(child)
        emit("relay.transport_closed")


if __name__ == "__main__":
    main()
