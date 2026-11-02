#!/usr/bin/env python3
"""Select a domestic frpc transport only before its first registration.

The overseas frpc configuration, admin port and transport state are separate.
This process never reads, queues or retries vehicle commands.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import threading
import time
import urllib.request

from render import BIN, CONFIG, admin_password


EXPECTED_PROXIES = {"board-cn-ssh", "board-cn-whep"}
STARTUP_QUIC_DEADLINE_SECONDS = 10.0
STATE_FILE = Path("/var/lib/followingcar-cn/transport-selection.json")
API_URL = "http://127.0.0.1:17401/api/status"


def registered_proxy_names(payload: object) -> set[str]:
    if not isinstance(payload, dict) or (payload and "tcp" not in payload):
        raise ValueError("invalid_frpc_status")
    rows = payload.get("tcp", [])
    if not isinstance(rows, list):
        raise ValueError("invalid_frpc_tcp_status")
    return {row.get("name") for row in rows
            if isinstance(row, dict) and row.get("name") in EXPECTED_PROXIES
            and row.get("status") == "running"}


@dataclass
class StartupFallback:
    started: float
    protocol: str = "quic"
    ever_registered: bool = False

    def observe(self, now: float, registered: set[str] | None) -> bool:
        if registered:
            self.ever_registered = True
        if (self.protocol == "quic" and not self.ever_registered
                and registered is not None and not registered
                and now - self.started >= STARTUP_QUIC_DEADLINE_SECONDS):
            self.protocol = "tcp"
            return True
        return False


def read_status(token: str) -> set[str]:
    credential = base64.b64encode(("followingcar-cn:" + admin_password(token)).encode()).decode()
    request = urllib.request.Request(API_URL, headers={"Authorization": "Basic " + credential})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=.5) as response:
        data = response.read(65537)
    if len(data) > 65536:
        raise ValueError("frpc_status_too_large")
    return registered_proxy_names(json.loads(data))


def stop_child(child: subprocess.Popen) -> None:
    if child.poll() is None:
        child.terminate()
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=3)


def persist_tcp(boot_id: str) -> None:
    STATE_FILE.parent.mkdir(mode=0o750, exist_ok=True)
    path = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=STATE_FILE.parent,
                                         prefix=".transport-", delete=False) as stream:
            path = Path(stream.name)
            os.fchmod(stream.fileno(), 0o600)
            json.dump({"bootId": boot_id, "transport": "tcp"}, stream)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(path, STATE_FILE)
    finally:
        if path is not None and path.exists():
            path.unlink()


def main() -> None:
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    token = (Path(CONFIG) / "frp-token").read_text().strip()
    boot_id = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    preferred = "quic"
    if STATE_FILE.exists():
        try:
            state = json.loads(STATE_FILE.read_text())
            if state.get("bootId") == boot_id and state.get("transport") == "tcp":
                preferred = "tcp"
        except (ValueError, AttributeError):
            pass
    policy = StartupFallback(time.monotonic(), preferred)
    child = None
    try:
        while not stop.is_set():
            child = subprocess.Popen((f"{BIN}/frpc", "-c", f"{CONFIG}/frpc-{policy.protocol}.toml"),
                                     stdin=subprocess.DEVNULL)
            print(json.dumps({"event": "cn_relay.transport_started", "transport": policy.protocol}),
                  flush=True)
            previous = None
            while not stop.wait(.5):
                if child.poll() is not None:
                    raise RuntimeError("owned_domestic_frpc_exited")
                try:
                    registered = read_status(token)
                except Exception:
                    registered = None
                if registered != previous:
                    print(json.dumps({"event": "cn_relay.registration",
                                      "transport": policy.protocol,
                                      "registered": sorted(registered) if registered is not None else None}),
                          flush=True)
                    previous = registered
                if policy.observe(time.monotonic(), registered):
                    stop_child(child)
                    child = None
                    persist_tcp(boot_id)
                    print(json.dumps({"event": "cn_relay.transport_fallback",
                                      "transport": "tcp", "source": "initial_quic_registration_timeout"}),
                          flush=True)
                    break
            else:
                continue
            if stop.is_set():
                break
    finally:
        if child is not None:
            stop_child(child)
        print(json.dumps({"event": "cn_relay.transport_closed"}), flush=True)


if __name__ == "__main__":
    main()
