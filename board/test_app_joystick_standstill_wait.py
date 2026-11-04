"""Normal-stop recovery tests: virtual clocks and in-memory serial, never hardware."""
from contextlib import ExitStack
import copy
import json
import threading
import unittest
from unittest.mock import patch

import app_joystick_bridge as bridge
from test_app_joystick_bridge import FakeClient, FakeClock, parking_request


class Diagnostic:
    def __init__(self):
        self.events = []

    def event(self, event, **fields):
        self.events.append(dict(event=event, at=bridge.time.monotonic(), **fields))

    def failure(self, event, error, **fields):
        self.event(event, error=str(error), **fields)

    def close(self, **_kwargs):
        pass


class StandstillWaitTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(bridge.time, "monotonic", self.clock.monotonic))
        self.stack.enter_context(patch.object(bridge.time, "sleep", self.clock.sleep))
        self.client = FakeClient()
        self.client.registers.update(left_parking_current=2.0, right_parking_current=2.0)
        self.original = copy.deepcopy(self.client.registers)
        self.motors = bridge.Motors(self.client)
        self.diagnostic = Diagnostic()
        self.motors.diagnostic = self.diagnostic
        self.session = bridge.ProtocolSession(self.motors, port="fake", dry_run=False)

    def request(self, command="stop", left=0, right=0):
        return self.session.accept(dict(seq=self.session.next_seq, token=self.session.token,
                                        cmd=command, left=left, right=right))[0]

    def begin_normal_stop(self):
        self.session.ready()
        # Select the historical normal-stop policy directly for this safety fixture.
        # There is no supported wire-level parking mode switch.
        self.motors.set_parking(True)
        self.request("drive", 20, -20)
        self.client.feedback = (53, -34, 0, 0)
        self.request()
        return self.clock.monotonic()

    def enter_wait(self):
        stopped_at = self.begin_normal_stop()
        self.clock.now = stopped_at + 1.1
        self.client.feedback = (-2, 0, 0, 0)
        self.motors.poll_safety()
        self.clock.now = stopped_at + 1.51
        self.client.feedback = (7, -3, 0, 0)
        self.motors.poll_safety()
        self.assertTrue(self.motors.standstill_pending)

    def assert_parameters_preserved(self):
        self.assertEqual(self.client.registers, self.original)
        self.assertFalse(any(call[0] == "write" for call in self.client.calls))

    def test_observed_53_minus34_then_minus2_zero_then_7_minus3_keeps_session_and_emergency_stop(self):
        self.enter_wait()
        self.assertIsNone(self.motors.fault_reason)
        self.assertFalse(self.motors.parking_enabled)
        self.assertTrue(self.motors.emergency_hold)
        self.assertFalse(self.motors.stopped_confirmed)
        self.assertEqual(self.motors.feedback, (7, -3, 0, 0))
        self.assertEqual(self.client.calls[-4:], [("left", 0), ("right", 0),
                                                ("left", "stop", 1), ("right", "stop", 1)])
        ack = self.request()
        self.assertTrue(ack["standstillPending"])
        self.assertTrue(ack["feedbackRecheck"])
        self.assertTrue(ack["recoveryRequired"])
        self.assertTrue(ack["targetStopped"])
        self.assertFalse(ack["stopped"])
        self.assertFalse(ack["parkingEnabled"])
        self.assertEqual((ack["parkingLeftCurrentAmps"], ack["parkingRightCurrentAmps"]), (2.0, 2.0))
        self.assertEqual(len([e for e in self.diagnostic.events
                              if e["event"] == "drive.standstill_wait_started"]), 1)
        self.assert_parameters_preserved()

    def test_neutral_heartbeats_preserve_emergency_and_200ms_stop_refresh(self):
        self.enter_wait()
        self.client.calls.clear()
        for _ in range(40):
            self.clock.sleep(.05)
            ack = self.request()
            self.assertTrue(ack["standstillPending"])
            self.assertFalse(ack["stopped"])
            self.assertTrue(self.motors.emergency_hold)
        left_stops = [call for call in self.client.calls if call[:2] == ("left", "stop")]
        self.assertGreaterEqual(len(left_stops), 5)
        self.assertLessEqual(len(left_stops), 11, "neutral must not force four serial writes on every heartbeat")
        self.assertTrue(all(call[2] == 1 for call in left_stops))
        self.assertEqual(len([e for e in self.diagnostic.events
                              if e["event"] == "drive.standstill_wait_started"]), 1)
        self.assert_parameters_preserved()

    def test_nonzero_request_that_first_observes_timeout_is_discarded_and_marks_its_ack(self):
        started = self.begin_normal_stop()
        self.clock.now = started + 1.51
        self.client.feedback = (7, -3, 0, 0)
        self.client.calls.clear()
        self.assertFalse(self.motors.standstill_pending)
        ack = self.request("drive", 40, -40)
        for field in ("standstillPending", "feedbackRecheck", "recoveryRequired", "targetStopped"):
            self.assertTrue(ack[field], field)
        self.assertEqual((ack["left"], ack["right"]), (0, 0))
        self.assertFalse(ack["stopped"])
        self.assertFalse(ack["parkingEnabled"])
        self.assertNotIn(("left", 40), self.client.calls)
        self.assertNotIn(("right", -40), self.client.calls)
        self.assert_parameters_preserved()

    def test_final_sample_first_observing_timeout_marks_neutral_ack_and_parking_off(self):
        started = self.begin_normal_stop()
        self.clock.now = started + 1.51
        self.client.feedback = (7, -3, 0, 0)
        # Model a recent stop write: stop() itself has no refresh/read to do,
        # so only accept()'s final feedback sample discovers the elapsed wait.
        self.motors._write_stop(emergency=False)
        self.assertFalse(self.motors.standstill_pending)
        with patch.object(self.motors, "_sample_feedback", wraps=self.motors._sample_feedback) as sample:
            ack = self.request()
        sample.assert_called_once_with(force=True)
        for field in ("standstillPending", "feedbackRecheck", "recoveryRequired", "targetStopped"):
            self.assertTrue(ack[field], field)
        self.assertFalse(ack["stopped"])
        self.assertFalse(ack["parkingEnabled"])
        self.assertTrue(self.motors.emergency_hold)
        self.assert_parameters_preserved()

    def test_two_fresh_zeros_then_neutral_release_without_replaying_held_motion(self):
        self.enter_wait()
        self.client.calls.clear()
        self.client.feedback = (0, 0, 0, 0)
        self.clock.sleep(.11)
        first = self.request("drive", 40, -40)
        self.assertFalse(first["stopped"])
        self.assertTrue(first["standstillPending"])
        self.clock.sleep(.11)
        confirmed = self.request("drive", 40, -40)
        self.assertTrue(confirmed["stopped"])
        self.assertTrue(confirmed["standstillPending"])
        self.assertIsNone(self.motors.standstill_wait_at)
        self.assertTrue(self.motors.standstill_pending)
        self.assertTrue(self.motors.emergency_hold)
        neutral = self.request("drive", 0, 0)
        self.assertTrue(neutral["standstillPending"])
        self.assertTrue(neutral["recoveryRequired"])
        self.assertFalse(self.motors.standstill_pending)
        self.assertFalse(self.session.recovery_active)
        self.assertNotIn(("left", 40), self.client.calls)
        self.assertNotIn(("right", -40), self.client.calls)
        fresh = self.request("drive", 10, -10)
        self.assertFalse(fresh["standstillPending"])
        self.assertEqual((fresh["left"], fresh["right"]), (10, -10))
        for event in ("drive.standstill_wait_started", "drive.standstill_wait_zero_confirmed",
                      "drive.standstill_wait_released"):
            self.assertEqual(sum(e["event"] == event for e in self.diagnostic.events), 1)
        self.assert_parameters_preserved()

    def test_duplicate_zero_reads_and_single_zero_do_not_release_wait(self):
        self.enter_wait()
        self.client.feedback = (0, 0, 0, 0)
        for _ in range(5):
            ack = self.request()
            self.assertFalse(ack["stopped"])
            self.assertTrue(self.motors.standstill_pending)
        self.clock.sleep(.05)
        self.client.feedback = (-2, 1, 0, 0)
        self.request()
        self.clock.sleep(.11)
        self.client.feedback = (0, 0, 0, 0)
        ack = self.request()
        self.assertFalse(ack["stopped"])
        self.assertEqual(ack["feedbackSamples"], 1)
        self.assertTrue(self.motors.standstill_pending)

    def test_real_two_zero_confirmation_restores_larger_unexpected_motion_protection(self):
        self.enter_wait()
        self.client.feedback = (0, 0, 0, 0)
        for _ in range(2):
            self.clock.sleep(.11)
            self.request("drive", 40, -40)
        self.assertTrue(self.motors.stopped_confirmed)
        self.assertTrue(self.motors.standstill_pending)
        self.client.feedback = (7, -3, 0, 0)
        self.clock.sleep(.11)
        with self.assertRaisesRegex(bridge.SessionError, "Unexpected motion after standstill"):
            self.motors.poll_safety()
        self.assertIsNotNone(self.motors.fault_reason)

    def test_retired_mode_command_cannot_cancel_emergency_or_pending_recovery(self):
        self.enter_wait()
        self.client.feedback = (0, 0, 0, 0)
        for _ in range(2):
            self.clock.sleep(.11)
            self.request("drive", 40, -40)
        self.client.calls.clear()
        with self.assertRaisesRegex(bridge.SessionError, "Legacy parking mode is unsupported"):
            self.session.accept(parking_request(self.session, True))
        self.assertFalse(self.motors.parking_enabled)
        self.assertTrue(self.motors.emergency_hold)
        self.assertNotIn(("left", "stop", 0), self.client.calls)
        self.assertNotIn(("right", "stop", 0), self.client.calls)
        self.assert_parameters_preserved()

    def test_invalid_neutral_token_cannot_unlock_or_advance_sequence(self):
        self.enter_wait()
        seq = self.session.next_seq
        with self.assertRaisesRegex(bridge.SessionError, "token"):
            self.session.accept(dict(seq=seq, token="0" * 32, cmd="stop", left=0, right=0))
        self.assertEqual(self.session.next_seq, seq)
        self.assertTrue(self.motors.standstill_pending)

    def test_entering_wait_keeps_serial_stop_failures_fatal_and_attempts_both_sides(self):
        started = self.begin_normal_stop()
        self.client.calls.clear()
        self.client.fail_left = True
        self.clock.now = started + 1.51
        with self.assertRaises(bridge.SessionError):
            self.motors.poll_safety()
        self.assertIsNotNone(self.motors.fault_reason)
        self.assertIn(("left", "stop", 1), self.client.calls)
        self.assertIn(("right", "stop", 1), self.client.calls)
        self.assert_parameters_preserved()

    def test_feedback_failure_while_waiting_remains_fatal(self):
        self.enter_wait()
        self.client.feedback_failure = TimeoutError("simulated serial failure")
        with self.assertRaisesRegex(bridge.SessionError, "feedback unavailable"):
            self.request()
        self.assertIsNotNone(self.motors.fault_reason)
        self.assert_parameters_preserved()

    def test_motor_error_while_waiting_remains_fatal(self):
        self.enter_wait()
        self.client.feedback = (7, -3, 0, 9)
        with self.assertRaisesRegex(bridge.SessionError, "Motor fault feedback"):
            self.request()
        self.assertIsNotNone(self.motors.fault_reason)
        self.assert_parameters_preserved()

    def test_startup_never_advertises_ready_from_an_unconfirmed_stop(self):
        self.client.feedback = (7, -3, 0, 0)
        started = self.clock.monotonic()
        with self.assertRaises(bridge.SessionError):
            self.session.ready()
        self.assertLessEqual(self.clock.monotonic() - started, 1.61)
        self.assertFalse(self.motors.standstill_pending)
        self.assertIsNotNone(self.motors.fault_reason)

    def test_explicit_close_and_estop_remain_bounded_and_do_not_claim_a_false_stop(self):
        for command in ("close", "estop"):
            with self.subTest(command=command):
                self.client = FakeClient()
                self.client.registers.update(left_parking_current=2.0, right_parking_current=2.0)
                self.motors = bridge.Motors(self.client)
                self.session = bridge.ProtocolSession(self.motors, port="fake", dry_run=False)
                self.enter_wait()
                started, seq = self.clock.monotonic(), self.session.next_seq
                with self.assertRaises(bridge.SessionError):
                    self.request(command)
                self.assertLessEqual(self.clock.monotonic() - started, 1.61)
                self.assertEqual(self.session.next_seq, seq)
                self.assertFalse(self.motors.stopped_confirmed)
                self.assert_parameters_preserved()


class SessionWaitTests(unittest.TestCase):
    def run_session(self, *, held_after_wait=False, drop_after_wait=False):
        clock, epoch = FakeClock(), [None]
        messages, deadlines = [], []
        diagnostic = Diagnostic()

        class CoastClient(FakeClient):
            def set_left_speed(self, rpm):
                super().set_left_speed(rpm)
                if rpm:
                    self.feedback = (rpm, *self.feedback[1:])

            def set_right_speed(self, rpm):
                super().set_right_speed(rpm)
                if rpm:
                    self.feedback = (self.feedback[0], rpm, *self.feedback[2:])

        client = CoastClient()
        client.registers.update(left_parking_current=2.0, right_parking_current=2.0)
        original = copy.deepcopy(client.registers)
        actions = [dict(at=.05, cmd="stop", normal_policy_fixture=True), dict(at=.2, cmd="drive", left=20, right=-20),
                   dict(at=.3, cmd="stop")]
        actions += [dict(at=n / 10, cmd="stop") for n in range(4, 20)]
        if held_after_wait:
            actions += [dict(at=at, cmd="drive", left=40, right=-40) for at in (2.1, 2.3, 2.6, 3.0, 5.5, 6.89, 7.0)]
        elif not drop_after_wait:
            actions += [dict(at=n / 10, cmd="stop") for n in range(20, 84)]
            actions += [dict(at=8.4, cmd="stop", feedback=(0, 0, 0, 0)),
                        dict(at=8.52, cmd="stop"), dict(at=8.6, cmd="close")]

        class Output:
            def __init__(self, _fd):
                pass

            def send(self, message):
                messages.append(message)
                if message["type"] == "ready":
                    epoch[0] = clock.monotonic()

        class Input:
            def __init__(self, _fd):
                self.index = 0

            def advance(self, destination):
                while clock.monotonic() + .02 < destination:
                    clock.sleep(.02)
                    self.poll()
                clock.now = destination
                self.poll()

            def line(self, deadline, _stopped):
                deadlines.append(deadline - epoch[0])
                action = actions[self.index] if self.index < len(actions) else None
                if action is None or epoch[0] + action["at"] >= deadline:
                    self.advance(deadline)
                    raise bridge.MotionLeaseExpired("Control watchdog expired")
                self.advance(epoch[0] + action["at"])
                if "feedback" in action:
                    client.feedback = action["feedback"]
                if action.get("normal_policy_fixture"):
                    # Offline state setup only; no legacy mode command is submitted.
                    motors.set_parking(True)
                self.index += 1
                request = dict(seq=self.index, token=messages[-1]["token"], cmd=action["cmd"],
                               left=action.get("left", 0), right=action.get("right", 0))
                return json.dumps(request).encode()

        motors = bridge.Motors(client)
        with patch.object(bridge.time, "monotonic", clock.monotonic), \
                patch.object(bridge.time, "sleep", clock.sleep), \
                patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input):
            result = bridge.run_session(motors, "fake", True, threading.Event(), diagnostic=diagnostic)
        self.assertEqual(client.registers, original)
        self.assertFalse(any(call[0] == "write" for call in client.calls))
        self.assertNotIn(("left", 40), client.calls)
        self.assertNotIn(("right", -40), client.calls)
        return result, messages, diagnostic.events, deadlines, epoch[0], client

    def test_valid_neutral_heartbeats_keep_waiting_session_alive_more_than_five_seconds(self):
        result, messages, events, _deadlines, epoch, _client = self.run_session()
        self.assertEqual(result, 0)
        self.assertFalse(any(message["type"] == "error" for message in messages))
        pending = [message for message in messages if message.get("standstillPending")]
        self.assertGreater(len(pending), 50)
        self.assertTrue(all(message["targetStopped"] and message["recoveryRequired"] for message in pending))
        started = next(event for event in events if event["event"] == "drive.standstill_wait_started")
        recovered = next(event for event in events if event["event"] == "drive.standstill_wait_zero_confirmed")
        self.assertGreater(recovered["at"] - started["at"], 5.0)
        self.assertTrue(messages[-1]["stopped"])
        self.assertFalse(messages[-1]["standstillPending"])
        for name in ("drive.standstill_wait_started", "drive.standstill_wait_zero_confirmed",
                     "drive.standstill_wait_released"):
            self.assertEqual(sum(event["event"] == name for event in events), 1)
        summaries = [event for event in events if event["event"] == "drive.ack_sent" and event.get("standstillPending")]
        self.assertLessEqual(len(summaries), 10, "waiting ACKs must not create a critical event per heartbeat")

    def test_held_motion_or_missing_input_cannot_renew_network_deadline_while_waiting(self):
        for held in (False, True):
            with self.subTest(held=held):
                result, messages, events, _deadlines, epoch, _client = self.run_session(
                    held_after_wait=held, drop_after_wait=not held)
                self.assertEqual(result, 1)
                failure = next(event for event in events if event["event"] == "drive.failure")
                self.assertEqual(failure["stopSource"], "network_grace_expired")
                self.assertAlmostEqual(failure["at"] - epoch, 6.9)
                self.assertIn("network grace expired", messages[-1]["error"])


class ResponseSizeTests(unittest.TestCase):
    def test_new_recovery_fields_fit_bounded_output_with_conservative_numeric_extremes(self):
        # Construct real schema templates using simulated Motors; the extreme
        # values below are serialization bounds, never accepted motor feedback.
        session = bridge.ProtocolSession(bridge.Motors(), port="/dev/ttyS0", dry_run=True)
        ready = session.ready()
        ack, _ = session.accept(dict(seq=1, token=session.token, cmd="stop", left=0, right=0))
        for payload in (ready, ack):
            payload.update(feedbackLeftRpm=-(2**31), feedbackRightRpm=2**31 - 1,
                           feedbackLeftError=65535, feedbackRightError=65535,
                           feedbackAgeMs=2**63 - 1, feedbackSamples=2, feedbackSource="hardware",
                           parkingLeftCurrentAmps=1.2345678901234567e-200,
                           parkingRightCurrentAmps=1.2345678901234567e-200,
                           parkingMaxCurrentAmps=1.2345678901234567e-200)
        ready["dryRun"] = False
        ack.update(seq=2**63 - 1, infraredBlocked=True, infraredDirections=["forward", "right", "left"],
                   standstillPending=True, feedbackRecheck=True, recoveryRequired=True, stopped=False)
        sent = []
        with patch.object(bridge.os, "set_blocking"), patch.object(bridge.os, "write",
                side_effect=lambda _fd, data: sent.append(data) or len(data)):
            output = bridge.JsonOutput(-1)
            output.send(ready)
            output.send(ack)
        self.assertEqual(len(sent), 2)
        for data in sent:
            self.assertTrue(data.endswith(b"\n"))
            self.assertLessEqual(len(data), 1024)
        self.assertTrue(json.loads(sent[0])["standstillWaitSupported"])
        self.assertTrue(json.loads(sent[1])["standstillPending"])


if __name__ == "__main__":
    unittest.main()
