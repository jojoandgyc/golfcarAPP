"""Offline protocol and safety tests. Never opens a UART or changes a real motor."""
from contextlib import contextmanager, redirect_stdout
import io
import json
import os
import struct
import threading
import unittest
from unittest.mock import patch

import app_motor_config_bridge as bridge


def request(operation='snapshot', **extra):
    return dict(schemaVersion=1, op=operation, requestId='offline-test', **extra)


class Guard:
    def __init__(self, busy=False):
        self.busy, self.held, self.openings = busy, False, 0
        self.lock = threading.Lock()

    def is_root(self):
        return True

    @contextmanager
    def idle_guard(self):
        if self.busy or not self.lock.acquire(False):
            raise bridge.ConfigError('control_lock_busy_or_unavailable')
        self.held = True
        try:
            yield
        finally:
            self.held = False
            self.lock.release()


class InjectPort(bridge.SimulatedPort):
    def __init__(self, mode=None, write_number=1):
        super().__init__()
        self.mode, self.write_number, self.write_count, self.read_after_write = mode, write_number, 0, False

    def write(self, payload, deadline):
        super().write(payload, deadline)
        if payload[1] == 16:
            self.write_count += 1
            self.read_after_write = self.write_count == self.write_number
            if self.read_after_write:
                if self.mode == 'ack_timeout':
                    self.buffer = b''
                elif self.mode == 'ack_crc':
                    self.buffer = self.buffer[:-1] + bytes((self.buffer[-1] ^ 1,))
                elif self.mode == 'ack_mismatch':
                    self.buffer = bridge.frame(self.buffer[:4] + b'\0\2')
        elif self.read_after_write:
            self.read_after_write = False
            if self.mode == 'readback_timeout':
                self.buffer = b''
            elif self.mode == 'readback_mismatch':
                value = (self.buffer[4] + 1) & 255
                self.buffer = bridge.frame(self.buffer[:4] + bytes((value,)) + self.buffer[5:-2])


class MotorConfigurationTests(unittest.TestCase):
    def run_request(self, req, port=None, guard=None):
        port, guard = port or bridge.SimulatedPort(), guard or Guard()
        def factory():
            self.assertTrue(guard.held)
            guard.openings += 1
            return port
        result = bridge.process(req, system=guard, transport_factory=factory, pause=lambda _: None)
        return result, port, guard

    def write_request(self, changes=None, port=None):
        current, _, _ = self.run_request(request(), port)
        self.assertTrue(current['ok'], current)
        snapshot = current['snapshot']
        fields = {field['id']: field['raw'] for field in snapshot['fields']}
        changes = changes or {'max_output_current': [1200]}
        return request('write', snapshotId=snapshot['snapshotId'], changes=[
            dict(id=key, expectedRaw=fields[key], raw=raw) for key, raw in changes.items()])

    def test_crc_known_modbus_vector_and_fragmented_response(self):
        self.assertEqual(bridge.crc16(bytes.fromhex('01030000000A')), 0xCDC5)
        class Fragmented(bridge.SimulatedPort):
            def read(self, count, deadline):
                return super().read(min(count, 1), deadline)
        port = Fragmented()
        self.assertEqual(bridge.Modbus(port).read(0, 3), [0x30EA, 0x100, 3000])
        self.assertEqual(port.requests[0][1], 3)

    def test_corrupt_read_crc_poison_session_without_retry(self):
        class Corrupt(bridge.SimulatedPort):
            def write(self, payload, deadline):
                super().write(payload, deadline)
                self.buffer = self.buffer[:-1] + bytes((self.buffer[-1] ^ 1,))
        port = Corrupt()
        client = bridge.Modbus(port)
        with self.assertRaisesRegex(bridge.ConfigError, 'crc_mismatch'):
            client.read(0, 3)
        with self.assertRaisesRegex(bridge.ConfigError, 'serial_session_unusable'):
            client.read(0, 3)
        self.assertEqual(len(port.requests), 1)

    def test_modbus_exception_and_wrong_slave_do_not_become_values(self):
        for body, code in [(b'\1\x83\2', 'device_exception_2'), (b'\2\3\6' + b'\0'*6, 'response_mismatch')]:
            class Replaced(bridge.SimulatedPort):
                def write(self, payload, deadline):
                    super().write(payload, deadline)
                    self.buffer = bridge.frame(body)
            result, _, _ = self.run_request(request(), Replaced())
            self.assertFalse(result['ok'])
            self.assertEqual(result['error']['code'], code)
            self.assertIsNone(result['snapshot'])

    def test_snapshot_contains_both_motor_speeds_and_never_claims_disabled(self):
        port = bridge.SimulatedPort()
        port.registers[0x13], port.registers[0x14] = 0xFFFF, 0xFFFB
        result, port, guard = self.run_request(request(), port)
        self.assertTrue(result['ok'])
        snapshot = result['snapshot']
        self.assertEqual(snapshot['observation']['leftSpeedRpm'], -5)
        self.assertFalse(snapshot['gate']['writeAllowed'])
        self.assertIsNone(snapshot['gate']['disabledVerified'])
        self.assertEqual(snapshot['gate']['samples'], 2)
        self.assertTrue(port.closed)
        self.assertFalse(guard.held)
        self.assertTrue(all(payload[1] == 3 for payload in port.requests))

    def test_write_uses_fc10_then_readback_and_preserves_other_registers(self):
        result, port, _ = self.run_request(self.write_request())
        self.assertTrue(result['ok'], result)
        self.assertEqual(result['results'][0]['status'], 'verified')
        self.assertEqual(result['results'][0]['actualRaw'], [1200])
        writes = [payload for payload in port.requests if payload[1] != 3]
        self.assertEqual(len(writes), 1)
        self.assertEqual(writes[0][1], 16)
        self.assertEqual(struct.unpack('>HH', writes[0][2:6]), (0x58, 1))
        self.assertEqual(port.registers[0x40], 0)
        self.assertEqual(port.registers[0x44], 0)
        self.assertEqual(port.registers[0x6D], 0)
        self.assertEqual(port.registers[0x76], 0)
        self.assertNotEqual(result['snapshot']['snapshotId'], self.write_request()['snapshotId'])

    def test_noop_does_not_write_eeprom(self):
        result, port, _ = self.run_request(self.write_request({'max_output_current': [1000]}))
        self.assertTrue(result['ok'])
        self.assertFalse(result['writeMayHaveOccurred'])
        self.assertFalse(any(payload[1] == 16 for payload in port.requests))

    def test_failure_classification_stops_remaining_writes(self):
        cases = [('ack_timeout', 'write_outcome_unknown'), ('ack_crc', 'write_outcome_unknown'),
                 ('ack_mismatch', 'write_outcome_unknown'), ('readback_timeout', 'acknowledged_unverified'),
                 ('readback_mismatch', 'acknowledged_unverified')]
        for mode, expected in cases:
            with self.subTest(mode=mode):
                changes = {'max_output_current': [1200], 'closed_loop_acceleration': [250]}
                result, port, guard = self.run_request(self.write_request(changes), InjectPort(mode))
                self.assertFalse(result['ok'])
                self.assertTrue(result['writeMayHaveOccurred'])
                self.assertEqual([row['status'] for row in result['results']], [expected, 'not_attempted'])
                self.assertEqual(port.write_count, 1)
                self.assertEqual(port.registers[0x58], 1200)  # Timeout is not proof no write occurred.
                self.assertEqual(port.registers[0x5F], 200)
                self.assertIsNone(result['snapshot'])
                self.assertTrue(port.closed)
                self.assertFalse(guard.held)

    def test_partial_success_is_retained_before_second_failure(self):
        changes = {'max_output_current': [1200], 'closed_loop_acceleration': [250], 'closed_loop_deceleration': [350]}
        result, port, _ = self.run_request(self.write_request(changes), InjectPort('ack_timeout', 2))
        self.assertEqual([row['status'] for row in result['results']], ['verified', 'write_outcome_unknown', 'not_attempted'])
        self.assertEqual(port.write_count, 2)

    def test_invalid_ranges_bool_nonintegers_and_readonly_ids_reject_before_open(self):
        cases = [('max_output_current', [49]), ('max_output_current', [6001]),
                 ('closed_loop_acceleration', [9]), ('max_forward_speed', [1, 50000]),
                 ('max_output_current', [True]), ('max_output_current', [1200.0]),
                 ('left_parking_current', [0]), ('system_mode', [4]), ('rs485_address', [2]),
                 ('left_stop', [1]), ('factory_reset_command', [1]), ('test_control', [1])]
        for field, raw in cases:
            with self.subTest(field=field, raw=raw):
                req = request('write', snapshotId='0'*64, changes=[dict(id=field, raw=raw, expectedRaw=[1000])])
                result, port, guard = self.run_request(req)
                self.assertFalse(result['ok'])
                self.assertEqual(guard.openings, 0)
                self.assertEqual(port.requests, [])

    def test_raw_control_addresses_and_combined_ranges_are_statically_denied(self):
        port = bridge.SimulatedPort()
        client = bridge.Modbus(port)
        for address, words in [(0x40, [1]), (0x41, [0, 100]), (0x43, [1]),
                               (0x48, [1]), (0x49, [1]), (0xC9, [0]), (0x58, [1000, 0, 100])]:
            with self.assertRaisesRegex(bridge.ConfigError, 'register_write_not_allowed'):
                client.write(address, words)
        self.assertEqual(port.requests, [])

    def test_stale_snapshot_and_wrong_expected_value_never_write(self):
        req = self.write_request()
        for alter in ('snapshot', 'expected'):
            changed = json.loads(json.dumps(req))
            if alter == 'snapshot':
                changed['snapshotId'] = '0'*64
            else:
                changed['changes'][0]['expectedRaw'] = [999]
            result, port, _ = self.run_request(changed)
            self.assertFalse(result['ok'])
            self.assertFalse(result['writeMayHaveOccurred'])
            self.assertTrue(all(row['status'] == 'not_attempted' for row in result['results']))
            self.assertFalse(any(payload[1] == 16 for payload in port.requests))

    def test_hardware_max_and_both_parking_currents_limit_max_current(self):
        for side in (0x6D, 0x76):
            port = bridge.SimulatedPort()
            port.registers[side] = 900
            req = self.write_request({'max_output_current': [800]}, port)
            again = bridge.SimulatedPort()
            again.registers[side] = 900
            result, actual, _ = self.run_request(req, again)
            self.assertEqual(result['error']['code'], 'current_below_parking_current')
            self.assertFalse(any(payload[1] == 16 for payload in actual.requests))
        result, port, _ = self.run_request(self.write_request({'max_output_current': [3100]}))
        self.assertEqual(result['error']['code'], 'device_current_limit_exceeded')
        self.assertFalse(any(payload[1] == 16 for payload in port.requests))

    def test_motion_pwm_or_external_source_rejects_write(self):
        for address, value, code in [(0xA, 1, 'motor_motion_observed'), (0x16, 1, 'motor_pwm_active'),
                                     (0x1E, 1, 'external_control_source')]:
            port = bridge.SimulatedPort()
            port.registers[address] = value
            result, port, _ = self.run_request(self.write_request(), port)
            self.assertEqual(result['error']['code'], code)
            self.assertFalse(result['writeMayHaveOccurred'])

    def test_busy_and_concurrent_sessions_never_open_serial(self):
        guard = Guard(True)
        result, _, guard = self.run_request(request(), guard=guard)
        self.assertFalse(result['ok'])
        self.assertEqual(guard.openings, 0)
        guard = Guard()
        with guard.idle_guard():
            result, _, guard = self.run_request(request(), guard=guard)
            self.assertFalse(result['ok'])
            self.assertEqual(guard.openings, 0)

    def test_check_never_opens_uart(self):
        with patch.object(bridge, 'SerialPort', side_effect=AssertionError('real UART opened')):
            result = bridge.check(Guard())
        self.assertTrue(result['ok'])
        self.assertFalse(result['serialOpened'])

    @unittest.skipUnless(os.name == 'posix', 'POSIX UART ioctl sequence, mocked FD')
    def test_post_open_race_releases_only_new_exclusion_before_configuring_uart(self):
        import termios
        import fcntl
        for prior in (0, 1):
            calls = []
            def ioctl(fd, operation, *args):
                calls.append(operation)
                if operation == getattr(termios, 'TIOCGEXCL', 0x80045440):
                    return struct.pack('I', prior)
                return 0
            def reject_owner():
                raise bridge.ConfigError('motor_serial_in_use')
            with patch.object(bridge.os, 'open', return_value=999), \
                 patch.object(bridge.os, 'close') as close, \
                 patch.object(fcntl, 'ioctl', side_effect=ioctl), \
                 patch.object(termios, 'tcsetattr', side_effect=AssertionError('configured competing UART')):
                with self.assertRaisesRegex(bridge.ConfigError, 'motor_serial_in_use'):
                    bridge.SerialPort(owner_check=reject_owner)
            self.assertEqual(termios.TIOCEXCL in calls, not bool(prior))
            self.assertEqual(termios.TIOCNXCL in calls, not bool(prior))
            close.assert_called_once_with(999)

    def test_simulation_never_loads_guard_or_real_uart_and_stdout_is_single_json(self):
        with patch.object(bridge, 'load_system', side_effect=AssertionError('guard loaded')), \
             patch.object(bridge, 'SerialPort', side_effect=AssertionError('real UART opened')), \
             redirect_stdout(io.StringIO()) as output:
            code = bridge.main(['--simulate'], stdin=io.BytesIO(json.dumps(request()).encode()))
        self.assertEqual(code, 0)
        self.assertEqual(len(output.getvalue().splitlines()), 1)
        result = json.loads(output.getvalue())
        self.assertTrue(result['simulation'])
        self.assertTrue(result['ok'])

    def test_json_duplicates_nan_extra_fields_and_oversize_are_rejected(self):
        for data in [b'{"op":"snapshot","op":"write"}', b'{"bad":NaN}', b'x'*8193]:
            with self.assertRaises(bridge.ConfigError):
                bridge.read_request(io.BytesIO(data))
        result, _, guard = self.run_request(dict(request(), port='/dev/ttyS3'))
        self.assertEqual(result['error']['code'], 'unexpected_request_fields')
        self.assertEqual(guard.openings, 0)


if __name__ == '__main__':
    unittest.main()
