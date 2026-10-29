#!/usr/bin/env python3
"""Install the independent frpc transport, never modifying the car/video runtime."""
import argparse
import json
import os
from pathlib import Path
import platform

from configuration import (CONFIG_ROOT, INSTALL_ROOT, FRP_VERSION, RelaySettings,
                           admin_password, frpc_config, mediamtx_turn_overlay, systemd_unit, validate_secret)
from provision import atomic_write, download_frp, ensure_account, run


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--transport", choices=("quic", "tcp"), default="quic")
    parser.add_argument("--video-local-port", type=int, choices=(8889, 19889), default=19889)
    parser.add_argument("--activate", action="store_true")
    args = parser.parse_args()
    if os.geteuid() != 0 or platform.machine() not in ("aarch64", "arm64"):
        raise SystemExit("Run as root on the ARM64 development board")
    if args.bundle.stat().st_size > 16384:
        raise SystemExit("Board provisioning bundle is too large")
    payload = json.loads(args.bundle.read_text())
    if (set(payload) != {"schemaVersion", "settings", "frpToken", "turnAuthSecret"}
            or type(payload["schemaVersion"]) is not int or payload["schemaVersion"] != 1):
        raise SystemExit("Unsupported board provisioning bundle")
    settings = RelaySettings(**payload["settings"])
    settings.validate()
    validate_secret(payload["frpToken"])
    validate_secret(payload["turnAuthSecret"])
    os.umask(0o077)
    ensure_account()
    download_frp("arm64", "frpc")
    config = Path(CONFIG_ROOT)
    atomic_write(config / "frp-token", payload["frpToken"])
    password = admin_password(payload["frpToken"])
    for transport in ("quic", "tcp"):
        atomic_write(config / f"frpc-{transport}.toml",
                     frpc_config(settings, transport, password, args.video_local_port))
    atomic_write(config / "preferred-transport", args.transport + "\n")
    for name in ("transport_supervisor.py", "configuration.py"):
        atomic_write(Path(INSTALL_ROOT) / "tools" / name,
                     Path(__file__).with_name(name).read_bytes(), mode=0o644, service_group=False)
    # This overlay is for the independent gateway, never the existing camera runtime.
    atomic_write(config / "gateway-turn-overlay.yml",
                 mediamtx_turn_overlay(settings, payload["turnAuthSecret"]))
    atomic_write(Path("/etc/systemd/system/followingcar-frpc.service"), systemd_unit("frpc"),
                 mode=0o644, service_group=False)
    for transport in ("quic", "tcp"):
        run(str(Path(INSTALL_ROOT) / "frp" / FRP_VERSION / "frpc"), "verify", "-c", str(config / f"frpc-{transport}.toml"))
    run("systemctl", "daemon-reload")
    if args.activate:
        run("systemctl", "enable", "--now", "followingcar-frpc.service")
        run("systemctl", "is-active", "--quiet", "followingcar-frpc.service")
    print(json.dumps({"installed": True, "active": args.activate, "transport": args.transport,
                      "videoLocalPort": args.video_local_port, "motorAccess": False,
                      "videoConfigChanged": False, "networkRoutesChanged": False}))


if __name__ == "__main__":
    main()
