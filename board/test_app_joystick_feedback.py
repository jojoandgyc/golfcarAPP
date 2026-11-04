"""Stop-policy/standstill tests: fake serial and virtual time only, never hardware.

Direct set_parking calls select a historical private stop-policy fixture. They do
not represent a supported parking switch command or the current-based UI.
"""
from contextlib import ExitStack, redirect_stderr
from io import StringIO
import copy
import json
import os
from pathlib import Path
import sys
import threading
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

    def test_internal_stop_policy_transitions_preserve_all_currents(self):
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
            self.client.feedback = (81, -20, 0, 0) if len(read_starts) <= 5 else (0, 0, 0, 0)
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
        self.client.feedback = (81, 0, 0, 0)
        observed = []
        original = self.client.set_left_speed

        def stop_and_erase_feedback(rpm):
            observed.append(self.client.feedback)
            original(rpm)
            self.client.feedback = (0, 0, 0, 0)

        with patch.object(self.client, "set_left_speed", side_effect=stop_and_erase_feedback):
            self.motors.stop()
        self.assertEqual(observed[0], (81, 0, 0, 0))
        self.assertIsNone(self.motors.fault_reason)
        self.assertTrue(self.motors.standstill_pending)
        self.assertEqual(self.motors.feedback[:2], (81, 0), "corrective writes must not erase initiating evidence")
        self.assertFalse(self.motors.stopped_confirmed)
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

    def test_feedback_above_noise_limit_stops_and_waits_without_ending_session(self):
        for left, right in ((81, 0), (-81, 0), (0, 81), (0, -81), (2, -81)):
            with self.subTest(left=left, right=right):
                client = FakeClient()
                motors = bridge.Motors(client)
                motors.initialize()
                client.calls.clear()
                client.feedback = (left, right, 0, 0)
                self.clock.sleep(.101)
                motors.poll_safety()
                self.assertIsNone(motors.fault_reason)
                self.assertTrue(motors.standstill_pending)
                self.assertEqual(motors.feedback[:2], (left, right))
                self.assertIn(('left', 'stop', 1), client.calls)
                self.assertIn(('right', 'stop', 1), client.calls)
                self.assertFalse(motors.stopped_confirmed)
                # A first zero or a held target cannot silently resume motion.
                client.feedback = (0, 0, 0, 0)
                self.assertEqual(motors.drive(10, -10), (0, 0))
                self.assertNotIn(('left', 10), client.calls)
                self.assertTrue(motors.standstill_pending)
                self.assertFalse(any(call[0] == 'write' for call in client.calls))

    def test_small_signed_feedback_stays_raw_without_recovery_or_false_stop(self):
        for left, right in ((1, 0), (-1, 0), (2, 0), (-2, 0),
                            (0, 1), (0, -1), (0, 2), (0, -2), (-2, 2)):
            with self.subTest(left=left, right=right):
                client = FakeClient()
                motors = bridge.Motors(client)
                motors.initialize()
                client.calls.clear()
                client.feedback = (left, right, 0, 0)
                self.clock.sleep(.101)
                motors.poll_safety()
                self.assertIsNone(motors.fault_reason)
                self.assertIsNone(motors.motion_recheck_at)
                self.assertFalse(motors.motion_recovery_required)
                self.assertFalse(motors.standstill_pending)
                self.assertEqual(motors.feedback[:2], (left, right))
                self.assertEqual(motors.zero_samples, 0)
                self.assertFalse(motors.stopped_confirmed)
                self.assertEqual(motors.drive(40, -40), (40, -40))
                self.assertIn(('left', 40), client.calls)
                self.assertIn(('right', -40), client.calls)
                self.assertFalse(any(call[0] == 'write' for call in client.calls))

    def noise_from_zero_time(self):
        # Monotonic time zero is valid; no 600 ms fault timer should be armed.
        self.clock.now = -1.0
        self.initialize()
        self.clock.now = 0.0
        self.client.feedback = (-1, 0, 0, 0)
        self.motors._sample_feedback(force=True)
        self.assertIsNone(self.motors.motion_recheck_at)
        self.assertFalse(self.motors.feedback_recovery_required)

    def test_sign_side_and_zero_changes_beyond_old_deadline_never_create_recovery(self):
        self.noise_from_zero_time()
        for at, feedback in ((0.0, (-1, 0, 0, 0)), (.1, (0, 0, 0, 0)),
                             (.2, (0, 2, 0, 0)), (.3, (-2, 0, 0, 0)),
                             (.6, (0, -2, 0, 0)), (1.5, (1, 0, 0, 0)),
                             (6.0, (0, 2, 0, 0)), (60.0, (-2, 2, 0, 0))):
            self.clock.now = at
            self.client.feedback = feedback
            for _ in range(3):
                self.motors._sample_feedback(force=True)
            self.assertIsNone(self.motors.motion_recheck_at)
            self.assertIsNone(self.motors.fault_reason)
            self.assertFalse(self.motors.feedback_recovery_required)
            self.assertFalse(self.motors.standstill_pending)
            self.assertFalse(self.motors.stopped_confirmed)
            self.assertEqual(self.motors.feedback, feedback)
        self.assert_no_parameter_writes()

    def test_noise_warning_is_throttled_across_sign_changes_and_intervening_zeros(self):
        self.initialize()
        events = []
        clock = self.clock
        class Diagnostic:
            def event(self, name, **fields):
                events.append(dict(event=name, at=clock.monotonic(), **fields))
        self.motors.diagnostic = Diagnostic()
        epoch = self.clock.monotonic()
        values = ((1, 0, 0, 0), (0, 0, 0, 0), (0, -2, 0, 0), (-1, 1, 0, 0))
        for index in range(121):
            self.clock.now = epoch + index * .02
            self.client.feedback = values[index % len(values)]
            self.motors._sample_feedback(force=True)
        warnings = [row for row in events if row['event'] == 'drive.feedback_noise_warning']
        self.assertGreaterEqual(len(warnings), 2)
        self.assertLessEqual(len(warnings), 3)
        self.assertTrue(all(not row.get('critical', False) for row in warnings))
        self.assertTrue(all(second['at'] - first['at'] >= .999
                            for first, second in zip(warnings, warnings[1:])))
        self.assertTrue(all(0 < row['maxRpm'] <= 80 and row['sampleCount'] >= 1 for row in warnings))
        self.assertFalse(self.motors.feedback_recovery_required)
        self.assert_no_parameter_writes()

    def test_one_late_zero_still_needs_a_distinct_fresh_second_zero(self):
        self.noise_from_zero_time()
        self.clock.now = .55
        self.client.feedback = (0, 0, 0, 0)
        self.motors._sample_feedback(force=True)
        self.assertEqual(self.motors.zero_samples, 1)
        self.assertFalse(self.motors.stopped_confirmed)
        self.clock.now = .6
        self.motors._sample_feedback(force=True)
        self.assertEqual(self.motors.zero_samples, 1)
        self.assertFalse(self.motors.stopped_confirmed)
        self.clock.now = .651
        self.motors._sample_feedback(force=True)
        self.assertTrue(self.motors.stopped_confirmed)
        self.assertFalse(self.motors.feedback_recovery_required)
        self.assertIsNone(self.motors.fault_reason)

    def test_cached_noise_at_old_600ms_deadline_is_not_a_fatal_timer(self):
        self.noise_from_zero_time()
        self.clock.now = .599
        self.motors._sample_feedback(force=True)
        self.client.calls.clear()
        self.clock.now = .6
        self.motors.poll_safety()
        self.assertIsNone(self.motors.fault_reason)
        self.assertFalse(self.motors.feedback_recovery_required)
        self.assertFalse(self.motors.stopped_confirmed)
        self.assertEqual(self.motors.feedback[:2], (-1, 0))
        self.assert_emergency_attempted()
        self.assert_no_parameter_writes()

    def test_feedback_read_crossing_old_deadline_keeps_real_zero_sample_count(self):
        self.noise_from_zero_time()
        self.clock.now = .59
        original = self.client.read_holding_registers
        def delayed_read(address, count):
            self.clock.sleep(.02)
            self.client.feedback = (0, 0, 0, 0)
            return original(address, count)
        with patch.object(self.client, 'read_holding_registers', side_effect=delayed_read):
            self.motors._sample_feedback(force=True)
        self.assertEqual(self.motors.zero_samples, 1)
        self.assertFalse(self.motors.stopped_confirmed)
        self.assertFalse(self.motors.feedback_recovery_required)
        self.assertIsNone(self.motors.fault_reason)

    def test_two_fresh_zero_samples_after_noise_need_no_extra_release_gesture(self):
        self.noise_from_zero_time()
        self.client.feedback = (0, 0, 0, 0)
        self.clock.now = .2
        self.motors._sample_feedback(force=True)
        self.motors._sample_feedback(force=True)
        self.assertEqual(self.motors.zero_samples, 1)
        self.clock.now = .301
        self.motors._sample_feedback(force=True)
        self.assertTrue(self.motors.stopped_confirmed)
        self.assertFalse(self.motors.feedback_recovery_required)
        self.client.calls.clear()
        self.assertEqual(self.motors.drive(40, -40), (40, -40))
        self.assertIn(('left', 40), self.client.calls)
        self.assertIn(('right', -40), self.client.calls)

    def test_old_stop_deadline_does_not_turn_idle_jitter_into_a_wait_lock(self):
        self.initialize()
        self.clock.sleep(20.0)
        self.client.feedback = (-1, 0, 0, 0)
        self.motors.poll_safety()
        self.assertIsNone(self.motors.fault_reason)
        self.assertIsNone(self.motors.motion_recheck_at)
        self.assertFalse(self.motors.feedback_recovery_required)
        self.assertFalse(self.motors.stopped_confirmed)
        self.assert_no_parameter_writes()

    def test_feedback_faults_and_transport_errors_remain_immediate_after_noise(self):
        for failure in ((0, 0, 1, 0), (0, 0, 0, 1), [], TimeoutError('feedback unavailable')):
            with self.subTest(failure=failure):
                client = FakeClient()
                motors = bridge.Motors(client)
                motors.initialize()
                client.feedback = (-1, 0, 0, 0)
                motors._sample_feedback(force=True)
                self.assertFalse(motors.feedback_recovery_required)
                client.calls.clear()
                client.feedback_samples.append(failure)
                with self.assertRaises(bridge.SessionError):
                    motors._sample_feedback(force=True)
                self.assertIn(('left', 'stop', 1), client.calls)
                self.assertIn(('right', 'stop', 1), client.calls)
                self.assertIsNotNone(motors.fault_reason)

    def test_feedback_above_eighty_rpm_enters_soft_stop_wait(self):
        self.noise_from_zero_time()
        self.clock.now = .02
        self.client.feedback = (0, -81, 0, 0)
        self.motors._sample_feedback(force=True)
        self.assertTrue(self.motors.standstill_pending)
        self.assertIsNotNone(self.motors.standstill_wait_at)
        self.assertIsNone(self.motors.fault_reason)
        self.assertFalse(self.motors.stopped_confirmed)
        self.assert_emergency_attempted()

    def test_protocol_soft_wait_suppresses_held_motion_until_two_zeros_and_neutral(self):
        session = bridge.ProtocolSession(self.motors, port='fake', dry_run=False)
        session.ready()
        self.client.calls.clear()
        def request(command='drive', left=40, right=-40):
            return session.accept(dict(seq=session.next_seq, token=session.token,
                cmd=command, left=left, right=right))[0]
        self.client.feedback = (-81, 0, 0, 0)
        self.clock.sleep(.101)
        first = request()
        self.assertEqual((first['left'], first['right']), (0, 0))
        self.assertTrue(first['standstillPending'])
        self.assertTrue(first['feedbackRecheck'])
        self.assertTrue(first['recoveryRequired'])
        self.assertFalse(first['stopped'])
        self.assertIsNone(self.motors.fault_reason)
        self.client.feedback = (0, 0, 0, 0)
        self.clock.sleep(.12)
        self.assertFalse(request()['stopped'])
        self.clock.sleep(.11)
        second = request()
        self.assertTrue(second['stopped'])
        self.assertTrue(second['standstillPending'])
        self.assertTrue(session.recovery_active)
        neutral = request('stop', 0, 0)
        self.assertTrue(neutral['recoveryRequired'])
        self.assertTrue(neutral['stopped'])
        self.assertFalse(session.recovery_active)
        self.assertNotIn(('left', 40), self.client.calls)
        self.assertNotIn(('right', -40), self.client.calls)
        resumed = request('drive', 20, -20)
        self.assertFalse(resumed['recoveryRequired'])
        self.assertFalse(resumed['feedbackRecheck'])
        self.assertEqual((resumed['left'], resumed['right']), (20, -20))
        self.assertIn(('left', 20), self.client.calls)
        self.assert_no_parameter_writes()

    def test_protocol_neutral_with_noise_stays_alive_without_recovery_flags(self):
        session = bridge.ProtocolSession(self.motors, port='fake', dry_run=False)
        session.ready()
        self.client.feedback = (0, -1, 0, 0)
        epoch = self.clock.monotonic()
        for elapsed in (.1, .2, .4, .599, .601, 1.6, 6.0):
            self.clock.now = epoch + elapsed
            ack, closing = session.accept(dict(seq=session.next_seq, token=session.token,
                cmd='stop', left=0, right=0))
            self.assertFalse(closing)
            self.assertFalse(ack['feedbackRecheck'])
            self.assertFalse(ack['recoveryRequired'])
            self.assertFalse(ack['standstillPending'])
            self.assertFalse(ack['stopped'])
            self.assertTrue(ack['targetStopped'])
            self.assertEqual((ack['feedbackLeftRpm'], ack['feedbackRightRpm']), (0, -1))
            self.assertFalse(session.recovery_active)
        self.assert_emergency_attempted()
        self.assert_no_parameter_writes()

    def test_neutral_final_sample_noise_never_creates_a_recovery_lock(self):
        session = bridge.ProtocolSession(self.motors, port='fake', dry_run=False)
        session.ready()
        self.client.feedback = (-1, 0, 0, 0)
        def neutral(command):
            return session.accept(dict(seq=session.next_seq, token=session.token,
                cmd=command, left=0, right=0))[0]
        started = neutral('stop')
        self.assertFalse(started['feedbackRecheck'])
        self.assertFalse(started['recoveryRequired'])
        self.assertFalse(started['stopped'])
        self.assertFalse(session.recovery_active)
        self.client.feedback = (0, 0, 0, 0)
        self.clock.sleep(.11)
        self.assertFalse(neutral('drive')['stopped'])
        self.clock.sleep(.11)
        confirmed = neutral('drive')
        self.assertTrue(confirmed['stopped'])
        self.assertFalse(confirmed['feedbackRecheck'])
        self.assertFalse(confirmed['recoveryRequired'])
        self.assert_no_parameter_writes()

    def test_private_stop_policy_after_noise_still_requires_exact_zero_confirmation(self):
        self.noise_from_zero_time()
        self.client.feedback = (0, 0, 0, 0)
        self.motors.set_parking(True)  # Historical private fixture, not a public parking switch.
        self.assertTrue(self.motors.stopped_confirmed)
        self.assertFalse(self.motors.feedback_recovery_required)
        self.assertEqual(self.motors.drive(40, -40), (40, -40))
        self.assert_no_parameter_writes()

    def test_private_stop_policy_without_real_zero_keeps_its_strict_budget(self):
        self.noise_from_zero_time()
        self.client.calls.clear()
        with self.assertRaises(bridge.SessionError):
            self.motors.set_parking(True)
        self.assertLessEqual(self.clock.monotonic(), 1.61)
        self.assertNotIn(('left', 'stop', 0), self.client.calls)
        self.assertNotIn(('right', 'stop', 0), self.client.calls)
        self.assert_emergency_attempted()
        self.assert_no_parameter_writes()

    def test_private_post_mode_confirmation_never_treats_noise_as_stopped(self):
        self.initialize()
        original = self.client.stop
        def jitter_after_mode_write(side, mode):
            original(side, mode)
            if mode == 0:
                self.client.feedback = (-1, 0, 0, 0)
        started = self.clock.monotonic()
        with patch.object(self.client, 'stop', side_effect=jitter_after_mode_write):
            with self.assertRaises(bridge.SessionError):
                self.motors.set_parking(True)
        self.assertLessEqual(self.clock.monotonic() - started, 1.71)
        self.assertFalse(self.motors.stopped_confirmed)
        self.assert_emergency_attempted()
        self.assert_no_parameter_writes()

    def test_startup_before_first_standstill_keeps_original_deceleration_window(self):
        epoch = self.clock.monotonic()
        original = self.client.read_holding_registers

        def deceleration(address, count):
            self.client.feedback = (-81, 0, 0, 0) if self.clock.monotonic() - epoch < .8 else (0, 0, 0, 0)
            return original(address, count)

        with patch.object(self.client, "read_holding_registers", side_effect=deceleration):
            self.motors.initialize()
        self.assertGreaterEqual(self.clock.monotonic() - epoch, .8)
        self.assertLess(self.clock.monotonic() - epoch, 1.5)
        self.assertTrue(self.motors.stopped_confirmed)
        self.assertIsNone(self.motors.motion_recheck_at)
        self.assertFalse(self.motors.motion_recovery_required)
        self.assert_no_parameter_writes()

    def test_terminal_cleanup_acknowledges_stop_writes_without_forging_zero(self):
        session = bridge.ProtocolSession(self.motors, port="fake", dry_run=False)
        session.ready()
        self.client.feedback = (-1, 0, 0, 0)
        self.motors._sample_feedback(force=True)
        started, seq = self.clock.monotonic(), session.next_seq
        ack, closing = session.accept({"seq": seq, "token": session.token,
                                      "cmd": "close", "left": 0, "right": 0})
        self.assertTrue(closing); self.assertTrue(ack['stopCommandConfirmed']); self.assertFalse(ack['stopped'])
        self.assertLessEqual(self.clock.monotonic() - started, 1.61)
        self.assertEqual(session.next_seq, seq + 1)
        self.assertFalse(self.motors.stopped_confirmed)
        self.assert_emergency_attempted()
        self.assert_no_parameter_writes()

    def test_terminal_ack_reuses_completed_confirmation_without_a_third_read(self):
        for command in ("close", "estop"):
            with self.subTest(command=command):
                client = FakeClient()
                motors = bridge.Motors(client)
                session = bridge.ProtocolSession(motors, port="fake", dry_run=False)
                session.ready()
                method = "shutdown"
                original = getattr(motors, method)
                reads_when_confirmed = []

                def confirmed(*args, **kwargs):
                    original(*args, **kwargs)
                    self.assertTrue(motors.stopped_confirmed)
                    reads_when_confirmed.append(len(client.feedback_reads))
                    # Any redundant post-transaction read would consume this and
                    # incorrectly turn the completed stop into a recheck ACK.
                    client.feedback_samples.append((-1, 0, 0, 0))

                request = {"seq": session.next_seq, "token": session.token,
                           "cmd": command, "left": 0, "right": 0}
                with patch.object(motors, method, side_effect=confirmed):
                    ack, closing = session.accept(request)
                self.assertTrue(ack["stopped"])
                self.assertTrue(ack["targetStopped"])
                self.assertFalse(ack["feedbackRecheck"])
                self.assertEqual(closing, command in ("close", "estop"))
                self.assertEqual(len(client.feedback_reads), reads_when_confirmed[0])
                self.assertEqual(len(client.feedback_samples), 1)
                self.assertFalse(any(call[0] == "write" for call in client.calls))

    def test_terminal_refreshes_stale_feedback_before_acknowledging_stop_writes(self):
        for command in ("close", "estop"):
            with self.subTest(command=command):
                client = FakeClient()
                motors = bridge.Motors(client)
                session = bridge.ProtocolSession(motors, port="fake", dry_run=False)
                session.ready()
                method = "shutdown"
                original = getattr(motors, method)

                def stale(*args, **kwargs):
                    original(*args, **kwargs)
                    self.clock.sleep(.251)

                seq, token = session.next_seq, session.token
                request = {"seq": seq, "token": token, "cmd": command, "left": 0, "right": 0}
                with patch.object(motors, method, side_effect=stale):
                    ack, closing = session.accept(request)
                self.assertTrue(closing); self.assertTrue(ack['stopCommandConfirmed'])
                self.assertEqual(ack['feedbackAgeMs'], 0)
                self.assertEqual(session.next_seq, seq + 1)
                self.assertNotEqual(session.token, token)
                self.assertFalse(motors.stopped_confirmed)
                self.assertFalse(any(call[0] == "write" for call in client.calls))

    def test_run_loop_retries_cleanup_when_terminal_stop_writes_were_not_confirmed(self):
        messages = []
        original_accept = bridge.ProtocolSession.accept

        class Output:
            def __init__(self, _fd):
                pass

            def send(self, message):
                messages.append(message)

        class Input:
            def __init__(self, _fd):
                pass

            def line(self, _deadline, _stopped):
                return json.dumps({"seq": 1, "token": messages[0]["token"],
                                   "cmd": "close", "left": 0, "right": 0}).encode()

        def unconfirmed(session, message):
            ack, closing = original_accept(session, message)
            return dict(ack, stopped=False, stopCommandConfirmed=False), closing

        with patch.object(bridge, "JsonOutput", Output), patch.object(bridge, "InputPump", Input), \
                patch.object(bridge.ProtocolSession, "accept", unconfirmed), \
                patch.object(self.motors, "shutdown", wraps=self.motors.shutdown) as shutdown:
            bridge.run_session(self.motors, "fake", True, threading.Event(),
                               diagnostic=bridge.NullDiagnosticLog())
        self.assertEqual(shutdown.call_count, 2,
                         "the defensive finalizer must not treat closing=True alone as confirmation")
        self.assertTrue(self.motors.stopped_confirmed)
        self.assert_no_parameter_writes()

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
        self.client.feedback = (0, 3, 0, 0)
        self.clock.sleep(.101)
        with self.assertRaises(bridge.SessionError):
            self.motors.poll_safety()
        self.assert_emergency_attempted()
        self.assertFalse(self.motors.stopped_confirmed)
        self.assert_no_parameter_writes()

    def test_deceleration_timeout_keeps_an_emergency_stopped_session_waiting(self):
        self.initialize()
        self.motors.drive(20, -20)
        self.client.feedback = (81, -20, 0, 0)
        self.motors.stop()
        self.assertFalse(self.motors.stopped_confirmed)
        for _ in range(14):
            self.clock.sleep(.1)
            self.motors.poll_safety()
            self.assertFalse(self.motors.stopped_confirmed)
        self.clock.sleep(.2)
        self.motors.poll_safety()
        self.assertTrue(self.motors.standstill_pending)
        self.assertIsNotNone(self.motors.standstill_wait_at)
        self.assertIsNone(self.motors.fault_reason)
        self.assertFalse(self.motors.stopped_confirmed)
        self.assertFalse(self.motors.parking_enabled)
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
