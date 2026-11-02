"""Only local temporary directories; never contacts a relay or a vehicle."""
from __future__ import annotations

import json
import hashlib
from pathlib import Path
import subprocess
import tempfile
import tomllib
import unittest
from unittest.mock import patch

import provision
from render import FRPS_IP, HOST, PUBLIC_IP, SETTINGS, TURN_IP, render_coturn, render_frps, render_nginx


class DomesticRenderTest(unittest.TestCase):
    def test_transport_and_video_routes_are_isolated(self):
        frp = tomllib.loads(render_frps())
        nginx = render_nginx()
        turn = render_coturn("a" * 64)
        self.assertEqual(FRPS_IP, frp["proxyBindAddr"])
        self.assertEqual([{"single": 22022}, {"single": 18889}], frp["allowPorts"])
        self.assertEqual(2, nginx.count("ssl_verify_client on;"))
        self.assertIn("listen 2443 ssl;", nginx)
        self.assertIn("listen 9443 ssl;", nginx)
        self.assertIn(f"proxy_pass {FRPS_IP}:22022;", nginx)
        self.assertIn(f"proxy_pass http://{FRPS_IP}:18889;", nginx)
        self.assertNotIn("load_module", nginx)
        self.assertIn(f"listening-ip={TURN_IP}", turn)
        self.assertIn(f"external-ip={PUBLIC_IP}/{TURN_IP}", turn)
        self.assertIn("min-port=62000\nmax-port=62099", turn)

    def test_compose_publishes_only_expected_ports(self):
        compose = (Path(__file__).parent / "compose.yaml").read_text()
        dockerignore = (Path(__file__).parent / ".dockerignore").read_text()
        for port in ("2443:2443/tcp", "9443:9443/tcp", "7443:7443/tcp",
                     "7443:7443/udp", "3478:3478/tcp", "3478:3478/udp",
                     "5349:5349/tcp", "62000-62099:62000-62099/udp"):
            self.assertIn(f'"{port}"', compose)
        self.assertNotIn("22022:22022", compose)
        self.assertNotIn("18889:18889", compose)
        self.assertNotIn("network_mode: host", compose)
        self.assertEqual(3, compose.count("./secrets/tls:/etc/followingcar-relay/tls:ro"))
        self.assertNotIn("./secrets/tls/server.crt:", compose)
        self.assertNotIn("./secrets/tls/server.key:", compose)
        self.assertTrue(dockerignore.startswith("**\n"))
        self.assertNotIn("!private", dockerignore)
        self.assertNotIn("!secrets", dockerignore)


class DomesticProvisionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "golfcar"
        self.source = Path(self.tmp.name) / "source-cert"
        self.source.mkdir()
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec",
                        "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-days", "3",
                        "-subj", "/CN=world-agents.com", "-addext",
                        "subjectAltName=DNS:world-agents.com", "-keyout",
                        str(self.source / "privkey.pem"), "-out",
                        str(self.source / "fullchain.pem")], check=True, capture_output=True)
        self.chown = patch.object(provision.os, "chown", return_value=None, create=True)
        self.chown.start()
        self.addCleanup(self.chown.stop)

    def make_certificate(self, source: Path, host: str = "world-agents.com") -> None:
        source.mkdir(parents=True, exist_ok=True)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec",
                        "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-days", "3",
                        "-subj", f"/CN={host}", "-addext", f"subjectAltName=DNS:{host}",
                        "-keyout", str(source / "privkey.pem"), "-out",
                        str(source / "fullchain.pem")], check=True, capture_output=True)

    def test_provision_identity_and_board_bundle_without_host_writes(self):
        prepared = provision.prepare(self.root, self.source)
        self.assertEqual(HOST, prepared["host"])
        self.assertEqual("9443", prepared["videoPort"])
        self.assertEqual(prepared, provision.prepare(self.root, self.source))
        phone = provision.issue_client(self.root, "phone-001")
        emulator = provision.issue_client(self.root, "emu-001")
        board = provision.export_board(self.root)
        self.assertEqual(phone, provision.issue_client(self.root, "phone-001"))
        self.assertTrue(emulator.is_file())
        phone_data = json.loads(phone.read_text())
        board_data = json.loads(board.read_text())
        self.assertEqual((HOST, 2443, 9443),
                         (phone_data["host"], phone_data["controlPort"], phone_data["videoPort"]))
        self.assertEqual(SETTINGS.video_port, board_data["settings"]["video_port"])
        self.assertEqual(board_data["frpToken"],
                         (self.root / "secrets/frp-token").read_text().strip())
        self.assertNotIn("clientPkcs12Password", (self.root / "config/frps.toml").read_text())

    def test_bad_certificate_and_unrelated_directory_are_rejected(self):
        other = self.root / "other"
        other.parent.mkdir()
        other.write_text("untouched")
        with self.assertRaises(FileExistsError):
            provision.prepare(self.root, self.source)
        self.assertEqual("untouched", other.read_text())
        (self.root / "other").unlink()
        wrong_cert = self.source / "fullchain.pem"
        wrong_cert.write_text("not a certificate")
        with self.assertRaises(RuntimeError):
            provision.prepare(self.root, self.source)

    def test_first_prepare_accepts_only_verified_staged_binary(self):
        binary = self.root / "bin/frps"
        binary.parent.mkdir(parents=True)
        binary.write_bytes(b"frps fixture")
        (self.root / "artifacts").mkdir()
        with self.assertRaises(FileExistsError):
            provision.prepare_root(self.root)
        with patch.object(provision, "FRPS_SHA256", hashlib.sha256(binary.read_bytes()).hexdigest()):
            provision.prepare_root(self.root)
        self.assertTrue((self.root / ".followingcar-domestic").is_file())

    def test_refresh_cert_backs_up_old_pair_without_service_action(self):
        provision.prepare(self.root, self.source)
        old_cert = (self.root / "secrets/tls/server.crt").read_bytes()
        old_key = (self.root / "secrets/tls/server.key").read_bytes()
        renewed = Path(self.tmp.name) / "renewed"
        self.make_certificate(renewed)
        result = provision.refresh_certificate(self.root, renewed)
        self.assertTrue(result["updated"])
        self.assertTrue(result["nginxReloadRequired"])
        self.assertTrue(result["frpsTurnRestartRequiresIdle"])
        backup = Path(result["backup"])
        self.assertEqual(old_cert, (backup / "server.crt").read_bytes())
        self.assertEqual(old_key, (backup / "server.key").read_bytes())
        self.assertEqual((renewed / "fullchain.pem").read_bytes(),
                         (self.root / "secrets/tls/server.crt").read_bytes())
        self.assertEqual((renewed / "privkey.pem").read_bytes(),
                         (self.root / "secrets/tls/server.key").read_bytes())
        self.assertFalse(provision.refresh_certificate(self.root, renewed)["updated"])
        self.assertEqual("true", provision.prepare(self.root, renewed)["prepared"])
        self.assertEqual([backup.name], [p.name for p in backup.parent.iterdir()])

    def test_refresh_rejects_bad_cert_without_touching_installed_pair(self):
        provision.prepare(self.root, self.source)
        cert = self.root / "secrets/tls/server.crt"
        key = self.root / "secrets/tls/server.key"
        before = cert.read_bytes(), key.read_bytes()
        wrong = Path(self.tmp.name) / "wrong"
        self.make_certificate(wrong, "wrong.example")
        with self.assertRaises(ValueError):
            provision.refresh_certificate(self.root, wrong)
        self.assertEqual(before, (cert.read_bytes(), key.read_bytes()))
        self.assertFalse((self.root / "private/cert-backups").exists())

    def test_refresh_restores_pair_if_second_replace_fails(self):
        provision.prepare(self.root, self.source)
        cert = self.root / "secrets/tls/server.crt"
        key = self.root / "secrets/tls/server.key"
        before = cert.read_bytes(), key.read_bytes()
        renewed = Path(self.tmp.name) / "renewed"
        self.make_certificate(renewed)
        real_replace = provision.os.replace
        failed = False

        def fail_key_once(source, destination):
            nonlocal failed
            if not failed and Path(destination) == key:
                failed = True
                raise OSError("simulated disk failure")
            return real_replace(source, destination)

        with patch.object(provision.os, "replace", side_effect=fail_key_once):
            with self.assertRaisesRegex(RuntimeError, "original pair restored"):
                provision.refresh_certificate(self.root, renewed)
        self.assertTrue(failed)
        self.assertEqual(before, (cert.read_bytes(), key.read_bytes()))


if __name__ == "__main__":
    unittest.main()
