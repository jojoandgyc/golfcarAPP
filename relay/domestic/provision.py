#!/usr/bin/env python3
"""Provision only /data/golfcar; never run the host-level relay installer."""
from __future__ import annotations

import argparse
import base64
from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess

from render import SETTINGS, render_coturn, render_frps, render_nginx


ROOT = Path("/data/golfcar")
CERT_SOURCE = Path("/www/server/panel/vhost/cert/world-agents.com")
CONTAINER_GID = 11000
FRPS_SHA256 = "2322762939e66e66850bbc9103b6d73fec39e83bd8ad6c71ebeeb120d56d6ab3"
CLIENT_PATTERN = re.compile(r"(?:phone|emu)-[0-9]{3}\Z")


def command(*args: str) -> str:
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    if result.returncode:
        # Do not echo arguments: they can contain paths to private material.
        raise RuntimeError(f"{Path(args[0]).name} failed ({result.returncode})")
    return result.stdout


def managed_path(root: Path, relative: str) -> Path:
    if Path(relative).is_absolute() or ".." in Path(relative).parts:
        raise ValueError("Destination must be relative to the managed root")
    path = root / relative
    if path.is_symlink() or any(parent.is_symlink() for parent in path.parents if parent != root.parent):
        raise ValueError("Managed directory contains a symlink")
    return path


def write_once(root: Path, relative: str, content: bytes | str,
               mode: int = 0o640, group: int | None = CONTAINER_GID) -> Path:
    path = managed_path(root, relative)
    payload = content.encode() if isinstance(content, str) else content
    if path.exists():
        if not path.is_file() or path.read_bytes() != payload:
            raise FileExistsError(f"Managed file already differs: {relative}")
        return path
    temporary = path.with_name(path.name + ".new")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
                 getattr(os, "O_NOFOLLOW", 0), mode)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, mode)
        if group is not None:
            os.chown(temporary, 0, group)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()
    return path


def directory(root: Path, relative: str, mode: int, group: int | None) -> Path:
    path = managed_path(root, relative)
    if path.exists() and not path.is_dir():
        raise ValueError(f"Expected directory: {relative}")
    path.mkdir(exist_ok=True)
    os.chmod(path, mode)
    if group is not None:
        os.chown(path, 0, group)
    return path


def prepare_root(root: Path) -> None:
    if root.is_symlink() or root.resolve() != root:
        raise ValueError("Managed root must be a real directory")
    if root.exists() and any(root.iterdir()) and not (root / ".followingcar-domestic").is_file():
        entries = {path.name for path in root.iterdir()}
        binary = root / "bin/frps"
        artifact_dir = root / "artifacts"
        staged_binary = (entries.issubset({"bin", "artifacts"}) and "bin" in entries
                         and (root / "bin").is_dir() and not (root / "bin").is_symlink()
                         and {path.name for path in (root / "bin").iterdir()} == {"frps"}
                         and binary.is_file() and not binary.is_symlink()
                         and hashlib.sha256(binary.read_bytes()).hexdigest() == FRPS_SHA256
                         and ("artifacts" not in entries or
                              (artifact_dir.is_dir() and not artifact_dir.is_symlink()
                               and not any(artifact_dir.iterdir()))))
        if not staged_binary:
            raise FileExistsError("/data/golfcar contains unrelated files or an unverified frps")
    root.mkdir(mode=0o750, exist_ok=True)
    os.chmod(root, 0o750)
    directory(root, "config", 0o750, CONTAINER_GID)
    directory(root, "secrets", 0o750, CONTAINER_GID)
    directory(root, "secrets/tls", 0o750, CONTAINER_GID)
    directory(root, "private", 0o700, None)
    directory(root, "private/tls", 0o700, None)
    directory(root, "private/clients", 0o700, None)
    directory(root, "web", 0o755, None)
    directory(root, "bin", 0o750, None)
    write_once(root, ".followingcar-domestic", "followingcar-domestic-v1\n", 0o600, None)


def checked_server_certificate(source: Path) -> tuple[bytes, bytes]:
    cert, key = source / "fullchain.pem", source / "privkey.pem"
    if not cert.is_file() or not key.is_file():
        raise FileNotFoundError("world-agents.com certificate pair is unavailable")
    san = command("openssl", "x509", "-in", str(cert), "-noout", "-ext", "subjectAltName")
    dns_names = re.findall(r"DNS:([a-zA-Z0-9.-]+)", san)
    if SETTINGS.host not in dns_names:
        # The root domain needs an explicit SAN; *.world-agents.com does not cover it.
        raise ValueError("Server certificate lacks the world-agents.com DNS SAN")
    host_result = command("openssl", "x509", "-in", str(cert), "-noout",
                          "-checkhost", SETTINGS.host)
    if "does match certificate" not in host_result:
        raise ValueError("Server certificate does not match configured host")
    command("openssl", "x509", "-in", str(cert), "-noout", "-checkend", "86400")
    cert_public = command("openssl", "x509", "-in", str(cert), "-pubkey", "-noout")
    key_public = command("openssl", "pkey", "-in", str(key), "-pubout")
    if cert_public != key_public:
        raise ValueError("Server certificate and private key differ")
    return cert.read_bytes(), key.read_bytes()


def secret_state(root: Path) -> dict[str, str]:
    path = managed_path(root, "private/secrets.json")
    if path.exists():
        state = json.loads(path.read_text())
    else:
        state = {"schemaVersion": 1, "frpToken": secrets.token_hex(32),
                 "turnAuthSecret": secrets.token_hex(32)}
        write_once(root, "private/secrets.json", json.dumps(state) + "\n", 0o600, None)
    for name in ("frpToken", "turnAuthSecret"):
        if not re.fullmatch(r"[0-9a-f]{64}", state.get(name, "")):
            raise ValueError("Invalid private relay secret")
    return state


def client_ca(root: Path) -> None:
    key = managed_path(root, "private/tls/client-ca.key")
    crt = managed_path(root, "secrets/tls/client-ca.crt")
    if key.exists() != crt.exists():
        raise ValueError("Incomplete client CA; refusing replacement")
    if key.exists():
        return
    command("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(key))
    os.chmod(key, 0o600)
    command("openssl", "req", "-new", "-x509", "-sha256", "-days", "3650",
            "-key", str(key), "-out", str(crt),
            "-subj", "/CN=FollowingCar Domestic Device Client CA",
            "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    os.chown(crt, 0, CONTAINER_GID)
    os.chmod(crt, 0o640)


def prepare(root: Path, cert_source: Path) -> dict[str, str]:
    prepare_root(root)
    cert, key = checked_server_certificate(cert_source)
    state = secret_state(root)
    client_ca(root)
    write_once(root, "secrets/tls/server.crt", cert)
    write_once(root, "secrets/tls/server.key", key)
    write_once(root, "secrets/frp-token", state["frpToken"] + "\n")
    write_once(root, "config/frps.toml", render_frps())
    write_once(root, "config/nginx.conf", render_nginx())
    write_once(root, "config/coturn.conf", render_coturn(state["turnAuthSecret"]))
    source = Path(__file__).resolve().parent
    for name in ("compose.yaml", "Dockerfile.frps", "Dockerfile.turn", ".dockerignore"):
        write_once(root, name, (source / name).read_bytes(), 0o640, None)
    web = source.parent / "web"
    for name in ("index.html", "player.css", "player.js", "whep.js", "THIRD_PARTY_LICENSES.txt"):
        write_once(root, "web/" + name, (web / name).read_bytes(), 0o644, None)
    return {"prepared": "true", "root": str(root), "host": SETTINGS.host,
            "controlPort": str(SETTINGS.control_port), "videoPort": str(SETTINGS.video_port)}


def issue_client(root: Path, name: str) -> Path:
    if not CLIENT_PATTERN.fullmatch(name):
        raise ValueError("Expected phone-NNN or emu-NNN")
    if not managed_path(root, ".followingcar-domestic").is_file():
        raise ValueError("Domestic relay is not prepared")
    folder = directory(root, f"private/clients/{name}", 0o700, None)
    bundle = managed_path(root, f"private/clients/{name}/{name}.carremote")
    if bundle.exists():
        return bundle
    key, csr, cert = folder / "client.key", folder / "client.csr", folder / "client.crt"
    if any(path.exists() for path in (key, csr, cert)):
        raise ValueError("Incomplete client issuance; inspect before replacing")
    command("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(key))
    os.chmod(key, 0o600)
    command("openssl", "req", "-new", "-key", str(key), "-out", str(csr),
            "-subj", f"/CN=followingcar-{name}")
    ext = write_once(root, f"private/clients/{name}/client.ext",
                     "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
                     "extendedKeyUsage=clientAuth\nsubjectKeyIdentifier=hash\n"
                     "authorityKeyIdentifier=keyid,issuer\n", 0o600, None)
    command("openssl", "x509", "-req", "-in", str(csr), "-CA",
            str(managed_path(root, "secrets/tls/client-ca.crt")), "-CAkey",
            str(managed_path(root, "private/tls/client-ca.key")),
            "-set_serial", "0x" + secrets.token_hex(16), "-out", str(cert),
            "-days", "365", "-sha256", "-extfile", str(ext))
    password = secrets.token_hex(20)
    password_file = write_once(root, f"private/clients/{name}/pkcs12-password",
                               password + "\n", 0o600, None)
    p12 = folder / "client.p12"
    command("openssl", "pkcs12", "-export", "-inkey", str(key), "-in", str(cert),
            "-certfile", str(managed_path(root, "secrets/tls/client-ca.crt")),
            "-name", "followingcar-relay-phone", "-out", str(p12),
            "-passout", f"file:{password_file}")
    os.chmod(p12, 0o600)
    content = json.dumps({"schemaVersion": 1, "host": SETTINGS.host,
                          "controlPort": SETTINGS.control_port, "videoPort": SETTINGS.video_port,
                          "clientPkcs12Base64": base64.b64encode(p12.read_bytes()).decode("ascii"),
                          "clientPkcs12Password": password}) + "\n"
    if len(content.encode()) > 65536:
        raise ValueError("Client bundle exceeds App limit")
    return write_once(root, f"private/clients/{name}/{name}.carremote", content, 0o600, None)


def export_board(root: Path) -> Path:
    if not managed_path(root, ".followingcar-domestic").is_file():
        raise ValueError("Domestic relay is not prepared")
    state = secret_state(root)
    content = json.dumps({"schemaVersion": 1, "settings": asdict(SETTINGS),
                          "frpToken": state["frpToken"],
                          "turnAuthSecret": state["turnAuthSecret"]}) + "\n"
    return write_once(root, "private/board-001.boardremote", content, 0o600, None)


def atomic_replace(root: Path, relative: str, payload: bytes) -> None:
    """Replace one owned file without exposing a partial file to readers."""
    path = managed_path(root, relative)
    if not path.is_file():
        raise FileNotFoundError(f"Missing existing certificate file: {relative}")
    temporary = path.with_name(path.name + ".new")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
                 getattr(os, "O_NOFOLLOW", 0), 0o640)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.chown(temporary, 0, CONTAINER_GID)
        os.chmod(temporary, 0o640)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def refresh_certificate(root: Path, cert_source: Path) -> dict[str, str | bool]:
    """Back up the old pair, update files, and leave every service untouched."""
    if not managed_path(root, ".followingcar-domestic").is_file():
        raise ValueError("Domestic relay is not prepared")
    # Validate the *complete* new pair before touching either installed file.
    new_cert, new_key = checked_server_certificate(cert_source)
    cert_rel, key_rel = "secrets/tls/server.crt", "secrets/tls/server.key"
    current_cert, current_key = managed_path(root, cert_rel), managed_path(root, key_rel)
    if not current_cert.is_file() or not current_key.is_file():
        raise ValueError("Existing server certificate pair is incomplete")
    old_cert, old_key = current_cert.read_bytes(), current_key.read_bytes()
    if old_cert == new_cert and old_key == new_key:
        return {"updated": False, "host": SETTINGS.host}

    backup_rel = "private/cert-backups/" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    directory(root, "private/cert-backups", 0o700, None)
    directory(root, backup_rel, 0o700, None)
    write_once(root, backup_rel + "/server.crt", old_cert, 0o600, None)
    write_once(root, backup_rel + "/server.key", old_key, 0o600, None)

    try:
        atomic_replace(root, cert_rel, new_cert)
        atomic_replace(root, key_rel, new_key)
    except Exception as failure:
        # Both replacements are individually atomic; restore the original pair
        # if the second replace fails. The backup remains for operator recovery.
        try:
            if current_cert.read_bytes() != old_cert:
                atomic_replace(root, cert_rel, old_cert)
            if current_key.read_bytes() != old_key:
                atomic_replace(root, key_rel, old_key)
        except Exception as rollback_failure:
            raise RuntimeError("Certificate update and rollback failed; inspect private backup") from rollback_failure
        raise RuntimeError("Certificate update failed; original pair restored") from failure
    return {"updated": True, "host": SETTINGS.host, "backup": str(root / backup_rel),
            "nginxReloadRequired": True, "frpsTurnRestartRequiresIdle": True}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "issue-client", "export-board", "refresh-cert"))
    parser.add_argument("--name")
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error("Run as root; writes remain inside /data/golfcar")
    if ROOT.is_symlink() or (ROOT.exists() and ROOT.resolve() != ROOT):
        parser.error("/data/golfcar must not be a symlink")
    os.umask(0o077)
    if args.action == "prepare":
        result = prepare(ROOT, CERT_SOURCE)
    elif args.action == "issue-client":
        if not args.name:
            parser.error("--name is required")
        result = {"bundle": str(issue_client(ROOT, args.name))}
    elif args.action == "refresh-cert":
        result = refresh_certificate(ROOT, CERT_SOURCE)
    else:
        result = {"bundle": str(export_board(ROOT))}
    print(json.dumps(result))


if __name__ == "__main__":
    main()
