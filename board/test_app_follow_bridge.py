"""Offline only: fake state or explicitly --dry-run subprocesses; no runtime imports."""
import contextlib
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from types import ModuleType, SimpleNamespace

import app_follow_bridge as bridge
import app_follow_worker as worker
from app_follow_worker import FollowSnapshot, LeaseExpired, LeaseGuard, install_runtime_hooks

SCRIPT = Path(__file__).with_name("app_follow_bridge.py")
WORKER = Path(__file__).with_name("app_follow_worker.py")


class FakeProcess:
    camera = "config"
    execute = False
    dry_run = True
    process = None
    stop_at = None

    def __init__(self):
        self.starts = self.pulses = 0
        self.exited = False

    def start(self):
        if self.process is None:
            self.starts += 1
            self.process = object()

    def pulse(self):
        self.pulses += 1

    def request_stop(self, reason):
        self.stop_at = time.monotonic()

    def status(self):
        return dict(runState="STOPPED" if self.exited or self.process is None else
                    "STOPPING" if self.stop_at else "STARTING",
                    processExited=self.exited or self.process is None)


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.process = FakeProcess()
        self.session = bridge.ProtocolSession(self.process)

    def request(self, command, **changes):
        return dict(dict(seq=self.session.sequence, token=self.session.token,
                         runId=self.session.run_id, cmd=command), **changes)

    def test_ready_is_stopped_and_does_not_start_a_process(self):
        ready = self.session.ready()
        self.assertEqual(ready["status"]["runState"], "STOPPED")
        self.assertEqual(ready["heartbeatMs"], 500)
        self.assertEqual(ready["watchdogMs"], 2000)
        self.assertEqual(self.process.starts, 0)

    def test_duplicate_start_is_idempotent_and_stop_during_startup_is_not_terminal(self):
        self.session.accept(self.request("start"))
        self.session.accept(self.request("start"))
        self.assertEqual(self.process.starts, 1)
        ack = self.session.accept(self.request("stop"))
        self.assertEqual(ack["status"]["runState"], "STOPPING")
        self.assertFalse(ack["terminal"])
        self.process.exited = True
        self.assertTrue(self.session.accept(self.request("heartbeat"))["terminal"])

    def test_close_without_start_is_terminal(self):
        self.assertTrue(self.session.accept(self.request("close"))["terminal"])
        self.assertEqual(self.process.starts, 0)

    def test_nonce_rotation_and_wrong_run_id_reject_stale_commands(self):
        old = self.request("start")
        reply = self.session.accept(old)
        self.assertNotEqual(reply["token"], old["token"])
        for bad in (dict(old, seq=2), self.request("stop", runId="a" * 32),
                    self.request("stop", seq=True), self.request("shell")):
            with self.assertRaises(bridge.SessionError):
                self.session.accept(bad)
        self.assertIsNone(self.process.stop_at)

    def test_strict_json_rejects_extra_duplicate_and_nonfinite_fields(self):
        for line in (b'{"seq":1,"seq":2}', b'{}', b'[]', b'x' * 1025,
                     json.dumps(dict(self.request("start"), path="/tmp/arbitrary")).encode(),
                     b'{"seq":NaN,"cmd":"start","token":"x","runId":"x"}'):
            with self.subTest(line=line[:80]), self.assertRaises(bridge.SessionError):
                bridge.strict_request(line)


class WorkerTests(unittest.TestCase):
    def test_distance_override_copies_full_settings_and_removes_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline = root / "continuous_follow/baseline.ini"
            baseline.parent.mkdir()
            baseline.write_text("[continuous_follow]\ntarget_distance_m = 1.2\nmax_rpm = 100\n", encoding="utf-8")
            with patch.object(worker, "RUNTIME_ROOT", root):
                with worker.runtime_settings(1.5) as settings:
                    self.assertNotEqual(settings, baseline)
                    self.assertEqual(settings.parent, baseline.parent)
                    self.assertIn("target_distance_m = 1.5", settings.read_text(encoding="utf-8"))
                    self.assertIn("max_rpm = 100", settings.read_text(encoding="utf-8"))
                self.assertFalse(settings.exists())
                self.assertIn("target_distance_m = 1.2", baseline.read_text(encoding="utf-8"))

    def test_runtime_arguments_use_selected_flags_without_execute_for_config_check(self):
        args = SimpleNamespace(camera="astra-pro", gestures=True, gesture_resume="Thumb_Up",
            avoidance="on", reverse_follow=True, reverse_distance=1.4,
            execute=False, check_config=True)
        arguments = worker.runtime_arguments(args, Path("/tmp/settings.ini"))
        self.assertEqual(arguments[-1], "--check-config")
        self.assertNotIn("--execute", arguments)
        for flag in ("--gestures", "--gesture-resume", "Thumb_Up", "--avoidance", "on",
                     "--reverse-follow", "--reverse-distance", "1.4"):
            self.assertIn(flag, arguments)

    def test_expired_lease_cannot_be_renewed_or_open_hardware(self):
        guard = LeaseGuard(os.getppid(), timeout=.01)
        guard.last_pulse -= 1
        with self.assertRaises(LeaseExpired):
            guard.ensure_live()
        guard.pulse()
        with self.assertRaises(LeaseExpired):
            guard.ensure_live()

    def test_parent_death_is_detected_even_with_a_fresh_pulse(self):
        guard = LeaseGuard(os.getppid())
        with patch("app_follow_worker.os.getppid", return_value=guard.parent_pid + 1):
            with self.assertRaisesRegex(LeaseExpired, "parent"):
                guard.ensure_live()

    def test_signal_marker_is_consumed_by_guard_without_revival(self):
        guard = LeaseGuard(os.getppid())
        guard.pending_signal = "app_signal_stop"
        with self.assertRaisesRegex(LeaseExpired, "signal"):
            guard.ensure_live()
        guard.pulse()
        self.assertTrue(guard.stopped.is_set())

    def test_eof_requests_runtime_stop_without_motor_access(self):
        guard = LeaseGuard(os.getppid())
        guard.start(io.BytesIO(b""))
        self.assertTrue(guard.stopped.wait(1))
        self.assertEqual(guard.reason, "app_lease_closed")

    def test_expired_startup_guard_stops_runtime_when_attached(self):
        reasons = []
        class Runtime:
            def request_stop(self, reason):
                reasons.append(reason)
        guard = LeaseGuard(os.getppid())
        guard.request_stop("expired_before_constructor")
        guard.attach(Runtime())
        self.assertEqual(reasons, ["expired_before_constructor"])

    def test_target_range_binding_freshness_and_feedback_unknown(self):
        snapshot = FollowSnapshot("config", True)
        now = time.monotonic()
        target = dict(track_id=7, detected=True, status="TRACKED", captured_at=now, binding=2, session_id=4)
        sample = dict(distance_m=1.8, sampled_at=now, binding=2, session_id=4)
        snapshot.note("target", {"target": target})
        snapshot.note("range", {"range": sample})
        self.assertAlmostEqual(snapshot.status()["range"]["distanceM"], 1.8)
        snapshot.note("range", {"range": dict(sample, binding=3)})
        self.assertIsNone(snapshot.status()["range"])
        snapshot.note("range", {"range": dict(sample, sampled_at=now - 2)})
        self.assertIsNone(snapshot.status()["range"])
        snapshot.note("target", {"target": dict(target, captured_at=now - 2)})
        self.assertIsNone(snapshot.status()["target"])
        snapshot.note("feedback", dict(left_rpm=0., right_rpm=0., timestamp=now - 2))
        self.assertIsNone(snapshot.status()["feedback"])
        self.assertIsNone(snapshot.status()["stop"]["physicalStopped"])

    def test_only_actual_terminal_stop_write_proves_communication_ack(self):
        snapshot = FollowSnapshot("config", True)
        intent = dict(stop=True, reason="shutdown")
        snapshot.note("write", dict(intent=intent, simulated=True,
                      receipt=dict(dispatched=True, acknowledged=True)))
        self.assertIsNone(snapshot.status()["stop"]["acknowledged"])
        snapshot.note("write", dict(intent=intent, simulated=False,
                      receipt=dict(dispatched=False, acknowledged=False)))
        self.assertFalse(snapshot.status()["stop"]["acknowledged"])
        snapshot.note("write", dict(intent=intent, simulated=False,
                      receipt=dict(dispatched=True, acknowledged=True)))
        self.assertTrue(snapshot.status()["stop"]["acknowledged"])
        self.assertIsNone(snapshot.status()["stop"]["physicalStopped"])

    def test_cleanup_failure_is_a_fault(self):
        snapshot = FollowSnapshot("config", True)
        snapshot.note("shutdown", dict(cleanup_errors=["stop:timeout"], reason="requested"))
        self.assertEqual(snapshot.status()["runState"], "FAULT")
        self.assertIn("stop:timeout", snapshot.status()["fault"])

    def test_one_shot_failure_is_retained_when_status_reader_holds_lock(self):
        snapshot = FollowSnapshot("config", True)
        with snapshot.lock:
            snapshot.note("shutdown", dict(cleanup_errors=["stop:timeout"], reason="requested"))
        self.assertEqual(snapshot.status()["runState"], "FAULT")
        self.assertIn("stop:timeout", snapshot.status()["fault"])

    def test_real_adapter_hooks_reject_device_open_and_motion_after_expiration(self):
        calls = []
        class Input:
            def open(self):
                calls.append("camera")
        class Motor:
            def open(self):
                calls.append("motor")
            def write(self, intent):
                calls.append("stop" if intent.stop else "motion")
        class Shadow:
            def open(self):
                pass
            def write(self, intent):
                pass
        class Runtime:
            def __init__(self):
                pass
            def request_stop(self, reason):
                calls.append("requested:" + reason)
            def run(self):
                calls.append("run")
        class Events:
            def __init__(self, path):
                pass
            def emit(self, event, **values):
                pass
        package = ModuleType("continuous_follow")
        modules = {"continuous_follow": package}
        for name, attrs in (("hardware", dict(BoardInput=Input, BoardMotor=Motor, ShadowMotor=Shadow)),
                            ("runtime", dict(ContinuousRuntime=Runtime)),
                            ("telemetry", dict(EventLog=Events))):
            module = ModuleType("continuous_follow." + name)
            module.__dict__.update(attrs)
            setattr(package, name, module)
            modules[module.__name__] = module
        guard = LeaseGuard(os.getppid())
        with patch.dict(sys.modules, modules), patch("app_follow_worker.signal.signal"):
            install_runtime_hooks(guard, FollowSnapshot("config", True))
            runtime = Runtime()
            guard.request_stop("expired")
            for action in (Input().open, Motor().open, runtime.run,
                           lambda: Motor().write(SimpleNamespace(stop=False))):
                with self.assertRaises(LeaseExpired):
                    action()
            Motor().write(SimpleNamespace(stop=True))
        self.assertNotIn("camera", calls)
        self.assertNotIn("motor", calls)
        self.assertNotIn("motion", calls)
        self.assertIn("stop", calls)


class ProcessTests(unittest.TestCase):
    def start(self, *arguments):
        p = subprocess.Popen([sys.executable, "-u", str(SCRIPT), "--dry-run", *arguments],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def cleanup():
            if p.poll() is None:
                with contextlib.suppress(Exception):
                    p.stdin.close()
                try:
                    p.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    p.kill()  # Tests only: this process can never import/open real hardware.
                    p.wait()
            for stream in (p.stdin, p.stdout, p.stderr):
                with contextlib.suppress(Exception):
                    stream.close()
        self.addCleanup(cleanup)
        ready = json.loads(p.stdout.readline())
        self.assertTrue(ready["dryRun"])
        return p, ready

    def test_selected_options_are_echoed_before_start(self):
        p, ready = self.start("--camera", "astra-pro", "--target-distance", "1.5",
            "--gestures", "--gesture-resume", "Thumb_Up", "--avoidance", "on",
            "--reverse-follow", "--reverse-distance", "1.4")
        self.assertEqual(ready["options"], dict(targetDistanceM=1.5, gesturesEnabled=True,
            resumeGesture="Thumb_Up", avoidance="on", reverseEnabled=True,
            reverseDistanceM=1.4))
        self.finish(p, ready, 1)

    def exchange(self, p, reply, command, seq):
        p.stdin.write((json.dumps(dict(seq=seq, token=reply["token"], runId=reply["runId"], cmd=command)) + "\n").encode())
        p.stdin.flush()
        return json.loads(p.stdout.readline())

    def finish(self, p, ack, seq=2):
        ack = self.exchange(p, ack, "stop", seq)
        while not ack["terminal"]:
            seq += 1
            time.sleep(.05)
            ack = self.exchange(p, ack, "heartbeat", seq)
        self.assertTrue(ack["status"]["processExited"])
        self.assertIsNone(ack["status"]["stop"]["physicalStopped"])
        self.assertEqual(p.wait(timeout=4), 0, p.stderr.read())
        return ack

    def test_dry_run_start_heartbeat_and_normal_stop(self):
        p, ready = self.start()
        ack = self.exchange(p, ready, "start", 1)
        self.assertEqual(ack["status"]["runState"], "STARTING")
        seq = 1
        deadline = time.monotonic() + 3
        while ack["status"]["runState"] == "STARTING" and time.monotonic() < deadline:
            time.sleep(.1)
            seq += 1
            ack = self.exchange(p, ack, "heartbeat", seq)
        self.assertEqual(ack["status"]["runState"], "RUNNING")
        self.assertFalse(ack["status"]["execute"])
        self.finish(p, ack, seq + 1)

    def test_stopping_during_startup_never_resumes(self):
        p, ready = self.start()
        ack = self.exchange(p, ready, "start", 1)
        final = self.finish(p, ack)
        self.assertEqual(final["status"]["runState"], "STOPPED")

    def test_eof_after_start_stops_child_and_bridge(self):
        p, ready = self.start()
        self.exchange(p, ready, "start", 1)
        p.stdin.close()
        self.assertNotEqual(p.wait(timeout=5), 0)

    def test_missing_heartbeat_expires_session_and_child(self):
        p, ready = self.start()
        self.exchange(p, ready, "start", 1)
        error = json.loads(p.stdout.readline())
        self.assertIn("watchdog", error["error"])
        self.assertNotEqual(p.wait(timeout=5), 0)

    def test_wrong_run_id_stops_session(self):
        p, ready = self.start()
        ack = self.exchange(p, ready, "start", 1)
        error = self.exchange(p, dict(ack, runId="0" * 32), "heartbeat", 2)
        self.assertEqual(error["type"], "error")
        self.assertNotEqual(p.wait(timeout=5), 0)

    def test_worker_itself_expires_when_parent_remains_alive_but_stops_pulsing(self):
        p = subprocess.Popen([sys.executable, str(WORKER), "--dry-run", "--parent-pid", str(os.getpid())],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        output = []
        reader = threading.Thread(target=lambda: output.append(p.stdout.read()), daemon=True)
        reader.start()
        try:
            self.assertEqual(p.wait(timeout=4), 0)
            reader.join(1)
            self.assertIn(b"app_heartbeat_timeout", b"".join(output))
        finally:
            for stream in (p.stdin, p.stdout, p.stderr):
                stream.close()

    @unittest.skipUnless(os.name == "posix", "flock ownership is a Linux board capability")
    def test_inspection_respects_locks_and_does_not_release_other_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            motor = Path(directory) / "motor.lock"
            follow = Path(directory) / "follow.lock"
            inspect = lambda: bridge.inspect_availability(motor, follow, lambda: None)
            self.assertTrue(inspect()["controlAvailable"])
            with bridge.motor_ownership(motor):
                self.assertFalse(inspect()["controlAvailable"])
                self.assertFalse(inspect()["controlAvailable"])
            with bridge.motor_ownership(follow):
                self.assertFalse(inspect()["controlAvailable"])
            self.assertTrue(inspect()["controlAvailable"])

    def test_stop_timeout_is_fault_but_not_exited(self):
        class Alive:
            def poll(self):
                return None
        process = bridge.FollowProcess("config", False, True)
        process.process = Alive()
        process.stop_at = time.monotonic() - bridge.STOP_TIMEOUT_SECONDS - 1
        status = process.status()
        self.assertEqual(status["runState"], "FAULT")
        self.assertFalse(status["processExited"])
        self.assertTrue(status["stop"]["requested"])

    def test_missing_first_worker_snapshot_faults_instead_of_starting_forever(self):
        class Alive:
            def poll(self):
                return None
        process = bridge.FollowProcess("config", False, True)
        process.process = Alive()
        process.started_at = time.monotonic() - 6
        with patch.object(process, "request_stop") as stop:
            status = process.status()
        self.assertEqual(status["runState"], "FAULT")
        self.assertFalse(status["processExited"])
        stop.assert_called_once()

    @unittest.skipUnless(os.name == "posix", "inherited flock and process death checked on Linux")
    def test_worker_retains_lock_after_parent_death_and_then_releases_on_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            lock_path = Path(directory) / "motor.lock"
            code = '''
import json, os, subprocess, sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from app_follow_bridge import motor_ownership
with motor_ownership(Path(sys.argv[2])) as lock:
    child = subprocess.Popen([sys.executable, '-u', sys.argv[3], '--dry-run',
        '--parent-pid', str(os.getpid())], stdin=subprocess.PIPE,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, pass_fds=(lock,))
    print(child.pid, flush=True)
    sys.stdin.buffer.read(1)
'''
            parent = subprocess.Popen([sys.executable, "-u", "-c", code, str(SCRIPT.parent),
                                       str(lock_path), str(WORKER)], stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                worker_pid = int(parent.stdout.readline())
                # Stop scheduling the worker briefly to test inherited ownership separately
                # from its fast parent-death cleanup, then allow it to exit normally.
                os.kill(worker_pid, signal.SIGSTOP)
                parent.kill()  # Both parent and child are explicitly dry-run in this test.
                parent.wait(timeout=2)
                with self.assertRaises(bridge.SessionError):
                    with bridge.motor_ownership(lock_path):
                        pass
                os.kill(worker_pid, signal.SIGCONT)
                deadline = time.monotonic() + 4
                while time.monotonic() < deadline:
                    try:
                        with bridge.motor_ownership(lock_path):
                            break
                    except bridge.SessionError:
                        time.sleep(.05)
                else:
                    self.fail("Dry-run worker kept motor lock after parent death")
            finally:
                if parent.poll() is None:
                    parent.kill()
                    parent.wait()
                for stream in (parent.stdin, parent.stdout, parent.stderr):
                    stream.close()


if __name__ == "__main__":
    unittest.main()
