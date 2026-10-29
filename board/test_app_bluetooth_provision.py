"""Offline protocol, crash recovery, and guarded network transaction tests."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid

import app_bluetooth_provision as pair


PASSWORD = "NeverLogThisPassword42"
SSID = "Phone hotspot"


class MemoryStore:
    def __init__(self):
        self.rows, self.fail = [], False

    def write(self, value):
        if self.fail:
            raise OSError("private disk unavailable")
        self.rows.append(json.loads(json.dumps(value)))

    def read(self):
        return self.rows[-1] if self.rows else None


class FakeSystem(pair.HotspotSystem):
    def __init__(self, directory):
        super().__init__(secret_root=directory)
        self.idle, self.locked = True, False
        self.current = "f550c401-1217-405e-9853-41e50c71db40"
        self.created, self.calls = None, []
        self.fail_up, self.fail_address = False, False
        self.extra_active = set()

    def is_root(self):
        return True

    @contextmanager
    def idle_guard(self):
        if not self.idle:
            raise pair.JoinError("control_busy_or_unconfirmed")
        self.locked = True
        try:
            yield
        finally:
            self.locked = False

    def network(self):
        return dict(state=100, supports2GHz=True, profileUuid=self.current,
                    addresses=["192.168.57.12"], ssid=SSID)

    def profile_uuids(self):
        return {self.current}

    def active_uuids(self):
        return {self.current} | self.extra_active

    def add_profile(self, profile_uuid, ssid):
        assert self.locked
        self.calls.append("create")
        self.created = profile_uuid

    def activate(self, profile_uuid, secret_path):
        assert self.locked
        assert Path(secret_path).read_text() == "802-11-wireless-security.psk:" + PASSWORD + "\n"
        self.calls.append("activate")
        if self.fail_up:
            raise pair.JoinError("networkmanager_command_failed")
        self.current = profile_uuid

    def cleanup_profile(self, profile_uuid, ssid):
        assert self.locked and profile_uuid == self.created
        self.calls.append("cleanup_own")
        return "deleted_and_transition_settled"

    def owns_profile(self, profile_uuid, ssid):
        return profile_uuid == self.created and ssid == SSID

    def address_result(self, profile_uuid):
        if self.fail_address:
            raise pair.JoinError("network_address_unconfirmed")
        assert self.current == profile_uuid
        return dict(ip="192.168.57.12", gateway="192.168.57.1", prefixLength=24)


class ProvisionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store, self.join_store = MemoryStore(), MemoryStore()
        self.state = pair.PairingState(self.root / "state.json", self.store)
        self.system = FakeSystem(self.root)
        self.jobs, self.replies = [], []
        self.provisioner = pair.Provisioner("followingcar-test", self.system, self.state,
                                           self.jobs.append, self.join_store)

    def tearDown(self):
        self.temp.cleanup()

    def request(self, command="provision", request_id=None, **extra):
        result = dict(v=1, requestId=request_id or str(uuid.uuid4()), cmd=command)
        if command == "provision":
            result.update(ssid=SSID, psk=PASSWORD)
        result.update(extra)
        return result

    def handle(self, request, emit=None):
        self.provisioner.handle(request, emit or self.replies.append)

    def test_success_uses_guarded_transaction_and_verified_addresses(self):
        request = self.request()
        self.handle(request)
        self.assertEqual("accepted", self.replies[-1]["stage"])
        self.assertEqual([], self.system.calls)
        self.jobs.pop()()
        self.assertEqual(["create", "activate"], self.system.calls)
        self.assertEqual(["accepted", "connecting"], [r["stage"] for r in self.replies if r["type"] == "progress"])
        self.assertEqual("192.168.57.12", self.replies[-1]["ip"])
        self.assertEqual(24, self.replies[-1]["prefixLength"])
        self.assertTrue(self.replies[-1]["ok"])
        self.assertIsNone(self.provisioner.active_id)
        self.assertNotIn(PASSWORD, json.dumps(self.store.rows + self.join_store.rows + self.replies))
        self.assertEqual([], list(self.root.glob("*.secret")))

    def test_busy_control_rejects_without_accept_or_mutation(self):
        self.system.idle = False
        self.handle(self.request())
        self.assertEqual("control_busy_or_unconfirmed", self.replies[-1]["error"])
        self.assertFalse(self.replies[-1]["networkChanged"])
        self.assertEqual([], self.jobs)
        self.assertEqual([], self.system.calls)

    def test_control_becomes_busy_after_accept_is_rechecked(self):
        self.handle(self.request())
        self.system.idle = False
        self.jobs.pop()()
        self.assertFalse(self.replies[-1]["ok"])
        self.assertEqual([], self.system.calls)

    def test_second_operation_rejected_while_first_pending(self):
        self.handle(self.request())
        self.handle(self.request())
        self.assertEqual("provisioning_busy", self.replies[-1]["error"])
        self.assertEqual(1, len(self.jobs))

    def test_duplicate_request_does_not_reapply_even_changed_credentials(self):
        request = self.request()
        self.handle(request)
        self.handle(dict(request, ssid="different hotspot"))
        self.assertEqual(1, len(self.jobs))
        self.jobs.pop()()
        self.handle(request)
        self.assertTrue(self.replies[-1]["ok"])
        self.assertEqual(["create", "activate"], self.system.calls)

    def test_disconnected_client_does_not_cancel_accepted_switch(self):
        request = self.request()
        def disconnected(_):
            raise BrokenPipeError()
        self.handle(request, disconnected)
        self.jobs.pop()()
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertTrue(self.replies[-1]["lastResult"]["ok"])
        self.assertEqual(request["requestId"], self.replies[-1]["lastResult"]["requestId"])

    def test_unknown_request_status_does_not_return_other_result(self):
        self.handle(self.request())
        self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
        self.assertIsNone(self.replies[-1]["lastResult"])
        self.assertTrue(self.replies[-1]["operationInProgress"])
        self.assertFalse(self.replies[-1]["controlAvailable"])

    def test_failed_activation_only_cleans_own_profile(self):
        self.system.fail_up = True
        self.handle(self.request())
        self.jobs.pop()()
        self.assertEqual(["create", "activate", "cleanup_own"], self.system.calls)
        self.assertEqual("networkmanager_command_failed", self.replies[-1]["error"])
        self.assertTrue(self.replies[-1]["networkChanged"])

    def test_uncertain_addresses_leave_verified_link_intact(self):
        self.system.fail_address = True
        self.handle(self.request())
        self.jobs.pop()()
        self.assertEqual(["create", "activate"], self.system.calls)
        self.assertEqual("network_address_unconfirmed", self.replies[-1]["error"])
        self.assertTrue(self.replies[-1]["networkChanged"])

    def test_state_write_failure_before_accept_prevents_mutation(self):
        self.store.fail = True
        self.handle(self.request())
        self.assertEqual([], self.jobs)
        self.assertEqual([], self.system.calls)
        self.assertFalse(self.replies[-1]["networkChanged"])

    def test_join_record_write_failure_prevents_nm_mutation(self):
        self.handle(self.request())
        self.join_store.fail = True
        self.jobs.pop()()
        self.assertEqual([], self.system.calls)
        self.assertFalse(self.replies[-1]["ok"])

    def test_status_has_no_network_mutations_or_credentials(self):
        self.handle(self.request("status"))
        self.assertTrue(self.replies[-1]["controlAvailable"])
        self.assertEqual("2.4GHz", self.replies[-1]["band"])
        self.assertEqual([], self.system.calls)

    def failed_unknown_operation(self):
        request = self.request()
        self.system.fail_address = True
        self.handle(request)
        self.jobs.pop()()
        self.assertFalse(self.replies[-1]["definitive"])
        return request

    def test_readonly_recovery_after_original_network_restored(self):
        request = self.failed_unknown_operation()
        self.system.current = self.join_store.rows[-1]["previousConnectionUuid"]
        previous_calls = list(self.system.calls)
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        recovered = self.replies[-1]["lastResult"]
        self.assertTrue(recovered["definitive"])
        self.assertFalse(recovered["ok"])
        self.assertFalse(recovered["networkChanged"])
        self.assertEqual("network_restored", recovered["error"])
        self.assertEqual("network_address_unconfirmed", recovered["firstError"])
        self.assertEqual(previous_calls, self.system.calls)

    def test_readonly_recovery_after_addresses_become_confirmed(self):
        request = self.failed_unknown_operation()
        self.system.fail_address = False
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        recovered = self.replies[-1]["lastResult"]
        self.assertTrue(recovered["definitive"])
        self.assertTrue(recovered["ok"])
        self.assertEqual("192.168.57.12", recovered["ip"])
        self.assertEqual(["create", "activate"], self.system.calls)

    def test_recovery_never_uses_another_request_latest_record(self):
        request = self.failed_unknown_operation()
        self.system.current = self.join_store.rows[-1]["previousConnectionUuid"]
        self.join_store.rows[-1]["requestId"] = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_busy_control_keeps_recovery_unknown(self):
        request = self.failed_unknown_operation()
        self.system.current = self.join_store.rows[-1]["previousConnectionUuid"]
        self.system.idle = False
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_foreign_profile_keeps_recovery_unknown(self):
        request = self.failed_unknown_operation()
        self.system.current = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_prior_network_with_own_profile_still_active_keeps_unknown(self):
        request = self.failed_unknown_operation()
        self.system.extra_active.add(self.system.current)
        self.system.current = self.join_store.rows[-1]["previousConnectionUuid"]
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_unconfirmed_profile_identity_keeps_recovery_unknown(self):
        request = self.failed_unknown_operation()
        self.system.fail_address = False
        self.join_store.rows[-1]["requestedSsid"] = "another hotspot"
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_missing_ip_or_wrong_interface_keeps_recovery_unknown(self):
        request = self.failed_unknown_operation()
        self.system.current = self.join_store.rows[-1]["previousConnectionUuid"]
        self.join_store.rows[-1]["interface"] = "eth0"
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        self.join_store.rows[-1]["interface"] = "wlan0"
        original_network = self.system.network
        self.system.network = lambda: dict(original_network(), addresses=[])
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_success_cache_is_rechecked_after_external_network_restore(self):
        request = self.request()
        self.handle(request)
        self.jobs.pop()()
        self.assertTrue(self.replies[-1]["ok"])
        self.system.current = self.join_store.rows[-1]["previousConnectionUuid"]
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["ok"])
        self.assertTrue(self.replies[-1]["lastResult"]["definitive"])
        self.assertEqual("network_restored", self.replies[-1]["lastResult"]["error"])

    def test_success_cache_cannot_claim_current_connection_while_busy(self):
        request = self.request()
        self.handle(request)
        self.jobs.pop()()
        self.system.idle = False
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["ok"])
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        self.assertEqual("current_network_unconfirmed", self.replies[-1]["lastResult"]["error"])


class ProtocolTests(unittest.TestCase):
    def wire(self, **extra):
        return (json.dumps(dict(v=1, requestId=str(uuid.uuid4()), cmd="status", **extra)) + "\n").encode()

    def test_status_accepts_valid_uuid(self):
        self.assertEqual("status", pair.parse_request(self.wire())["cmd"])

    def test_unknown_fields_rejected(self):
        with self.assertRaises(pair.JoinError):
            pair.parse_request(self.wire(command="sudo reboot"))

    def test_duplicate_fields_rejected(self):
        value = self.wire().replace(b'"v": 1', b'"v": 1, "v": 1')
        with self.assertRaisesRegex(pair.JoinError, "duplicate_field"):
            pair.parse_request(value)

    def test_invalid_types_versions_ids_and_commands_rejected(self):
        for patch in ({"v": True}, {"v": 2}, {"cmd": "shell"}, {"requestId": "../../file"}):
            value = json.loads(self.wire())
            value.update(patch)
            with self.subTest(patch=patch), self.assertRaises(pair.JoinError):
                pair.parse_request((json.dumps(value) + "\n").encode())

    def test_oversize_or_missing_newline_rejected(self):
        for value in (b"x" * 4097 + b"\n", self.wire().rstrip(), b"\xff\n"):
            with self.assertRaises(pair.JoinError):
                pair.parse_request(value)

    def test_hotspot_ssid_is_not_restricted_to_direct_prefix(self):
        self.assertEqual("Phone hotspot", pair.validate_credentials(SSID, PASSWORD).ssid)
        self.assertEqual("小车热点", pair.validate_credentials("小车热点", PASSWORD).ssid)

    def test_credentials_reject_injection_and_invalid_encodings(self):
        for ssid in ("", "x" * 33, "bad\nname", "\ud800"):
            with self.assertRaises(pair.JoinError):
                pair.validate_credentials(ssid, PASSWORD)
        for psk in ("short", "bad\npassword", "中文密码密码密码", 123):
            with self.assertRaises(pair.JoinError):
                pair.validate_credentials(SSID, psk)

    def test_credentials_repr_does_not_contain_password(self):
        self.assertNotIn(PASSWORD, repr(pair.validate_credentials(SSID, PASSWORD)))

    @unittest.skipUnless(os.name == "posix", "POSIX private-state file permissions")
    def test_restart_preserves_unknown_and_does_not_rerun(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            state = pair.PairingState(path)
            request_id = str(uuid.uuid4())
            state.put(pair.response(request_id, "progress", stage="connecting"))
            reopened = pair.PairingState(path)
            self.assertEqual("outcome_unknown_after_restart", reopened.get(request_id)["error"])
            self.assertTrue(reopened.get(request_id)["networkChanged"])

    def test_history_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            state = pair.PairingState(Path(directory) / "state.json", MemoryStore())
            for _ in range(24):
                state.put(pair.response(str(uuid.uuid4()), "result", ok=False, error="test"))
            self.assertEqual(pair.MAX_HISTORY, len(state.records))

    def test_slow_drip_without_socket_timeout_cannot_hold_connection(self):
        clock = [0.0]
        class SlowSocket:
            reads, closed = 0, False
            def settimeout(self, _):
                pass
            def recv(self, _):
                self.reads += 1
                clock[0] += 2
                return b" "
            def close(self):
                self.closed = True
        connection = SlowSocket()
        with patch.object(pair.time, "monotonic", lambda: clock[0]):
            pair.serve_socket(connection, None, threading.Event())
        self.assertLessEqual(connection.reads, 7)
        self.assertTrue(connection.closed)


class AddressTests(unittest.TestCase):
    def system(self, output):
        class Addresses(pair.HotspotSystem):
            def network(self):
                return dict(state=100, profileUuid="owned", addresses=["192.168.57.12"])
            def checked(self, *_args, **_kwargs):
                return output
        return Addresses()

    def test_actual_router_and_prefix_are_reported(self):
        result = self.system("IP4.ADDRESS[1]:192.168.57.12/24\nDHCP4.OPTION[1]:routers = 192.168.57.1\n").address_result("owned")
        self.assertEqual(dict(ip="192.168.57.12", gateway="192.168.57.1", prefixLength=24), result)

    def test_local_only_dhcp_server_can_identify_phone_without_default_route(self):
        result = self.system("IP4.ADDRESS[1]:192.168.57.12/24\nDHCP4.OPTION[1]:dhcp_server_identifier = 192.168.57.1\n").address_result("owned")
        self.assertEqual("192.168.57.1", result["gateway"])

    def test_wrong_subnet_multiple_addresses_or_missing_peer_rejected(self):
        for options in ("routers = 192.168.58.1", "routers = 192.168.57.1 192.168.57.2", "domain_name = test"):
            with self.subTest(options=options), self.assertRaises(pair.JoinError):
                self.system("IP4.ADDRESS[1]:192.168.57.12/24\nDHCP4.OPTION[1]:" + options + "\n").address_result("owned")


class PageScanTests(unittest.TestCase):
    def lease(self, settings):
        current, calls = set(settings), []
        def run(arguments):
            calls.append(arguments[-2:])
            if arguments[-1] == "info":
                return "hci0:\n\tcurrent settings: " + " ".join(sorted(current)) + "\n"
            if arguments[-2:] == ["connectable", "on"]:
                current.add("connectable")
            elif arguments[-2:] == ["connectable", "off"]:
                current.discard("connectable")
            else:
                self.fail("Unexpected Bluetooth mutation")
            return ""
        return pair.PageScanLease(run), current, calls

    def test_nonconnectable_controller_enabled_and_owned_change_restored(self):
        lease, current, calls = self.lease({"powered", "br/edr", "bondable"})
        lease.open()
        self.assertIn("connectable", current)
        lease.close()
        self.assertNotIn("connectable", current)
        self.assertIn(["connectable", "on"], calls)
        self.assertIn(["connectable", "off"], calls)

    def test_existing_connectable_state_is_not_disabled(self):
        lease, current, calls = self.lease({"powered", "br/edr", "connectable"})
        lease.open()
        lease.close()
        self.assertIn("connectable", current)
        self.assertNotIn(["connectable", "on"], calls)
        self.assertNotIn(["connectable", "off"], calls)

    def test_powered_off_controller_is_not_powered_on(self):
        lease, current, calls = self.lease({"br/edr"})
        with self.assertRaises(pair.JoinError):
            lease.open()
        self.assertNotIn("powered", current)
        self.assertEqual([["0", "info"]], calls)


if __name__ == "__main__":
    unittest.main()
