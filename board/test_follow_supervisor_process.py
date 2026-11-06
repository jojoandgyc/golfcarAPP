"""Worker-link tests use only fake processes and pipes, never camera or motor imports."""
import os
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from app_follow_bridge import FollowOptions, FollowProcess, SessionError


class WorkerNetworkStateTests(unittest.TestCase):
    def process(self):
        value = FollowProcess("astra-pro", False, True,
                              options=FollowOptions(gestures=True), offline_until_gesture=True)
        value.process = SimpleNamespace(pid=123, poll=lambda: None,
                                        stdin=SimpleNamespace(fileno=lambda: 456))
        return value

    def test_network_mode_requires_the_explicit_gesture_capability(self):
        with self.assertRaises(SessionError):
            FollowProcess("config", False, True, offline_until_gesture=True)
        legacy = FollowProcess("config", False, True)
        with self.assertRaises(SessionError):
            legacy.set_network_detached(True)

    def test_detach_and_reattach_keep_local_health_lease_and_do_not_send_stop(self):
        process = self.process()
        with patch.object(os, "write", return_value=2) as write:
            process.set_network_detached(True)
            process.pulse()
            process.set_network_detached(True)
            process.set_network_detached(False)
            self.assertEqual([call.args[1] for call in write.call_args_list], [b"D\n", b"H\n", b"C\n"])
        self.assertIsNone(process.stop_at)
        self.assertFalse(process.network_detached)

    def test_reconnect_cannot_revive_a_stop_already_accepted(self):
        process = self.process()
        with patch.object(os, "write", return_value=2) as write:
            process.set_network_detached(True)
            process.request_stop("app_stop_requested")
            process.set_network_detached(False)
            process.pulse()
            self.assertEqual([call.args[1] for call in write.call_args_list], [b"D\n", b"S\n"])
        self.assertEqual(process.reason, "app_stop_requested")
        self.assertTrue(process.network_detached)

    def test_local_pipe_failure_is_not_treated_as_a_remote_network_outage(self):
        process = self.process()
        with patch.object(os, "write", side_effect=OSError("broken local pipe")):
            process.set_network_detached(True)
        self.assertIsNotNone(process.stop_at)
        self.assertEqual(process.reason, "app_lease_write_failed")
        self.assertFalse(process.network_detached)

    def test_reconnect_never_writes_to_an_exited_or_unstarted_worker(self):
        for child in (None, SimpleNamespace(poll=lambda: 0)):
            process = self.process()
            process.process = child
            with patch.object(os, "write") as write:
                process.set_network_detached(True)
                process.set_network_detached(False)
                write.assert_not_called()


if __name__ == "__main__":
    unittest.main()
