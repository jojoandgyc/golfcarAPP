"""Offline lease/gesture adapter tests. Fake runtime only; never opens hardware."""
import contextlib
import io
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
from types import ModuleType, SimpleNamespace
from unittest.mock import patch

import app_follow_worker as worker


class Clock:
    now = 10.0

    def __call__(self):
        return self.now


def gate_type():
    """Small qualified-gate contract, with no classifier/camera imports.

    Its separate owner, age, duplicate, geometry and hold checks let these tests
    prove the wrapper consumes actual gate events, never a label/diagnostic.
    Every test gets a new class because the production adapter patches a class.
    """
    class QualifiedGate:
        def __init__(self):
            self.config = SimpleNamespace(enabled=True, hold_sec=.4, min_samples=3)
            self.state = "WAITING"
            self.reset_observation()
            self.all_calls = 0

        def reset_observation(self):
            self.samples = []
            self.owner = None
            self.last_id = -1
            self.last_at = float("-inf")

        def observe(self, label, captured_at, capture_id, owner, now, score=1., *,
                    hands=None, inconclusive=False):
            self.all_calls += 1
            if (not isinstance(owner, tuple) or len(owner) != 3
                    or any(type(v) is not int or v <= 0 for v in owner)
                    or type(captured_at) not in (int, float)
                    or not math.isfinite(captured_at) or not 0 <= now-captured_at <= .5
                    or hands == [] or inconclusive or score < .6):
                self.reset_observation()
                return None
            if self.owner != owner or captured_at-self.last_at > .301:
                self.reset_observation()
                self.owner = owner
            if capture_id <= self.last_id or captured_at <= self.last_at:
                return None
            self.last_id, self.last_at = capture_id, captured_at
            if label not in ("Open_Palm", "Thumb_Up"):
                self.samples = []
                return None
            if self.samples and self.samples[-1][0] != label:
                self.samples = []
            self.samples.append((label, captured_at))
            if len(self.samples) < 3 or captured_at-self.samples[0][1] < .399999:
                return None
            state = "PAUSED" if label == "Open_Palm" else "RUNNING"
            if state == self.state:
                return None
            self.state = state
            self.reset_observation()
            return dict(action="pause" if state == "PAUSED" else "resume",
                        gesture=label, captured_at=captured_at, capture_id=capture_id,
                        owner=owner, state=state)
    return QualifiedGate


class OfflineGestureTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.time_patch = patch.object(worker.time, "monotonic", self.clock)
        self.time_patch.start()
        self.addCleanup(self.time_patch.stop)
        self.guard = worker.LeaseGuard(os.getppid(), allow_offline=True)
        self.Gate = gate_type()
        self.calls = []
        self.runtime = SimpleNamespace(gesture_gate=self.Gate(),
            request_stop=lambda reason: self.calls.append(reason))
        worker.install_offline_gesture_hook(self.guard, self.Gate)
        self.guard.attach(self.runtime)
        self.frame_id = 0

    def detach(self):
        self.guard.accept_lease(b"D")

    def frame(self, stamp, label="Open_Palm", deliver=True, **changes):
        self.clock.now = stamp
        self.frame_id += 1
        arguments = dict(label=label, captured_at=stamp, capture_id=self.frame_id,
                         owner=(1, 2, 3), now=stamp, hands=None)
        arguments.update(changes)
        event = self.runtime.gesture_gate.observe(**arguments)
        if deliver and event is not None:
            self.guard.observe_gesture_command("gesture_command", event)
        return event

    def hold(self, start=10.1, **changes):
        return [self.frame(start+delta, **changes) for delta in (0, .2, .4)]

    def test_offline_fresh_full_palm_stops_with_source(self):
        self.detach()
        events = self.hold()
        self.assertIsNone(events[0])
        self.assertIsNone(events[1])
        self.assertEqual(events[2]["source"], "offline_stop_gate")
        self.assertEqual(self.calls, ["app_offline_stop_gesture"])

    def test_online_original_pause_is_unchanged_and_does_not_exit(self):
        events = self.hold()
        self.assertEqual(events[-1]["action"], "pause")
        self.assertNotIn("source", events[-1])
        self.assertEqual(self.runtime.gesture_gate.state, "PAUSED")
        self.assertFalse(self.guard.stopped.is_set())
        self.assertIsNone(self.guard._offline_observer)

    def test_already_paused_requires_new_full_offline_hold(self):
        self.hold(9.5)
        self.assertEqual(self.runtime.gesture_gate.state, "PAUSED")
        self.clock.now = 10
        self.detach()
        events = self.hold()
        self.assertEqual(events[-1]["source"], "offline_stop_gate")
        self.assertEqual(self.runtime.gesture_gate.state, "PAUSED")
        self.assertEqual(self.guard.reason, "app_offline_stop_gesture")

    def test_online_history_does_not_shortcut_offline_hold(self):
        self.frame(9.7)
        self.frame(9.9)
        self.clock.now = 10
        self.detach()
        first = self.frame(10.1)
        self.assertEqual(first["action"], "pause")  # Online gate may finish its old hold.
        self.assertNotIn("source", first)
        self.assertFalse(self.guard.stopped.is_set())
        self.frame(10.3)
        self.frame(10.5)
        self.assertTrue(self.guard.stopped.is_set())

    def test_old_equal_future_and_duplicate_frames_cannot_authorize(self):
        self.detach()
        for stamp in (9.6, 9.8, 10.):
            self.frame(10.1, captured_at=stamp)
        self.assertFalse(self.guard.stopped.is_set())
        self.frame(10.2, captured_at=11.)
        for _ in range(4):
            self.frame(10.3, captured_at=10.3, capture_id=200)
        self.assertFalse(self.guard.stopped.is_set())
        self.assertIsNone(self.guard.reason)

    def test_reconnect_discards_partial_evidence_and_each_detach_has_new_epoch(self):
        self.detach()
        self.frame(10.1)
        self.frame(10.3)
        first_epoch = self.guard.network_state[0]
        self.guard.accept_lease(b"C")
        self.assertIsNone(self.guard._offline_observer)
        self.clock.now = 10.4
        self.detach()
        self.assertGreater(self.guard.network_state[0], first_epoch)
        self.frame(10.5)
        self.assertFalse(self.guard.stopped.is_set())
        self.frame(10.7)
        self.frame(10.9)
        self.assertEqual(self.guard.reason, "app_offline_stop_gesture")

    def test_repeated_detach_does_not_reset_or_extend_epoch(self):
        self.detach()
        state = self.guard.network_state
        self.frame(10.1)
        self.guard.accept_lease(b"D")
        self.assertEqual(self.guard.network_state, state)
        self.frame(10.3)
        self.frame(10.5)
        self.assertTrue(self.guard.stopped.is_set())

    def test_reconnect_between_observe_and_emit_rejects_delayed_event(self):
        self.detach()
        event = self.hold(deliver=False)[-1]
        self.guard.accept_lease(b"C")
        self.guard.observe_gesture_command("gesture_command", event)
        self.assertFalse(self.guard.stopped.is_set())
        self.clock.now = 10.6
        self.detach()
        self.guard.observe_gesture_command("gesture_command", event)
        self.assertFalse(self.guard.stopped.is_set())

    def test_c_then_d_during_shadow_observe_cannot_authorize_old_epoch(self):
        self.detach()
        self.frame(10.1)
        self.frame(10.3)
        shadow = self.guard._offline_observer[2]
        # Invoked inside the original observe via its reset when pause qualifies.
        reset = shadow.reset_observation
        def reconnect():
            reset()
            self.guard.accept_lease(b"C")
            self.guard.accept_lease(b"D")
        shadow.reset_observation = reconnect
        event = self.frame(10.5)
        self.assertNotEqual((event or {}).get("source"), "offline_stop_gate")
        self.assertFalse(self.guard.stopped.is_set())

    def test_external_eligibility_reset_discards_partial_shadow_hold(self):
        self.detach()
        self.frame(10.1)
        self.frame(10.3)
        self.runtime.gesture_gate.reset_observation()
        self.assertIsNone(self.guard._offline_observer)
        self.frame(10.5)
        self.frame(10.7)
        self.assertFalse(self.guard.stopped.is_set())
        self.frame(10.9)
        self.assertTrue(self.guard.stopped.is_set())

    def test_owner_change_geometry_inconclusive_score_and_age_still_qualify(self):
        for rejection in ({"owner": (2, 3, 4)}, {"hands": []},
                          {"inconclusive": True}, {"score": .1},
                          {"captured_at": 8.}):
            with self.subTest(rejection=rejection):
                self.guard.accept_lease(b"C")
                self.clock.now = 10
                self.detach()
                self.frame(10.1)
                self.frame(10.3, **rejection)
                self.frame(10.5)
                self.assertFalse(self.guard.stopped.is_set())

    def test_non_palm_commands_diagnostics_and_plain_stop_never_exit(self):
        self.detach()
        self.hold(label="Thumb_Up")
        self.assertFalse(self.guard.stopped.is_set())
        event = dict(action="pause", gesture="Open_Palm", captured_at=10.5)
        self.guard.observe_gesture_command("gesture_command", event)
        for name in ("gesture_decision", "write", "target", "command"):
            self.guard.observe_gesture_command(name, dict(event, source="offline_stop_gate",
                app_offline_epoch=self.guard.network_state[0]))
        self.assertFalse(self.guard.stopped.is_set())

    def test_explicit_stop_first_reason_survives_late_gesture_cleanup(self):
        self.detach()
        event = self.hold(deliver=False)[-1]
        self.guard.accept_lease(b"S")
        self.guard.observe_gesture_command("gesture_command", event)
        self.guard.request_stop("app_lease_closed")
        self.assertEqual(self.guard.reason, "app_stop_requested")
        self.assertEqual(set(self.calls), {"app_stop_requested"})

    def test_busy_epoch_commit_never_blocks_and_allows_a_new_full_hold(self):
        self.detach()
        event = self.hold(deliver=False)[-1]
        with self.guard._state_lock:
            self.guard.observe_gesture_command("gesture_command", event)
        self.assertFalse(self.guard.stopped.is_set())
        self.assertIsNone(self.guard._offline_observer)
        self.hold(10.7)
        self.assertTrue(self.guard.stopped.is_set())

    def test_other_gate_instances_cannot_create_offline_events(self):
        self.detach()
        other = self.Gate()
        events = [other.observe("Open_Palm", stamp, i, (1, 2, 3), stamp)
                  for i, stamp in enumerate((10.1, 10.3, 10.5))]
        self.assertNotIn("source", events[-1])
        self.assertIsNone(self.guard._offline_observer)

    def test_attach_does_not_copy_live_samples_and_never_changes_original_state(self):
        self.assertEqual(self.runtime.gesture_gate.state, "WAITING")
        self.assertEqual(self.guard._offline_gate_template.state, "RUNNING")
        self.runtime.gesture_gate.config.enabled = False
        with self.assertRaisesRegex(RuntimeError, "enabled gesture"):
            worker.LeaseGuard(os.getppid(), allow_offline=True).attach(self.runtime)


class OfflineLeaseTests(unittest.TestCase):
    def test_offline_never_disables_parent_local_lease_signal_or_startup_guards(self):
        for reason in ("app_parent_exited", "app_heartbeat_timeout", "app_signal_stop", "app_startup_timeout"):
            with self.subTest(reason=reason):
                guard = worker.LeaseGuard(os.getppid(), allow_offline=True)
                guard.accept_lease(b"D")
                if reason == "app_parent_exited":
                    guard.parent_pid += 1
                elif reason == "app_heartbeat_timeout":
                    guard.last_pulse -= 3
                elif reason == "app_signal_stop":
                    guard.pending_signal = reason
                else:
                    guard.startup_check = lambda: True
                with self.assertRaisesRegex(worker.LeaseExpired, reason):
                    guard.ensure_live()
                self.assertEqual(guard.reason, reason)

    def test_d_and_c_do_not_refresh_local_watchdog(self):
        clock = Clock()
        with patch.object(worker.time, "monotonic", clock):
            guard = worker.LeaseGuard(os.getppid(), allow_offline=True)
            clock.now += 1
            guard.accept_lease(b"D")
            clock.now += .5
            guard.accept_lease(b"C")
            self.assertEqual(guard.last_pulse, 10.)
            clock.now += .5
            with self.assertRaisesRegex(worker.LeaseExpired, "heartbeat"):
                guard.accept_lease(b"H")
            self.assertEqual(guard.last_pulse, 10.)

    def test_offline_local_heartbeat_can_continue_for_longer_than_network_timeout(self):
        clock = Clock()
        with patch.object(worker.time, "monotonic", clock):
            guard = worker.LeaseGuard(os.getppid(), allow_offline=True)
            guard.accept_lease(b"D")
            for _ in range(80):
                clock.now += .5
                guard.accept_lease(b"H")
                guard.ensure_live()
            self.assertTrue(guard.network_detached)
            self.assertFalse(guard.stopped.is_set())

    def test_eof_and_protocol_error_remain_stop_even_in_offline_mode(self):
        for data, reason in ((b"D\n", "app_lease_closed"),
                             (b"D\ninvalid\n", "app_lease_invalid"),
                             (b"D\n123456789", "app_lease_invalid")):
            with self.subTest(data=data):
                guard = worker.LeaseGuard(os.getppid(), allow_offline=True)
                guard.start(io.BytesIO(data))
                self.assertTrue(guard.stopped.wait(1))
                self.assertEqual(guard.reason, reason)

    def test_legacy_worker_rejects_new_lease_controls(self):
        for command in (b"D", b"C"):
            guard = worker.LeaseGuard(os.getppid())
            guard.accept_lease(command)
            self.assertEqual(guard.reason, "app_lease_invalid")

    def test_no_gestures_rejected_before_config_or_runtime_access(self):
        arguments = ["worker", "--dry-run", "--offline-until-gesture", "--no-gestures",
                     "--parent-pid", str(os.getppid())]
        with patch.object(sys, "argv", arguments), contextlib.redirect_stderr(io.StringIO()) as error, \
                patch.object(worker, "prepare_environment") as setup:
            with self.assertRaises(SystemExit) as raised:
                worker.main()
        self.assertEqual(raised.exception.code, 2)
        self.assertIn("requires --gestures", error.getvalue())
        setup.assert_not_called()

    def test_subprocess_dryrun_accepts_detach_reconnect_and_explicit_startup_cancel(self):
        process = subprocess.Popen([sys.executable, "-B", "-u", str(Path(worker.__file__)),
            "--dry-run", "--gestures", "--offline-until-gesture", "--parent-pid", str(os.getpid())],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            output, error = process.communicate(b"D\nH\nC\nD\nS\n", timeout=5)
            self.assertEqual(process.returncode, 0, error.decode())
            messages = [json.loads(line) for line in output.splitlines()]
            self.assertTrue(messages)
            # The owner marks STOPPED only after waitpid proves child exit.
            self.assertEqual(messages[-1]["runState"], "STOPPING")
            self.assertNotEqual(messages[-1].get("runState"), "FAULT")
            self.assertEqual(messages[-1]["reason"], "app_stop_requested")
        finally:
            if process.poll() is None:
                process.kill()  # This command is explicitly --dry-run; no hardware path.
                process.wait(timeout=3)


class OfflineRuntimeHookTests(unittest.TestCase):
    def test_cancel_during_startup_is_not_reclassified_as_runtime_failure(self):
        calls = []
        Gate = gate_type()
        class Input:
            def open(self):
                calls.append("device-open")
        class Motor(Input):
            def write(self, intent):
                calls.append("write")
        class Shadow(Motor):
            pass
        class Runtime:
            def __init__(self):
                self.gesture_gate = Gate()
            def run(self):
                pass
            def request_stop(self, reason):
                calls.append(reason)
        class Events:
            def __init__(self, path):
                pass
            def emit(self, event, **values):
                calls.append((event, values))
        package = ModuleType("continuous_follow")
        modules = {package.__name__: package}
        for name, attrs in (("hardware", dict(BoardInput=Input, BoardMotor=Motor, ShadowMotor=Shadow)),
                            ("runtime", dict(ContinuousRuntime=Runtime)),
                            ("telemetry", dict(EventLog=Events)), ("gesture", dict(GestureGate=Gate))):
            module = ModuleType("continuous_follow." + name)
            module.__dict__.update(attrs)
            setattr(package, name, module)
            modules[module.__name__] = module
        guard = worker.LeaseGuard(os.getppid(), allow_offline=True)
        snapshot = worker.FollowSnapshot("config", False)
        with patch.dict(sys.modules, modules):
            worker.install_runtime_hooks(guard, snapshot)
            Runtime()
            events = package.telemetry.EventLog("/fake/events.jsonl")
            guard.accept_lease(b"S")
            try:
                guard.ensure_live()
            except worker.LeaseExpired:
                events.emit("runtime_failure", stage="camera")
            with self.assertRaises(worker.LeaseExpired):
                Input().open()
        self.assertNotIn("device-open", calls)
        self.assertNotIn("write", calls)
        self.assertIsNone(snapshot.fault)
        self.assertIn(("app_cancelled", dict(stage="camera", reason="app_stop_requested")), calls)


if __name__ == "__main__":
    unittest.main()
