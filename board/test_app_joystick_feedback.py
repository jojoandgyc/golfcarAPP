"""Parking/standstill regression tests: fake serial and virtual time only, never hardware."""
from contextlib import ExitStack, redirect_stderr
from io import StringIO
import copy
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

from test_app_joystick_bridge import FakeClient, FakeClock, parking_request
import app_joystick_bridge as bridge


class ParkingFeedbackTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.patches = ExitStack()
        self.addCleanup(self.patches.close)
        self.patches.enter_context(patch.object(bridge.time, "monotonic", self.clock.monotonic))
        self.patches.enter_context(patch.object(bridge.time, "sleep", self.clock.sleep))
        self.client = FakeClient()
        self.original = copy.deepcopy(self.client.registers)
        self.motors = bridge.Motors(self.client)

    def assert_no_parameter_writes(self):
        self.assertFalse(any(call[0] == "write" for call in self.client.calls))
        self.assertEqual(self.original, self.client.registers)

    def assert_emergency_attempted(self):
        self.assertIn(("left", "stop", 1), self.client.calls)
        self.assertIn(("right", "stop", 1), self.client.calls)

    def initialize(self):
        self.motors.initialize()
        self.assertTrue(self.motors.stopped_confirmed)
        self.client.calls.clear()
        self.client.feedback_reads.clear()

    def test_startup_reads_asymmetric_fractional_currents_without_rewriting(self):
        self.client.registers.update(left_parking_current=3.25, right_parking_current=4.75,
                                     max_output_current=12.5)
        self.original = copy.deepcopy(self.client.registers)
        session = bridge.ProtocolSession(self.motors, port="fake", dry_run=False)
        ready = session.ready()
        self.assertEqual(ready["safetyVersion"], 2)
        self.assertEqual(ready["parkingLeftCurrentAmps"], 3.25)
        self.assertEqual(ready["parkingRightCurrentAmps"], 4.75)
        self.assertEqual(ready["parkingMaxCurrentAmps"], 12.5)
        self.assertTrue(ready["parkingCurrentReadOnly"])
        self.assertNotIn("parkingCurrent", ready)
        self.assertTrue(ready["stopped"])
        self.assertTrue(ready["targetStopped"])
        self.assert_no_parameter_writes()

    def test_hardware_confirmation_requires_two_distinct_spaced_zero_samples(self):
        self.motors.initialize()
        reads = self.client.feedback_reads
        self.assertGreaterEqual(len(reads), 2)
        self.assertGreaterEqual(max(row[2] for row in reads) - min(row[2] for row in reads), .099)
        self.assertTrue(self.motors.stopped_confirmed)
        self.assert_no_parameter_writes()

    def test_both_parking_transitions_issue_stop_mode_and_preserve_all_currents(self):
        self.initialize()
        self.motors.set_parking(True)
        self.assertIn(("left", "stop", 0), self.client.calls)
        self.assertIn(("right", "stop", 0), self.client.calls)
        self.assertTrue(self.motors.parking_enabled)
        self.client.calls.clear()
        self.motors.set_parking(False)
        self.assert_emergency_attempted()
        self.assertFalse(self.motors.parking_enabled)
        self.assertTrue(self.motors.stopped_confirmed)
        self.assert_no_parameter_writes()

    def test_shutdown_preserves_tuned_currents_after_parking(self):
        self.initialize()
        self.motors.set_parking(True)
        self.client.calls.clear()
        self.motors.shutdown()
        self.assert_emergency_attempted()
        self.assertTrue(self.motors.stopped_confirmed)
        self.assertFalse(self.motors.parking_enabled)
        self.assert_no_parameter_writes()

    def test_zero_current_or_current_over_limit_never_engages_normal_parking(self):
        for register, value in (("left_parking_current", 0), ("right_parking_current", 0),
                                ("left_parking_current", 61), ("right_parking_current", 61)):
            with self.subTest(register=register, value=value):
                client = FakeClient()
                client.registers[register] = value
                motors = bridge.Motors(client)
                try:
                    motors.initialize()
                    with self.assertRaises(bridge.SessionError):
                        motors.set_parking(True)
                except bridge.SessionError:
                    pass  # Refusal may occur during startup validation as well.
                self.assertNotIn(("left", "stop", 0), client.calls)
                self.assertNotIn(("right", "stop", 0), client.calls)
                self.assertFalse(any(call[0] == "write" for call in client.calls))

    def test_nonfinite_or_failed_configuration_read_never_claims_initialized(self):
        for value in (float("nan"), float("inf"), -1, True, "5", TimeoutError("read unavailable")):
            with self.subTest(value=value):
                client = FakeClient()
                client.readback["left_parking_current"] = value
                motors = bridge.Motors(client)
                with self.assertRaises(bridge.SessionError):
                    motors.initialize()
                self.assertFalse(any(call[0] == "write" for call in client.calls))

    def test_standstill_confirmation_expires_without_new_feedback(self):
        self.initialize()
        self.clock.sleep(.251)
        self.assertFalse(self.motors.stopped_confirmed)

    def test_one_new_zero_sample_cannot_revive_expired_standstill_history(self):
        self.initialize()
        self.clock.sleep(.3)
        self.motors._sample_feedback(force=True)
        self.assertEqual(self.motors.feedback_state()["feedbackSamples"], 1)
        self.assertFalse(self.motors.stopped_confirmed)
        # Same-time duplicate reads are not independent physical evidence.
        self.motors._sample_feedback(force=True)
        self.assertFalse(self.motors.stopped_confirmed)
        self.clock.sleep(.101)
        self.motors._sample_feedback(force=True)
        self.assertTrue(self.motors.stopped_confirmed)

    def test_newest_feedback_cannot_keep_an_expired_older_zero_observation_alive(self):
        self.initialize()
        self.motors.drive(1, -1)
        self.motors.stop()
        epoch = self.clock.monotonic()
        for elapsed in (.1, .34):
            self.clock.now = epoch + elapsed
            self.motors._sample_feedback(force=True)
        self.assertEqual(self.motors.zero_samples, 2)
        self.assertTrue(self.motors.stopped_confirmed)
        self.clock.now = epoch + .36
        self.motors._sample_feedback(force=True)
        # .36 is too close to .34 to count, while .1 is now 260 ms old.
        # The latest feedback is fresh, but there is only one valid observation.
        self.assertEqual(self.motors.feedback_state()["feedbackAgeMs"], 0)
        self.assertEqual(self.motors.zero_samples, 1)
        self.assertFalse(self.motors.stopped_confirmed)
        self.clock.now = epoch + .441
        self.motors._sample_feedback(force=True)
        self.assertEqual(self.motors.zero_samples, 2)
        self.assertTrue(self.motors.stopped_confirmed)

    def test_expired_standstill_deadline_still_attempts_emergency_stop_on_both_sides(self):
        self.initialize()
        self.client.fail_left = True
        deadline = self.clock.monotonic()
        with self.assertRaisesRegex(bridge.SessionError, "timed out"):
            self.motors.await_standstill(deadline=deadline)
        self.assertEqual(self.client.feedback_reads, [], "expired budget must not start a fresh read")
        self.assert_emergency_attempted()
        self.assertFalse(self.motors.stopped_confirmed)
        self.assert_no_parameter_writes()

    def test_parking_pre_and_post_confirmation_share_one_two_second_budget(self):
        self.initialize()
        self.motors.drive(20, -20)
        self.motors.stop()
        self.client.calls.clear()
        self.client.feedback_reads.clear()
        epoch = self.clock.monotonic()
        read_starts, mode_times = [], []
        original_read, original_stop = self.client.read_holding_registers, self.client.stop
        mode_change_started = False

        def slow_feedback(address, count):
            read_starts.append(self.clock.monotonic() - epoch)
            self.clock.sleep(.105)
            # Deceleration consumes most of the first confirmation's budget.
            self.client.feedback = (20, -20, 0, 0) if len(read_starts) <= 5 else (0, 0, 0, 0)
            return original_read(address, count)

        def slow_stop(side, mode):
            nonlocal mode_change_started
            if mode == 0:
                mode_change_started = True
            mode_times.append((side, mode, self.clock.monotonic() - epoch))
            if mode_change_started:
                self.clock.sleep(.15)
            return original_stop(side, mode)

        with patch.object(self.client, "read_holding_registers", side_effect=slow_feedback), \
                patch.object(self.client, "stop", side_effect=slow_stop):
            with self.assertRaisesRegex(bridge.SessionError, "timed out"):
                self.motors.set_parking(True)
        self.assertTrue(mode_change_started, "pre-confirmation must succeed before testing the shared remainder")
        self.assertTrue(all(start < 2.0 + 1e-8 for start in read_starts))
        self.assertEqual([(side, mode) for side, mode, _ in mode_times[-2:]],
                         [("left", 1), ("right", 1)])
        # The deadline stops confirmation; mandatory emergency writes still finish.
        self.assertGreaterEqual(mode_times[-2][2], 2.0 - 1e-8)
        self.assertLess(self.clock.monotonic() - epoch, 2.41)
        self.assertFalse(self.motors.stopped_confirmed)
        self.assert_no_parameter_writes()

    def test_slow_configuration_reads_require_two_fresh_samples_before_ready(self):
        original = self.client.read_register
        read_completed_at = []

        def slow_read(name):
            self.clock.sleep(.2)
            read_completed_at.append(self.clock.monotonic())
            return original(name)

        session = bridge.ProtocolSession(self.motors, port="fake", dry_run=False)
        with patch.object(self.client, "read_register", side_effect=slow_read):
            ready = session.ready()
        new_samples = [row[2] for row in self.client.feedback_reads
                       if row[2] >= read_completed_at[-1]]
        self.assertGreaterEqual(len(new_samples), 2)
        self.assertGreaterEqual(max(new_samples) - min(new_samples), .099)
        self.assertTrue(ready["stopped"])
        self.assertLessEqual(ready["feedbackAgeMs"], 250)
        self.assert_no_parameter_writes()

    def test_valid_current_above_actual_configured_limit_refuses_parking(self):
        self.client.registers.update(left_parking_current=3.25, right_parking_current=5.0,
                                     max_output_current=4.5)
        self.original = copy.deepcopy(self.client.registers)
        self.initialize()
        with self.assertRaisesRegex(bridge.SessionError, "output-current limit"):
            self.motors.set_parking(True)
        self.assertNotIn(("left", "stop", 0), self.client.calls)
        self.assertNotIn(("right", "stop", 0), self.client.calls)
        self.assert_no_parameter_writes()

    def test_idle_poll_samples_at_100ms_and_reissues_stop_at_200ms(self):
        self.initialize()
        for _ in range(10):
            self.clock.sleep(.02)
            self.motors.poll_safety()
        self.assertGreaterEqual(len(self.client.feedback_reads), 1)
        self.assertLessEqual(len(self.client.feedback_reads), 3)
        self.assert_emergency_attempted()
        self.assert_no_parameter_writes()

    def test_repeated_neutral_ack_does_not_suppress_idle_safety_polling(self):
        session = bridge.ProtocolSession(self.motors, port="fake", dry_run=False)
        session.ready()
        self.client.calls.clear()
        self.client.feedback_reads.clear()
        for _ in range(12):
            self.clock.sleep(.02)
            self.motors.poll_safety()
            ack, _ = session.accept({"seq": session.next_seq, "token": session.token,
                                     "cmd": "stop", "left": 0, "right": 0})
            self.assertTrue(ack["targetStopped"])
            self.assertEqual(ack["safetyVersion"], 2)
        self.assert_emergency_attempted()
        self.assertGreaterEqual(len(self.client.feedback_reads), 2)
        self.assert_no_parameter_writes()

    def test_queued_neutral_checks_feedback_before_stop_refresh_can_hide_motion(self):
        self.initialize()
        self.clock.sleep(.21)
        self.client.feedback = (1, 0, 0, 0)
        observed = []
        original = self.client.set_left_speed

        def stop_and_erase_feedback(rpm):
            observed.append(self.client.feedback)
            original(rpm)
            self.client.feedback = (0, 0, 0, 0)

        with patch.object(self.client, "set_left_speed", side_effect=stop_and_erase_feedback):
            with self.assertRaisesRegex(bridge.SessionError, "Unexpected motion"):
                self.motors.stop()
        self.assertEqual(observed[0], (1, 0, 0, 0))
        self.assertTrue(self.motors.fault_reason)
        self.assert_emergency_attempted()
        self.assert_no_parameter_writes()

    def test_emergency_refresh_keeps_mode_one_with_parking_enabled(self):
        self.initialize()
        self.motors.set_parking(True)
        self.motors.stop(emergency=True, release_interlock=False)
        self.client.calls.clear()
        self.clock.sleep(.21)
        self.motors.poll_safety()
        self.assert_emergency_attempted()
        self.assertNotIn(("left", "stop", 0), self.client.calls)
        self.assertNotIn(("right", "stop", 0), self.client.calls)
        self.assertTrue(self.motors.parking_enabled)
        self.assert_no_parameter_writes()

    def test_nonzero_feedback_after_confirmed_stop_trips_even_for_one_rpm(self):
        for left, right in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            with self.subTest(left=left, right=right):
                client = FakeClient()
                motors = bridge.Motors(client)
                motors.initialize()
                client.calls.clear()
                client.feedback = (left, right, 0, 0)
                self.clock.sleep(.101)
                with self.assertRaises(bridge.SessionError):
                    motors.poll_safety()
                self.assertIn(("left", "stop", 1), client.calls)
                self.assertIn(("right", "stop", 1), client.calls)
                self.assertFalse(motors.stopped_confirmed)
                # Returning zero cannot silently clear the fault or resume movement.
                client.feedback = (0, 0, 0, 0)
                with self.assertRaises(bridge.SessionError):
                    motors.drive(10, -10)
                self.assertNotIn(("left", 10), client.calls)

    def test_error_feedback_or_invalid_frame_trips_and_attempts_both_sides(self):
        for failure in ((0, 0, 1, 0), (0, 0, 0, 18), [], [0] * 12,
                        [0] * 12 + [True], [0] * 12 + [-1], TimeoutError("feedback timed out")):
            with self.subTest(failure=failure):
                client = FakeClient()
                motors = bridge.Motors(client)
                motors.initialize()
                client.calls.clear()
                client.feedback_samples.append(failure)
                self.clock.sleep(.101)
                with self.assertRaises(bridge.SessionError):
                    motors.poll_safety()
                self.assertIn(("left", "stop", 1), client.calls)
                self.assertIn(("right", "stop", 1), client.calls)
                self.assertFalse(motors.stopped_confirmed)

    def test_fault_stop_attempts_right_even_when_left_serial_write_fails(self):
        self.initialize()
        self.client.fail_left = True
        self.client.feedback = (0, 1, 0, 0)
        self.clock.sleep(.101)
        with self.assertRaises(bridge.SessionError):
            self.motors.poll_safety()
        self.assert_emergency_attempted()
        self.assertFalse(self.motors.stopped_confirmed)
        self.assert_no_parameter_writes()

    def test_deceleration_is_not_reported_as_stopped_and_times_out(self):
        self.initialize()
        self.motors.drive(20, -20)
        self.client.feedback = (20, -20, 0, 0)
        self.motors.stop()
        self.assertFalse(self.motors.stopped_confirmed)
        for _ in range(14):
            self.clock.sleep(.1)
            self.motors.poll_safety()
            self.assertFalse(self.motors.stopped_confirmed)
        self.clock.sleep(.2)
        with self.assertRaises(bridge.SessionError):
            self.motors.poll_safety()
        self.assert_emergency_attempted()

    def test_signed_feedback_and_zero_target_are_independent(self):
        session = bridge.ProtocolSession(self.motors, port="fake", dry_run=False)
        session.ready()
        self.motors.drive(-30, 40)
        self.client.feedback = (-30, 40, 0, 0)
        self.clock.sleep(.101)
        self.motors.poll_safety()
        state = self.motors.feedback_state()
        self.assertEqual(state["feedbackLeftRpm"], -30)
        self.assertEqual(state["feedbackRightRpm"], 40)
        ack, _ = session.accept({"seq": session.next_seq, "token": session.token,
                                 "cmd": "stop", "left": 0, "right": 0})
        self.assertTrue(ack["targetStopped"])
        self.assertFalse(ack["stopped"])

    def test_old_mutating_parking_payload_is_rejected_before_any_serial_access(self):
        session = bridge.ProtocolSession(self.motors, port="fake", dry_run=False)
        session.ready()
        self.client.calls.clear()
        with self.assertRaises(bridge.SessionError):
            session.accept(parking_request(session, parkingCurrent=5))
        self.assertEqual(self.client.calls, [])
        self.assertEqual(session.next_seq, 1)

    def test_dry_run_reports_simulated_feedback_and_safety_version(self):
        session = bridge.ProtocolSession(bridge.Motors(), port="dry-run", dry_run=True)
        ready = session.ready()
        self.assertEqual(ready["safetyVersion"], 2)
        self.assertEqual(ready["feedbackSource"], "simulated")
        self.assertTrue(ready["stopped"])
        self.assertEqual(ready["parkingLeftCurrentAmps"], 5.0)
        self.assertEqual(ready["parkingRightCurrentAmps"], 5.0)
        self.assertEqual(ready["parkingMaxCurrentAmps"], 60.0)

    def test_missing_or_wrong_safety_cli_version_cannot_open_hardware(self):
        for options in ([], ["--require-safety-version", "1"], ["--require-safety-version", "3"]):
            with self.subTest(options=options), patch.dict(os.environ, {}, clear=True), \
                    redirect_stderr(StringIO()), \
                    patch.object(sys, "argv", [str(Path(bridge.__file__)), *options]), \
                    patch.object(bridge.signal, "signal"), \
                    patch.object(bridge, "open_hardware", side_effect=AssertionError("hardware must not open")):
                try:
                    result = bridge.main()
                except SystemExit as error:
                    result = error.code
                self.assertNotEqual(result, 0)


if __name__ == "__main__":
    unittest.main()
