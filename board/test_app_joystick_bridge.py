"""Offline tests: fake motors or --dry-run subprocesses only; no board access."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
from unittest.mock import patch
import threading
from collections import deque
from tempfile import TemporaryDirectory

import app_joystick_bridge as bridge
from app_joystick_bridge import Motors, ProtocolSession, SessionError, strict_message


SCRIPT = Path(__file__).with_name("app_joystick_bridge.py")
ROOT = SCRIPT.parent.parent


class FakeClock:
    def __init__(self, now=10.0):
        self.now = now

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        if seconds < 0:
            raise AssertionError("negative simulated sleep")
        self.now += seconds


class FakeClient:
    def __init__(self, fail_left=False):
        self.calls = []
        self.fail_left = fail_left
        self.registers = {"right_parking_current": 5.0, "left_parking_current": 5.0,
                          "max_output_current": 60.0}
        self.fail_writes = set()
        self.readback = {}
        self.feedback = (0, 0, 0, 0)  # left RPM, right RPM, left error, right error
        self.feedback_samples = deque()
        self.feedback_reads = []
        self.feedback_failure = None

    def set_left_speed(self, rpm):
        self.calls.append(("left", rpm))
        if self.fail_left:
            raise TimeoutError("left failed")

    def set_right_speed(self, rpm):
        self.calls.append(("right", rpm))

    def stop(self, side, mode):
        self.calls.append((side, "stop", mode))
        if side == "left" and self.fail_left:
            raise TimeoutError("left stop failed")

    def write_register(self, register, value, *, persist=False):
        self.calls.append(("write", register, value, persist))
        if register in self.fail_writes:
            raise TimeoutError(f"{register} write failed")
        self.registers[register] = value

    def read_register(self, register):
        self.calls.append(("read", register))
        value = self.readback.get(register, self.registers[register])
        if isinstance(value, Exception):
            raise value
        return value

    def read_holding_registers(self, address, count):
        self.feedback_reads.append((address, count, time.monotonic()))
        if (address, count) != (0x0009, 13):
            raise AssertionError("Feedback must read both signed speeds and both errors together")
        if self.feedback_failure:
            raise self.feedback_failure
        value = self.feedback_samples.popleft() if self.feedback_samples else self.feedback
        if isinstance(value, Exception):
            raise value
        if isinstance(value, list):
            return value  # Explicit malformed frame fixture.
        left, right, left_error, right_error = value
        words = [0] * 13
        words[0:2] = [(right >> 16) & 65535, right & 65535]
        words[2] = right_error
        words[10:12] = [(left >> 16) & 65535, left & 65535]
        words[12] = left_error
        return words


def parking_request(session, enabled=True, **extra):
    """Retired wire format, retained only for rejection regressions."""
    return {"seq": session.next_seq, "token": session.token, "cmd": "parking",
            "left": 0, "right": 0, "parkingEnabled": enabled, **extra}


class ParkingReadFakeClient(FakeClient):
    """Extra read-only registers for testing owner reads during network recovery."""
    def read_holding_registers(self, address, count):
        if (address, count) == (0, 3): return [0x30EA, 256, 6000]
        names = {0x58: "max_output_current", 0x76: "left_parking_current", 0x6D: "right_parking_current"}
        if address in names and count == 1: return [round(self.registers[names[address]] * 100)]
        if (address, count) == (0x1C, 5): return [24, 1, 0x38, 0, 0]
        if (address, count) in ((8, 6), (0x12, 6)):
            rpm = self.feedback[1 if address == 8 else 0]
            error = self.feedback[3 if address == 8 else 2]
            return [100, (rpm >> 16) & 65535, rpm & 65535, error, 100, 25]
        return super().read_holding_registers(address, count)


def current_read_fields():
    return dict(schemaVersion=2, parkingSemantics="current_zero_disables", op="read")


class BridgeTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        for replacement in (
            patch.dict(os.environ, {"APP_DRIVE_DIAGNOSTIC_TEST_DIR": temporary.name}),
        ):
            replacement.start()
            self.addCleanup(replacement.stop)
        self.clock = FakeClock()
        for replacement in (patch.object(bridge.time, "monotonic", self.clock.monotonic),
                            patch.object(bridge.time, "sleep", self.clock.sleep)):
            replacement.start()
            self.addCleanup(replacement.stop)

    def hardware_session(self):
        client = FakeClient()
        session = ProtocolSession(Motors(client), port="fake", dry_run=False)
        ready = session.ready()
        return client, session, ready

    def assert_no_configuration_writes(self, client):
        self.assertFalse(any(call[0] == "write" for call in client.calls))
        self.assertEqual(client.registers, {"right_parking_current": 5.0,
            "left_parking_current": 5.0, "max_output_current": 60.0})

    def test_ready_preserves_existing_currents_and_reports_measured_stop(self):
        client, _session, ready = self.hardware_session()
        self.assertTrue(ready["parkingSupported"])
        self.assertFalse(ready["parkingEnabled"])
        self.assertTrue(ready["parkingCurrentReadOnly"])
        self.assertEqual(ready["parkingLeftCurrentAmps"], 5.0)
        self.assertEqual(ready["parkingRightCurrentAmps"], 5.0)
        self.assertEqual(ready["watchdogMs"], 600)
        self.assertEqual(ready["networkGraceMs"], int(bridge.NETWORK_GRACE_SECONDS * 1000))
        self.assertEqual(ready["safetyVersion"], 2)
        self.assertTrue(ready["stopped"])
        self.assert_no_configuration_writes(client)

    def test_network_pause_preserves_settings_and_ir_until_neutral(self):
        for neutral in ("stop", "drive"):
            with self.subTest(neutral=neutral):
                client, session, _ = self.hardware_session()
                motors = session.motors
                # Historical stop-policy fixture, never a wire-level parking switch.
                motors.set_parking(True)
                motors.drive(30, -30)
                motors.ir_latched_directions.add("forward")
                client.calls.clear()
                session.pause_for_network()
                self.assertIn(("left", "stop", 1), client.calls)
                self.assertIn(("right", "stop", 1), client.calls)
                self.assertTrue(motors.parking_enabled)
                self.assertEqual(motors.ir_latched_directions, {"forward"})
                self.assertEqual(motors.requested_target, (30, -30))
                client.calls.clear()
                for _ in range(3):
                    self.clock.sleep(.11)
                    ack, closing = session.accept({"seq": session.next_seq, "token": session.token,
                        "cmd": "drive", "left": 50, "right": -50})
                    self.assertFalse(closing)
                    self.assertTrue(ack["recoveryRequired"])
                    self.assertTrue(ack["targetStopped"])
                    self.assertEqual((ack["left"], ack["right"]), (0, 0))
                self.assertNotIn(("left", 50), client.calls)
                self.assertNotIn(("right", -50), client.calls)
                self.assertEqual(motors.ir_latched_directions, {"forward"})
                ack, _ = session.accept({"seq": session.next_seq, "token": session.token,
                    "cmd": neutral, "left": 0, "right": 0})
                self.assertTrue(ack["recoveryRequired"])
                self.assertFalse(session.recovery_required)
                self.assertEqual(motors.requested_target, (0, 0))
                self.assertEqual(motors.ir_latched_directions, set())
                self.assertIn(("left", "stop", 0), client.calls)
                self.assertIn(("right", "stop", 0), client.calls)
                self.assert_no_configuration_writes(client)
                client.calls.clear()
                ack, _ = session.accept({"seq": session.next_seq, "token": session.token,
                    "cmd": "drive", "left": 20, "right": -20})
                self.assertFalse(ack["recoveryRequired"])
                self.assertFalse(ack["targetStopped"])
                self.assertFalse(ack["stopped"])
                self.assertIn(("left", 20), client.calls)
                self.assertIn(("right", -20), client.calls)

    def test_recovery_still_validates_sequence_and_token_before_neutral(self):
        session = ProtocolSession(Motors(), port="fake", dry_run=True)
        session.ready()
        session.pause_for_network()
        for changes in ({"seq": 2}, {"token": "0" * 32}, {"left": True}):
            with self.subTest(changes=changes):
                request = {"seq": 1, "token": session.token, "cmd": "stop", "left": 0, "right": 0}
                with self.assertRaises(SessionError):
                    session.accept(dict(request, **changes))
                self.assertTrue(session.recovery_required)
                self.assertEqual(session.next_seq, 1)

    def test_internal_normal_stop_policy_and_neutral_preserve_saved_configuration(self):
        client, session, _ = self.hardware_session()
        client.calls.clear()
        session.motors.set_parking(True)  # Private historical stop-policy fixture only.
        self.assertIn(("left", "stop", 0), client.calls)
        self.assertIn(("right", "stop", 0), client.calls)
        session.accept({"seq": session.next_seq, "token": session.token, "cmd": "drive", "left": 30, "right": -30})
        ack, _ = session.accept({"seq": session.next_seq, "token": session.token, "cmd": "stop", "left": 0, "right": 0})
        self.assertTrue(ack["parkingEnabled"])
        self.assertTrue(ack["targetStopped"])
        self.assert_no_configuration_writes(client)

    def test_internal_emergency_policy_reissues_stop_without_changing_currents(self):
        client, session, _ = self.hardware_session()
        session.motors.set_parking(True)  # Private historical stop-policy fixture only.
        client.calls.clear()
        session.motors.set_parking(False)
        self.assertFalse(session.motors.parking_enabled)
        self.assertTrue(session.motors.stopped_confirmed)
        self.assertIn(("left", "stop", 1), client.calls)
        self.assertIn(("right", "stop", 1), client.calls)
        self.assert_no_configuration_writes(client)

    def test_parking_rejects_motion_and_invalid_boolean_without_parameter_writes(self):
        client, session, _ = self.hardware_session()
        client.calls.clear()
        for enabled in (1, "true", None, 0):
            with self.subTest(enabled=enabled), self.assertRaises(SessionError):
                session.accept(parking_request(session, enabled))
        with self.assertRaisesRegex(SessionError, "zero"):
            session.accept(parking_request(session, left=1))
        self.assertEqual(client.calls, [])

    def test_legacy_switch_is_rejected_before_all_io_without_advancing_session(self):
        client, session, _ = self.hardware_session()
        for enabled in (True, False):
            client.calls.clear(); client.feedback_reads.clear()
            seq, token = session.next_seq, session.token
            original = dict(client.registers)
            with self.subTest(enabled=enabled), self.assertRaisesRegex(SessionError, "Legacy parking mode is unsupported"):
                session.accept(parking_request(session, enabled))
            self.assertEqual(client.calls, [])
            self.assertEqual(client.feedback_reads, [])
            self.assertEqual(client.registers, original)
            self.assertEqual((session.next_seq, session.token), (seq, token))
        session.motors.drive(10, -10)
        client.calls.clear()
        with self.assertRaises(SessionError):
            session.accept(parking_request(session))
        self.assertEqual(client.calls, [])

    def test_parking_json_rejects_legacy_current_mutation_and_duplicate_fields(self):
        session = ProtocolSession(Motors(), port="fake", dry_run=True)
        request = parking_request(session)
        self.assertEqual(strict_message(json.dumps(request).encode()), request)
        invalid = [dict(request, extra=1), dict(request, parkingCurrent=5),
                   {key: value for key, value in request.items() if key != "parkingEnabled"},
                   dict(request, cmd="stop"), dict(request, cmd="drive")]
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(SessionError):
                strict_message(json.dumps(payload).encode())
        with self.assertRaises(SessionError):
            strict_message(b'{"seq":1,"token":"x","cmd":"parking","left":0,"right":0,'
                           b'"parkingEnabled":true,"parkingEnabled":false}')

    def test_internal_normal_stop_failure_attempts_both_sides_without_success(self):
        client, session, _ = self.hardware_session()
        client.calls.clear()
        client.fail_left = True
        with self.assertRaises(SessionError):
            session.motors.set_parking(True)  # Test the stop primitive, not a supported wire command.
        self.assertIn(("right", "stop", 0), client.calls)
        self.assertIn(("right", "stop", 1), client.calls)
        self.assertFalse(session.motors.stopped_confirmed)
        self.assertEqual(session.next_seq, 1)
        self.assert_no_configuration_writes(client)

    def test_terminal_commands_emergency_stop_and_preserve_configuration_before_ack(self):
        for command in ("estop", "close"):
            with self.subTest(command=command):
                client, session, _ = self.hardware_session()
                session.motors.set_parking(True)  # Private historical stop-policy fixture only.
                client.calls.clear()
                ack, closing = session.accept({"seq": session.next_seq, "token": session.token,
                                               "cmd": command, "left": 0, "right": 0})
                self.assertIn(("left", "stop", 1), client.calls)
                self.assertIn(("right", "stop", 1), client.calls)
                self.assertFalse(ack["parkingEnabled"])
                self.assertTrue(ack["stopped"])
                self.assertTrue(closing)
                self.assert_no_configuration_writes(client)

    def test_terminal_session_stops_once_even_when_ack_output_fails(self):
        for command in ("estop", "close"):
            for fail_output in (False, True):
                with self.subTest(command=command, fail_output=fail_output):
                    client, messages = FakeClient(), []
                    class Output:
                        def __init__(self, _fd): pass
                        def send(self, message):
                            messages.append(message)
                            if message["type"] == "ready": client.calls.clear()
                            elif message["type"] == "ack" and fail_output:
                                raise BlockingIOError("SSH reader stalled")
                    class Input:
                        def __init__(self, _fd): pass
                        def line(self, _deadline, _stopped):
                            return json.dumps({"seq": 1, "token": messages[0]["token"],
                                               "cmd": command, "left": 0, "right": 0}).encode()
                    with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
                        result = bridge.run_session(Motors(client), "fake", False, threading.Event())
                    self.assertEqual(result, int(fail_output))
                    self.assertEqual(client.calls.count(("left", "stop", 1)), 1)
                    self.assertEqual(client.calls.count(("right", "stop", 1)), 1)
                    self.assert_no_configuration_writes(client)

    def test_shutdown_attempts_both_stops_despite_left_failure_without_writing_settings(self):
        client = FakeClient(fail_left=True)
        with self.assertRaises(SessionError): Motors(client).shutdown()
        self.assertIn(("left", "stop", 1), client.calls)
        self.assertIn(("right", "stop", 1), client.calls)
        self.assert_no_configuration_writes(client)

    def test_feedback_failure_during_neutral_never_acks_and_cleanup_preserves_settings(self):
        client, messages = FakeClient(), []
        class Output:
            def __init__(self, _fd): pass
            def send(self, message): messages.append(message)
        class Input:
            def __init__(self, _fd): pass
            def line(self, _deadline, _stopped):
                client.feedback_failure = TimeoutError("feedback unavailable")
                return json.dumps({"seq": 1, "token": messages[0]["token"], "cmd": "stop",
                                   "left": 0, "right": 0}).encode()
        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
            self.assertEqual(bridge.run_session(Motors(client), "fake", False, threading.Event()), 1)
        self.assertEqual([message["type"] for message in messages], ["ready", "error"])
        self.assertIn(("left", "stop", 1), client.calls)
        self.assertIn(("right", "stop", 1), client.calls)
        self.assert_no_configuration_writes(client)

    def test_eof_after_normal_stop_policy_emergency_stops_without_clearing_saved_currents(self):
        client, messages = FakeClient(), []
        motors = Motors(client)
        class Output:
            def __init__(self, _fd): pass
            def send(self, message): messages.append(message)
        class Input:
            def __init__(self, _fd): self.requested = False
            def line(self, _deadline, _stopped):
                if self.requested: raise SessionError("Control input closed")
                self.requested = True
                motors.set_parking(True)  # Private historical stop-policy fixture only.
                return json.dumps({"seq": 1, "token": messages[0]["token"], "cmd": "stop",
                                   "left": 0, "right": 0}).encode()
        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
            self.assertEqual(bridge.run_session(motors, "fake", False, threading.Event()), 1)
        self.assertEqual([message["type"] for message in messages], ["ready", "ack", "error"])
        self.assertTrue(messages[1]["parkingEnabled"])
        self.assertFalse(motors.parking_enabled)
        self.assertIn(("left", "stop", 1), client.calls)
        self.assertIn(("right", "stop", 1), client.calls)
        self.assert_no_configuration_writes(client)

    def test_stop_attempts_zero_and_emergency_on_both_sides_after_failure(self):
        client = FakeClient(fail_left=True)
        with self.assertRaises(SessionError): Motors(client).stop(force=True)
        for call in (("left", 0), ("right", 0), ("left", "stop", 1), ("right", "stop", 1)):
            self.assertIn(call, client.calls)

    def test_repeated_movement_targets_do_not_repeat_motor_writes(self):
        client, session, _ = self.hardware_session()
        motors = session.motors
        client.calls.clear()
        motors.drive(50, -50)
        motors.drive(50, -50)
        self.assertEqual(client.calls, [("left", 50), ("right", -50)])
        motors.stop()
        self.assertEqual(client.calls[-4:], [("left", 0), ("right", 0),
                                            ("left", "stop", 1), ("right", "stop", 1)])

    def test_nonce_rotation_rejects_a_queued_or_replayed_command(self):
        session = ProtocolSession(Motors(), port="fake", dry_run=True)
        ready = session.ready()
        request = {"seq": 1, "token": ready["token"], "cmd": "drive", "left": 50, "right": -50}
        ack, closing = session.accept(request)
        self.assertNotEqual(ack["token"], ready["token"])
        self.assertFalse(closing)
        request["seq"] = 2
        with self.assertRaisesRegex(SessionError, "token"): session.accept(request)

    def test_json_rejects_duplicate_fields_boolean_rpm_and_overspeed(self):
        with self.assertRaises(SessionError):
            strict_message(b'{"seq":1,"seq":1,"token":"x","cmd":"stop","left":0,"right":0}')
        with self.assertRaises(SessionError): strict_message(b'x' * 513)
        for left in (True, 101, -101, 1.5, "10"):
            session = ProtocolSession(Motors(), port="fake", dry_run=True)
            with self.assertRaises(SessionError):
                session.accept({"seq": 1, "token": session.token, "cmd": "drive", "left": left, "right": 0})

    def test_output_failure_after_motion_still_attempts_both_stops(self):
        client, messages = FakeClient(), []
        class Output:
            def __init__(self, _fd): pass
            def send(self, value):
                messages.append(value)
                if value["type"] == "ack": raise BlockingIOError("SSH reader stalled")
        class Input:
            def __init__(self, _fd): pass
            def line(self, _deadline, _stopped):
                return json.dumps({"seq": 1, "token": messages[0]["token"],
                                   "cmd": "drive", "left": 50, "right": -50}).encode()
        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
            self.assertEqual(bridge.run_session(Motors(client), "fake", True, threading.Event()), 1)
        for call in (("left", 50), ("right", -50), ("left", "stop", 1), ("right", "stop", 1)):
            self.assertIn(call, client.calls)
        self.assert_no_configuration_writes(client)


class BridgeProtocolTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        environment = patch.dict(os.environ, {"APP_DRIVE_DIAGNOSTIC_TEST_DIR": temporary.name})
        environment.start()
        self.addCleanup(environment.stop)

    def start(self):
        process = subprocess.Popen([sys.executable, str(SCRIPT), "--dry-run"], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def cleanup():
            if process.poll() is None:
                process.kill()
            process.wait(timeout=3)
            for stream in (process.stdin, process.stdout, process.stderr):
                if not stream.closed:
                    stream.close()
        self.addCleanup(cleanup)
        ready = json.loads(process.stdout.readline())
        self.assertEqual(ready["type"], "ready")
        self.assertTrue(ready["dryRun"])
        return process, ready

    def exchange(self, process, ready, *, seq=1, cmd="drive", left=50, right=-50, **extra):
        request = {"seq": seq, "token": ready["token"], "cmd": cmd, "left": left, "right": right, **extra}
        process.stdin.write((json.dumps(request) + "\n").encode())
        process.stdin.flush()
        return json.loads(process.stdout.readline())

    def finish(self, process):
        process.wait(timeout=3)
        process.stdin.close()
        process.stdout.close()
        process.stderr.close()

    def test_watchdog_exits_after_active_drive_without_further_input(self):
        process, ready = self.start()
        ack = self.exchange(process, ready)
        self.assertEqual(ack["type"], "ack")
        self.assertFalse(ack["stopped"])
        started = time.monotonic()
        time.sleep(0.8)
        self.assertIsNone(process.poll(), "motion lease expiry must retain the stopped session")
        error = json.loads(process.stdout.readline())
        self.assertIn("network grace expired", error["error"])
        self.assertGreaterEqual(time.monotonic() - started, bridge.NETWORK_GRACE_SECONDS - .5)
        self.assertLess(time.monotonic() - started, bridge.NETWORK_GRACE_SECONDS + 1.5)
        self.finish(process)
        self.assertNotEqual(process.returncode, 0)
        rows = [json.loads(line) for path in Path(os.environ["APP_DRIVE_DIAGNOSTIC_TEST_DIR"]).glob("*.jsonl")
                for line in path.read_text(encoding="utf-8").splitlines()]
        rows = [row for row in rows if row["runId"] == ready["runId"]]
        pause = [row for row in rows if row["event"] == "drive.network_paused"]
        self.assertEqual(len(pause), 1)
        self.assertAlmostEqual(pause[0]["sessionDeadlineMonotonicMs"] -
                               pause[0]["leaseExpiredMonotonicMs"],
                               (bridge.NETWORK_GRACE_SECONDS - bridge.WATCHDOG_SECONDS) * 1000, delta=1)
        self.assertLess(pause[0]["stoppedMonotonicMs"] - pause[0]["leaseExpiredMonotonicMs"], 500)
        self.assertEqual(pause[0]["lastValidRequest"]["seq"], 1)
        failure = next(row for row in rows if row["event"] == "drive.failure")
        self.assertEqual(failure["stopSource"], "network_grace_expired")
        self.assertNotIn(ready["token"], json.dumps(rows))
        self.assertNotIn(ack["token"], json.dumps(rows))

    def test_dry_run_network_pause_suppresses_held_target_then_recovers(self):
        process, ready = self.start()
        reply = self.exchange(process, ready)
        time.sleep(0.8)
        self.assertIsNone(process.poll())
        suppressed = self.exchange(process, reply, seq=2)
        self.assertTrue(suppressed["recoveryRequired"])
        self.assertTrue(suppressed["stopped"])
        neutral = self.exchange(process, suppressed, seq=3, cmd="stop", left=0, right=0)
        self.assertTrue(neutral["recoveryRequired"])
        moving = self.exchange(process, neutral, seq=4)
        self.assertFalse(moving["recoveryRequired"])
        self.assertFalse(moving["stopped"])
        self.exchange(process, moving, seq=5, cmd="close", left=0, right=0)
        self.finish(process)
        self.assertEqual(process.returncode, 0)

    def test_eof_is_terminal(self):
        process, ready = self.start()
        self.exchange(process, ready)
        process.stdin.close()
        error = json.loads(process.stdout.readline())
        self.assertIn("closed", error["error"])
        self.finish(process)
        self.assertNotEqual(process.returncode, 0)

    def test_invalid_json_stops_session(self):
        process, _ = self.start()
        process.stdin.write(b"not-json\n")
        process.stdin.flush()
        error = json.loads(process.stdout.readline())
        self.assertEqual(error["type"], "error")
        self.finish(process)

    def test_stop_target_ack_does_not_forge_standstill_and_close_waits_for_it(self):
        process, ready = self.start()
        drive = self.exchange(process, ready)
        stop = self.exchange(process, drive, seq=2, cmd="stop", left=0, right=0)
        self.assertTrue(stop["targetStopped"])
        self.assertFalse(stop["stopped"])
        close = self.exchange(process, stop, seq=3, cmd="close", left=0, right=0)
        self.assertTrue(close["stopped"])
        self.finish(process)
        self.assertEqual(process.returncode, 0)

    def test_estop_ack_is_terminal(self):
        process, ready = self.start()
        stop = self.exchange(process, ready, cmd="estop", left=0, right=0)
        self.assertTrue(stop["stopped"])
        self.finish(process)
        self.assertEqual(process.returncode, 0)

    def test_partial_line_does_not_refresh_watchdog(self):
        process, _ = self.start()
        process.stdin.write(b'{"seq":')
        process.stdin.flush()
        error = json.loads(process.stdout.readline())
        self.assertIn("watchdog", error["error"])
        self.finish(process)

    def test_dry_run_current_read_and_terminal_preserve_configuration(self):
        process, ready = self.start()
        self.assertTrue(ready["parkingSupported"])
        self.assertEqual(ready["parkingLeftCurrentAmps"], 5.0)
        self.assertFalse(ready["parkingEnabled"])
        parking = self.exchange(process, ready, cmd="parking_settings", left=0, right=0,
                                **current_read_fields())
        self.assertEqual(parking["type"], "ack")
        self.assertTrue(parking["parkingResult"]["ok"])
        self.assertTrue(parking["parkingMode"])
        self.assertEqual(parking["parkingLeftCurrentAmps"], 5.0)
        drive = self.exchange(process, parking, seq=2)
        self.assertTrue(drive["parkingMode"])
        stop = self.exchange(process, drive, seq=3, cmd="stop", left=0, right=0)
        self.assertTrue(stop["parkingMode"])
        close = self.exchange(process, stop, seq=4, cmd="close", left=0, right=0)
        self.assertFalse(close["parkingEnabled"])
        self.assertEqual(close["parkingLeftCurrentAmps"], 5.0)
        self.finish(process)
        self.assertEqual(process.returncode, 0)

    def run_fake_timed_session(self, command, duration):
        clock = [0.0]
        messages, deadlines = [], []

        class TimedMotors(Motors):
            def unified_parking(self, request):
                # Delay the operation, then let its real final confirmation run.
                # Delaying after return models stale evidence rather than slow I/O.
                clock[0] += duration
                return super().unified_parking(request)

            def drive(self, left, right):
                target = super().drive(left, right)
                clock[0] += duration
                return target

        class Output:
            def __init__(self, _fd):
                pass

            def send(self, message):
                messages.append(message)

        class Input:
            def __init__(self, _fd):
                self.seq = 0

            def line(self, deadline, _stopped):
                deadlines.append(deadline)
                self.seq += 1
                cmd = command if self.seq == 1 else "close"
                request = {"seq": self.seq, "token": messages[-1]["token"], "cmd": cmd,
                           "left": 10 if cmd == "drive" else 0, "right": 0}
                if cmd == "parking_settings":
                    request.update(current_read_fields())
                return json.dumps(request).encode()

        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input), \
                patch.object(bridge.time, "monotonic", lambda: clock[0]):
            result = bridge.run_session(TimedMotors(), "fake", True, threading.Event())
        return result, messages, deadlines

    def test_only_settings_read_can_extend_transaction_and_refresh_lease_after_completion(self):
        result, messages, deadlines = self.run_fake_timed_session("parking_settings", 0.8)
        self.assertEqual(result, 0)
        self.assertEqual([message["type"] for message in messages], ["ready", "ack", "ack"])
        self.assertAlmostEqual(deadlines[1], 1.4)
        result, messages, _ = self.run_fake_timed_session("drive", 0.8)
        self.assertEqual(result, 1)
        self.assertEqual(messages[-1]["type"], "error")
        self.assertIn("watchdog", messages[-1]["error"])

    def test_settings_transaction_over_its_hard_budget_has_no_success_ack(self):
        result, messages, _ = self.run_fake_timed_session("parking_settings", bridge.UNIFIED_PARKING_TRANSACTION_SECONDS + .01)
        self.assertEqual(result, 1)
        self.assertEqual([message["type"] for message in messages], ["ready", "error"])
        self.assertIn("Parking transaction exceeded timeout", messages[-1]["error"])

    def run_scripted_grace(self, actions, *, fail_ack=None, parking_seconds=0, fail_soft_stop=False):
        """Drive the real session with fake serial, a virtual clock and scripted input."""
        clock = [0.0]
        messages, deadlines, events = [], [], []
        epoch = [0.0]
        client = ParkingReadFakeClient()
        remaining = iter(actions)

        class TimedMotors(Motors):
            polls = 0

            def unified_parking(self, request):
                clock[0] += parking_seconds
                return super().unified_parking(request)

            def check_infrared(self):
                self.polls += 1
                return super().check_infrared()

        motors = TimedMotors(client)

        class Diagnostic:
            def event(self, name, **fields):
                normalized = {key: value - epoch[0] * 1000
                              if key.endswith("MonotonicMs") and isinstance(value, (int, float)) else value
                              for key, value in fields.items()}
                events.append(dict(event=name, clock=clock[0] - epoch[0], **normalized))

            def failure(self, name, error, **fields):
                self.event(name, error=str(error), **fields)

            def close(self, **_kwargs):
                pass

        class Output:
            def __init__(self, _fd):
                pass

            def send(self, message):
                if fail_ack is not None and message.get("seq") == fail_ack:
                    raise BlockingIOError("SSH output blocked")
                if message.get("type") == "ready":
                    epoch[0] = clock[0]
                messages.append(message)

        class Input:
            def __init__(self, _fd):
                self.seq = 0

            def line(self, deadline, _stopped):
                deadlines.append(deadline - epoch[0])
                action = next(remaining)
                destination = deadline if action == "deadline" else epoch[0] + action[0]
                # The production InputPump polls safety every 20 ms while idle.
                # Advance that same cadence so two physical feedback samples can
                # confirm standstill during network grace, without real sleeps.
                while clock[0] + .02 < destination:
                    clock[0] += .02
                    self.poll()
                clock[0] = destination
                self.poll()
                if action == "deadline":
                    if fail_soft_stop:
                        client.fail_left = True
                    raise bridge.MotionLeaseExpired("Control watchdog expired")
                elapsed, command, *targets = action
                if isinstance(command, Exception):
                    raise command
                if command == "malformed":
                    return b"not-json"
                self.seq += 1
                left, right = targets or ((30, -30) if command == "drive" else (0, 0))
                request = {"seq": self.seq, "token": messages[-1]["token"],
                           "cmd": command, "left": left, "right": right}
                if command == "parking_settings":
                    request.update(current_read_fields())
                return json.dumps(request).encode()

        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input), \
                patch.object(bridge.time, "monotonic", lambda: clock[0]), \
                patch.object(bridge.time, "sleep", lambda seconds: clock.__setitem__(0, clock[0] + seconds)):
            result = bridge.run_session(motors, "fake", True, threading.Event(), diagnostic=Diagnostic())
        terminal = next((event["clock"] for event in events if event["event"] == "drive.failure"),
                        clock[0] - epoch[0])
        return result, messages, deadlines, events, terminal, motors, client

    def assert_deadlines(self, actual, expected):
        self.assertEqual(len(actual), len(expected))
        for left, right in zip(actual, expected):
            self.assertAlmostEqual(left, right)

    def test_suppressed_held_targets_do_not_extend_grace_or_repeat_motor_writes(self):
        grace = bridge.NETWORK_GRACE_SECONDS
        result, messages, deadlines, events, elapsed, motors, client = self.run_scripted_grace([
            (0.1, "drive"), "deadline", (1.2, "drive"), (grace - .1, "drive"), "deadline"])
        self.assertEqual(result, 1)
        self.assert_deadlines(deadlines, [0.6, 0.7, grace + .1, grace + .1, grace + .1])
        self.assertAlmostEqual(elapsed, grace + .1)
        acks = [message for message in messages if message["type"] == "ack"]
        self.assertEqual([ack["recoveryRequired"] for ack in acks], [False, True, True])
        self.assertFalse(acks[0]["stopped"])
        self.assertTrue(acks[1]["stopped"])
        # A late ACK may fall between two fresh physical samples. The lease
        # guarantee is the zero target; it must not forge physical confirmation.
        self.assertTrue(all(ack["targetStopped"] for ack in acks[1:]))
        # Stop refreshes may repeat; the suppressed held targets must never reach a motor.
        self.assertEqual(client.calls.count(("left", 30)), 1)
        self.assertEqual(client.calls.count(("right", -30)), 1)
        self.assertFalse(any(call[0] == "write" for call in client.calls))
        paused = [row for row in events if row["event"] == "drive.network_paused"]
        self.assertEqual(len(paused), 1)
        self.assertAlmostEqual(paused[0]["clock"], 0.7)
        self.assertEqual(paused[0]["lastAckSeq"], 1)
        recovery = [row for row in events if row["event"] == "drive.network_recovery_ack"]
        self.assertEqual([row["neutralAccepted"] for row in recovery], [False, False])
        for row in recovery:
            self.assertAlmostEqual(row["sessionDeadlineMonotonicMs"], (grace + .1) * 1000)
        self.assertEqual(next(row for row in events if row["event"] == "drive.failure")["stopSource"],
                         "network_grace_expired")
        self.assertGreaterEqual(motors.polls, 5, "IR polling remains active during grace")

    def test_current_read_during_recovery_does_not_release_latch_or_extend_grace(self):
        grace = bridge.NETWORK_GRACE_SECONDS
        result, messages, deadlines, events, elapsed, _motors, _client = self.run_scripted_grace([
            (0.1, "drive"), "deadline", (2.0, "parking_settings"), "deadline"], parking_seconds=0.8)
        self.assertEqual(result, 1)
        self.assert_deadlines(deadlines, [0.6, 0.7, grace + .1, grace + .1])
        self.assertAlmostEqual(elapsed, grace + .1)
        self.assertTrue(messages[2]["recoveryRequired"])
        self.assertTrue(messages[2]["parkingResult"]["ok"])
        self.assertTrue(messages[2]["parkingMode"])
        recovery = next(row for row in events if row["event"] == "drive.network_recovery_ack")
        self.assertFalse(recovery["neutralAccepted"])

    def test_recovery_current_read_completion_cannot_outlive_fixed_grace(self):
        result, messages, _deadlines, events, _elapsed, _motors, _client = self.run_scripted_grace([
            (0.1, "drive"), "deadline", (bridge.NETWORK_GRACE_SECONDS - .1, "parking_settings")], parking_seconds=0.3)
        self.assertEqual(result, 1)
        self.assertEqual([message["type"] for message in messages], ["ready", "ack", "error"])
        self.assertIn("network grace expired", messages[-1]["error"])
        self.assertFalse(any(row["event"] == "drive.network_recovery_ack" for row in events))

    def test_neutral_recovers_but_its_ack_still_marks_stale_input(self):
        for neutral in ("stop", "drive"):
            with self.subTest(neutral=neutral):
                result, messages, deadlines, events, elapsed, _motors, _client = self.run_scripted_grace([
                    (0.1, "drive"), "deadline", (1.2, neutral, 0, 0),
                    (1.3, "drive"), "deadline", "deadline"])
                self.assertEqual(result, 1)
                for actual, expected in zip(deadlines, [0.6, 0.7, bridge.NETWORK_GRACE_SECONDS + .1,
                                                        1.8, 1.9, bridge.NETWORK_GRACE_SECONDS + 1.3]):
                    self.assertAlmostEqual(actual, expected)
                self.assertEqual(len(deadlines), 6)
                self.assertAlmostEqual(elapsed, bridge.NETWORK_GRACE_SECONDS + 1.3)
                self.assertEqual([message["recoveryRequired"] for message in messages if message["type"] == "ack"],
                                 [False, True, False])
                recovery = next(row for row in events if row["event"] == "drive.network_recovery_ack")
                self.assertTrue(recovery["neutralAccepted"])
                self.assertTrue(recovery["stopped"])
                self.assertEqual(recovery["request"]["cmd"], neutral)
                self.assertEqual(len([row for row in events if row["event"] == "drive.network_paused"]), 2)

    def test_eof_and_protocol_errors_remain_terminal_during_grace(self):
        for cause, expected in [(SessionError("Control input closed"), "closed"), ("malformed", "Malformed")]:
            with self.subTest(cause=cause):
                result, messages, _deadlines, _events, elapsed, _motors, _client = self.run_scripted_grace([
                    (0.1, "drive"), "deadline", (1.0, cause)])
                self.assertEqual(result, 1)
                self.assertEqual(elapsed, 1.0)
                self.assertIn(expected, messages[-1]["error"])

    def test_output_failure_remains_terminal_and_never_claims_recovery_ack_sent(self):
        result, messages, _deadlines, events, elapsed, _motors, client = self.run_scripted_grace([
            (0.1, "drive"), "deadline", (1.0, "stop")], fail_ack=2)
        self.assertEqual(result, 1)
        self.assertEqual(elapsed, 1.0)
        self.assertIn("output blocked", messages[-1]["error"])
        self.assertFalse(any(row["event"] == "drive.network_recovery_ack" for row in events))
        self.assertFalse(any(call[0] == "write" for call in client.calls))

    def test_soft_stop_serial_failure_does_not_enter_waiting_grace(self):
        result, messages, deadlines, events, elapsed, _motors, _client = self.run_scripted_grace([
            (0.1, "drive"), "deadline"], fail_soft_stop=True)
        self.assertEqual(result, 1)
        self.assert_deadlines(deadlines, [0.6, 0.7])
        self.assertAlmostEqual(elapsed, 0.7)
        self.assertIn("Stop write was not confirmed", messages[-1]["error"])
        self.assertFalse(any(row["event"] == "drive.network_paused" for row in events))

    def test_dribbling_partial_input_never_renews_motion_or_network_deadline(self):
        # Exercise the real bounded InputPump across both deadlines without real time or IO.
        with patch.object(bridge.threading, "Thread"):
            pump = bridge.InputPump(-1)
        now, polls, reads = [0.0], [], [0]
        pump.poll = lambda: polls.append(now[0])

        def drip(*, timeout):
            now[0] += timeout
            reads[0] += 1
            # A slow byte drip stays within the framing bound throughout 15 s;
            # silence between bytes still exercises the actual queue/deadline path.
            if reads[0] % 5:
                raise bridge.queue.Empty
            return b" "

        with patch.object(bridge.time, "monotonic", lambda: now[0]), \
                patch.object(pump.chunks, "get", side_effect=drip):
            with self.assertRaises(bridge.MotionLeaseExpired):
                pump.line(0.6, threading.Event())
            self.assertAlmostEqual(now[0], 0.6)
            with self.assertRaises(bridge.MotionLeaseExpired):
                pump.line(bridge.NETWORK_GRACE_SECONDS, threading.Event())
            self.assertAlmostEqual(now[0], bridge.NETWORK_GRACE_SECONDS)
        self.assertGreater(len(polls), 200)
        self.assertNotIn(b"\n", pump.buffer)
        self.assertLess(len(pump.buffer), bridge.MAX_LINE_BYTES)


if __name__ == "__main__":
    unittest.main()
