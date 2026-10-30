"""Offline protocol, crash recovery, and guarded network transaction tests."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import socket
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
        return dict(interface="wlan0", managed=True, state=100 if self.current else 30,
                    supports2GHz=True, profileUuid=self.current,
                    addresses=["192.168.57.12"] if self.current else [], ssid=SSID,
                    profileName="followingcar-direct-" + self.current if self.current == self.created and self.current
                    else "Original Wi-Fi")

    def external_profile_confirmed(self, network):
        return network.get("profileName") == "Original Wi-Fi"

    def profile_uuids(self):
        return {self.current} if self.current else set()

    def active_uuids(self):
        return ({self.current} if self.current else set()) | self.extra_active

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

    def test_socket_replacement_recovers_accepted_operation_without_repeating_switch(self):
        registry, stopped = pair.ProvisioningConnections(), threading.Event()
        old_client, old_server = socket.socketpair()
        new_client, new_server = socket.socketpair()
        old_client.settimeout(2)
        new_client.settimeout(2)
        threads = []
        streams = []
        def start(server):
            registry.accept("phone", server)
            def run():
                try:
                    pair.serve_socket(server, self.provisioner, stopped,
                                      is_current=lambda: registry.is_current(server))
                finally:
                    registry.discard(server)
            thread = threading.Thread(target=run)
            thread.start()
            threads.append(thread)
        def send(client, request):
            client.sendall((json.dumps(request) + "\n").encode())
        try:
            old_stream = old_client.makefile("rb")
            new_stream = new_client.makefile("rb")
            streams.extend([old_stream, new_stream])
            start(old_server)
            request = self.request()
            send(old_client, request)
            self.assertEqual("accepted", json.loads(old_stream.readline())["stage"])
            start(new_server)
            threads[0].join(2)
            self.assertFalse(threads[0].is_alive())
            send(new_client, self.request("status", queryRequestId=request["requestId"]))
            self.assertTrue(json.loads(new_stream.readline())["operationInProgress"])
            self.jobs.pop()()
            send(new_client, self.request("status", queryRequestId=request["requestId"]))
            result = json.loads(new_stream.readline())
            self.assertTrue(result["lastResult"]["ok"])
            self.assertFalse(result["operationInProgress"])
            self.assertEqual(["create", "activate"], self.system.calls)
        finally:
            stopped.set()
            registry.close()
            for stream in streams:
                stream.close()
            for connection in (old_client, new_client, old_server, new_server):
                connection.close()
            for thread in threads:
                thread.join(2)

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
        self.assertEqual("clear", self.replies[-1]["recoveryState"])
        self.assertEqual("2.4GHz", self.replies[-1]["band"])
        self.assertEqual([], self.system.calls)
        self.assertEqual([], self.store.rows)

    def test_absent_request_requires_clear_proof_and_gets_durable_replay_tombstone(self):
        missing_id = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=missing_id))
        status = self.replies[-1]
        self.assertEqual("clear", status["recoveryState"])
        expired = status["lastResult"]
        self.assertEqual(missing_id, expired["requestId"])
        self.assertEqual("request_expired", expired["error"])
        self.assertTrue(expired["definitive"])
        self.assertFalse(expired["ok"] or expired["networkChanged"])
        self.assertEqual(expired, self.store.rows[-1][-1])
        self.handle(self.request(request_id=missing_id))
        self.assertEqual(expired, self.replies[-1])
        self.assertEqual([], self.jobs)
        self.assertEqual([], self.system.calls)
        self.handle(self.request("status", queryRequestId=missing_id))
        self.assertEqual(1, len(self.store.rows))

    def test_tombstone_persistence_failure_cannot_claim_absent_request_is_resolved(self):
        self.store.fail = True
        self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
        self.assertEqual("unavailable", self.replies[-1]["recoveryState"])
        self.assertIsNone(self.replies[-1]["lastResult"])
        self.assertEqual([], self.state.records)
        self.assertEqual([], self.system.calls)

    def test_missing_request_while_worker_or_unknown_transaction_exists_cannot_expire(self):
        request = self.request()
        self.handle(request)
        missing_id = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=missing_id))
        self.assertEqual("pending", self.replies[-1]["recoveryState"])
        self.assertEqual(request["requestId"], self.replies[-1]["recoveryRequestId"])
        self.assertIsNone(self.replies[-1]["lastResult"])
        self.system.fail_address = True
        self.jobs.pop()()
        self.handle(self.request("status", queryRequestId=missing_id))
        self.assertEqual("pending", self.replies[-1]["recoveryState"])
        self.assertIsNone(self.state.get(missing_id))

    def test_connected_owned_hotspot_returns_recoverable_id_instead_of_clear(self):
        request = self.request()
        self.handle(request)
        self.jobs.pop()()
        self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
        self.assertEqual("pending", self.replies[-1]["recoveryState"])
        self.assertEqual(request["requestId"], self.replies[-1]["recoveryRequestId"])
        self.assertIsNone(self.replies[-1]["lastResult"])
        self.assertEqual(1, len(self.state.records))

    def test_untracked_owned_hotspot_or_uncertain_profile_identity_cannot_clear(self):
        network = self.system.network()
        with patch.object(self.system, "network", return_value=dict(network,
                profileName="followingcar-direct-" + self.system.current)):
            self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
        self.assertEqual("unavailable", self.replies[-1]["recoveryState"])
        self.assertIsNone(self.replies[-1]["lastResult"])
        with patch.object(self.system, "external_profile_confirmed", return_value=False):
            self.handle(self.request("status"))
        self.assertEqual("unavailable", self.replies[-1]["recoveryState"])
        self.assertEqual([], self.state.records)

    def test_busy_control_and_live_finishing_worker_do_not_report_clear(self):
        self.system.idle = False
        self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
        self.assertFalse(self.replies[-1]["controlAvailable"])
        self.assertEqual("unavailable", self.replies[-1]["recoveryState"])
        self.assertIsNone(self.replies[-1]["lastResult"])
        self.system.idle = True
        class FinishingWorker:
            def is_alive(self):
                return True
        self.provisioner.worker = FinishingWorker()
        self.handle(self.request("status"))
        self.assertTrue(self.replies[-1]["operationInProgress"])
        self.assertNotEqual("clear", self.replies[-1]["recoveryState"])

    def test_network_flap_or_missing_active_uuid_prevents_clear_and_tombstone(self):
        network = self.system.network()
        for key, value in (("state", 40), ("profileUuid", str(uuid.uuid4())),
                           ("profileName", "Another network"), ("addresses", [])):
            with self.subTest(key=key), patch.object(self.system, "network",
                    side_effect=[network, dict(network, **{key: value})]):
                self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
            self.assertEqual("unavailable", self.replies[-1]["recoveryState"])
            self.assertIsNone(self.replies[-1]["lastResult"])
        for observed in ([set()], [{self.system.current}, set()]):
            with patch.object(self.system, "active_uuids", side_effect=observed):
                self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
            self.assertEqual("unavailable", self.replies[-1]["recoveryState"])
        self.assertEqual([], self.store.rows)

    def test_disconnected_or_invalid_address_cannot_clear_recovery(self):
        network = self.system.network()
        for values in (dict(state=30), dict(addresses=[]), dict(addresses=["169.254.2.1"]),
                       dict(addresses=["127.0.0.1"]), dict(addresses=["invalid"]), dict(profileUuid=None)):
            with self.subTest(values=values), patch.object(self.system, "network",
                    return_value=dict(network, **values)):
                self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
            self.assertEqual("unavailable", self.replies[-1]["recoveryState"])
            self.assertIsNone(self.replies[-1]["lastResult"])
        self.assertEqual([], self.store.rows)

    def test_corrupted_network_journal_prevents_clear(self):
        self.join_store.rows.append({"unexpected": True})
        self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
        self.assertEqual("unavailable", self.replies[-1]["recoveryState"])
        self.assertIsNone(self.replies[-1]["lastResult"])

    def test_incomplete_history_record_is_pending_not_clear(self):
        previous_id = str(uuid.uuid4())
        self.state.put(pair.response(previous_id, "result", error="incomplete_record"))
        self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
        self.assertEqual("pending", self.replies[-1]["recoveryState"])
        self.assertEqual(previous_id, self.replies[-1]["recoveryRequestId"])
        self.assertIsNone(self.replies[-1]["lastResult"])

    def test_replaced_socket_cannot_submit_previously_queued_credentials(self):
        request = self.request()
        self.provisioner.handle(request, self.replies.append, request_is_current=lambda: False)
        self.assertEqual([], self.replies)
        self.assertEqual([], self.jobs)
        self.assertEqual([], self.state.records)
        self.assertEqual([], self.system.calls)

    @unittest.skipUnless(os.name == "posix", "POSIX private-state file permissions")
    def test_expiration_tombstone_survives_daemon_restart(self):
        path = self.root / "durable-state.json"
        self.provisioner.state = pair.PairingState(path)
        request_id = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=request_id))
        reopened = pair.PairingState(path)
        replacement = pair.Provisioner("followingcar-test", self.system, reopened,
                                       self.jobs.append, self.join_store)
        replacement.handle(self.request(request_id=request_id), self.replies.append)
        self.assertEqual("request_expired", self.replies[-1]["error"])
        self.assertEqual([], self.jobs)
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

    def test_lost_successful_hotspot_without_another_wifi_is_definitively_expired(self):
        request = self.request()
        self.handle(request)
        self.jobs.pop()()
        self.system.current = None
        calls = list(self.system.calls)
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        status = self.replies[-1]
        self.assertEqual("hotspot_disconnected", status["lastResult"]["error"])
        self.assertTrue(status["lastResult"]["definitive"])
        self.assertFalse(status["lastResult"]["ok"] or status["lastResult"]["networkChanged"])
        self.assertEqual("clear", status["recoveryState"])
        self.assertEqual(calls, self.system.calls)
        self.handle(self.request())
        self.assertEqual("accepted", self.replies[-1]["stage"])
        self.assertEqual(calls, self.system.calls)  # New credentials queued, not silently replayed.

    def test_disconnected_unknown_history_requires_matching_query_before_clear(self):
        request = self.failed_unknown_operation()
        self.system.current = None
        self.handle(self.request("status"))
        self.assertEqual("pending", self.replies[-1]["recoveryState"])
        self.assertEqual(request["requestId"], self.replies[-1]["recoveryRequestId"])
        missing_id = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=missing_id))
        self.assertEqual("pending", self.replies[-1]["recoveryState"])
        self.assertIsNone(self.replies[-1]["lastResult"])
        self.assertIsNone(self.state.get(missing_id))
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertEqual("clear", self.replies[-1]["recoveryState"])
        self.assertEqual("hotspot_disconnected", self.replies[-1]["lastResult"]["error"])
        self.assertEqual("network_address_unconfirmed", self.replies[-1]["lastResult"]["firstError"])
        self.assertEqual(["create", "activate"], self.system.calls)

    def test_stably_disconnected_fresh_board_can_expire_a_missing_request(self):
        self.system.current = None
        missing_id = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=missing_id))
        self.assertEqual("clear", self.replies[-1]["recoveryState"])
        self.assertEqual("request_expired", self.replies[-1]["lastResult"]["error"])
        self.handle(self.request(request_id=missing_id))
        self.assertEqual("request_expired", self.replies[-1]["error"])
        self.assertEqual([], self.jobs)
        self.assertEqual([], self.system.calls)

    def test_transitioning_unmanaged_or_inconsistent_disconnect_is_not_final(self):
        request = self.failed_unknown_operation()
        self.system.current = None
        disconnected = self.system.network()
        invalid = [dict(state=value) for value in (20, 40, 50, 60, 70, 80, 90, 110, 120)]
        invalid.extend([dict(managed=False), dict(managed=None), dict(interface="wlan1"),
                        dict(profileUuid=self.system.created), dict(addresses=["192.168.57.12"])])
        for changed in invalid:
            with self.subTest(changed=changed), patch.object(self.system, "network",
                    return_value=dict(disconnected, **changed)):
                self.handle(self.request("status", queryRequestId=request["requestId"]))
                self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
                self.assertNotEqual("clear", self.replies[-1]["recoveryState"])
        self.assertEqual(["create", "activate"], self.system.calls)

    def test_disconnected_observation_must_stay_identical_and_active_set_must_not_change(self):
        request = self.failed_unknown_operation()
        self.system.current = None
        disconnected = self.system.network()
        for changed in (dict(state=40), dict(managed=False), dict(profileUuid=self.system.created),
                        dict(addresses=["192.168.57.12"])):
            with self.subTest(changed=changed), patch.object(self.system, "network",
                    side_effect=[disconnected, dict(disconnected, **changed)]):
                self.handle(self.request("status", queryRequestId=request["requestId"]))
                self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        with patch.object(self.system, "active_uuids", side_effect=[set(), {self.system.created}]):
            self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        self.assertEqual(["create", "activate"], self.system.calls)

    def test_disconnected_recovery_requires_old_profile_inactive_and_non_autoconnecting(self):
        request = self.failed_unknown_operation()
        self.system.current = None
        self.system.extra_active.add(self.system.created)
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        self.system.extra_active.clear()
        with patch.object(self.system, "inactive_profile_safe", return_value=False):
            self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_control_busy_or_worker_alive_cannot_resolve_a_disconnected_hotspot(self):
        request = self.failed_unknown_operation()
        self.system.current = None
        self.system.idle = False
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        self.assertFalse(self.replies[-1]["controlAvailable"])
        self.system.idle = True
        class ActiveWorker:
            def is_alive(self):
                return True
        self.provisioner.worker = ActiveWorker()
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        self.assertTrue(self.replies[-1]["operationInProgress"])
        self.assertEqual("pending", self.replies[-1]["recoveryState"])

    def test_disconnected_resolution_disk_failure_does_not_unlock_unknown_request(self):
        request = self.failed_unknown_operation()
        self.system.current = None
        self.store.fail = True
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        self.assertEqual("pending", self.replies[-1]["recoveryState"])

    def test_missing_record_cannot_be_expired_during_disconnected_network_flap(self):
        self.system.current = None
        disconnected = self.system.network()
        for changed in (dict(state=40), dict(managed=False), dict(addresses=["192.168.57.12"])):
            with self.subTest(changed=changed), patch.object(self.system, "network",
                    side_effect=[disconnected, dict(disconnected, **changed)]):
                self.handle(self.request("status", queryRequestId=str(uuid.uuid4())))
            self.assertEqual("unavailable", self.replies[-1]["recoveryState"])
            self.assertIsNone(self.replies[-1]["lastResult"])
        self.assertEqual([], self.state.records)

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

    def test_settled_alternate_network_ends_old_lease_without_claiming_success(self):
        request = self.failed_unknown_operation()
        self.system.current = str(uuid.uuid4())
        calls = list(self.system.calls)
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        result = self.replies[-1]["lastResult"]
        self.assertTrue(result["definitive"])
        self.assertFalse(result["ok"])
        self.assertFalse(result["networkChanged"])
        self.assertEqual("network_superseded", result["error"])
        self.assertNotIn("ip", result)
        self.assertEqual("network_address_unconfirmed", result["firstError"])
        self.assertEqual(calls, self.system.calls)

    def test_successful_historical_lease_on_alternate_network_is_expired(self):
        request = self.request()
        self.handle(request)
        self.jobs.pop()()
        self.system.current = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        result = self.replies[-1]["lastResult"]
        self.assertEqual("network_superseded", result["error"])
        self.assertTrue(result["definitive"])
        self.assertFalse(result["ok"])

    def test_alternate_network_does_not_resolve_while_old_profile_can_autoconnect(self):
        request = self.failed_unknown_operation()
        self.system.current = str(uuid.uuid4())
        with patch.object(self.system, "inactive_profile_safe", return_value=False):
            self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_alternate_network_with_old_profile_still_active_remains_unknown(self):
        request = self.failed_unknown_operation()
        self.system.extra_active.add(self.system.current)
        self.system.current = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_changing_network_or_active_set_cannot_resolve_old_lease(self):
        request = self.failed_unknown_operation()
        self.system.current = str(uuid.uuid4())
        network = self.system.network()
        for observations in ([network, dict(network, profileUuid=str(uuid.uuid4()))],
                             [network, dict(network, addresses=[])]):
            with self.subTest(observations=observations), patch.object(self.system, "network", side_effect=observations):
                self.handle(self.request("status", queryRequestId=request["requestId"]))
            self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        with patch.object(self.system, "active_uuids", side_effect=[{self.system.current},
                {self.system.current, self.system.created}]):
            self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_invalid_current_address_cannot_resolve_old_lease(self):
        request = self.failed_unknown_operation()
        self.system.current = str(uuid.uuid4())
        network = self.system.network()
        for address in ("127.0.0.1", "0.0.0.1", "169.254.1.2", "224.0.0.1", "bad"):
            with self.subTest(address=address), patch.object(self.system, "network",
                    return_value=dict(network, addresses=[address])):
                self.handle(self.request("status", queryRequestId=request["requestId"]))
            self.assertFalse(self.replies[-1]["lastResult"]["definitive"])

    def test_recovery_can_start_from_previously_disconnected_wifi(self):
        self.system.current = None
        request = self.failed_unknown_operation()
        self.assertIsNone(self.join_store.rows[-1]["previousConnectionUuid"])
        self.system.fail_address = False
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        result = self.replies[-1]["lastResult"]
        self.assertTrue(result["ok"])
        self.assertTrue(result["definitive"])
        self.assertEqual(["create", "activate"], self.system.calls)

    def test_unknown_operation_blocks_new_credentials_and_preserves_network_record(self):
        request = self.failed_unknown_operation()
        record = dict(self.join_store.rows[-1])
        self.handle(self.request())
        self.assertEqual("recovery_pending", self.replies[-1]["error"])
        self.assertEqual(request["requestId"], self.replies[-1]["recoveryRequestId"])
        self.assertEqual(record, self.join_store.rows[-1])
        self.assertEqual([], self.jobs)
        self.assertEqual(["create", "activate"], self.system.calls)

    def test_status_can_recover_lost_phone_request_id_without_reflecting_secrets(self):
        request = self.failed_unknown_operation()
        self.handle(self.request("status"))
        self.assertEqual(request["requestId"], self.replies[-1]["recoveryRequestId"])
        self.assertIsNone(self.replies[-1]["lastResult"])
        self.assertNotIn(SSID, json.dumps(self.replies[-1]))
        self.assertNotIn(PASSWORD, json.dumps(self.replies[-1]))
        self.system.current = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertNotIn("recoveryRequestId", self.replies[-1])

    def test_new_request_can_continue_after_readonly_resolution_of_expired_lease(self):
        self.failed_unknown_operation()
        self.system.current = str(uuid.uuid4())
        self.handle(self.request())
        self.assertEqual("accepted", self.replies[-1]["stage"])
        self.assertEqual(1, len(self.jobs))
        self.assertEqual(["create", "activate"], self.system.calls)

    def test_worker_launch_and_final_disk_failure_still_returns_queryable_terminal_result(self):
        request = self.request()
        def fail_launch(_):
            self.store.fail = True
            raise RuntimeError("thread unavailable")
        self.provisioner.start_thread = fail_launch
        self.handle(request)
        self.assertEqual("worker_unavailable", self.replies[-1]["error"])
        self.assertTrue(self.replies[-1]["definitive"])
        self.assertFalse(self.replies[-1]["networkChanged"])
        self.assertEqual("unavailable", self.replies[-1]["resultRecording"])
        self.assertEqual(1, len(self.state.records))
        self.assertIsNone(self.provisioner.active_id)
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        self.assertEqual("worker_unavailable", self.replies[-1]["lastResult"]["error"])
        self.assertNotIn("recoveryRequestId", self.replies[-1])
        self.assertEqual([], self.system.calls)

    def test_terminal_disk_failure_does_not_leave_duplicate_progress_or_hide_first_error(self):
        request = self.failed_unknown_operation()
        self.store.fail = True
        self.system.current = str(uuid.uuid4())
        self.handle(self.request("status", queryRequestId=request["requestId"]))
        # Reconciliation cannot claim durable recovery when that write fails.
        self.assertFalse(self.replies[-1]["lastResult"]["definitive"])
        self.assertEqual("network_address_unconfirmed", self.replies[-1]["lastResult"]["error"])

    def test_final_write_failure_keeps_one_queryable_result_without_repeating_activation(self):
        request = self.request()
        original = self.system.address_result
        def addresses(profile_uuid):
            result = original(profile_uuid)
            self.store.fail = True
            return result
        self.system.address_result = addresses
        self.handle(request)
        self.jobs.pop()()
        self.assertTrue(self.replies[-1]["ok"])
        self.assertEqual("unavailable", self.replies[-1]["resultRecording"])
        self.assertEqual(1, len(self.state.records))
        self.assertEqual("result", self.state.records[0]["type"])
        self.assertEqual(["create", "activate"], self.system.calls)

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


class InactiveLeaseTests(unittest.TestCase):
    def test_external_profile_identity_is_checked_against_actual_wlan0_observation(self):
        system = pair.HotspotSystem()
        profile_uuid = str(uuid.uuid4())
        network = dict(profileUuid=profile_uuid, profileName="Original network")
        fields = {"connection.id": "Original network", "connection.uuid": profile_uuid,
                  "connection.interface-name": "wlan0"}
        for pin in ("wlan0", "", "--"):
            output = "\n".join(key + ":" + value for key, value in
                               dict(fields, **{"connection.interface-name": pin}).items())
            with self.subTest(pin=pin), patch.object(system, "checked", return_value=output):
                self.assertTrue(system.external_profile_confirmed(network))
        for updates in ({"connection.id": "followingcar-direct-" + profile_uuid},
                        {"connection.id": "Other network"}, {"connection.interface-name": "wlan1"},
                        {"connection.uuid": str(uuid.uuid4())}):
            output = "\n".join(key + ":" + value for key, value in dict(fields, **updates).items())
            with self.subTest(updates=updates), patch.object(system, "checked", return_value=output):
                self.assertFalse(system.external_profile_confirmed(network))

    def test_absent_profile_needs_no_configuration_read_or_mutation(self):
        system = pair.HotspotSystem()
        with patch.object(system, "profile_uuids", return_value=set()), \
                patch.object(system, "checked") as command:
            self.assertTrue(system.inactive_profile_safe("owned", SSID))
        command.assert_not_called()

    def test_retained_profile_must_be_owned_and_explicitly_non_autoconnecting(self):
        system = pair.HotspotSystem()
        with patch.object(system, "profile_uuids", return_value={"owned"}), \
                patch.object(system, "owns_profile", return_value=True), \
                patch.object(system, "checked", return_value="no\n") as command:
            self.assertTrue(system.inactive_profile_safe("owned", SSID))
            self.assertNotIn(SSID, command.call_args.args[0])
            self.assertEqual("connection.autoconnect", command.call_args.args[0][2])
            for value in ("yes", "unknown", "", "no\nyes"):
                command.return_value = value
                self.assertFalse(system.inactive_profile_safe("owned", SSID))
        with patch.object(system, "profile_uuids", return_value={"owned"}), \
                patch.object(system, "owns_profile", return_value=False), \
                patch.object(system, "checked") as command:
            self.assertFalse(system.inactive_profile_safe("owned", SSID))
        command.assert_not_called()


class ConnectionRecoveryTests(unittest.TestCase):
    class Connection:
        shutdowns = 0
        def shutdown(self, _):
            self.shutdowns += 1

    def test_same_authenticated_owner_can_replace_stale_connection_immediately(self):
        registry = pair.ProvisioningConnections()
        old, new = self.Connection(), self.Connection()
        registry.accept("phone", old)
        registry.accept("phone", new)
        self.assertEqual(1, old.shutdowns)
        self.assertEqual(0, new.shutdowns)
        registry.discard(old)  # Old worker exit must not remove the new owner.
        self.assertEqual({new: "phone"}, registry.connections)
        registry.close("phone")
        self.assertEqual(1, new.shutdowns)

    def test_different_owner_cannot_interrupt_existing_authenticated_session(self):
        registry = pair.ProvisioningConnections()
        old, new = self.Connection(), self.Connection()
        registry.accept("phone", old)
        with self.assertRaisesRegex(pair.JoinError, "provisioning_connection_busy"):
            registry.accept("other-phone", new)
        registry.close("other-phone")
        self.assertEqual(0, old.shutdowns)
        self.assertEqual({old: "phone"}, registry.connections)

    def test_already_lost_old_socket_does_not_prevent_new_status_connection(self):
        registry = pair.ProvisioningConnections()
        old, new = self.Connection(), self.Connection()
        old.shutdown = lambda _: (_ for _ in ()).throw(OSError("already disconnected"))
        registry.accept("phone", old)
        registry.accept("phone", new)
        self.assertEqual({new: "phone"}, registry.connections)


class PageScanTests(unittest.TestCase):
    def lease(self, settings, supported=None, fail_after=None, ignore=()):
        current, calls = set(settings), []
        def run(arguments):
            calls.append(arguments[-2:])
            if arguments[-1] == "info":
                capabilities = "" if supported is None else "\tsupported settings: " + " ".join(sorted(supported)) + "\n"
                return "hci0:\n" + capabilities + "\tcurrent settings: " + " ".join(sorted(current)) + "\n"
            command, enabled = arguments[-2:]
            if command not in ("connectable", "fast-conn") or enabled not in ("on", "off"):
                self.fail("Unexpected Bluetooth mutation")
            action = (command, enabled)
            if action not in ignore:
                flag = "connectable" if command == "connectable" else "fast-connectable"
                if enabled == "on":
                    current.add(flag)
                else:
                    current.discard(flag)
            if action == fail_after:
                raise pair.JoinError("bluetooth_management_unavailable")
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

    def test_supported_fast_scan_is_enabled_and_restored_without_disabling_existing_page_scan(self):
        initial = {"powered", "br/edr", "connectable", "bondable"}
        lease, current, calls = self.lease(initial, supported=initial | {"fast-connectable"})
        lease.open()
        self.assertEqual(initial | {"fast-connectable"}, current)
        lease.close()
        lease.close()
        self.assertEqual(initial, current)
        self.assertEqual([["fast-conn", "on"], ["fast-conn", "off"]], [c for c in calls if c[-1] != "info"])

    def test_both_owned_flags_are_restored_in_reverse_order(self):
        initial = {"powered", "br/edr", "bondable"}
        lease, current, calls = self.lease(initial, supported=initial | {"connectable", "fast-connectable"})
        lease.open()
        lease.close()
        self.assertEqual(initial, current)
        self.assertEqual([["connectable", "on"], ["fast-conn", "on"], ["fast-conn", "off"],
                          ["connectable", "off"]], [c for c in calls if c[-1] != "info"])

    def test_existing_fast_and_discoverable_settings_are_never_claimed_or_changed(self):
        initial = {"powered", "br/edr", "connectable", "fast-connectable", "discoverable", "bondable"}
        lease, current, calls = self.lease(initial, supported=initial)
        lease.open()
        lease.close()
        self.assertEqual(initial, current)
        self.assertEqual([["0", "info"]], calls)

    def test_unsupported_or_missing_capability_retains_basic_page_scan(self):
        for supported in (None, set(), {"powered", "br/edr", "connectable"}):
            with self.subTest(supported=supported):
                initial = {"powered", "br/edr"}
                lease, current, calls = self.lease(initial, supported=supported)
                lease.open()
                self.assertIn("connectable", current)
                self.assertNotIn(["fast-conn", "on"], calls)
                lease.close()
                self.assertEqual(initial, current)

    def test_fast_command_timeout_after_hardware_change_rolls_back_both_flags(self):
        initial = {"powered", "br/edr"}
        lease, current, calls = self.lease(initial, supported={"fast-connectable"},
                                           fail_after=("fast-conn", "on"))
        with self.assertRaisesRegex(pair.JoinError, "bluetooth_management_unavailable"):
            lease.open()
        self.assertEqual(initial, current)
        self.assertEqual([["fast-conn", "off"], ["connectable", "off"]], calls[-2:])
        self.assertFalse(lease.changed or lease.fast_changed)

    def test_unconfirmed_fast_setting_is_rejected_and_owned_state_restored(self):
        initial = {"powered", "br/edr", "connectable"}
        lease, current, calls = self.lease(initial, supported={"fast-connectable"},
                                           ignore={("fast-conn", "on")})
        with self.assertRaisesRegex(pair.JoinError, "bluetooth_fast_connectable_unconfirmed"):
            lease.open()
        self.assertEqual(initial, current)
        self.assertEqual(["fast-conn", "off"], calls[-1])
        self.assertNotIn(["connectable", "off"], calls)

    def test_partial_cleanup_failure_does_not_skip_page_scan_and_can_retry(self):
        initial = {"powered", "br/edr"}
        lease, current, calls = self.lease(initial, supported={"fast-connectable"})
        lease.open()
        runner = lease.runner
        failures = [True]
        def flaky(arguments):
            if arguments[-2:] == ["fast-conn", "off"] and failures:
                failures.pop()
                raise pair.JoinError("bluetooth_management_unavailable")
            return runner(arguments)
        lease.runner = flaky
        lease.close()
        self.assertNotIn("connectable", current)
        self.assertTrue(lease.fast_changed)
        lease.close()
        self.assertEqual(initial, current)
        self.assertFalse(lease.changed or lease.fast_changed)

    def test_malformed_capabilities_never_mutate_controller(self):
        calls = []
        def ambiguous(arguments):
            calls.append(arguments[-2:])
            return "current settings: powered br/edr\nsupported settings: fast-connectable\nsupported settings: connectable\n"
        with self.assertRaisesRegex(pair.JoinError, "bluetooth_state_unconfirmed"):
            pair.PageScanLease(ambiguous).open()
        self.assertEqual([["0", "info"]], calls)


if __name__ == "__main__":
    unittest.main()
