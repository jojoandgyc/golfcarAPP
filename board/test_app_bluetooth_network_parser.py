"""Bluetooth status/recovery using the inherited real nmcli parser and fake commands."""
from contextlib import contextmanager
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

import app_bluetooth_provision as pairing
import app_wifi_direct_join as wifi


PROFILE_UUID = "bdf7733a-5db0-432e-b888-6da3df00948f"


class MemoryStore:
    def __init__(self):
        self.rows = []

    def write(self, value):
        self.rows.append(value)

    def read(self):
        return self.rows[-1] if self.rows else None


def disconnected_fields(**changes):
    fields = {"GENERAL.DEVICE": "wlan0", "GENERAL.TYPE": "wifi", "GENERAL.STATE": "30 (disconnected)",
              "GENERAL.NM-MANAGED": "yes", "GENERAL.CON-UUID": "", "GENERAL.CONNECTION": "",
              "IP4.ADDRESS[1]": "", "WIFI-PROPERTIES.2GHZ": "yes", "WIFI-PROPERTIES.5GHZ": "yes"}
    fields.update(changes)
    return fields


class RawHotspotSystem(pairing.HotspotSystem):
    """Do not override network(): exercise HotspotSystem -> BoardSystem parsing."""
    def __init__(self, directory, observations, active_sets=None):
        super().__init__(runner=self.command, secret_root=directory)
        self.observations = observations
        self.active_sets = active_sets or [set()]
        self.network_reads = self.active_reads = 0
        self.calls = []
        self.locked, self.idle = False, True

    @contextmanager
    def idle_guard(self):
        if not self.idle:
            raise wifi.JoinError("control_busy_or_unconfirmed")
        self.locked = True
        try:
            yield
        finally:
            self.locked = False

    def command(self, arguments, timeout=6):
        assert self.locked, "status observations must remain inside the idle guard"
        self.calls.append(arguments)
        if arguments[-2:] == ["device", "status"]:
            output = "wlan0:wifi\np2p-dev-wlan0:wifi-p2p\n"
        elif arguments[-3:] == ["device", "show", "wlan0"]:
            fields = self.observations[min(self.network_reads, len(self.observations) - 1)]
            self.network_reads += 1
            output = "".join(key + ":" + value + "\n" for key, value in fields.items())
        elif arguments == [wifi.NMCLI, "-t", "-f", "UUID", "connection", "show", "--active"]:
            active = self.active_sets[min(self.active_reads, len(self.active_sets) - 1)]
            self.active_reads += 1
            output = "".join(value + "\n" for value in sorted(active))
        elif arguments == [wifi.NMCLI, "-t", "-f", "UUID", "connection", "show"]:
            output = ""  # No saved temporary profile; nothing can auto-reconnect.
        elif arguments[:3] == [wifi.NMCLI, "-g", "802-11-wireless.ssid"]:
            output = "Test network\n"
        else:
            raise AssertionError("Unexpected or mutating NetworkManager command")
        return subprocess.CompletedProcess(arguments, 0, output, "")


class BluetoothRawNetworkParserTests(unittest.TestCase):
    def create(self, observations=None, active_sets=None):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        system = RawHotspotSystem(root, observations or [disconnected_fields()], active_sets)
        state_store, join_store = MemoryStore(), MemoryStore()
        state = pairing.PairingState(root / "state.json", state_store)
        provisioner = pairing.Provisioner("followingcar-test", system, state,
                                          lambda _job: self.fail("status must not start a worker"), join_store)
        return system, state, join_store, provisioner

    def status(self, provisioner, query_id=None):
        query_id = query_id or str(uuid.uuid4())
        result = provisioner.status(dict(v=1, requestId=str(uuid.uuid4()), cmd="status", queryRequestId=query_id))
        return result, query_id

    def assert_not_cleared(self, state, reply, query_id):
        self.assertEqual(reply["recoveryState"], "unavailable")
        self.assertIsNone(reply["lastResult"])
        self.assertIsNone(state.get(query_id), "unknown observation must not write an expiry tombstone")

    def test_real_inherited_parser_empty_disconnected_network_can_clear_missing_request(self):
        self.assertIs(RawHotspotSystem.network, wifi.BoardSystem.network)
        system, state, _store, provisioner = self.create()
        reply, query_id = self.status(provisioner)
        self.assertEqual(reply["recoveryState"], "clear")
        self.assertIsNone(reply["error"])
        self.assertTrue(reply["controlAvailable"])
        self.assertEqual(reply["lastResult"]["error"], "request_expired")
        self.assertTrue(state.get(query_id)["definitive"])
        self.assertEqual(system.network_reads, 2)
        self.assertEqual(system.active_reads, 2)
        self.assertFalse(any("802-11-wireless.ssid" in command for command in system.calls))

    def test_placeholder_uuid_and_addresses_are_also_settled_disconnected(self):
        _system, _state, _store, provisioner = self.create([
            disconnected_fields(**{"GENERAL.CON-UUID": " -- ", "IP4.ADDRESS[1]": " -- "})])
        reply, _ = self.status(provisioner)
        self.assertEqual(reply["recoveryState"], "clear")

    def test_missing_or_malformed_uuid_does_not_clear_recovery(self):
        for value in (None, "invalid-uuid"):
            with self.subTest(value=value):
                fields = disconnected_fields()
                if value is None:
                    fields.pop("GENERAL.CON-UUID")
                else:
                    fields["GENERAL.CON-UUID"] = value
                _system, state, _store, provisioner = self.create([fields])
                reply, query_id = self.status(provisioner)
                self.assert_not_cleared(state, reply, query_id)
                self.assertEqual(reply["error"], "invalid_current_profile_uuid")

    def test_connected_empty_uuid_and_disconnected_leftover_ip_never_clear(self):
        for changes in ({"GENERAL.STATE": "100 (connected)"},
                        {"IP4.ADDRESS[1]": "172.16.16.116/22"},
                        {"GENERAL.CON-UUID": PROFILE_UUID}):
            with self.subTest(changes=changes):
                _system, state, _store, provisioner = self.create([disconnected_fields(**changes)])
                reply, query_id = self.status(provisioner)
                self.assert_not_cleared(state, reply, query_id)

    def test_nonempty_bad_ip_is_not_hidden_by_absent_uuid(self):
        _system, state, _store, provisioner = self.create([
            disconnected_fields(**{"IP4.ADDRESS[1]": "not-an-address"})])
        reply, query_id = self.status(provisioner)
        self.assert_not_cleared(state, reply, query_id)
        self.assertEqual(reply["error"], "invalid_ip_observation")

    def test_changed_second_raw_network_observation_cannot_expire_request(self):
        for changes in ({"GENERAL.STATE": "40 (prepare)"},
                        {"IP4.ADDRESS[1]": "172.16.16.116/22"},
                        {"GENERAL.CON-UUID": PROFILE_UUID},
                        {"GENERAL.CONNECTION": "Another network"}):
            with self.subTest(changes=changes):
                system, state, _store, provisioner = self.create([
                    disconnected_fields(), disconnected_fields(**changes)])
                reply, query_id = self.status(provisioner)
                self.assert_not_cleared(state, reply, query_id)
                self.assertEqual(system.network_reads, 2)

    def test_changed_active_uuid_set_cannot_expire_request(self):
        _system, state, _store, provisioner = self.create(active_sets=[set(), {PROFILE_UUID}])
        reply, query_id = self.status(provisioner)
        self.assert_not_cleared(state, reply, query_id)

    def test_failed_preflight_record_can_be_checked_without_replaying_network_change(self):
        system, state, join_store, provisioner = self.create()
        request_id = str(uuid.uuid4())
        state.put(pairing.response(request_id, "result", ok=False, definitive=True,
                                   networkChanged=False, error="invalid_current_profile_uuid"))
        join_store.write(dict(schemaVersion=1, action="join", method="bluetooth_hotspot", interface="wlan0",
                              profileUuid=PROFILE_UUID, requestId=request_id, requestedSsid="Test hotspot",
                              failedPhase="preflight", phase="failed", ok=False,
                              error="invalid_current_profile_uuid"))
        reply, _ = self.status(provisioner, request_id)
        self.assertEqual(reply["recoveryState"], "clear")
        self.assertTrue(reply["lastResult"]["definitive"])
        self.assertFalse(reply["lastResult"]["ok"])
        self.assertEqual(reply["lastResult"]["error"], "invalid_current_profile_uuid")
        self.assertEqual(system.network_reads, 2)
        self.assertFalse(any(action in command for command in system.calls
                             for action in ("up", "down", "load", "delete", "modify")))

    def test_busy_control_refuses_before_any_nmcli_observation(self):
        system, state, _store, provisioner = self.create()
        system.idle = False
        reply, query_id = self.status(provisioner)
        self.assert_not_cleared(state, reply, query_id)
        self.assertEqual(reply["error"], "control_busy_or_unconfirmed")
        self.assertEqual(system.calls, [])


if __name__ == "__main__":
    unittest.main()
