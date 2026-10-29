import unittest

from configuration import RelaySettings, admin_password, frpc_config
from transport_supervisor import StartupFallback, registered_proxy_names


class StartupFallbackTest(unittest.TestCase):
    def test_udp_blocked_initial_registration_falls_back_at_ten_seconds(self):
        policy = StartupFallback(100.0)
        self.assertFalse(policy.observe(109.99, set()))
        self.assertTrue(policy.observe(110.0, set()))
        self.assertEqual("tcp", policy.protocol)
        self.assertFalse(policy.observe(1000, set()))

    def test_no_handover_after_any_proxy_was_registered(self):
        policy = StartupFallback(0)
        self.assertFalse(policy.observe(2, {"followingcar.board-ssh"}))
        self.assertFalse(policy.observe(15, set()))
        self.assertFalse(policy.observe(1000, None))
        self.assertEqual("quic", policy.protocol)

    def test_api_failure_does_not_mistakenly_kill_connected_tunnel(self):
        policy = StartupFallback(0)
        self.assertFalse(policy.observe(15, None))
        self.assertFalse(policy.observe(16, {"followingcar.board-whep"}))
        self.assertFalse(policy.observe(17, set()))

    def test_tcp_never_automatically_promotes_to_quic(self):
        policy = StartupFallback(0, "tcp")
        self.assertFalse(policy.observe(100, set()))
        self.assertFalse(policy.observe(101, {"followingcar.board-ssh"}))
        self.assertEqual("tcp", policy.protocol)

    def test_only_named_running_proxies_prove_registration(self):
        self.assertEqual(set(), registered_proxy_names({}))
        self.assertEqual({"board-ssh"}, registered_proxy_names({"tcp": [
            {"name": "board-ssh", "status": "running"},
            {"name": "board-whep", "status": "start error"},
            {"name": "unrelated", "status": "running"}]}))
        with self.assertRaises(ValueError):
            registered_proxy_names({"tcp": "bad"})

    def test_admin_password_is_separate_and_loopback_only(self):
        token = "a" * 64
        password = admin_password(token)
        self.assertNotEqual(token, password)
        self.assertEqual(password, admin_password(token))
        config = frpc_config(RelaySettings(), local_admin_password=password)
        self.assertIn('webServer.addr = "127.0.0.1"', config)
        self.assertIn('webServer.port = 17400', config)


if __name__ == "__main__":
    unittest.main()
