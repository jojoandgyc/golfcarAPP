"""Stop/ready tolerance uses fake serial and time only; never operates a car."""
import copy
import threading
import unittest
from unittest.mock import patch
import app_joystick_bridge as bridge
from test_app_joystick_bridge import FakeClient, FakeClock
from test_app_joystick_standstill_wait import Diagnostic


class StopToleranceTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        for p in (patch.object(bridge.time, 'monotonic', self.clock.monotonic),
                  patch.object(bridge.time, 'sleep', self.clock.sleep)):
            p.start(); self.addCleanup(p.stop)
        self.client = FakeClient()
        self.original = copy.deepcopy(self.client.registers)
        self.motors = bridge.Motors(self.client)
        self.log = Diagnostic(); self.motors.diagnostic = self.log

    def ready(self):
        self.session = bridge.ProtocolSession(self.motors, port='fake', dry_run=False)
        return self.session.ready()

    def request(self, command='stop', left=0, right=0):
        return self.session.accept(dict(seq=self.session.next_seq, token=self.session.token,
            cmd=command, left=left, right=right))

    def assert_settings_unchanged(self):
        self.assertEqual(self.original, self.client.registers)
        self.assertFalse(any(call[0] in ('write', 'fc10') for call in self.client.calls))

    def test_startup_noise_returns_ready_without_claiming_physical_zero(self):
        self.client.feedback = (2, -2, 0, 0)
        ready = self.ready()
        self.assertTrue(ready['stopConfirmationWarningSupported'])
        self.assertTrue(ready['stopCommandConfirmed'])
        self.assertFalse(ready['stopped'])
        self.assertEqual((ready['feedbackLeftRpm'], ready['feedbackRightRpm']), (2, -2))
        self.assertIsNone(self.motors.fault_reason)
        moving, _ = self.request('drive', 30, -30)
        self.assertEqual((moving['left'], moving['right']), (30, -30))
        self.assert_settings_unchanged()

    def test_startup_larger_feedback_timeout_keeps_ready_and_raw_feedback(self):
        self.client.feedback = (81, -30, 0, 0)
        started = self.clock.now
        ready = self.ready()
        self.assertFalse(ready['stopped']); self.assertTrue(ready['stopCommandConfirmed'])
        self.assertEqual(ready['feedbackLeftRpm'], 81)
        self.assertIsNone(self.motors.fault_reason)
        self.assertLess(self.clock.now - started, 4)
        self.assertTrue(any(e['event'] == 'drive.stop_confirmation_warning' for e in self.log.events))
        self.assert_settings_unchanged()

    def test_runtime_eighty_boundary_keeps_session_and_new_input_available(self):
        ready = self.ready(); self.assertEqual(ready['feedbackNoiseMaxRpm'], 80)
        for left, right in ((80, -80), (-80, 80), (7, -3), (0, 80)):
            self.clock.sleep(.11); self.client.feedback = (left, right, 0, 0)
            ack, closing = self.request()
            self.assertFalse(closing); self.assertFalse(ack['stopped'])
            self.assertFalse(ack['recoveryRequired']); self.assertFalse(ack['standstillPending'])
            self.assertEqual((ack['feedbackLeftRpm'], ack['feedbackRightRpm']), (left, right))
            moving, _ = self.request('drive', 30, -30)
            self.assertEqual((moving['left'], moving['right']), (30, -30))
            self.request()
        self.assert_settings_unchanged()

    def test_eighty_one_still_soft_stops_without_ending_session(self):
        self.ready(); self.clock.sleep(.11); self.client.feedback = (81, 0, 0, 0)
        ack, closing = self.request('drive', 30, -30)
        self.assertFalse(closing); self.assertTrue(ack['standstillPending'])
        self.assertEqual((ack['left'], ack['right']), (0, 0))
        self.assertIsNone(self.motors.fault_reason)

    def test_close_and_emergency_accept_command_ack_without_inventing_standstill(self):
        for command in ('close', 'estop'):
            for rpm in (2, 60, 81):
                with self.subTest(command=command, rpm=rpm):
                    self.client.feedback = (0, 0, 0, 0); self.motors = bridge.Motors(self.client)
                    self.ready(); self.client.feedback = (rpm, -rpm, 0, 0)
                    ack, closing = self.request(command)
                    self.assertTrue(closing); self.assertTrue(ack['stopCommandConfirmed'])
                    self.assertFalse(ack['stopped'])
                    self.assertEqual(ack['feedbackLeftRpm'], rpm)
                    self.assertIsNone(self.motors.fault_reason)
                    self.assert_settings_unchanged()

    def test_terminal_serial_write_or_motor_error_still_reports_failure(self):
        for kind in ('write', 'read', 'fault'):
            with self.subTest(kind=kind):
                self.client = FakeClient(); self.motors = bridge.Motors(self.client); self.ready()
                if kind == 'write': self.client.fail_left = True
                elif kind == 'read': self.client.feedback_failure = TimeoutError('fake')
                else: self.client.feedback = (1, 0, 4, 0)
                with self.assertRaises(bridge.SessionError): self.request('close')
                self.assert_settings_unchanged()

    def test_run_close_returns_success_with_explicit_unconfirmed_physical_stop(self):
        messages = []; client = self.client; clock = self.clock; diagnostic = self.log
        class Output:
            def __init__(self, _fd): pass
            def send(self, message): messages.append(message)
        class Input:
            def __init__(self, _fd): pass
            def line(self, _deadline, _stopped):
                client.feedback = (60, -30, 0, 0)
                return bridge.json.dumps(dict(seq=1, token=messages[0]['token'],
                    cmd='close', left=0, right=0)).encode()
        with patch.object(bridge, 'JsonOutput', Output), patch.object(bridge, 'InputPump', Input):
            result = bridge.run_session(self.motors, 'fake', False, threading.Event(), diagnostic=diagnostic)
        self.assertEqual(result, 0)
        self.assertEqual([m['type'] for m in messages], ['ready', 'ack'])
        finished = next(e for e in diagnostic.events if e['event'] == 'drive.finished')
        self.assertFalse(finished['cleanupConfirmed']); self.assertTrue(finished['stopCommandConfirmed'])
        self.assert_settings_unchanged()


if __name__ == '__main__': unittest.main()
