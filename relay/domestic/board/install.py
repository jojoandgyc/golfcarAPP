#!/usr/bin/env python3
"""Stage an additional domestic board relay; activation is explicit.

Only files under /etc/followingcar-cn, /opt/followingcar-cn and two uniquely
named systemd units are created. The existing relay, camera and car runtime
are never rewritten, restarted or stopped.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import pwd
import socket
import stat
import subprocess

from render import (ACCOUNT, BIN, CONFIG, FRPC_UNIT, GATEWAY_UNIT,
                    frpc_config, gateway_config, systemd_unit, validate_bundle)


FRPC_SOURCE = Path("/opt/followingcar-relay/frp/0.71.1/frpc")
FRPC_SHA256 = "651de3428bdc7f37b4bad76ed3236b221939a989c433cd7daee79d2817519f8b"
GATEWAY_SOURCE = Path("/opt/followingcar-relay/mediamtx/1.21.3/mediamtx")
GATEWAY_SHA256 = "a1621ba39c0aa58430809df28263865e37a75bb4d2f1c87e642ddf5f3f67b19d"
UNIT_DIR = Path("/etc/systemd/system")


def run(*args: str) -> None:
    result = subprocess.run(args, stdin=subprocess.DEVNULL, capture_output=True,
                            timeout=25, check=False)
    if result.returncode:
        # A tool error can include configuration text or credentials; never emit it.
        raise RuntimeError(f"command_failed:{Path(args[0]).name}:{result.returncode}")


def read_bundle(path: Path) -> dict:
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or
                info.st_mode & 0o077 or info.st_size > 16384):
            raise ValueError("private_root_board_bundle_required")
        payload = json.load(stream)
    return validate_bundle(payload)


def checked_binary(path: Path, digest: str) -> bytes:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError("unexpected_source_binary")
    content = path.read_bytes()
    if hashlib.sha256(content).hexdigest() != digest:
        raise ValueError("source_binary_digest_changed")
    return content


def ensure_account() -> int:
    try:
        account = pwd.getpwnam(ACCOUNT)
        if account.pw_shell != "/usr/sbin/nologin" or account.pw_dir != "/nonexistent":
            raise ValueError("unexpected_service_account")
    except KeyError:
        run("/usr/sbin/useradd", "--system", "--user-group", "--no-create-home",
            "--home-dir", "/nonexistent", "--shell", "/usr/sbin/nologin", ACCOUNT)
        account = pwd.getpwnam(ACCOUNT)
    return account.pw_gid


def ensure_directory(path: Path, gid: int) -> None:
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if (not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or
                info.st_gid != gid or info.st_mode & 0o027):
            raise ValueError("unexpected_owned_directory")
        return
    path.mkdir(mode=0o750)
    os.chown(path, 0, gid)
    os.chmod(path, 0o750)


def write_owned(path: Path, content: bytes, mode: int, gid: int) -> None:
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or
                info.st_gid != gid or stat.S_IMODE(info.st_mode) != mode or
                path.read_bytes() != content):
            raise ValueError("existing_domestic_file_differs")
        return
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, "wb") as output:
        os.fchown(output.fileno(), 0, gid)
        os.fchmod(output.fileno(), mode)
        output.write(content)
        output.flush()
        os.fsync(output.fileno())


def ports_available() -> None:
    for address, port, kind in (("127.0.0.1", 19890, socket.SOCK_STREAM),
                                ("0.0.0.0", 18190, socket.SOCK_DGRAM)):
        with socket.socket(socket.AF_INET, kind) as probe:
            probe.bind((address, port))


@contextmanager
def control_idle_guard():
    # This existing guard checks control locks, processes and motor UART ownership.
    # It holds locks only while activating the new, network-only units.
    script = "/home/topeet/lianzhan/scripts/app_control_guard.py"
    spec = importlib.util.spec_from_file_location("followingcar_cn_idle_guard", script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with module.ControlGuard().idle_guard():
        yield


def active(unit: str) -> bool:
    return subprocess.run(("systemctl", "is-active", "--quiet", unit),
                          stdin=subprocess.DEVNULL, capture_output=True, timeout=8).returncode == 0


def activate() -> None:
    units = (GATEWAY_UNIT, FRPC_UNIT)
    if any(active(unit) for unit in units):
        raise ValueError("domestic_unit_already_active")
    started = []
    with control_idle_guard():
        try:
            for unit in units:
                started.append(unit)
                run("systemctl", "enable", "--now", unit)
                if not active(unit):
                    raise RuntimeError("domestic_unit_failed_to_start")
        except Exception:
            for unit in reversed(started):
                subprocess.run(("systemctl", "disable", "--now", unit),
                               stdin=subprocess.DEVNULL, capture_output=True, timeout=15)
            raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--activate", action="store_true")
    args = parser.parse_args()
    if os.geteuid() != 0 or platform.machine() not in ("aarch64", "arm64"):
        raise SystemExit("root_on_arm64_board_required")
    os.umask(0o077)
    payload = read_bundle(args.bundle)
    frpc = checked_binary(FRPC_SOURCE, FRPC_SHA256)
    gateway = checked_binary(GATEWAY_SOURCE, GATEWAY_SHA256)
    if args.activate:
        ports_available()
    # Reject any pre-existing unit from another project before writing anything.
    for unit, kind in ((FRPC_UNIT, "frpc"), (GATEWAY_UNIT, "video-gateway")):
        target = UNIT_DIR / unit
        if target.exists() or target.is_symlink():
            if target.is_symlink() or target.read_text() != systemd_unit(kind):
                raise ValueError("domestic_unit_name_conflict")
    gid = ensure_account()
    ensure_directory(Path("/opt/followingcar-cn"), gid)
    ensure_directory(Path(BIN), gid)
    ensure_directory(Path("/opt/followingcar-cn/tools"), gid)
    ensure_directory(Path(CONFIG), gid)
    write_owned(Path(BIN) / "frpc", frpc, 0o550, gid)
    write_owned(Path(BIN) / "mediamtx", gateway, 0o550, gid)
    for name in ("render.py", "transport_supervisor.py"):
        write_owned(Path("/opt/followingcar-cn/tools") / name,
                    Path(__file__).with_name(name).read_bytes(), 0o440, gid)
    write_owned(Path(CONFIG) / "frp-token", payload["frpToken"].encode(), 0o640, gid)
    for transport in ("quic", "tcp"):
        write_owned(Path(CONFIG) / f"frpc-{transport}.toml",
                    frpc_config(payload, transport).encode(), 0o640, gid)
    write_owned(Path(CONFIG) / "video-gateway.json", gateway_config(payload).encode(), 0o640, gid)
    run("/usr/sbin/runuser", "-u", ACCOUNT, "--", f"{BIN}/frpc", "verify",
        "-c", f"{CONFIG}/frpc-quic.toml")
    run("/usr/sbin/runuser", "-u", ACCOUNT, "--", f"{BIN}/frpc", "verify",
        "-c", f"{CONFIG}/frpc-tcp.toml")
    run(f"{BIN}/mediamtx", "--version")
    for unit, kind in ((FRPC_UNIT, "frpc"), (GATEWAY_UNIT, "video-gateway")):
        write_owned(UNIT_DIR / unit, systemd_unit(kind).encode(), 0o644, 0)
    run("systemd-analyze", "verify", str(UNIT_DIR / FRPC_UNIT), str(UNIT_DIR / GATEWAY_UNIT))
    run("systemctl", "daemon-reload")
    if args.activate:
        activate()
    print(json.dumps({"staged": True, "active": args.activate,
                      "services": [GATEWAY_UNIT, FRPC_UNIT],
                      "cameraAccess": False, "motorAccess": False}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"errorType": type(error).__name__, "staged": False,
                          "cameraAccess": False, "motorAccess": False}))
        raise SystemExit(1)
