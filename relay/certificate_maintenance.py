#!/usr/bin/env python3
"""Report pending TLS maintenance without restarting relay or media services."""
from __future__ import annotations

import json
from pathlib import Path
import time

from configuration import CONFIG_ROOT, RelaySettings


def network_busy(settings: RelaySettings, proc_net: Path = Path("/proc/net")) -> bool:
    """Read-only, conservative check; any unreadable/invalid table is busy."""
    for name in ("tcp", "tcp6", "udp", "udp6"):
        try:
            lines = (proc_net / name).read_text().splitlines()[1:]
        except FileNotFoundError:
            if name.endswith("6"):
                continue
            return True
        except OSError:
            return True
        for line in lines:
            try:
                fields = line.split()
                port = int(fields[1].rsplit(":", 1)[1], 16)
                state = fields[3]
            except (ValueError, IndexError):
                return True
            if name.startswith("tcp"):
                # Include handshake/closing states. Only LISTEN and TIME_WAIT are idle.
                if port in (settings.control_port, settings.video_port) and state not in ("0A", "06"):
                    return True
            elif settings.relay_min <= port <= settings.relay_max:
                # An allocated TURN socket remains busy until Coturn releases it.
                return True
    return False


def maintain_if_idle(busy, restart, active, now=time.monotonic, sleep=time.sleep,
                     idle_seconds: float = 5.0) -> str:
    """Observe activity for reporting only; a quiet snapshot cannot authorize restart.

    ``restart`` and ``active`` remain for caller compatibility and are deliberately
    never invoked. New clients can arrive after any network check, so unattended
    maintenance must retain the pending marker for a coordinated manual window.
    """
    start = now()
    while True:
        if busy():
            return "busy_deferred"
        if now() - start >= idle_seconds:
            break
        sleep(0.5)
    # This final snapshot is informational, not a lock against new connections.
    return "busy_deferred" if busy() else "manual_maintenance_required"


def main() -> None:
    root = Path(CONFIG_ROOT)
    pending = root / "certificate-maintenance-pending.json"
    if not pending.exists():
        return
    settings = RelaySettings(**json.loads((root / "settings.json").read_text()))
    settings.validate()
    hold = root / "maintenance.hold"

    def busy():
        return hold.exists() or network_busy(settings)

    outcome = maintain_if_idle(busy, restart=None, active=None)
    record = {"event": "relay.certificate_maintenance", "outcome": outcome, "timeUnix": time.time()}
    report = root / "certificate-maintenance-last.json"
    temporary = report.with_suffix(".new")
    temporary.write_text(json.dumps(record) + "\n")
    temporary.replace(report)
    # Never clear pending here: reporting cannot confirm that frps/Coturn loaded
    # the new certificate. Only coordinated manual maintenance can do that.
    print(json.dumps(record), flush=True)


if __name__ == "__main__":
    main()
