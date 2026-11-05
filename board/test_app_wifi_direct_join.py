"""Offline helper tests. All NetworkManager/inspection commands are fakes; no Wi-Fi operations."""
from contextlib import contextmanager, redirect_stdout
import configparser
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import app_wifi_direct_join as helper


OLD_UUID = "bdf7733a-5db0-432e-b888-6da3df00948f"
SSID = "DIRECT-Ab-FollowingCar"
PSK = "KeepThisPasswordPrivate!"


class MemoryStore:
    def __init__(self, fail=False):
        self.rows, self.fail = [], fail

    def write(self, value):
        if self.fail:
            raise OSError("disk unavailable")
        self.rows.append(json.loads(json.dumps(value)))


class FakeBoard(helper.BoardSystem):
    def __init__(self, secret_root):
        super().__init__(runner=self.command, secret_root=Path(secret_root), profile_root=Path(secret_root))
        self.calls, self.events = [], []
        self.idle, self.inspection_valid, self.root = True, True, True
        self.locked = False
        self.fail_add, self.fail_up, self.fail_cleanup = False, False, False
        self.foreign_profile = False
        self.profile_exists = False
        self.on_up = None
        self.on_delete = None
        self.secret_cleanup_error = False
        self.active_uuid = OLD_UUID
        self.created_uuid = None
        self.secret_path = None
        self.secret_mode = None
        self.net_state = 100
        self.profile_file = None
        self.profile_content = None

    def is_root(self):
        return self.root

    def capability(self):
        return {"driver": "rtl8723du", "driverInterfaceLimit": 1, "parallelStaP2p": False}

    @contextmanager
    def idle_guard(self):
        self.events.append("inspect")
        if not self.inspection_valid:
            raise helper.JoinError("control_inspection_failed")
        if not self.idle:
            raise helper.JoinError("control_busy_or_unconfirmed")
        self.locked = True
        self.events.append("lock")
        try:
            yield
        finally:
            self.locked = False
            self.events.append("unlock")

    def command(self, args, timeout=6):
        self.calls.append(list(args))
        if "load" in args:
            self.assert_locked()
            self.profile_file = Path(args[-1])
            self.profile_content = self.profile_file.read_text()
            parsed = configparser.ConfigParser()
            parsed.read_string(self.profile_content)
            self.created_uuid = parsed['connection']['uuid']
            self.profile_exists = not self.fail_add or self.foreign_profile
            self.assert_no_secret(args)
            return subprocess.CompletedProcess(args, 10 if self.fail_add else 0, "", PSK)
        if "up" in args:
            self.assert_locked()
            self.assert_no_secret(args)
            self.secret_path = Path(args[args.index("passwd-file") + 1])
            self.secret_mode = self.secret_path.stat().st_mode & 0o777
            assert self.secret_path.read_text() == "802-11-wireless-security.psk:" + PSK + "\n"
            if self.fail_up:
                return subprocess.CompletedProcess(args, 4, PSK, "password=" + PSK)
            self.active_uuid = self.created_uuid
            self.net_state = 100
            if self.on_up:
                self.on_up()
            return subprocess.CompletedProcess(args, 0, "", "")
        if "down" in args:
            self.assert_locked()
            assert args[-1] == self.created_uuid
            self.active_uuid, self.net_state = None, 30
            return subprocess.CompletedProcess(args, 0, "", "")
        if "delete" in args:
            self.assert_locked()
            assert args[-1] == self.created_uuid and args[-1] != OLD_UUID
            if not self.fail_cleanup:
                self.profile_exists = False
                if self.on_delete:
                    self.on_delete()
            return subprocess.CompletedProcess(args, 10 if self.fail_cleanup else 0, "", PSK)
        if args[-2:] == ["device", "status"]:
            output = "wlan0:wifi\np2p-dev-wlan0:wifi-p2p\neth0:ethernet\n"
        elif "device" in args:
            output = ("GENERAL.DEVICE:wlan0\nGENERAL.TYPE:wifi\nGENERAL.STATE:" + str(self.net_state) + " (connected)\n"
                      "GENERAL.NM-MANAGED:yes\nGENERAL.CON-UUID:" + (self.active_uuid or "") + "\n"
                      "GENERAL.CONNECTION:" + ("Original access point" if self.active_uuid else "") + "\n"
                      + ("IP4.ADDRESS[1]:172.16.16.82/22\n" if self.net_state == 100 and self.active_uuid else "") +
                      "WIFI-PROPERTIES.2GHZ:yes\nWIFI-PROPERTIES.5GHZ:no\n")
        elif "802-11-wireless.ssid" in args:
            output = SSID if self.active_uuid == self.created_uuid else "Original network"
        elif any("connection.id,connection.uuid" in arg for arg in args):
            if not self.profile_exists:
                return subprocess.CompletedProcess(args, 10, "", "")
            output = ("connection.id:" + ("someone-elses-profile" if self.foreign_profile else "followingcar-direct-" + self.created_uuid)
                      + "\nconnection.uuid:" + self.created_uuid + "\nconnection.interface-name:wlan0\n802-11-wireless.ssid:" + SSID + "\n")
        elif "--active" in args:
            output = self.active_uuid + "\n" if self.active_uuid else ""
        else:
            output = OLD_UUID + "\n" + (self.created_uuid + "\n" if self.profile_exists else "")
        return subprocess.CompletedProcess(args, 0, output, "")

    @contextmanager
    def password_file(self, psk):
        with super().password_file(psk) as path:
            yield path
        if self.secret_cleanup_error:
            raise OSError("simulated private file cleanup error")

    def assert_locked(self):
        assert self.locked, "Networking mutation must occur while holding both control locks"

    @staticmethod
    def assert_no_secret(args):
        assert all(PSK not in arg for arg in args), "PSK leaked into argv"


def replace_device_fields(board, replacements=None, omitted=()):
    """Feed actual parser text while keeping every command inside FakeBoard."""
    original = board.run
    replacements = replacements or {}
    def run(arguments, timeout=6):
        result = original(arguments, timeout)
        if arguments[-3:] == ["device", "show", "wlan0"]:
            fields = dict(line.split(":", 1) for line in result.stdout.splitlines() if ":" in line)
            fields.update(replacements)
            for key in omitted:
                fields.pop(key, None)
            result.stdout = "".join(key + ":" + value + "\n" for key, value in fields.items())
        return result
    board.run = run


class NetworkObservationTests(unittest.TestCase):
    def board(self, fields=None, omitted=()):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        board = FakeBoard(temporary.name)
        board.active_uuid, board.net_state = None, 30
        replace_device_fields(board, fields, omitted)
        return board

    def assert_no_ssid_query(self, board):
        self.assertFalse(any("802-11-wireless.ssid" in command for command in board.calls))

    def test_empty_and_placeholder_uuid_are_absent_without_ssid_query(self):
        for value in ("", "--", "  ", " \t--\t "):
            with self.subTest(value=value):
                board = self.board({"GENERAL.CON-UUID": value})
                network = board.network()
                self.assertIsNone(network["profileUuid"])
                self.assertIsNone(network["ssid"])
                self.assertEqual(network["state"], 30)
                self.assertEqual(network["addresses"], [])
                self.assert_no_ssid_query(board)

    def test_missing_uuid_field_is_unknown_not_a_disconnected_observation(self):
        board = self.board(omitted=("GENERAL.CON-UUID",))
        with self.assertRaisesRegex(helper.JoinError, "invalid_current_profile_uuid"):
            board.network()
        self.assert_no_ssid_query(board)

    def test_nonempty_malformed_uuid_stays_an_error(self):
        for value in ("not-a-uuid", "0", " --unknown ", OLD_UUID + ":unexpected"):
            with self.subTest(value=value):
                board = self.board({"GENERAL.CON-UUID": value})
                with self.assertRaisesRegex(helper.JoinError, "invalid_current_profile_uuid"):
                    board.network()
                self.assert_no_ssid_query(board)

    def test_valid_uuid_is_trimmed_validated_and_used_for_exact_ssid_read(self):
        board = self.board({"GENERAL.CON-UUID": " " + OLD_UUID + " ", "GENERAL.STATE": "100 (connected)",
                            "IP4.ADDRESS[1]": "172.16.16.116/22"})
        network = board.network()
        self.assertEqual(network["profileUuid"], OLD_UUID)
        self.assertEqual(network["state"], 100)
        self.assertEqual(network["addresses"], ["172.16.16.116"])
        ssid_reads = [command for command in board.calls if "802-11-wireless.ssid" in command]
        self.assertEqual(ssid_reads, [[helper.NMCLI, "-g", "802-11-wireless.ssid", "connection", "show", "uuid", OLD_UUID]])

    def test_empty_address_placeholders_are_skipped_without_hiding_valid_addresses(self):
        board = self.board({"IP4.ADDRESS[1]": "", "IP4.ADDRESS[2]": " -- ",
                            "IP4.ADDRESS[3]": " 172.16.16.116/22 "})
        network = board.network()
        self.assertEqual(network["state"], 30, "parser must not rewrite an inconsistent state")
        self.assertEqual(network["addresses"], ["172.16.16.116"])

    def test_nonempty_invalid_address_stays_an_error(self):
        for value in ("invalid", "192.0.2.5/99", "2001:db8::1/64", "--unknown"):
            with self.subTest(value=value):
                board = self.board({"IP4.ADDRESS[1]": value})
                with self.assertRaisesRegex(helper.JoinError, "invalid_ip_observation"):
                    board.network()

    def test_connected_empty_uuid_is_not_relabelled_disconnected(self):
        board = self.board({"GENERAL.STATE": "100 (connected)"})
        network = board.network()
        self.assertEqual(network["state"], 100)
        self.assertIsNone(network["profileUuid"])
        self.assert_no_ssid_query(board)


class CredentialTests(unittest.TestCase):
    def test_accepts_direct_ssid_and_does_not_expose_psk_in_repr(self):
        credentials = helper.read_credentials(io.BytesIO(json.dumps({"ssid": SSID, "psk": PSK}).encode()))
        self.assertEqual(credentials.psk, PSK)
        self.assertNotIn(PSK, repr(credentials))

    def test_invalid_or_extra_fields_cannot_reach_network_mutation(self):
        invalid = [b"{}", b'{"ssid":"DIRECT-Ab-car","ssid":"DIRECT-Xy-car","psk":"abcdefgh"}',
                   json.dumps({"ssid": "HomeWiFi", "psk": PSK}).encode(),
                   json.dumps({"ssid": "DIRECT-Ab-\ncar", "psk": PSK}).encode(),
                   json.dumps({"ssid": SSID, "psk": "short"}).encode(),
                   json.dumps({"ssid": SSID, "psk": PSK + "\n"}).encode(),
                   json.dumps({"ssid": SSID, "psk": PSK, "interface": "eth0"}).encode(), b"x" * 2049]
        with tempfile.TemporaryDirectory() as folder:
            board, store = FakeBoard(folder), MemoryStore()
            for payload in invalid:
                with self.subTest(payloadSize=len(payload)), redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(helper.main(["--join"], board, store, io.BytesIO(payload)), 1)
                    self.assertNotIn(PSK, output.getvalue())
            self.assertEqual(board.calls, [])
            self.assertEqual(store.rows, [])


class JoinTests(unittest.TestCase):
    def setup_board(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        return FakeBoard(folder.name), MemoryStore()

    def test_default_and_status_are_read_only_and_do_not_read_credentials(self):
        board, store = self.setup_board()
        class NoRead:
            def read(self, _size):
                raise AssertionError("Read-only check must not request credentials")
        for flags in ([], ["--check"], ["--status"]):
            with redirect_stdout(io.StringIO()) as output:
                self.assertEqual(helper.main(flags, board, store, NoRead()), 0)
            self.assertTrue(json.loads(output.getvalue())["controlAvailable"])
        self.assertEqual(store.rows, [])
        self.assertFalse(any(any(action in call for action in ("load", "add", "up", "delete", "down")) for call in board.calls))

    def test_busy_unknown_and_not_root_refuse_before_profile_creation(self):
        for changed in ("idle", "inspection_valid", "root"):
            board, store = self.setup_board()
            setattr(board, changed, False)
            result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
            self.assertFalse(result["ok"])
            self.assertIsNone(board.created_uuid)
            self.assertNotIn(PSK, json.dumps(result))

    def test_unprivileged_check_does_not_claim_other_users_serial_is_idle(self):
        board, _ = self.setup_board()
        board.root = False
        result = helper.check(board)
        self.assertFalse(result["controlAvailable"])
        self.assertEqual(result["error"], "root_required_for_complete_idle_probe")
        self.assertIn("currentNetwork", result)
        self.assertEqual(board.events, [])

    def test_success_uses_private_password_file_without_secret_argv_or_autoconnect(self):
        board, store = self.setup_board()
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertTrue(result["ok"])
        self.assertTrue(any("load" in call for call in board.calls))
        self.assertFalse(any("add" in call for call in board.calls))
        parsed = configparser.ConfigParser()
        parsed.read_string(board.profile_content)
        self.assertEqual(parsed['connection']['autoconnect'], 'false')
        self.assertEqual(parsed['connection']['uuid'], board.created_uuid)
        self.assertEqual(parsed['wifi-security']['psk-flags'], '2')
        self.assertEqual(parsed['connection']['interface-name'], 'wlan0')
        self.assertEqual(parsed['ipv4']['method'], 'auto')
        self.assertEqual(parsed['ipv4']['never-default'], 'true')
        self.assertFalse(result['internetAccessRequested'])
        self.assertNotIn('psk', parsed['wifi-security'])
        self.assertNotIn(PSK, board.profile_content)
        self.assertFalse(board.secret_path.exists())
        if os.name == "posix":
            self.assertEqual(board.secret_mode, 0o600)
            self.assertEqual(board.profile_file.stat().st_mode & 0o777, 0o600)
        self.assertEqual(result["previousConnectionUuid"], OLD_UUID)
        self.assertEqual([row["phase"] for row in store.rows], ["preflight", "activating", "connected"])
        self.assertFalse(any("delete" in call for call in board.calls))
        self.assertNotIn(PSK, json.dumps(board.calls) + json.dumps(store.rows) + json.dumps(result))

    def test_internet_access_only_changes_new_profiles_default_route_policy(self):
        profiles = []
        for enabled in (False, True):
            board, store = self.setup_board()
            result = helper.join_group(helper.Credentials(SSID, PSK), board, store,
                                       method="bluetooth_hotspot", internet_access=enabled)
            self.assertTrue(result["ok"])
            self.assertEqual("bluetooth_hotspot", result["method"])
            self.assertIs(enabled, result["internetAccessRequested"])
            self.assertTrue(all(row["internetAccessRequested"] is enabled for row in store.rows))
            parsed = configparser.ConfigParser()
            parsed.read_string(board.profile_content)
            self.assertEqual("false" if enabled else "true", parsed["ipv4"]["never-default"])
            self.assertEqual("auto", parsed["ipv4"]["method"])
            self.assertEqual("disabled", parsed["ipv6"]["method"])
            self.assertFalse(any("modify" in command or "delete" in command for command in board.calls))
            # Ignore only the generated identity and the one intentional policy difference.
            del parsed["connection"]["id"]
            del parsed["connection"]["uuid"]
            del parsed["ipv4"]["never-default"]
            profiles.append({section: dict(parsed[section]) for section in parsed.sections()})
            self.assertNotIn(PSK, json.dumps(store.rows) + json.dumps(result) + board.profile_content)
        self.assertEqual(profiles[0], profiles[1])

    def test_invalid_internet_access_is_rejected_before_any_network_or_storage_action(self):
        board, store = self.setup_board()
        for invalid in (None, 0, 1, "true", [], {}):
            with self.subTest(value=invalid), self.assertRaisesRegex(helper.JoinError, "invalid_internet_access"):
                helper.join_group(helper.Credentials(SSID, PSK), board, store, internet_access=invalid)
        self.assertEqual([], board.calls)
        self.assertEqual([], board.events)
        self.assertEqual([], store.rows)

    def test_direct_cli_does_not_accept_the_new_bluetooth_mode_field(self):
        board, store = self.setup_board()
        wire = json.dumps(dict(ssid=SSID, psk=PSK, internetAccess=True)).encode()
        with redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, helper.main(["--join"], board, store, io.BytesIO(wire)))
        self.assertEqual([], board.calls)
        self.assertNotIn(PSK, output.getvalue())

    def test_known_network_not_found_reason_is_classified_without_leaking_stderr(self):
        board, store = self.setup_board()
        original = board.run
        def missing_network(args, timeout=6):
            if "up" in args:
                return subprocess.CompletedProcess(args, 4, PSK,
                    "Error: Connection activation failed: The Wi-Fi network could not be found.\n" + PSK)
            return original(args, timeout)
        board.run = missing_network
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store, internet_access=True)
        self.assertFalse(result["ok"])
        self.assertEqual("hotspot_not_visible_2g", result["error"])
        self.assertEqual("deleted_and_transition_settled", result["ownProfileCleanup"])
        self.assertNotIn(PSK, json.dumps(result) + json.dumps(store.rows))
        self.assertNotIn("Connection activation failed", json.dumps(result) + json.dumps(store.rows))

    def test_other_activation_failures_remain_generic_and_success_is_not_reclassified(self):
        for returncode, stderr, expected in (
            (4, "Secrets were required, but not provided." + PSK, "networkmanager_command_failed"),
            (4, "ssid-not-found " + PSK, "networkmanager_command_failed"),
            (0, "The Wi-Fi network could not be found", None),
        ):
            calls = []
            def run(args, timeout=6):
                calls.append((args, timeout))
                return subprocess.CompletedProcess(args, returncode, "", stderr)
            system = helper.BoardSystem(runner=run)
            with self.subTest(returncode=returncode, expected=expected):
                if expected:
                    with self.assertRaisesRegex(helper.JoinError, "^" + expected + "$"):
                        system.activate(OLD_UUID, Path("test-secret-file"))
                else:
                    system.activate(OLD_UUID, Path("test-secret-file"))
            self.assertEqual(1, len(calls))

    def test_failure_deletes_only_new_uuid_and_removes_secret_without_auto_restoring_old(self):
        board, store = self.setup_board()
        board.fail_up = True
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertFalse(result["ok"])
        self.assertEqual(result["ownProfileCleanup"], "deleted_and_transition_settled")
        deletes = [call for call in board.calls if "delete" in call]
        self.assertEqual(len(deletes), 1)
        self.assertEqual(deletes[0][-1], board.created_uuid)
        self.assertFalse(board.secret_path.exists())
        self.assertFalse(board.profile_file.exists())
        self.assertEqual(len([call for call in board.calls if "up" in call]), 1)
        self.assertNotIn(PSK, json.dumps(result) + json.dumps(store.rows))

    def test_cleanup_failure_is_explicit_without_deleting_any_other_profile(self):
        board, store = self.setup_board()
        board.fail_up = board.fail_cleanup = True
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertEqual(result["ownProfileCleanup"], "unconfirmed_manual_cleanup_required")
        self.assertEqual([call[-1] for call in board.calls if "delete" in call], [board.created_uuid])

    def test_delete_success_does_not_claim_network_transition_has_settled(self):
        board, store = self.setup_board()
        board.fail_up = True
        board.on_delete = lambda: setattr(board, "net_state", 40)
        with patch.object(helper.time, "monotonic", side_effect=[0, 13]):
            result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertFalse(result["ok"])
        self.assertEqual(result["ownProfileCleanup"], "unconfirmed_manual_cleanup_required")

    def test_add_conflict_cannot_delete_another_creators_same_uuid_profile(self):
        board, store = self.setup_board()
        board.fail_add = board.foreign_profile = True
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertFalse(result["ok"])
        self.assertEqual(result["ownProfileCleanup"], "not_owned_or_absent")
        self.assertFalse(any("delete" in call or "down" in call for call in board.calls))

    def test_keyfile_load_failure_removes_only_new_file_without_activation(self):
        board, store = self.setup_board()
        board.fail_add = True
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertFalse(result["ok"])
        self.assertFalse(board.profile_file.exists())
        self.assertFalse(any("up" in call or "delete" in call for call in board.calls))
        self.assertEqual(list(board.profile_root.iterdir()), [])

    def test_existing_keyfile_is_never_overwritten_or_loaded(self):
        board, _ = self.setup_board()
        profile_uuid = "e45c48a1-f585-4436-a072-477e16b3ef5b"
        path = board.profile_root / ("followingcar-direct-" + profile_uuid + ".nmconnection")
        path.write_text("pre-existing owner data")
        with board.idle_guard(), self.assertRaises(FileExistsError):
            board.add_profile(profile_uuid, SSID)
        self.assertEqual(path.read_text(), "pre-existing owner data")
        self.assertFalse(any("load" in call or "up" in call for call in board.calls))

    def test_keyfile_ssid_byte_array_cannot_inject_properties(self):
        board, _ = self.setup_board()
        ssid = "DIRECT-Ab-a\\b;[x]=\u503c"
        with board.idle_guard(), patch.object(board, 'owns_profile', return_value=True):
            board.add_profile("e45c48a1-f585-4436-a072-477e16b3ef5b", ssid)
        parsed = configparser.ConfigParser()
        parsed.read_string(board.profile_content)
        self.assertEqual(parsed.sections(), ['connection', 'wifi', 'wifi-security', 'ipv4', 'ipv6'])
        self.assertEqual(bytes(int(part) for part in parsed['wifi']['ssid'].split(';') if part), ssid.encode('utf-8'))

    def test_final_record_failure_does_not_disconnect_confirmed_join(self):
        board, _ = self.setup_board()
        class FinalWriteFailure(MemoryStore):
            def write(self, value):
                if value["phase"] == "connected":
                    raise OSError("final write failed")
                super().write(value)
        store = FinalWriteFailure()
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertTrue(result["ok"])
        self.assertEqual(result["resultRecording"], "unavailable")
        self.assertEqual(board.active_uuid, board.created_uuid)
        self.assertFalse(any("delete" in call or "down" in call for call in board.calls))
        self.assertEqual(store.rows[-1]["phase"], "activating")

    def test_secret_cleanup_error_does_not_disconnect_confirmed_join(self):
        board, store = self.setup_board()
        board.secret_cleanup_error = True
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertTrue(result["ok"])
        self.assertEqual(result["secretCleanup"], "unconfirmed_manual_cleanup_required")
        self.assertEqual(board.active_uuid, board.created_uuid)
        self.assertFalse(any("delete" in call or "down" in call for call in board.calls))

    def test_hup_keeps_locks_and_finishes_join_instead_of_tearing_down_network(self):
        board, store = self.setup_board()
        signals = helper.JoinSignals()
        @contextmanager
        def monitor():
            yield signals
        board.on_up = lambda: setattr(signals, "caller_disconnected", True)
        with patch.object(helper, "join_signal_monitor", monitor):
            result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertTrue(result["ok"])
        self.assertTrue(result["callerDisconnected"])
        self.assertFalse(any("delete" in call or "down" in call for call in board.calls))
        self.assertEqual(board.events[-1], "unlock")

    def test_cancel_during_activation_waits_for_command_then_only_cleans_owned_profile(self):
        board, store = self.setup_board()
        signals = helper.JoinSignals()
        @contextmanager
        def monitor():
            yield signals
        board.on_up = lambda: setattr(signals, "cancel_requested", True)
        with patch.object(helper, "join_signal_monitor", monitor):
            result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "join_cancelled")
        self.assertEqual(result["ownProfileCleanup"], "deleted_and_transition_settled")
        self.assertEqual([call[-1] for call in board.calls if "down" in call], [board.created_uuid])
        self.assertEqual(board.events[-1], "unlock")

    def test_signal_handlers_only_mark_state_and_restore_original_handlers(self):
        original = object()
        handlers = {1: original, 15: original, 2: original}
        def register(number, handler):
            previous = handlers[number]
            handlers[number] = handler
            return previous
        fake_signal = SimpleNamespace(SIGHUP=1, SIGTERM=15, SIGINT=2, signal=register)
        with patch.object(helper, "signal", fake_signal):
            with helper.join_signal_monitor() as flags:
                handlers[1](1, None)
                self.assertTrue(flags.caller_disconnected)
                self.assertFalse(flags.cancel_requested)
                handlers[15](15, None)
                self.assertTrue(flags.cancel_requested)
                with self.assertRaisesRegex(helper.JoinError, "join_cancelled"):
                    flags.check_cancelled()
            self.assertTrue(all(handler is original for handler in handlers.values()))

    def test_connected_state_without_recoverable_old_uuid_refuses_switch(self):
        board, store = self.setup_board()
        board.active_uuid = None
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "wifi_not_ready_for_switch")
        self.assertIsNone(board.created_uuid)

    def test_raw_empty_disconnected_profile_can_join_without_existing_profile(self):
        board, store = self.setup_board()
        board.active_uuid, board.net_state = None, 30
        # The real parser sees nmcli's empty current UUID and address placeholders.
        original = board.run
        def run(arguments, timeout=6):
            result = original(arguments, timeout)
            if arguments[-3:] == ["device", "show", "wlan0"] and board.active_uuid is None:
                result.stdout += "IP4.ADDRESS[1]:\nIP4.ADDRESS[2]:--\n"
            return result
        board.run = run
        result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
        self.assertTrue(result["ok"])
        self.assertIsNone(result["previousConnectionUuid"])
        self.assertEqual(result["currentNetwork"]["profileUuid"], board.created_uuid)
        self.assertEqual(board.net_state, 100)
        self.assertEqual(len([command for command in board.calls if "up" in command]), 1)
        self.assertFalse(any("down" in command or "delete" in command for command in board.calls))
        self.assertNotIn(PSK, json.dumps(result) + json.dumps(store.rows) + json.dumps(board.calls))

    def test_disconnected_state_with_profile_or_leftover_address_refuses_switch(self):
        for fields in ({"GENERAL.CON-UUID": OLD_UUID}, {"IP4.ADDRESS[1]": "172.16.16.116/22"}):
            with self.subTest(fields=fields):
                board, store = self.setup_board()
                board.active_uuid, board.net_state = None, 30
                replace_device_fields(board, fields)
                result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
                self.assertFalse(result["ok"])
                self.assertEqual(result["error"], "wifi_not_ready_for_switch")
                self.assertEqual(result["failedPhase"], "preflight")
                self.assertIsNone(board.created_uuid)

    def test_transition_states_with_empty_uuid_never_create_profile(self):
        for state in (20, 40, 50, 60, 70, 80, 90, 110, 120):
            with self.subTest(state=state):
                board, store = self.setup_board()
                board.active_uuid, board.net_state = None, state
                result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
                self.assertEqual(result["error"], "wifi_not_ready_for_switch")
                self.assertIsNone(board.created_uuid)

    def test_missing_or_invalid_uuid_during_preflight_never_creates_profile(self):
        for fields, omitted in (({}, ("GENERAL.CON-UUID",)), ({"GENERAL.CON-UUID": "not-a-uuid"}, ())):
            with self.subTest(fields=fields, omitted=omitted):
                board, store = self.setup_board()
                board.active_uuid, board.net_state = None, 30
                replace_device_fields(board, fields, omitted)
                result = helper.join_group(helper.Credentials(SSID, PSK), board, store)
                self.assertEqual(result["error"], "invalid_current_profile_uuid")
                self.assertIsNone(board.created_uuid)

    def test_unrecordable_operation_cannot_start_network_change(self):
        board, _ = self.setup_board()
        result = helper.join_group(helper.Credentials(SSID, PSK), board, MemoryStore(fail=True))
        self.assertFalse(result["ok"])
        self.assertEqual(result["resultRecording"], "unavailable")
        self.assertIsNone(board.created_uuid)

    def test_default_probe_rejects_actual_worker_even_with_no_app_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            (proc / "123").mkdir()
            (proc / "123/cmdline").write_bytes(b"python3\0/home/topeet/runtime/app_follow_worker.py\0--execute\0")
            calls = []
            system = helper.BoardSystem(runner=lambda *args: calls.append(args), proc_root=proc)
            self.assertEqual(helper.running_control_processes(proc), [123])
            with self.assertRaisesRegex(helper.JoinError, "control_process_running"):
                system.verify_hardware_idle()
            self.assertEqual(calls, [])
            (proc / "123/cmdline").write_bytes(b"python3\0request_continuous_follow.py\0")
            self.assertEqual(helper.running_control_processes(proc), [123])

    def test_unknown_serial_observation_is_not_idle_when_no_pid_was_returned(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(Path, "is_char_device", return_value=True):
            for code, output, error in [(1, "", "permission denied"), (2, "", "failed"), (0, "123", "")]:
                calls = []
                def run(args, timeout):
                    calls.append(args)
                    return subprocess.CompletedProcess(args, code, output, error)
                with self.subTest(code=code, hasError=bool(error)):
                    system = helper.BoardSystem(runner=run, proc_root=Path(directory))
                    with self.assertRaises(helper.JoinError):
                        system.verify_hardware_idle()
                    self.assertEqual(calls, [["/usr/bin/fuser", "/dev/ttyS0"]])

    def test_existing_secondary_motor_port_is_also_checked(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(Path, "is_char_device", return_value=True), \
             patch.object(Path, "exists", lambda path: path.as_posix() == "/dev/ttyS3"):
            calls = []
            def run(args, timeout):
                calls.append(args)
                return subprocess.CompletedProcess(args, 0 if args[-1].endswith("S3") else 1,
                                                   "123" if args[-1].endswith("S3") else "", "")
            system = helper.BoardSystem(runner=run, proc_root=Path(directory))
            with self.assertRaisesRegex(helper.JoinError, "motor_serial_in_use"):
                system.verify_hardware_idle()
            self.assertEqual([args[-1] for args in calls], ["/dev/ttyS0", "/dev/ttyS3"])

    def test_bridge_inspection_must_be_well_formed_explicit_true(self):
        replies = [(0, '{"type":"availability","controlAvailable":"true"}'),
                   (0, '{"type":"availability","controlAvailable":false}'),
                   (0, 'noise\n{"type":"availability","controlAvailable":true}'),
                   (0, '{"type":"ready","controlAvailable":true}'),
                   (1, '{"type":"availability","controlAvailable":true}')]
        for code, output in replies:
            with self.subTest(code=code, output=output):
                system = helper.BoardSystem(runner=lambda args, timeout: subprocess.CompletedProcess(args, code, output, ""))
                with self.assertRaises(helper.JoinError):
                    system.inspect_bridge()

    def test_missing_locks_are_unknown_and_never_created_even_by_inspect(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = (Path(directory) / "motor.lock", Path(directory) / "follow.lock")
            calls = []
            system = helper.BoardSystem(runner=lambda *args: calls.append(args), lock_paths=paths)
            with self.assertRaisesRegex(helper.JoinError, "control_lock_busy_or_unavailable"):
                with system.idle_guard():
                    self.fail("Missing locks must not authorize joining")
            self.assertEqual(calls, [])
            self.assertTrue(all(not path.exists() for path in paths))

    def test_result_file_is_private_and_atomic(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state/last-result.json"
            store = helper.ResultStore(path, owner=None)
            store.write({"phase": "activating"})
            store.write({"phase": "connected"})
            self.assertEqual(json.loads(path.read_text())["phase"], "connected")
            self.assertEqual([item.name for item in path.parent.iterdir()], ["last-result.json"])
            if os.name == "posix":
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
                self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)


if __name__ == "__main__":
    unittest.main()
