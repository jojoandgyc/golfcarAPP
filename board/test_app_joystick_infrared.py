"""App infrared protection tests use fake motors and temporary sysfs only."""
from contextlib import contextmanager, redirect_stdout
from io import StringIO
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
from tempfile import TemporaryDirectory
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from test_app_joystick_bridge import FakeClient, FakeClock, ROOT, SCRIPT, bridge

REAL_SLEEP = time.sleep


@unittest.skipUnless(os.name == "posix", "IIO sysfs names require POSIX; run this suite under Linux/WSL")
class AppInfraredTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        for replacement in (patch.object(bridge.time, "monotonic", self.clock.monotonic),
                            patch.object(bridge.time, "sleep", self.clock.sleep)):
            replacement.start()
            self.addCleanup(replacement.stop)
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        environment = patch.dict(os.environ, {"APP_DRIVE_DIAGNOSTIC_TEST_DIR": str(self.root / "diagnostics")})
        environment.start()
        self.addCleanup(environment.stop)
        for device in (3, 4, 5):
            self.path(device).parent.mkdir()
            self.write(device, 1)
        self.infrared = bridge.InfraredInterlock(self.root)

    def path(self, device):
        return self.root / f"iio:device{device}" / "in_proximity_raw"

    def write(self, device, value):
        self.path(device).write_text(f"{value}\n", encoding="ascii")

    def motors(self):
        client = FakeClient()
        motors = bridge.Motors(client, infrared=self.infrared)
        motors.initialize()
        client.calls.clear()
        return motors, client

    def emergency_calls(self):
        return [("left", 0), ("right", 0), ("left", "stop", 1), ("right", "stop", 1)]

    def request(self, session, left, right, command="drive"):
        return {"seq": session.next_seq, "token": session.token, "cmd": command,
                "left": left, "right": right}

    def test_device_mapping_and_simultaneous_triggers(self):
        self.assertEqual(self.infrared.blocked_directions(), set())
        for device, direction in ((3, "forward"), (4, "right"), (5, "left")):
            with self.subTest(device=device):
                self.write(device, 0)
                self.assertEqual(self.infrared.blocked_directions(), {direction})
                self.write(device, 1)
        for device in (3, 4, 5):
            self.write(device, 0)
        self.assertEqual(self.infrared.blocked_directions(), {"forward", "right", "left"})

    def test_ready_advertises_real_guard_and_independent_public_run_id(self):
        session = bridge.ProtocolSession(self.motors()[0], port="fake", dry_run=True)
        ready = session.ready()
        self.assertTrue(ready["infraredSupported"])
        self.assertRegex(ready["runId"], r"^[0-9a-f]{32}$")
        self.assertNotEqual(ready["runId"], ready["token"])
        plain = bridge.ProtocolSession(bridge.Motors(), port="fake", dry_run=True)
        self.assertFalse(plain.ready()["infraredSupported"])

    def test_reply_snapshot_does_not_read_sensor_and_lists_only_relevant_latches(self):
        motors, _client = self.motors()
        self.write(3, 0)
        self.write(4, 0)
        session = bridge.ProtocolSession(motors, port="fake", dry_run=True)
        session.ready()
        calls = 0
        original = motors.infrared.blocked_directions

        def read():
            nonlocal calls
            calls += 1
            return original()

        with patch.object(motors.infrared, "blocked_directions", side_effect=read):
            ack, _ = session.accept(self.request(session, 70, -30))
        self.assertEqual(calls, 1)
        self.assertEqual(ack["infraredDirections"], ["forward", "right"])
        self.assertTrue(ack["infraredBlocked"])
        with patch.object(motors.infrared, "blocked_directions", side_effect=AssertionError("unexpected sensor read")):
            self.assertEqual(motors.infrared_state(), {
                "infraredBlocked": True, "infraredDirections": ["forward", "right"]})

    def test_non_drive_acks_never_present_old_ir_latch_as_an_override(self):
        for command in ("stop", "estop", "close"):
            with self.subTest(command=command):
                motors, _client = self.motors()
                session = bridge.ProtocolSession(motors, port="fake", dry_run=True)
                session.ready()
                self.write(3, 0)
                session.accept(self.request(session, 50, -50))
                request = self.request(session, 0, 0, command)
                ack, _ = session.accept(request)
                self.assertFalse(ack["infraredBlocked"])
                self.assertEqual(ack["infraredDirections"], [])

    def test_retired_parking_command_does_not_write_or_release_infrared_latch(self):
        motors, client = self.motors()
        session = bridge.ProtocolSession(motors, port="fake", dry_run=True)
        session.ready()
        self.write(3, 0)
        session.accept(self.request(session, 50, -50))
        latch = set(motors.ir_latched_directions)
        client.calls.clear(); client.feedback_reads.clear()
        request = self.request(session, 0, 0, "parking")
        request.update(parkingEnabled=True)
        with self.assertRaisesRegex(bridge.SessionError, "Legacy parking mode is unsupported"):
            session.accept(request)
        self.assertEqual(client.calls, [])
        self.assertEqual(client.feedback_reads, [])
        self.assertEqual(motors.ir_latched_directions, latch)

    def test_slow_ir_poll_cannot_renew_lease_from_buffered_input(self):
        pump = self.make_pump(self.motors()[0])
        pump.buffer.extend(b"late-request\n")
        now = [0.0]
        pump.poll = lambda: now.__setitem__(0, 0.61)
        with patch.object(bridge.time, "monotonic", side_effect=lambda: now[0]):
            with self.assertRaisesRegex(bridge.SessionError, "watchdog"):
                pump.line(0.6, threading.Event())
        self.assertEqual(pump.max_poll_seconds, 0.61)
        self.assertEqual(pump.buffer, b"late-request\n")

    def test_ir_poll_recalculates_remaining_wait_budget(self):
        pump = self.make_pump(self.motors()[0])
        now = [0.0]
        pump.poll = lambda: now.__setitem__(0, now[0] + 0.015)
        waits = []

        def timeout(*, timeout):
            waits.append(timeout)
            now[0] += timeout
            raise queue.Empty

        with patch.object(bridge.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(pump.chunks, "get", side_effect=timeout):
            with self.assertRaisesRegex(bridge.SessionError, "watchdog"):
                pump.line(0.02, threading.Event())
        self.assertEqual(len(waits), 1)
        self.assertAlmostEqual(waits[0], 0.005)

    def test_trigger_polarity_can_be_inverted(self):
        infrared = bridge.InfraredInterlock(self.root, trigger_value=1)
        self.assertEqual(infrared.blocked_directions(), {"forward", "right", "left"})
        for device in (3, 4, 5):
            self.write(device, 0)
        self.assertEqual(infrared.blocked_directions(), set())
        self.write(4, 1)
        self.assertEqual(infrared.blocked_directions(), {"right"})
        with self.assertRaises(ValueError):
            bridge.InfraredInterlock(self.root, trigger_value=2)

    def test_missing_sensor_blocks_its_direction(self):
        self.path(4).unlink()
        self.assertEqual(self.infrared.blocked_directions(), {"right"})

    def test_missing_sysfs_blocks_all_protected_directions(self):
        infrared = bridge.InfraredInterlock(self.root / "missing")
        self.assertEqual(infrared.blocked_directions(), {"forward", "right", "left"})

    def test_invalid_sensor_values_block_instead_of_clearing(self):
        for value in (b"", b"unknown\n", b"2\n", b"-1\n", b"0.0\n", b"\xff"):
            with self.subTest(value=value):
                self.path(3).write_bytes(value)
                self.assertEqual(self.infrared.blocked_directions(), {"forward"})

    def test_permission_error_blocks_only_unreadable_sensor(self):
        original = Path.read_text

        def read_text(path, *args, **kwargs):
            if path == self.path(5):
                raise PermissionError("simulated unavailable input")
            return original(path, *args, **kwargs)

        with patch.object(Path, "read_text", read_text):
            self.assertEqual(self.infrared.blocked_directions(), {"left"})

    def test_motion_components_include_forward_and_reverse_arcs(self):
        targets = (
            ((50, -50), {3}),
            ((-50, 50), set()),
            ((50, 50), {4}),
            ((-50, -50), {5}),
            ((70, -30), {3, 4}),
            ((30, -70), {3, 5}),
            ((-30, 70), {4}),
            ((-70, 30), {5}),
        )
        for target, blocking_devices in targets:
            for device in (3, 4, 5):
                with self.subTest(target=target, device=device):
                    for other in (3, 4, 5):
                        self.write(other, 1)
                    motors, client = self.motors()
                    self.write(device, 0)
                    accepted = motors.drive(*target)
                    if device in blocking_devices:
                        self.assertEqual(accepted, (0, 0))
                        self.assertEqual(motors.last_target, (0, 0))
                        # Initialization has already emergency-stopped and measured
                        # both wheels; a blocked request must not write motion.
                        self.assertEqual(client.calls, [])
                        self.assertTrue(motors.stopped_confirmed)
                    else:
                        self.assertEqual(accepted, target)
                        self.assertEqual(client.calls, [("left", target[0]), ("right", target[1])])
                    self.write(device, 1)

    def test_all_sensors_triggered_allow_straight_retreat(self):
        for device in (3, 4, 5):
            self.write(device, 0)
        motors, client = self.motors()
        self.assertEqual(motors.drive(-40, 40), (-40, 40))
        self.assertEqual(client.calls, [("left", -40), ("right", 40)])

    def test_trigger_during_motion_immediately_stops_both_motors_once(self):
        motors, client = self.motors()
        motors.drive(50, -50)
        client.calls.clear()
        self.write(3, 0)
        self.assertTrue(motors.check_infrared())
        self.assertEqual(client.calls, self.emergency_calls())
        self.assertEqual(motors.last_target, (0, 0))
        motors.check_infrared()
        self.assertEqual(client.calls, self.emergency_calls())

    def test_repeated_target_checks_sensor_before_deduplicating(self):
        motors, client = self.motors()
        motors.drive(50, -50)
        client.calls.clear()
        self.write(3, 0)
        self.assertEqual(motors.drive(50, -50), (0, 0))
        self.assertEqual(client.calls, self.emergency_calls())

    def test_sensor_loss_during_motion_stops_affected_direction(self):
        motors, client = self.motors()
        motors.drive(50, 50)
        client.calls.clear()
        self.path(4).unlink()
        self.assertTrue(motors.check_infrared())
        self.assertEqual(client.calls, self.emergency_calls())

    def test_clearing_sensor_does_not_restart_until_explicit_stop(self):
        motors, client = self.motors()
        motors.drive(50, -50)
        self.write(3, 0)
        motors.check_infrared()
        self.write(3, 1)
        client.calls.clear()
        self.assertEqual(motors.drive(50, -50), (0, 0))
        self.assertEqual(motors.drive(20, -20), (0, 0))
        self.assertEqual(client.calls, [])
        motors.stop()
        self.assertEqual(motors.drive(20, -20), (20, -20))
        self.assertEqual(client.calls, [("left", 20), ("right", -20)])

    def test_neutral_drive_releases_interlock(self):
        motors, client = self.motors()
        self.write(4, 0)
        motors.drive(30, 30)
        self.write(4, 1)
        self.assertEqual(motors.drive(30, 30), (0, 0))
        self.assertEqual(motors.drive(0, 0), (0, 0))
        client.calls.clear()
        self.assertEqual(motors.drive(30, 30), (30, 30))
        self.assertEqual(client.calls, [("left", 30), ("right", 30)])

    def test_neutral_does_not_allow_motion_if_obstacle_remains(self):
        motors, client = self.motors()
        self.write(3, 0)
        motors.drive(50, -50)
        motors.drive(0, 0)
        client.calls.clear()
        self.assertEqual(motors.drive(50, -50), (0, 0))
        self.assertEqual(client.calls, [])

    def test_retreat_and_other_turn_work_without_clearing_blocked_direction_latch(self):
        motors, client = self.motors()
        self.write(4, 0)
        motors.drive(40, 40)
        self.write(4, 1)
        self.assertEqual(motors.drive(-40, 40), (-40, 40))
        self.assertEqual(motors.drive(-40, -40), (-40, -40))
        client.calls.clear()
        self.assertEqual(motors.drive(20, 20), (0, 0))
        self.assertEqual(client.calls, self.emergency_calls())

    def test_trigger_during_motor_writes_is_checked_before_returning(self):
        motors, client = self.motors()
        original = client.set_right_speed

        def trigger_on_write(rpm):
            original(rpm)
            self.write(3, 0)

        with patch.object(client, "set_right_speed", side_effect=trigger_on_write):
            self.assertEqual(motors.drive(50, -50), (0, 0))
        self.assertEqual(client.calls, [("left", 50), ("right", -50)] + self.emergency_calls())

    def test_ir_uses_emergency_stop_when_parking_is_enabled(self):
        motors, client = self.motors()
        motors.parking_enabled = True
        motors.drive(50, -50)
        client.calls.clear()
        self.write(3, 0)
        motors.check_infrared()
        self.assertEqual(client.calls, self.emergency_calls())
        self.assertTrue(motors.parking_enabled)

    def test_internal_stop_policy_changes_preserve_ir_latch_and_current_configuration(self):
        # Direct private setup for IR protection, never a wire-level parking switch.
        for parking_already_enabled in (False, True):
            with self.subTest(parking_already_enabled=parking_already_enabled):
                motors, client = self.motors()
                motors.stop()
                if parking_already_enabled:
                    motors.set_parking(True)
                motors.drive(50, -50)
                self.write(3, 0)
                motors.check_infrared()
                self.write(3, 1)
                motors.await_standstill()
                motors.set_parking(True)
                self.assertFalse(any(call[0] == "write" for call in client.calls))
                client.calls.clear()
                self.assertEqual(motors.drive(50, -50), (0, 0))
                self.assertEqual(client.calls, [])
                motors.stop()
                self.assertEqual(motors.drive(50, -50), (50, -50))

    def test_ir_stop_failure_attempts_both_motors_and_does_not_claim_stopped(self):
        motors, client = self.motors()
        motors.drive(50, -50)
        client.calls.clear()
        client.fail_left = True
        self.write(3, 0)
        with self.assertRaisesRegex(bridge.SessionError, "not confirmed"):
            motors.check_infrared()
        self.assertEqual(client.calls, self.emergency_calls() * 2)
        self.assertIsNone(motors.last_target)

    def test_blocked_drive_ack_reports_actual_zero_and_rotates_token(self):
        motors, client = self.motors()
        session = bridge.ProtocolSession(motors, port="fake", dry_run=False)
        session.ready()
        self.write(3, 0)
        previous = session.token
        ack, closing = session.accept(self.request(session, 50, -50))
        self.assertEqual((ack["left"], ack["right"]), (0, 0))
        self.assertTrue(ack["stopped"])
        self.assertTrue(ack["infraredBlocked"])
        self.assertEqual(ack["infraredDirections"], ["forward"])
        self.assertFalse(closing)
        self.assertEqual(ack["seq"], 1)
        self.assertEqual(session.next_seq, 2)
        self.assertNotEqual(ack["token"], previous)
        with self.assertRaisesRegex(bridge.SessionError, "token"):
            session.accept(dict(self.request(session, -50, 50), token=previous))
        ack, _ = session.accept(self.request(session, -50, 50))
        self.assertEqual((ack["left"], ack["right"]), (-50, 50))
        self.assertFalse(ack["stopped"])
        self.assertFalse(ack["infraredBlocked"])
        self.assertEqual(ack["infraredDirections"], [])

    def test_protocol_neutral_releases_blocked_request(self):
        session = bridge.ProtocolSession(self.motors()[0], port="fake", dry_run=False)
        session.ready()
        self.write(5, 0)
        session.accept(self.request(session, -20, -20))
        self.write(5, 1)
        session.accept(self.request(session, 0, 0))
        ack, _ = session.accept(self.request(session, -20, -20))
        self.assertEqual((ack["left"], ack["right"]), (-20, -20))

    def test_ir_reader_and_motor_guard_never_print_to_json_stdout(self):
        output = StringIO()
        with redirect_stdout(output):
            self.infrared.blocked_directions()
            self.write(3, 0)
            motors, _ = self.motors()
            motors.drive(50, -50)
            motors.check_infrared()
            self.path(4).unlink()
            self.infrared.blocked_directions()
        self.assertEqual(output.getvalue(), "")

    def make_pump(self, motors):
        with patch.object(bridge.threading, "Thread"):
            pump = bridge.InputPump(-1)
        pump.poll = motors.check_infrared
        return pump

    def test_waiting_for_input_polls_ir_without_complete_command(self):
        motors, client = self.motors()
        motors.drive(50, -50)
        client.calls.clear()
        pump = self.make_pump(motors)
        now = [0.0]
        waits = []

        def get_chunk(*, timeout):
            waits.append(timeout)
            if len(waits) == 1:
                self.write(3, 0)
                now[0] += timeout
                raise queue.Empty
            self.assertEqual(client.calls, self.emergency_calls())
            return b"request\n"

        with patch.object(pump.chunks, "get", side_effect=get_chunk), \
                patch.object(bridge.time, "monotonic", side_effect=lambda: now[0]):
            self.assertEqual(pump.line(1.0, threading.Event()), b"request")
        self.assertTrue(all(0 < timeout <= 0.02 for timeout in waits))

    def test_buffered_complete_request_cannot_skip_ir_poll(self):
        motors, client = self.motors()
        motors.drive(50, -50)
        client.calls.clear()
        self.write(3, 0)
        pump = self.make_pump(motors)
        pump.buffer.extend(b"first\nsecond\n")
        self.assertEqual(pump.line(time.monotonic() + 1, threading.Event()), b"first")
        self.assertEqual(client.calls, self.emergency_calls())

    def test_partial_request_still_polls_and_expires_watchdog(self):
        motors, client = self.motors()
        motors.drive(50, -50)
        client.calls.clear()
        pump = self.make_pump(motors)
        pump.buffer.extend(b'{"seq":')
        now = [0.0]

        def no_complete_request(*, timeout):
            self.write(3, 0)
            now[0] += timeout
            raise queue.Empty

        with patch.object(pump.chunks, "get", side_effect=no_complete_request), \
                patch.object(bridge.time, "monotonic", side_effect=lambda: now[0]):
            with self.assertRaisesRegex(bridge.SessionError, "watchdog"):
                pump.line(0.1, threading.Event())
        self.assertEqual(client.calls, self.emergency_calls())

    def test_run_session_connects_idle_poll_to_motor_guard(self):
        motors, client = self.motors()
        messages = []
        owner = self

        class Output:
            def __init__(self, _fd):
                pass

            def send(self, value):
                messages.append(value)

        class Input:
            def __init__(self, _fd):
                self.count = 0

            def line(self, _deadline, _stopped):
                self.count += 1
                if self.count == 1:
                    return json.dumps({"seq": 1, "token": messages[-1]["token"],
                                       "cmd": "drive", "left": 50, "right": -50}).encode()
                client.calls.clear()
                owner.write(3, 0)
                self.poll()
                owner.assertEqual(client.calls, owner.emergency_calls())
                return json.dumps({"seq": 2, "token": messages[-1]["token"],
                                   "cmd": "close", "left": 0, "right": 0}).encode()

        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
            self.assertEqual(bridge.run_session(motors, "fake", False, threading.Event()), 0)

    def test_dry_run_default_does_not_construct_or_read_ir(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(sys, "argv", [str(SCRIPT), "--dry-run"]), \
                patch.object(bridge.signal, "signal"), \
                patch.object(bridge, "InfraredInterlock", side_effect=AssertionError("unexpected sysfs access")), \
                patch.object(bridge, "run_session", return_value=0) as session:
            self.assertEqual(bridge.main(), 0)
        self.assertIsNone(session.call_args.args[0].infrared)

    def test_dry_run_explicit_fake_root_enables_ir_and_requested_polarity(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(sys, "argv", [str(SCRIPT), "--dry-run", "--ir-sysfs-root", str(self.root),
                                           "--ir-trigger-value", "1"]), \
                patch.object(bridge.signal, "signal"), \
                patch.object(bridge, "run_session", return_value=0) as session:
            self.assertEqual(bridge.main(), 0)
        infrared = session.call_args.args[0].infrared
        self.assertEqual(infrared.root, self.root)
        self.assertEqual(infrared.blocked_directions(), {"forward", "right", "left"})

    def test_main_hardware_session_passes_default_ir_guard(self):
        received = {}

        @contextmanager
        def fake_hardware(port, *, infrared):
            received.update(port=port, infrared=infrared)
            yield bridge.Motors(infrared=infrared), "fake"

        with patch.dict(os.environ, {}, clear=True), \
                patch.object(sys, "argv", [str(SCRIPT), "--require-safety-version", "2"]), \
                patch.object(bridge.signal, "signal"), \
                patch.object(bridge, "open_hardware", fake_hardware), \
                patch.object(bridge, "run_session", return_value=0) as session:
            self.assertEqual(bridge.main(), 0)
        self.assertEqual(received["port"], "auto")
        self.assertIsInstance(received["infrared"], bridge.InfraredInterlock)
        self.assertEqual(received["infrared"].root, Path("/sys/bus/iio/devices"))
        self.assertIs(session.call_args.args[0].infrared, received["infrared"])
        self.assertFalse(session.call_args.args[2])

    @unittest.skipUnless(os.name == "posix", "requires POSIX serial ownership APIs")
    def test_hardware_factory_installs_default_ir_guard_without_real_devices(self):
        sys.path.insert(0, str(ROOT / "src"))
        client = FakeClient()
        client.read_device_info = Mock(return_value={})
        transport = SimpleNamespace(fileno=lambda: 123, close=Mock())
        serial = SimpleNamespace(Serial=Mock(return_value=transport))
        with (
            patch.dict(sys.modules, {"serial": serial,
                "lz30ema_rs485": SimpleNamespace(LZ30EMAClient=Mock(return_value=client))}),
            patch.object(bridge, "PROJECT_ROOT", self.root),
            patch.object(bridge, "reject_conflicting_processes"),
            patch.object(bridge, "reject_other_serial_owners"),
            patch("fcntl.flock"),
            patch("fcntl.ioctl"),
        ):
            with bridge.open_hardware("/dev/ttyS0") as (motors, _port):
                self.assertIsInstance(motors.infrared, bridge.InfraredInterlock)
                self.assertEqual(motors.infrared.root, Path("/sys/bus/iio/devices"))
        transport.close.assert_called_once()

    def test_dry_run_json_session_latches_trigger_while_no_commands_arrive(self):
        environment = {key: value for key, value in os.environ.items()
                       if key not in {"LZ_IR_SYSFS_ROOT", "LZ_IR_TRIGGER_VALUE"}}
        process = subprocess.Popen(
            [sys.executable, str(SCRIPT), "--dry-run", "--ir-sysfs-root", str(self.root)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment,
        )

        def cleanup():
            if process.poll() is None:
                process.kill()
            process.wait(timeout=3)
            process.stdin.close()
            process.stdout.close()
            process.stderr.close()

        self.addCleanup(cleanup)
        reply = json.loads(process.stdout.readline())
        self.assertEqual(reply["type"], "ready")
        seq = 0

        def exchange(left, right, command="drive"):
            nonlocal reply, seq
            seq += 1
            request = {"seq": seq, "token": reply["token"], "cmd": command,
                       "left": left, "right": right}
            process.stdin.write((json.dumps(request) + "\n").encode())
            process.stdin.flush()
            reply = json.loads(process.stdout.readline())
            self.assertEqual(reply["type"], "ack")
            return reply

        self.assertFalse(exchange(40, -40)["stopped"])
        self.write(3, 0)
        REAL_SLEEP(0.08)
        self.write(3, 1)
        stopped = exchange(40, -40)
        self.assertTrue(stopped["targetStopped"])
        self.assertEqual((stopped["left"], stopped["right"]), (0, 0))
        REAL_SLEEP(0.12)
        self.assertTrue(exchange(40, -40)["stopped"])
        self.assertFalse(exchange(-40, 40)["stopped"])
        self.assertTrue(exchange(40, -40)["targetStopped"])
        self.assertTrue(exchange(0, 0)["targetStopped"])
        self.assertFalse(exchange(40, -40)["stopped"])
        self.assertTrue(exchange(0, 0, "close")["stopped"])
        process.wait(timeout=3)
        self.assertEqual(process.returncode, 0)
        self.assertEqual(process.stdout.read(), b"")
        self.assertEqual(process.stderr.read(), b"")


if __name__ == "__main__":
    unittest.main()
