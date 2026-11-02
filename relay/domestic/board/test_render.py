"""Offline checks for the parallel board endpoints and security boundaries."""

import json
import tomllib
import unittest

from render import frpc_config, gateway_config, systemd_unit, validate_bundle


def bundle():
    return {"schemaVersion": 1,
            "settings": {"host": "world-agents.com", "public_ip": "117.50.34.249",
                         "local_ip": "172.29.41.13", "control_port": 2443,
                         "video_port": 9443, "transport_port": 7443,
                         "turn_port": 3478, "turns_port": 5349,
                         "relay_min": 62000, "relay_max": 62099},
            "frpToken": "a" * 64, "turnAuthSecret": "b" * 64}


class ParallelBoardConfigTest(unittest.TestCase):
    def test_no_existing_board_port_or_config_is_reused(self):
        config = tomllib.loads(frpc_config(bundle()))
        self.assertEqual("world-agents.com", config["serverAddr"])
        self.assertEqual("world-agents.com", config["transport"]["tls"]["serverName"])
        self.assertEqual("/etc/followingcar-cn/frp-token",
                         config["auth"]["tokenSource"]["file"]["path"])
        self.assertEqual([(22, 22022), (19890, 18889)],
                         [(p["localPort"], p["remotePort"]) for p in config["proxies"]])
        self.assertTrue(all(p["localIP"] == "127.0.0.1" for p in config["proxies"]))
        self.assertEqual("127.0.0.1", config["webServer"]["addr"])
        self.assertEqual(17401, config["webServer"]["port"])
        self.assertEqual("tcp", tomllib.loads(frpc_config(bundle(), "tcp"))["transport"]["protocol"])

    def test_second_gateway_only_reads_encoded_source_on_demand(self):
        config = json.loads(gateway_config(bundle()))
        self.assertEqual("127.0.0.1:19890", config["webrtcAddress"])
        self.assertEqual(":18190", config["webrtcLocalUDPAddress"])
        self.assertEqual(["https://world-agents.com:9443"], config["webrtcAllowOrigins"])
        self.assertEqual("rtsp://127.0.0.1:8554/rgbd", config["paths"]["rgbd"]["source"])
        self.assertTrue(config["paths"]["rgbd"]["sourceOnDemand"])
        self.assertFalse(config["rtsp"])
        self.assertFalse(config["api"])
        self.assertEqual(3, len(config["webrtcICEServers2"]))
        self.assertTrue(all(s["username"] == "AUTH_SECRET" and
                            s["password"] == bundle()["turnAuthSecret"]
                            for s in config["webrtcICEServers2"]))

    def test_server_bundle_must_match_domestic_endpoint(self):
        wrong = bundle()
        wrong["settings"]["video_port"] = 8443
        with self.assertRaisesRegex(ValueError, "unexpected_domestic_endpoint"):
            validate_bundle(wrong)
        wrong = bundle()
        wrong["turnAuthSecret"] = "not-a-token"
        with self.assertRaisesRegex(ValueError, "invalid_board_secret"):
            validate_bundle(wrong)

    def test_units_have_new_namespaces_and_no_capabilities(self):
        for kind in ("frpc", "video-gateway"):
            unit = systemd_unit(kind)
            self.assertIn("User=followingcar-cn", unit)
            if kind == "frpc":
                self.assertIn("/opt/followingcar-cn/tools/transport_supervisor.py", unit)
            else:
                self.assertIn("/opt/followingcar-cn/bin/", unit)
                self.assertIn("/etc/followingcar-cn/", unit)
            self.assertIn("CapabilityBoundingSet=\n", unit)
            self.assertNotIn("/etc/followingcar-relay/", unit)
        self.assertIn("AF_NETLINK", systemd_unit("video-gateway"))


if __name__ == "__main__":
    unittest.main()
