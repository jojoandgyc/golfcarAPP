"""Runtime feedback tolerance: fake UART and clock only, never real hardware."""
import copy
import threading
import unittest
from unittest.mock import patch

import app_joystick_bridge as bridge
from test_app_joystick_bridge import FakeClient, FakeClock
from test_app_joystick_standstill_wait import Diagnostic


class FeedbackNoiseTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        for patcher in (patch.object(bridge.time, 'monotonic', self.clock.monotonic),
                        patch.object(bridge.time, 'sleep', self.clock.sleep)):
            patcher.start(); self.addCleanup(patcher.stop)
        self.client = FakeClient()
        self.original = copy.deepcopy(self.client.registers)
        self.motors = bridge.Motors(self.client)
        self.diagnostic = Diagnostic(); self.motors.diagnostic = self.diagnostic
        self.session = bridge.ProtocolSession(self.motors, port='fake', dry_run=False)
        self.ready = self.session.ready()
        self.client.calls.clear()

    def request(self, command='stop', left=0, right=0):
        return self.session.accept(dict(seq=self.session.next_seq, token=self.session.token,
            cmd=command, left=left, right=right))[0]

    def assert_currents_preserved(self):
        self.assertEqual(self.client.registers, self.original)
        self.assertFalse(any(call[0] in ('write', 'fc10') for call in self.client.calls))

    def test_ready_advertises_disabled_recheck_timer_and_nonfatal_noise(self):
        self.assertTrue(self.ready['feedbackNoiseNonFatal'])
        self.assertEqual(self.ready['feedbackNoiseMaxRpm'], 80)
        self.assertEqual(self.ready['motionRecheckMs'], 0)
        self.assertEqual((self.ready['watchdogMs'], self.ready['networkGraceMs']),
                         (600, int(bridge.NETWORK_GRACE_SECONDS * 1000)))
        self.assertTrue(self.ready['stopped'])

    def test_alternating_noise_over_three_seconds_never_locks_new_joystick(self):
        for index in range(32):
            self.clock.sleep(.101)
            left, right = ((-1, 0), (0, 1), (2, -2), (1, 0))[index % 4]
            self.client.feedback = (left, right, 0, 0)
            ack = self.request()
            self.assertFalse(ack['stopped'])
            self.assertEqual((ack['feedbackLeftRpm'], ack['feedbackRightRpm']), (left, right))
            for field in ('feedbackRecheck', 'recoveryRequired', 'standstillPending'):
                self.assertFalse(ack[field], field)
            self.assertIsNone(self.motors.motion_recheck_at)
            self.assertFalse(self.motors.motion_recovery_required)
            self.assertIsNone(self.motors.fault_reason)
        ack = self.request('drive', 35, -35)
        self.assertEqual((ack['left'], ack['right']), (35, -35))
        self.assertIn(('left', 35), self.client.calls); self.assertIn(('right', -35), self.client.calls)
        warnings = [event for event in self.diagnostic.events if event['event'] == 'drive.feedback_noise_warning']
        self.assertGreaterEqual(len(warnings), 3); self.assertLessEqual(len(warnings), 4)
        self.assertTrue(all(not event['critical'] for event in warnings))
        self.assertTrue(all(b['at'] - a['at'] >= 1 for a, b in zip(warnings, warnings[1:])))
        self.assert_currents_preserved()

    def test_noise_after_normal_drive_does_not_trigger_old_one_point_five_second_wait(self):
        self.request('drive', 20, -20)
        self.client.feedback = (1, -1, 0, 0)
        for _ in range(25):
            self.clock.sleep(.101); ack = self.request()
            self.assertFalse(ack['standstillPending']); self.assertFalse(ack['stopped'])
        ack = self.request('drive', 40, -40)
        self.assertEqual((ack['left'], ack['right']), (40, -40))
        self.assert_currents_preserved()

    def test_late_zero_and_duplicate_reads_do_not_expire_or_falsely_confirm_stop(self):
        self.clock.sleep(.101); self.client.feedback = (-1, 0, 0, 0); self.request()
        started = self.clock.now
        self.clock.now = started + .55; self.client.feedback = (0, 0, 0, 0)
        first = self.request(); self.assertEqual(first['feedbackSamples'], 1); self.assertFalse(first['stopped'])
        self.clock.now = started + .601
        for _ in range(3):
            ack = self.request(); self.assertFalse(ack['stopped']); self.assertEqual(ack['feedbackSamples'], 1)
        self.clock.now = started + .661
        ack = self.request(); self.assertTrue(ack['stopped']); self.assertEqual(ack['feedbackSamples'], 2)
        self.assertFalse(ack['recoveryRequired']); self.assertIsNone(self.motors.fault_reason)
        self.assert_currents_preserved()

    def test_feedback_read_crossing_old_deadline_is_not_a_hardware_fault(self):
        self.clock.sleep(.101); self.client.feedback = (1, 0, 0, 0); self.motors.poll_safety()
        self.clock.sleep(.59)
        original = self.client.read_holding_registers
        def delayed(address, count):
            self.clock.sleep(.02); self.client.feedback = (0, 0, 0, 0)
            return original(address, count)
        with patch.object(self.client, 'read_holding_registers', side_effect=delayed):
            self.motors._sample_feedback(force=True)
        self.assertIsNone(self.motors.fault_reason); self.assertEqual(self.motors.zero_samples, 1)
        self.assertFalse(self.motors.stopped_confirmed); self.assertFalse(self.motors.feedback_recovery_required)

    def test_larger_motion_soft_stops_then_neutral_releases_without_replaying_held_input(self):
        self.clock.sleep(.101); self.client.feedback = (81, -3, 0, 0)
        ack = self.request('drive', 40, -40)
        self.assertEqual((ack['left'], ack['right']), (0, 0))
        self.assertTrue(ack['standstillPending']); self.assertTrue(ack['recoveryRequired'])
        self.assertTrue(ack['feedbackRecheck']); self.assertFalse(ack['stopped'])
        self.assertIsNone(self.motors.fault_reason)
        self.assertIn(('left', 'stop', 1), self.client.calls)
        self.assertNotIn(('left', 40), self.client.calls)
        event = next(event for event in self.diagnostic.events if event['event'] == 'drive.standstill_wait_started')
        self.assertEqual(event['reason'], 'unexpected_motion_after_stop')
        self.client.feedback = (0, 0, 0, 0)
        for _ in range(2):
            self.clock.sleep(.11); ack = self.request('drive', 40, -40)
        self.assertTrue(ack['stopped']); self.assertTrue(ack['recoveryRequired'])
        self.assertEqual((ack['left'], ack['right']), (0, 0))
        released = self.request(); self.assertTrue(released['recoveryRequired'])
        fresh = self.request('drive', 20, -20)
        self.assertFalse(fresh['recoveryRequired']); self.assertEqual((fresh['left'], fresh['right']), (20, -20))
        self.assert_currents_preserved()

    def test_larger_motion_after_zero_before_neutral_rearms_only_soft_wait(self):
        self.clock.sleep(.101); self.client.feedback = (81, -3, 0, 0); self.request()
        self.client.feedback = (0, 0, 0, 0)
        for _ in range(2): self.clock.sleep(.11); self.request('drive', 40, -40)
        self.assertIsNone(self.motors.standstill_wait_at); self.assertTrue(self.motors.standstill_pending)
        self.client.feedback = (81, 0, 0, 0); self.clock.sleep(.11); ack = self.request('drive', 40, -40)
        self.assertIsNotNone(self.motors.standstill_wait_at); self.assertIsNone(self.motors.fault_reason)
        self.assertTrue(ack['standstillPending']); self.assertFalse(ack['stopped'])
        self.assertEqual((ack['left'], ack['right']), (0, 0))
        self.assertEqual(sum(e['event'] == 'drive.standstill_wait_restarted' for e in self.diagnostic.events), 1)
        self.assert_currents_preserved()

    def test_real_fault_error_or_malformed_feedback_still_emergency_stops(self):
        for kind in ('motor_error', 'read_failure', 'malformed'):
            with self.subTest(kind=kind):
                client = FakeClient(); motors = bridge.Motors(client); motors.initialize(); client.calls.clear()
                if kind == 'motor_error': client.feedback = (1, -1, 2, 0)
                elif kind == 'read_failure': client.feedback_failure = TimeoutError('simulated')
                else: client.feedback_samples.append([0, 0])
                self.clock.sleep(.101)
                with self.assertRaises(bridge.SessionError): motors.poll_safety()
                self.assertIsNotNone(motors.fault_reason)
                self.assertIn(('left', 0), client.calls); self.assertIn(('right', 0), client.calls)
                self.assertIn(('left', 'stop', 1), client.calls); self.assertIn(('right', 'stop', 1), client.calls)
                self.assertEqual(client.registers, self.original)

    def test_soft_stop_write_failure_remains_a_hard_fault(self):
        self.client.feedback = (81, 0, 0, 0); self.client.fail_left = True; self.clock.sleep(.101)
        with self.assertRaisesRegex(bridge.SessionError, 'Standstill emergency stop failed'):
            self.request('drive', 40, -40)
        self.assertIsNotNone(self.motors.fault_reason)
        self.assertIn(('right', 'stop', 1), self.client.calls); self.assert_currents_preserved()

    def test_startup_and_terminal_noise_stay_truthful_without_fatal_zero_confirmation(self):
        client = FakeClient(); client.feedback = (1, 0, 0, 0); motors = bridge.Motors(client)
        started = self.clock.now
        motors.initialize()
        self.assertFalse(motors.stopped_confirmed)
        self.assertTrue(motors.stop_command_confirmed)
        self.assertLessEqual(self.clock.now - started, 1.61)
        self.client.feedback = (1, -1, 0, 0); started = self.clock.now
        ack = self.request('close')
        self.assertTrue(ack['stopCommandConfirmed']); self.assertFalse(ack['stopped'])
        self.assertLessEqual(self.clock.now - started, 1.61); self.assert_currents_preserved()

    def test_warning_activity_does_not_renew_network_motion_or_session_lease(self):
        messages = []; epoch = [None]; clock = self.clock; client = self.client
        diagnostic = Diagnostic()
        class Output:
            def __init__(self, _fd): pass
            def send(self, message):
                messages.append(message)
                if message['type'] == 'ready':
                    epoch[0] = clock.now; client.feedback = (1, -1, 0, 0)
        class Input:
            def __init__(self, _fd): pass
            def line(self, deadline, _stopped):
                while clock.now + .02 < deadline:
                    clock.sleep(.02); self.poll()
                clock.now = deadline; self.poll()
                raise bridge.MotionLeaseExpired('no authenticated requests')
        with patch.object(bridge, 'JsonOutput', Output), patch.object(bridge, 'InputPump', Input):
            result = bridge.run_session(self.motors, 'fake', False, threading.Event(), diagnostic=diagnostic)
        self.assertEqual(result, 1)
        paused = next(event for event in diagnostic.events if event['event'] == 'drive.network_paused')
        failed = next(event for event in diagnostic.events if event['event'] == 'drive.failure')
        self.assertAlmostEqual(paused['at'] - epoch[0], .6)
        self.assertAlmostEqual(failed['at'] - epoch[0], bridge.NETWORK_GRACE_SECONDS)
        self.assertGreaterEqual(sum(e['event'] == 'drive.feedback_noise_warning' for e in diagnostic.events), 4)
        self.assertEqual([message['type'] for message in messages], ['ready', 'error'])
        self.assertIn('network grace expired', messages[-1]['error']); self.assert_currents_preserved()


if __name__ == '__main__': unittest.main()
