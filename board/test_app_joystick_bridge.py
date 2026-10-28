"""Offline tests: fake motors or --dry-run subprocesses only; no board access."""
import json
from pathlib import Path
import subprocess
import sys
import time
import unittest
from unittest.mock import patch
import threading

import app_joystick_bridge as bridge
from app_joystick_bridge import Motors, ProtocolSession, SessionError, strict_message


SCRIPT = Path(__file__).with_name("app_joystick_bridge.py")


class FakeClient:
    def __init__(self, fail_left=False):
        self.calls = []
        self.fail_left = fail_left
        self.registers = {"right_parking_current": 9, "left_parking_current": 9}
        self.fail_writes = set()
        self.readback = {}

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
        return self.readback.get(register, self.registers[register])


def parking_calls(current):
    return [("write", "right_parking_current", current, True),
            ("write", "left_parking_current", current, True),
            ("read", "right_parking_current"), ("read", "left_parking_current")]


def parking_request(session, enabled=True, current=5, **extra):
    return {"seq": session.next_seq, "token": session.token, "cmd": "parking",
            "left": 0, "right": 0, "parkingEnabled": enabled,
            "parkingCurrent": current, **extra}


class BridgeTests(unittest.TestCase):
    def test_ready_clears_existing_currents_and_reports_default_setting(self):
        client = FakeClient()
        session = ProtocolSession(Motors(client), port="fake", dry_run=False)
        ready = session.ready()
        self.assertEqual(client.calls[-4:], parking_calls(0))
        self.assertTrue(ready["parkingSupported"])
        self.assertFalse(ready["parkingEnabled"])
        self.assertEqual(ready["parkingCurrent"], 5)

    def test_parking_engages_normal_stop_and_release_keeps_parking(self):
        client = FakeClient()
        session = ProtocolSession(Motors(client), port="fake", dry_run=False)
        session.ready()
        client.calls.clear()
        ack, closing = session.accept(parking_request(session, current=6))
        self.assertTrue(ack["parkingEnabled"])
        self.assertEqual(ack["parkingCurrent"], 6)
        self.assertFalse(closing)
        self.assertEqual(client.calls[:4], parking_calls(6))
        self.assertEqual(client.calls[-2:], [("left", "stop", 0), ("right", "stop", 0)])
        session.accept({"seq": 2, "token": ack["token"], "cmd": "drive", "left": 30, "right": -30})
        ack, _ = session.accept({"seq": 3, "token": session.token, "cmd": "stop", "left": 0, "right": 0})
        self.assertTrue(ack["parkingEnabled"])
        self.assertEqual(client.calls[-2:], [("left", "stop", 0), ("right", "stop", 0)])

    def test_parking_off_adjustment_and_repeated_heartbeats_do_not_write_settings(self):
        client = FakeClient()
        motors = Motors(client)
        session = ProtocolSession(motors, port="fake", dry_run=False)
        session.ready()
        client.calls.clear()
        session.accept(parking_request(session, enabled=False, current=60))
        self.assertEqual(client.calls, [])
        session.accept(parking_request(session, current=60))
        client.calls.clear()
        session.accept(parking_request(session, current=60))
        session.accept({"seq": session.next_seq, "token": session.token, "cmd": "stop", "left": 0, "right": 0})
        self.assertEqual(client.calls, [])
        ack, _ = session.accept(parking_request(session, enabled=False, current=0))
        self.assertEqual(client.calls, parking_calls(0))
        self.assertFalse(ack["parkingEnabled"])
        motors.drive(10, -10)
        motors.stop()
        self.assertEqual(client.calls[-2:], [("left", "stop", 1), ("right", "stop", 1)])

    def test_parking_rejects_motion_and_invalid_parameters_without_writes(self):
        client = FakeClient()
        session = ProtocolSession(Motors(client), port="fake", dry_run=False)
        session.ready()
        client.calls.clear()
        for enabled, current in [(1, 5), ("true", 5), (True, 0), (False, -1),
                                 (True, 61), (True, True), (True, 1.5), (True, "5")]:
            with self.subTest(enabled=enabled, current=current), self.assertRaises(SessionError):
                session.accept(parking_request(session, enabled, current))
        with self.assertRaisesRegex(SessionError, "zero"):
            session.accept(parking_request(session, left=1))
        self.assertEqual(client.calls, [])
        session.motors.drive(10, -10)
        client.calls.clear()
        with self.assertRaisesRegex(SessionError, "stopped"):
            session.accept(parking_request(session))
        self.assertEqual(client.calls, [])

    def test_parking_json_requires_exact_command_specific_fields(self):
        session = ProtocolSession(Motors(), port="fake", dry_run=True)
        request = parking_request(session)
        self.assertEqual(strict_message(json.dumps(request).encode()), request)
        invalid = [dict(request, extra=1), {key: value for key, value in request.items() if key != "parkingCurrent"},
                   dict(request, cmd="stop"), dict(request, cmd="drive")]
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(SessionError):
                strict_message(json.dumps(payload).encode())
        with self.assertRaises(SessionError):
            strict_message(b'{"seq":1,"token":"x","cmd":"parking","left":0,"right":0,'
                           b'"parkingEnabled":true,"parkingEnabled":false,"parkingCurrent":5}')

    def test_parking_write_failure_attempts_both_sides_without_confirming(self):
        client = FakeClient()
        session = ProtocolSession(Motors(client), port="fake", dry_run=False)
        session.ready()
        client.calls.clear()
        client.fail_writes.add("right_parking_current")
        with self.assertRaisesRegex(SessionError, "not confirmed"):
            session.accept(parking_request(session))
        self.assertEqual(client.calls, parking_calls(5))
        self.assertFalse(session.motors.parking_enabled)
        self.assertEqual(session.next_seq, 1)

    def test_parking_readback_mismatch_and_nonfinite_values_are_not_confirmed(self):
        for actual in (4, float("nan"), float("inf")):
            with self.subTest(actual=actual):
                client = FakeClient()
                session = ProtocolSession(Motors(client), port="fake", dry_run=False)
                session.ready()
                client.calls.clear()
                client.readback["right_parking_current"] = actual
                with self.assertRaisesRegex(SessionError, "readback mismatch"):
                    session.accept(parking_request(session))
                self.assertEqual(client.calls, parking_calls(5))
                self.assertFalse(session.motors.parking_enabled)

    def test_terminal_commands_emergency_stop_and_clear_parking_before_ack(self):
        for command in ("estop", "close"):
            with self.subTest(command=command):
                client = FakeClient()
                session = ProtocolSession(Motors(client), port="fake", dry_run=False)
                session.ready()
                session.accept(parking_request(session))
                client.calls.clear()
                ack, closing = session.accept({"seq": session.next_seq, "token": session.token,
                                               "cmd": command, "left": 0, "right": 0})
                self.assertEqual(client.calls[:4], [("left", 0), ("right", 0),
                                                    ("left", "stop", 1), ("right", "stop", 1)])
                self.assertEqual(client.calls[-4:], parking_calls(0))
                self.assertFalse(ack["parkingEnabled"])
                self.assertEqual(ack["parkingCurrent"], 5)
                self.assertTrue(closing)

    def test_terminal_session_clears_once_even_when_ack_output_fails(self):
        for command in ("estop", "close"):
            for fail_output in (False, True):
                with self.subTest(command=command, fail_output=fail_output):
                    client = FakeClient()
                    messages = []

                    class Output:
                        def __init__(self, _fd):
                            pass

                        def send(self, message):
                            messages.append(message)
                            if message["type"] == "ready":
                                client.calls.clear()  # Count only terminal cleanup.
                            elif message["type"] == "ack" and fail_output:
                                raise BlockingIOError("SSH reader stalled")

                    class Input:
                        def __init__(self, _fd):
                            pass

                        def line(self, _deadline, _stopped):
                            return json.dumps({"seq": 1, "token": messages[0]["token"],
                                               "cmd": command, "left": 0, "right": 0}).encode()

                    with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
                        result = bridge.run_session(Motors(client), "fake", False, threading.Event())
                    self.assertEqual(result, int(fail_output))
                    self.assertEqual(client.calls, [("left", 0), ("right", 0),
                                                    ("left", "stop", 1), ("right", "stop", 1)] + parking_calls(0))

    def test_shutdown_attempts_stop_and_clear_despite_both_failures(self):
        client = FakeClient(fail_left=True)
        client.fail_writes.add("right_parking_current")
        with self.assertRaises(SessionError):
            Motors(client).shutdown()
        self.assertEqual(client.calls[:4], [("left", 0), ("right", 0),
                                            ("left", "stop", 1), ("right", "stop", 1)])
        self.assertEqual(client.calls[-4:], parking_calls(0))

    def test_session_readback_failure_never_acks_and_still_stops_and_clears(self):
        client = FakeClient()
        messages = []

        class Output:
            def __init__(self, _fd):
                pass

            def send(self, message):
                messages.append(message)

        class Input:
            def __init__(self, _fd):
                pass

            def line(self, _deadline, _stopped):
                client.readback["right_parking_current"] = 4
                return json.dumps({"seq": 1, "token": messages[0]["token"], "cmd": "parking",
                                   "left": 0, "right": 0, "parkingEnabled": True,
                                   "parkingCurrent": 5}).encode()

        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
            self.assertEqual(bridge.run_session(Motors(client), "fake", False, threading.Event()), 1)
        self.assertEqual([message["type"] for message in messages], ["ready", "error"])
        self.assertIn("readback mismatch", messages[-1]["error"])
        self.assertEqual(client.calls[-8:-4], [("left", 0), ("right", 0),
                                             ("left", "stop", 1), ("right", "stop", 1)])
        self.assertEqual(client.calls[-4:], parking_calls(0))

    def test_eof_after_parking_enable_emergency_stops_and_clears(self):
        client = FakeClient()
        motors = Motors(client)
        messages = []

        class Output:
            def __init__(self, _fd):
                pass

            def send(self, message):
                messages.append(message)

        class Input:
            def __init__(self, _fd):
                self.requested = False

            def line(self, _deadline, _stopped):
                if self.requested:
                    raise SessionError("Control input closed")
                self.requested = True
                return json.dumps({"seq": 1, "token": messages[0]["token"], "cmd": "parking",
                                   "left": 0, "right": 0, "parkingEnabled": True,
                                   "parkingCurrent": 5}).encode()

        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
            self.assertEqual(bridge.run_session(motors, "fake", False, threading.Event()), 1)
        self.assertEqual([message["type"] for message in messages], ["ready", "ack", "error"])
        self.assertTrue(messages[1]["parkingEnabled"])
        self.assertFalse(motors.parking_enabled)
        self.assertEqual(client.calls[-8:-4], [("left", 0), ("right", 0),
                                             ("left", "stop", 1), ("right", "stop", 1)])
        self.assertEqual(client.calls[-4:], parking_calls(0))

    def test_stop_attempts_zero_and_emergency_on_both_sides_after_failure(self):
        client = FakeClient(fail_left=True)
        with self.assertRaises(SessionError):
            Motors(client).stop(force=True)
        self.assertEqual(client.calls, [("left", 0), ("right", 0), ("left", "stop", 1), ("right", "stop", 1)])

    def test_repeated_targets_do_not_repeat_serial_transactions(self):
        client = FakeClient()
        motors = Motors(client)
        motors.stop(force=True)
        motors.stop()
        self.assertEqual(len(client.calls), 4)
        motors.drive(50, -50)
        motors.drive(50, -50)
        self.assertEqual(len(client.calls), 6)
        motors.stop()
        self.assertEqual(len(client.calls), 10)

    def test_nonce_rotation_rejects_a_queued_or_replayed_command(self):
        session = ProtocolSession(Motors(), port="fake", dry_run=True)
        ready = session.ready()
        request = {"seq": 1, "token": ready["token"], "cmd": "drive", "left": 50, "right": -50}
        ack, closing = session.accept(request)
        self.assertNotEqual(ack["token"], ready["token"])
        self.assertFalse(closing)
        request["seq"] = 2
        with self.assertRaisesRegex(SessionError, "token"):
            session.accept(request)

    def test_json_rejects_duplicate_fields_boolean_rpm_and_overspeed(self):
        with self.assertRaises(SessionError):
            strict_message(b'{"seq":1,"seq":1,"token":"x","cmd":"stop","left":0,"right":0}')
        with self.assertRaises(SessionError):
            strict_message(b'x' * 513)
        for left in (True, 101, -101, 1.5, "10"):
            session = ProtocolSession(Motors(), port="fake", dry_run=True)
            with self.assertRaises(SessionError):
                session.accept({"seq": 1, "token": session.token, "cmd": "drive", "left": left, "right": 0})

    def test_output_failure_after_motion_still_attempts_both_stops(self):
        client = FakeClient()
        messages = []

        class Output:
            def __init__(self, _fd):
                pass

            def send(self, value):
                messages.append(value)
                if value["type"] == "ack":
                    raise BlockingIOError("SSH reader stalled")

        class Input:
            def __init__(self, _fd):
                pass

            def line(self, _deadline, _stopped):
                return json.dumps({"seq": 1, "token": messages[0]["token"],
                                   "cmd": "drive", "left": 50, "right": -50}).encode()

        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
            self.assertEqual(bridge.run_session(Motors(client), "fake", True, threading.Event()), 1)
        self.assertEqual(client.calls[-8:-4], [("left", 0), ("right", 0),
                                            ("left", "stop", 1), ("right", "stop", 1)])
        self.assertEqual(client.calls[-4:], parking_calls(0))
        self.assertIn(("left", 50), client.calls)
        self.assertIn(("right", -50), client.calls)

    def start(self):
        process = subprocess.Popen([sys.executable, str(SCRIPT), "--dry-run"], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(lambda: process.poll() is None and process.kill())
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
        error = json.loads(process.stdout.readline())
        self.assertIn("watchdog", error["error"])
        self.assertLess(time.monotonic() - started, 1.5)
        self.finish(process)
        self.assertNotEqual(process.returncode, 0)

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

    def test_stop_ack_and_close_are_confirmed(self):
        process, ready = self.start()
        drive = self.exchange(process, ready)
        stop = self.exchange(process, drive, seq=2, cmd="stop", left=0, right=0)
        self.assertTrue(stop["stopped"])
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

    def test_dry_run_parking_roundtrip_and_terminal_clear(self):
        process, ready = self.start()
        self.assertTrue(ready["parkingSupported"])
        self.assertEqual(ready["parkingCurrent"], 5)
        self.assertFalse(ready["parkingEnabled"])
        parking = self.exchange(process, ready, cmd="parking", left=0, right=0,
                                parkingEnabled=True, parkingCurrent=6)
        self.assertEqual(parking["type"], "ack")
        self.assertTrue(parking["parkingEnabled"])
        self.assertEqual(parking["parkingCurrent"], 6)
        drive = self.exchange(process, parking, seq=2)
        self.assertTrue(drive["parkingEnabled"])
        stop = self.exchange(process, drive, seq=3, cmd="stop", left=0, right=0)
        self.assertTrue(stop["parkingEnabled"])
        close = self.exchange(process, stop, seq=4, cmd="close", left=0, right=0)
        self.assertFalse(close["parkingEnabled"])
        self.assertEqual(close["parkingCurrent"], 6)
        self.finish(process)
        self.assertEqual(process.returncode, 0)

    def run_fake_timed_session(self, command, duration):
        clock = [0.0]
        messages, deadlines = [], []

        class TimedMotors(Motors):
            def set_parking(self, enabled, current):
                super().set_parking(enabled, current)
                clock[0] += duration

            def drive(self, left, right):
                super().drive(left, right)
                clock[0] += duration

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
                if cmd == "parking":
                    request.update(parkingEnabled=True, parkingCurrent=5)
                return json.dumps(request).encode()

        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input), \
                patch.object(bridge.time, "monotonic", lambda: clock[0]):
            result = bridge.run_session(TimedMotors(), "fake", True, threading.Event())
        return result, messages, deadlines

    def test_only_parking_can_extend_transaction_and_refresh_lease_after_completion(self):
        result, messages, deadlines = self.run_fake_timed_session("parking", 0.8)
        self.assertEqual(result, 0)
        self.assertEqual([message["type"] for message in messages], ["ready", "ack", "ack"])
        self.assertAlmostEqual(deadlines[1], 1.4)
        result, messages, _ = self.run_fake_timed_session("drive", 0.8)
        self.assertEqual(result, 1)
        self.assertEqual(messages[-1]["type"], "error")
        self.assertIn("watchdog", messages[-1]["error"])

    def test_parking_transaction_over_two_seconds_has_no_success_ack(self):
        result, messages, _ = self.run_fake_timed_session("parking", 2.01)
        self.assertEqual(result, 1)
        self.assertEqual([message["type"] for message in messages], ["ready", "error"])
        self.assertIn("Parking transaction exceeded timeout", messages[-1]["error"])


if __name__ == "__main__":
    unittest.main()
