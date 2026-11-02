"""Offline tests for the one-time transport choice and isolated admin API."""

import hashlib
import unittest

from render import (admin_password, frpc_config, legacy_direct_frpc_config,
                    legacy_direct_frpc_unit, systemd_unit)
from test_render import bundle
from transport_supervisor import (API_URL, EXPECTED_PROXIES, STATE_FILE,
                                  StartupFallback, registered_proxy_names)


class DomesticFallbackTest(unittest.TestCase):
    def test_initial_quic_no_registration_falls_back_once(self):
        policy = StartupFallback(100.0)
        self.assertFalse(policy.observe(109.99, set()))
        self.assertTrue(policy.observe(110.0, set()))
        self.assertEqual("tcp", policy.protocol)
        self.assertFalse(policy.observe(110.5, set()))

    def test_api_unavailable_never_proves_empty_registration(self):
        policy = StartupFallback(0.0)
        self.assertFalse(policy.observe(11.0, None))
        self.assertFalse(policy.observe(100.0, None))
        self.assertEqual("quic", policy.protocol)

    def test_one_registered_proxy_prevents_future_handover(self):
        policy = StartupFallback(0.0)
        self.assertFalse(policy.observe(2.0, {"board-cn-ssh"}))
        self.assertFalse(policy.observe(20.0, set()))
        self.assertFalse(policy.observe(100.0, None))
        self.assertEqual("quic", policy.protocol)

    def test_tcp_state_never_promotes_itself(self):
        policy = StartupFallback(0.0, "tcp")
        self.assertFalse(policy.observe(100.0, set()))
        self.assertEqual("tcp", policy.protocol)

    def test_only_domestic_running_proxy_names_count(self):
        self.assertEqual({"board-cn-ssh"}, registered_proxy_names({"tcp": [
            {"name": "board-cn-ssh", "status": "running"},
            {"name": "board-cn-whep", "status": "start error"},
            {"name": "board-ssh", "status": "running"}]}))
        self.assertEqual(set(), registered_proxy_names({"tcp": []}))
        for invalid in ([], {"tcp": {}}, {"wrong": []}):
            with self.assertRaises(ValueError):
                registered_proxy_names(invalid)

    def test_admin_and_state_do_not_overlap_overseas_instance(self):
        token = bundle()["frpToken"]
        config = frpc_config(bundle())
        self.assertIn('webServer.addr = "127.0.0.1"', config)
        self.assertIn("webServer.port = 17401", config)
        self.assertIn(admin_password(token), config)
        self.assertNotIn('webServer.port = 17400', config)
        self.assertNotEqual(token, admin_password(token))
        self.assertEqual("http://127.0.0.1:17401/api/status", API_URL)
        self.assertEqual("/var/lib/followingcar-cn/transport-selection.json",
                         str(STATE_FILE).replace("\\", "/"))
        self.assertEqual({"board-cn-ssh", "board-cn-whep"}, EXPECTED_PROXIES)
        unit = systemd_unit("frpc")
        self.assertIn("StateDirectory=followingcar-cn", unit)
        self.assertIn("/opt/followingcar-cn/tools/transport_supervisor.py", unit)

    def test_upgrade_preflight_templates_match_live_first_release(self):
        self.assertEqual("daf1de2b18fea82cf4c22e30716f0fd358462ec6216c490e3015b10ce3913288",
                         hashlib.sha256(legacy_direct_frpc_unit().encode()).hexdigest())
        for transport, digest in (
                ("quic", "5eb79c1570b853852d22e8b742d7f0ee26f605f45818cb8d671118758c2b94bf"),
                ("tcp", "ff4e7941613f5650bac1f864cfafcc340150f7ec369c4a3802efd830c4ca5859")):
            self.assertEqual(digest,
                             hashlib.sha256(legacy_direct_frpc_config(bundle(), transport).encode()).hexdigest())


if __name__ == "__main__":
    unittest.main()
