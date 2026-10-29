#!/usr/bin/env python3
"""Provision only FollowingCar-owned files/services. Never changes network routes.

Run on the relay server as root. Secrets are generated there, outside Git.
Preparation does not start services; activation is an explicit separate command.
"""
from __future__ import annotations

import argparse
import base64
from dataclasses import asdict
from datetime import datetime, timezone
import grp
import hashlib
import io
import json
import os
from pathlib import Path
import pwd
import re
import secrets
import shutil
import subprocess
import tarfile
import urllib.request

from configuration import (CONFIG_ROOT, FRP_SHA256, FRP_VERSION, INSTALL_ROOT,
                           SERVICE_USER, RelaySettings, coturn_config, frpc_config,
                           frps_config, maintenance_timer, maintenance_unit, mediamtx_turn_overlay, nginx_config,
                           systemd_unit, validate_secret)


CONFIG = Path(CONFIG_ROOT)
INSTALL = Path(INSTALL_ROOT)


def run(*args: str, capture: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None, check=False)
    if result.returncode:
        # Never include command arguments: an external program can be passed sensitive paths.
        raise RuntimeError(f"{Path(args[0]).name} failed (exit {result.returncode}): "
                           + (result.stderr or "")[-2000:])
    return result


def atomic_write(path: Path, data: str | bytes, mode: int = 0o640,
                 service_group: bool = True) -> None:
    missing = []
    parent = path.parent
    while not parent.exists():
        missing.append(parent)
        parent = parent.parent
    for directory in reversed(missing):
        directory.mkdir(mode=0o755)
        os.chmod(directory, 0o755)
    payload = data.encode() if isinstance(data, str) else data
    if path.is_symlink():
        raise ValueError(f"Refusing symlink destination {path}")
    if path.exists() and path.read_bytes() != payload:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        backup = CONFIG / "backups" / stamp / path.name
        backup.parent.mkdir(parents=True, mode=0o700)
        shutil.copy2(path, backup)
        os.chmod(backup, 0o600)
    temporary = path.with_name(path.name + ".new")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, "wb") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, mode)
    if service_group:
        os.chown(temporary, 0, grp.getgrnam(SERVICE_USER).gr_gid)
    os.replace(temporary, path)


def ensure_account() -> None:
    try:
        pwd.getpwnam(SERVICE_USER)
    except KeyError:
        run("useradd", "--system", "--user-group", "--home-dir", "/nonexistent",
            "--shell", "/usr/sbin/nologin", SERVICE_USER)
    CONFIG.mkdir(exist_ok=True, mode=0o750)
    os.chown(CONFIG, 0, grp.getgrnam(SERVICE_USER).gr_gid)
    os.chmod(CONFIG, 0o750)
    (CONFIG / "tls").mkdir(exist_ok=True, mode=0o750)
    os.chown(CONFIG / "tls", 0, grp.getgrnam(SERVICE_USER).gr_gid)
    os.chmod(CONFIG / "tls", 0o750)
    (CONFIG / "clients").mkdir(exist_ok=True, mode=0o700)
    INSTALL.mkdir(exist_ok=True, mode=0o755)
    os.chmod(INSTALL, 0o755)


def download_frp(architecture: str, binary: str) -> None:
    if architecture not in FRP_SHA256 or binary not in ("frps", "frpc"):
        raise ValueError("Unsupported frp artifact")
    folder = f"frp_{FRP_VERSION}_linux_{architecture}"
    url = f"https://github.com/fatedier/frp/releases/download/v{FRP_VERSION}/{folder}.tar.gz"
    cache = INSTALL / "downloads" / (folder + ".tar.gz")
    if not cache.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        os.chmod(cache.parent, 0o755)
        with urllib.request.urlopen(url, timeout=60) as response:
            archive = response.read(40 * 1024 * 1024)
    else:
        archive = cache.read_bytes()
    if hashlib.sha256(archive).hexdigest() != FRP_SHA256[architecture]:
        raise ValueError("Official frp release digest mismatch")
    atomic_write(cache, archive, mode=0o644, service_group=False)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as source:
        member = source.getmember(f"{folder}/{binary}")
        if not member.isfile() or member.size > 60 * 1024 * 1024:
            raise ValueError("Unexpected frp archive member")
        stream = source.extractfile(member)
        if stream is None:
            raise ValueError("Missing frp binary")
        atomic_write(INSTALL / "frp" / FRP_VERSION / binary, stream.read(),
                     mode=0o755, service_group=False)


def ensure_secrets() -> dict:
    target = CONFIG / "secrets.json"
    if target.exists():
        state = json.loads(target.read_text())
    else:
        state = {"schemaVersion": 1, "frpToken": secrets.token_hex(32),
                 "turnAuthSecret": secrets.token_hex(32)}
        atomic_write(target, json.dumps(state) + "\n", mode=0o600, service_group=False)
    validate_secret(state["frpToken"])
    validate_secret(state["turnAuthSecret"])
    return state


def ensure_client_ca() -> None:
    key = CONFIG / "tls/client-ca.key"
    certificate = CONFIG / "tls/client-ca.crt"
    if key.exists() != certificate.exists():
        raise ValueError("Incomplete client CA; refusing to rotate trust automatically")
    if not key.exists():
        run("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(key))
        os.chmod(key, 0o600)
        run("openssl", "req", "-new", "-x509", "-sha256", "-days", "3650", "-key", str(key),
            "-out", str(certificate), "-subj", "/CN=FollowingCar Device Client CA",
            "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    os.chmod(certificate, 0o644)


def copy_server_certificate(settings: RelaySettings) -> None:
    source = Path("/etc/letsencrypt/live") / settings.host
    certificate = (source / "fullchain.pem").read_bytes()
    private_key = (source / "privkey.pem").read_bytes()
    # openssl parses before replacements; hostname checking requires a matching SAN.
    run("openssl", "x509", "-in", str(source / "fullchain.pem"), "-noout", "-checkhost", settings.host)
    run("openssl", "x509", "-in", str(source / "fullchain.pem"), "-noout", "-checkend", "86400")
    cert_public = run("openssl", "x509", "-in", str(source / "fullchain.pem"), "-pubkey", "-noout").stdout
    key_public = run("openssl", "pkey", "-in", str(source / "privkey.pem"), "-pubout").stdout
    if cert_public != key_public:
        raise ValueError("Server certificate and private key do not match")
    atomic_write(CONFIG / "tls/server.crt", certificate)
    atomic_write(CONFIG / "tls/server.key", private_key)


def settings_load() -> RelaySettings:
    target = CONFIG / "settings.json"
    return RelaySettings(**json.loads(target.read_text())) if target.exists() else RelaySettings()


def prepare_server(settings: RelaySettings) -> None:
    settings.validate()
    for executable in ("nginx", "turnserver", "openssl"):
        if not shutil.which(executable):
            raise ValueError(f"Install required package first: {executable}")
    ensure_account()
    state = ensure_secrets()
    ensure_client_ca()
    copy_server_certificate(settings)
    download_frp("amd64", "frps")
    atomic_write(CONFIG / "settings.json", json.dumps(asdict(settings), indent=2) + "\n")
    atomic_write(CONFIG / "frp-token", state["frpToken"])
    atomic_write(CONFIG / "frps.toml", frps_config(settings))
    atomic_write(CONFIG / "nginx.conf", nginx_config(settings))
    atomic_write(CONFIG / "coturn.conf", coturn_config(settings, state["turnAuthSecret"]))
    for kind in ("frps", "nginx", "turn"):
        atomic_write(Path(f"/etc/systemd/system/followingcar-{kind}.service"), systemd_unit(kind),
                     mode=0o644, service_group=False)
    atomic_write(Path("/etc/systemd/system/followingcar-certificate-maintenance.service"),
                 maintenance_unit(), mode=0o644, service_group=False)
    atomic_write(Path("/etc/systemd/system/followingcar-certificate-maintenance.timer"),
                 maintenance_timer(), mode=0o644, service_group=False)
    for path in Path(__file__).resolve().parent.glob("*.py"):
        atomic_write(INSTALL / "tools" / path.name, path.read_bytes(), mode=0o644, service_group=False)
    web = Path(__file__).resolve().parent / "web"
    if web.exists():
        for path in web.iterdir():
            if path.name in {"index.html", "player.css", "player.js", "whep.js"} and path.is_file():
                atomic_write(INSTALL / "web" / path.relative_to(web), path.read_bytes(),
                             mode=0o644, service_group=False)
        notice = web / "THIRD_PARTY_LICENSES.txt"
        if notice.exists():
            atomic_write(INSTALL / "licenses" / notice.name, notice.read_bytes(),
                         mode=0o644, service_group=False)
    hook = f'''#!/bin/sh
set -eu
[ "${{RENEWED_LINEAGE:-}}" = "/etc/letsencrypt/live/{settings.host}" ] || exit 0
exec /usr/bin/python3 {INSTALL}/tools/provision.py renew-certificate
'''
    atomic_write(Path("/etc/letsencrypt/renewal-hooks/deploy/followingcar-relay"), hook,
                 mode=0o750, service_group=False)
    run("systemctl", "daemon-reload")
    print(json.dumps({"prepared": True, "started": False, "frpVersion": FRP_VERSION,
                      "host": settings.host, "relayUdpRange": [settings.relay_min, settings.relay_max]}))


def issue_client(settings: RelaySettings, name: str) -> None:
    if not re.fullmatch(r"(?:phone|emu)-[0-9]{3}", name):
        raise ValueError("Expected phone-NNN or emu-NNN")
    ensure_client_ca()
    folder = CONFIG / "clients" / name
    folder.mkdir(mode=0o700, exist_ok=True)
    bundle = folder / f"{name}.carremote"
    if bundle.exists():
        print(json.dumps({"existingBundle": str(bundle)}))
        return
    key, csr, cert = folder / "client.key", folder / "client.csr", folder / "client.crt"
    password_path = folder / "pkcs12-password"
    if key.exists() or cert.exists():
        raise ValueError("Incomplete client provisioning; inspect before replacing")
    run("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(key))
    os.chmod(key, 0o600)
    run("openssl", "req", "-new", "-key", str(key), "-out", str(csr),
        "-subj", f"/CN=followingcar-{name}")
    extensions = folder / "client.ext"
    atomic_write(extensions, "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
                 "extendedKeyUsage=clientAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n",
                 mode=0o600, service_group=False)
    run("openssl", "x509", "-req", "-in", str(csr), "-CA", str(CONFIG / "tls/client-ca.crt"),
        "-CAkey", str(CONFIG / "tls/client-ca.key"), "-set_serial", "0x" + secrets.token_hex(16),
        "-out", str(cert), "-days", "365", "-sha256", "-extfile", str(extensions))
    password = secrets.token_hex(20)
    atomic_write(password_path, password + "\n", mode=0o600, service_group=False)
    p12 = folder / "client.p12"
    run("openssl", "pkcs12", "-export", "-inkey", str(key), "-in", str(cert),
        "-certfile", str(CONFIG / "tls/client-ca.crt"), "-name", "followingcar-relay-phone",
        "-out", str(p12), "-passout", f"file:{password_path}")
    os.chmod(p12, 0o600)
    payload = {"schemaVersion": 1, "host": settings.host, "controlPort": settings.control_port,
               "videoPort": settings.video_port,
               "clientPkcs12Base64": base64.b64encode(p12.read_bytes()).decode("ascii"),
               "clientPkcs12Password": password}
    content = json.dumps(payload) + "\n"
    if len(content.encode()) > 65536:
        raise ValueError("Client bundle exceeds App import limit")
    atomic_write(bundle, content, mode=0o600, service_group=False)
    print(json.dumps({"bundle": str(bundle), "client": name, "expiresDays": 365}))


def export_board(settings: RelaySettings) -> None:
    state = ensure_secrets()
    payload = {"schemaVersion": 1, "settings": asdict(settings),
               "frpToken": state["frpToken"], "turnAuthSecret": state["turnAuthSecret"]}
    destination = CONFIG / "board-001.boardremote"
    atomic_write(destination, json.dumps(payload) + "\n", mode=0o600, service_group=False)
    print(json.dumps({"bundle": str(destination)}))


def validate_server() -> None:
    run(str(INSTALL / "frp" / FRP_VERSION / "frps"), "verify", "-c", str(CONFIG / "frps.toml"))
    # nginx -t also opens its pid/temp paths, even before systemd creates them.
    runtime = Path("/run/followingcar-nginx")
    runtime.mkdir(mode=0o750, exist_ok=True)
    account = pwd.getpwnam(SERVICE_USER)
    os.chown(runtime, account.pw_uid, account.pw_gid)
    run("runuser", "-u", SERVICE_USER, "--", "nginx", "-t", "-c", str(CONFIG / "nginx.conf"))
    run("openssl", "verify", "-CAfile", str(CONFIG / "tls/client-ca.crt"),
        str(CONFIG / "tls/client-ca.crt"))
    print(json.dumps({"configurationValid": True}))


def activate_server() -> None:
    validate_server()
    for service in ("frps", "nginx", "turn"):
        # start is intentionally not restart: never terminate an active control session.
        run("systemctl", "enable", "--now", f"followingcar-{service}.service")
        run("systemctl", "is-active", "--quiet", f"followingcar-{service}.service")
    run("systemctl", "enable", "--now", "followingcar-certificate-maintenance.timer")
    print(json.dumps({"active": True}))


def renew_certificate() -> None:
    settings = settings_load()
    copy_server_certificate(settings)
    # Nginx reload preserves existing TCP sessions. frps and Coturn must be
    # restarted later in an explicitly coordinated maintenance window. A point-in-time
    # idle check cannot prevent a new client from entering before a restart.
    if subprocess.run(["systemctl", "is-active", "--quiet", "followingcar-nginx.service"]).returncode == 0:
        run("nginx", "-t", "-c", str(CONFIG / "nginx.conf"))
        run("systemctl", "kill", "--kill-who=main", "--signal=HUP", "followingcar-nginx.service")
    atomic_write(CONFIG / "certificate-maintenance-pending.json",
                 json.dumps({"issuedAt": datetime.now(timezone.utc).isoformat(),
                             "requiresManualMaintenance": ["followingcar-frps", "followingcar-turn"]}) + "\n")
    print(json.dumps({"copied": True, "transportRestartRequiresManualMaintenance": True}))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare-server", "issue-client", "export-board",
                                           "validate-server", "activate-server", "renew-certificate"))
    parser.add_argument("--name")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit("Run as root on the relay server")
    os.umask(0o077)
    settings = settings_load()
    if args.action == "prepare-server":
        prepare_server(settings)
    elif args.action == "issue-client":
        issue_client(settings, args.name or "")
    elif args.action == "export-board":
        export_board(settings)
    elif args.action == "validate-server":
        validate_server()
    elif args.action == "activate-server":
        activate_server()
    elif args.action == "renew-certificate":
        renew_certificate()


if __name__ == "__main__":
    main()
