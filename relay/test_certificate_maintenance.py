from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch

import certificate_maintenance
from certificate_maintenance import maintain_if_idle, network_busy
from configuration import RelaySettings, maintenance_timer, maintenance_unit


class CertificateMaintenanceTest(unittest.TestCase):
    def test_active_control_prevents_restart(self):
        changed = []
        self.assertEqual("busy_deferred", maintain_if_idle(lambda: True, changed.append, lambda _: True))
        self.assertEqual([], changed)

    def test_continuous_idle_still_requires_manual_maintenance(self):
        clock = [0.0]
        changed = []
        inspected = []
        result = maintain_if_idle(lambda: False, changed.append, inspected.append,
                                  now=lambda: clock[0], sleep=lambda delay: clock.__setitem__(0, clock[0] + delay))
        self.assertEqual("manual_maintenance_required", result)
        self.assertGreaterEqual(clock[0], 5)
        self.assertEqual([], changed)
        self.assertEqual([], inspected)

    def test_client_accepted_after_last_idle_snapshot_cannot_trigger_restart(self):
        connected = [False]
        probes = [0]
        changed = []

        def busy():
            probes[0] += 1
            snapshot = connected[0]
            if probes[0] == 2:
                # Reproduce the old check-then-restart race: the snapshot is
                # already idle, but a new control connection enters before
                # the caller can act on it. No polling frequency fixes this.
                connected[0] = True
            return snapshot

        result = maintain_if_idle(busy, lambda unit: changed.append((unit, connected[0])),
                                  lambda _: True, idle_seconds=0)
        self.assertTrue(connected[0])
        self.assertEqual("manual_maintenance_required", result)
        self.assertEqual([], changed)

    def test_arriving_client_during_idle_window_defers(self):
        clock = [0.0]
        changed = []
        result = maintain_if_idle(lambda: clock[0] >= 2, changed.append, lambda _: True,
                                  now=lambda: clock[0], sleep=lambda delay: clock.__setitem__(0, clock[0] + delay))
        self.assertEqual("busy_deferred", result)
        self.assertEqual([], changed)

    def test_stopped_services_are_not_started(self):
        changed = []
        result = maintain_if_idle(lambda: False, changed.append, lambda _: False, idle_seconds=0)
        self.assertEqual("manual_maintenance_required", result)
        self.assertEqual([], changed)

    def test_report_retains_pending_certificate_marker_even_when_idle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "certificate-maintenance-pending.json"
            original = b'{"issuedAt":"synthetic-renewal"}\n'
            marker.write_bytes(original)
            (root / "settings.json").write_text(json.dumps(RelaySettings().__dict__))
            clock = [0.0]
            with patch.object(certificate_maintenance, "CONFIG_ROOT", directory), \
                    patch.object(certificate_maintenance, "network_busy", return_value=False), \
                    patch.object(certificate_maintenance, "maintain_if_idle", wraps=lambda busy, restart, active:
                        maintain_if_idle(busy, restart, active, now=lambda: clock[0],
                            sleep=lambda delay: clock.__setitem__(0, clock[0] + delay))), \
                    patch("builtins.print"):
                certificate_maintenance.main()
            self.assertEqual(original, marker.read_bytes())
            report = json.loads((root / "certificate-maintenance-last.json").read_text())
            self.assertEqual("manual_maintenance_required", report["outcome"])

    def test_timer_describes_reporting_and_does_not_execute_service_restart(self):
        self.assertIn("pending manual coordination", maintenance_unit())
        self.assertIn("without service restarts", maintenance_timer())
        self.assertNotIn("systemctl", maintenance_unit())

    def test_control_video_and_media_allocation_tables(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("tcp", "tcp6", "udp", "udp6"):
                (root / name).write_text("header\n")
            def tcp(port, state):
                return f"0: 00000000:{port:04X} 00000000:0000 {state} rest\n"
            (root / "tcp").write_text("header\n" + tcp(2443, "0A"))
            self.assertFalse(network_busy(RelaySettings(), root))
            for port in (2443, 8443):
                (root / "tcp").write_text("header\n" + tcp(port, "01"))
                self.assertTrue(network_busy(RelaySettings(), root))
            (root / "tcp").write_text("header\n")
            (root / "udp").write_text("header\n" + tcp(62000, "07"))
            self.assertTrue(network_busy(RelaySettings(), root))
            (root / "udp").write_text("header\nbroken\n")
            self.assertTrue(network_busy(RelaySettings(), root))


if __name__ == "__main__":
    unittest.main()
