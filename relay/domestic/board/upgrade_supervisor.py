#!/usr/bin/env python3
"""Upgrade only the domestic frpc to initial-registration QUIC fallback.

The operation checks the exact original domestic files, acquires the existing
control-idle guard, and restarts only followingcar-cn-frpc. No overseas relay,
video gateway, camera or car-control service is changed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import pwd
import stat
import tempfile

from install import (FRPC_SHA256, FRPC_UNIT, UNIT_DIR, active, checked_binary,
                     control_idle_guard, ensure_directory, run, write_owned)
from render import (ACCOUNT, BIN, CONFIG, admin_password,
                    legacy_direct_frpc_unit, systemd_unit)


LEGACY_CONFIG_SHA256 = {
    "quic": "5eb79c1570b853852d22e8b742d7f0ee26f605f45818cb8d671118758c2b94bf",
    "tcp": "ff4e7941613f5650bac1f864cfafcc340150f7ec369c4a3802efd830c4ca5859",
}
ADMIN_MARKER = 'log.level = "warn"\n\n[[proxies]]'


def add_private_status_api(old_config: str, token: str) -> str:
    if old_config.count(ADMIN_MARKER) != 1:
        raise ValueError("unexpected_old_domestic_frpc_config")
    admin = f'''log.level = "warn"

webServer.addr = "127.0.0.1"
webServer.port = 17401
webServer.user = "followingcar-cn"
webServer.password = "{admin_password(token)}"
webServer.pprofEnable = false

[[proxies]]'''
    return old_config.replace(ADMIN_MARKER, admin)


def atomic_replace(path: Path, content: bytes, mode: int, gid: int) -> None:
    # The caller already checked this exact file's contents, owner and mode.
    temp = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".followingcar-cn-",
                                         delete=False) as output:
            temp = Path(output.name)
            os.fchown(output.fileno(), 0, gid)
            os.fchmod(output.fileno(), mode)
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp, path)
    finally:
        if temp is not None and temp.exists():
            temp.unlink()


def preflight() -> tuple[int, dict[Path, bytes], dict[Path, bytes]]:
    account = pwd.getpwnam(ACCOUNT)
    if account.pw_shell != "/usr/sbin/nologin":
        raise ValueError("unexpected_service_account")
    checked_binary(Path(BIN) / "frpc", FRPC_SHA256)
    token_path = Path(CONFIG) / "frp-token"
    token_info = token_path.lstat()
    if (not stat.S_ISREG(token_info.st_mode) or token_info.st_uid != 0 or
            token_info.st_gid != account.pw_gid or
            stat.S_IMODE(token_info.st_mode) != 0o640):
        raise ValueError("unexpected_domestic_token_file")
    token = token_path.read_text().strip()
    admin_password(token)  # Validates without logging the token.
    originals = {}
    replacements = {}
    for transport in ("quic", "tcp"):
        path = Path(CONFIG) / f"frpc-{transport}.toml"
        data = path.read_bytes()
        old_digest = hashlib.sha256(data).hexdigest()
        if old_digest != LEGACY_CONFIG_SHA256[transport]:
            raise ValueError("unexpected_domestic_frpc_config")
        originals[path] = data
        replacements[path] = add_private_status_api(data.decode(), token).encode()
    unit = UNIT_DIR / FRPC_UNIT
    original_unit = unit.read_bytes()
    if original_unit != legacy_direct_frpc_unit().encode():
        raise ValueError("unexpected_domestic_frpc_unit")
    originals[unit] = original_unit
    replacements[unit] = systemd_unit("frpc").encode()
    for path in originals:
        info = path.lstat()
        mode = 0o644 if path == unit else 0o640
        gid = 0 if path == unit else account.pw_gid
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or
                info.st_gid != gid or stat.S_IMODE(info.st_mode) != mode):
            raise ValueError("unexpected_domestic_file_ownership")
    return account.pw_gid, originals, replacements


def upgrade() -> None:
    gid, originals, replacements = preflight()
    ensure_directory(Path("/opt/followingcar-cn/tools"), gid)
    for name in ("render.py", "transport_supervisor.py"):
        write_owned(Path("/opt/followingcar-cn/tools") / name,
                    Path(__file__).with_name(name).read_bytes(), 0o440, gid)
    # Hold the control locks across the only service interruption.
    with control_idle_guard():
        was_active = active(FRPC_UNIT)
        changed = []
        try:
            if was_active:
                run("systemctl", "stop", FRPC_UNIT)
            for path, content in replacements.items():
                atomic_replace(path, content, 0o644 if path == UNIT_DIR / FRPC_UNIT else 0o640,
                               0 if path == UNIT_DIR / FRPC_UNIT else gid)
                changed.append(path)
            run("/usr/sbin/runuser", "-u", ACCOUNT, "--", f"{BIN}/frpc", "verify",
                "-c", f"{CONFIG}/frpc-quic.toml")
            run("/usr/sbin/runuser", "-u", ACCOUNT, "--", f"{BIN}/frpc", "verify",
                "-c", f"{CONFIG}/frpc-tcp.toml")
            run("systemd-analyze", "verify", str(UNIT_DIR / FRPC_UNIT))
            run("systemctl", "daemon-reload")
            if was_active:
                run("systemctl", "start", FRPC_UNIT)
                if not active(FRPC_UNIT):
                    raise RuntimeError("domestic_supervisor_failed_to_start")
        except Exception:
            if active(FRPC_UNIT):
                run("systemctl", "stop", FRPC_UNIT)
            for path in reversed(changed):
                atomic_replace(path, originals[path],
                               0o644 if path == UNIT_DIR / FRPC_UNIT else 0o640,
                               0 if path == UNIT_DIR / FRPC_UNIT else gid)
            run("systemctl", "daemon-reload")
            if was_active:
                run("systemctl", "start", FRPC_UNIT)
            raise
    print(json.dumps({"upgraded": True, "domesticFrpcActive": was_active,
                      "overseasRelayChanged": False, "videoGatewayChanged": False,
                      "motorAccess": False}))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate the old domestic files only")
    args = parser.parse_args()
    if os.geteuid() != 0 or platform.machine() not in ("aarch64", "arm64"):
        raise SystemExit("root_on_arm64_board_required")
    if args.check:
        preflight()
        print(json.dumps({"eligible": True, "domesticFrpcActive": active(FRPC_UNIT)}))
    else:
        upgrade()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"errorType": type(error).__name__, "upgraded": False}))
        raise SystemExit(1)
