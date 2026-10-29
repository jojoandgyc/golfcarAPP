"""Security and protocol invariants; does not connect to any vehicle or server."""
import json
import unittest
import tomllib

from configuration import (RelaySettings, coturn_config, frpc_config, frps_config,
                           mediamtx_turn_overlay, nginx_config, systemd_unit)


class RelayConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.settings = RelaySettings()

    def test_backend_ports_are_loopback_only_and_allowlisted(self):
        config = tomllib.loads(frps_config(self.settings))
        self.assertEqual("127.0.0.1", config["proxyBindAddr"])
        self.assertEqual([{ "single": 22022 }, { "single": 18889 }], config["allowPorts"])
        self.assertEqual(2, config["maxPortsPerClient"])
        self.assertTrue(config["transport"]["tls"]["force"])
        self.assertNotIn("webServer", config)

    def test_board_proxies_cannot_publish_uart_or_video_udp(self):
        config = tomllib.loads(frpc_config(self.settings))
        self.assertEqual("quic", config["transport"]["protocol"])
        self.assertEqual([(22, 22022), (19889, 18889)],
                         [(p["localPort"], p["remotePort"]) for p in config["proxies"]])
        self.assertTrue(all(p["type"] == "tcp" and p["localIP"] == "127.0.0.1"
                            for p in config["proxies"]))
        self.assertEqual("/etc/ssl/certs/ca-certificates.crt",
                         config["transport"]["tls"]["trustedCaFile"])
        self.assertEqual(self.settings.host, config["transport"]["tls"]["serverName"])

    def test_explicit_tcp_fallback_keeps_tls(self):
        config = tomllib.loads(frpc_config(self.settings, "tcp"))
        self.assertTrue(config["transport"]["tls"]["enable"])
        self.assertEqual("tcp", config["transport"]["protocol"])
        with self.assertRaises(ValueError):
            frpc_config(self.settings, "shell\ncommand")

    def test_mtls_covers_control_and_all_video_requests(self):
        text = nginx_config(self.settings)
        self.assertEqual(2, text.count("ssl_verify_client on;"))
        self.assertIn("listen 2443 ssl;", text)
        self.assertIn("listen 8443 ssl;", text)
        self.assertNotIn("listen 443", text)
        self.assertIn("limit_except OPTIONS POST PATCH DELETE", text)
        self.assertIn("location / { return 404; }", text)
        self.assertNotIn("proxy_pass http://127.0.0.1:9997", text)

    def test_turn_has_secret_and_bounded_server_only_relay(self):
        text = coturn_config(self.settings, "a" * 64)
        self.assertIn("use-auth-secret", text)
        self.assertIn("min-port=62000\nmax-port=62099", text)
        self.assertIn("denied-peer-ip=0.0.0.0-255.255.255.255", text)
        self.assertIn("allowed-peer-ip=43.162.101.137", text)
        self.assertIn("no-tcp-relay", text)
        self.assertNotIn("no-auth", text)

    def test_media_overlay_preserves_motor_and_stream_settings(self):
        text = mediamtx_turn_overlay(self.settings, "b" * 64)
        self.assertEqual(2, text.count("clientOnly: false"))
        self.assertNotIn("paths:", text)
        self.assertNotIn("webrtcLocalUDPAddress", text)
        self.assertIn("24-hour", text)

    def test_input_injection_and_invalid_ranges_rejected(self):
        for settings in (RelaySettings(host='a"\ncommand'), RelaySettings(public_ip="127.1"),
                         RelaySettings(control_port=443), RelaySettings(video_port=2443),
                         RelaySettings(relay_min=2000, relay_max=9000)):
            with self.assertRaises(ValueError):
                frps_config(settings)
        with self.assertRaises(ValueError):
            coturn_config(self.settings, 'secret\nno-auth')

    def test_services_run_without_root_or_privileged_capabilities(self):
        for name in ("frpc", "frps", "nginx", "turn"):
            text = systemd_unit(name)
            self.assertIn("User=followingcar-relay", text)
            self.assertIn("NoNewPrivileges=true", text)
            self.assertIn("CapabilityBoundingSet=\n", text)
            self.assertNotIn("ExecStartPre=", text)


if __name__ == "__main__":
    unittest.main()
