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
    if operation == 'write': extra.setdefault('writeMode', 'persistent')
    return dict(schemaVersion=2, op=operation, requestId='offline-test', **extra)


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
                 ('left_parking_current', [6001]), ('system_mode', [2]), ('rs485_address', [256]),
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
                               (0x48, [1]), (0x49, [1]), (0xCC, [0]), (0x58, [1000, 0, 100])]:
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
        for data in [b'{"op":"snapshot","op":"write"}', b'{"bad":NaN}', b'x'*32769]:
            with self.assertRaises(bridge.ConfigError):
                bridge.read_request(io.BytesIO(data))
        result, _, guard = self.run_request(dict(request(), port='/dev/ttyS3'))
        self.assertEqual(result['error']['code'], 'unexpected_request_fields')
        self.assertEqual(guard.openings, 0)


class CompleteProtocolTests(unittest.TestCase):
    def snapshot(self, port=None, connection=None):
        req = request(**({'connection': connection} if connection else {}))
        return bridge.process(req, simulate=True, transport_factory=lambda: port or bridge.SimulatedPort())['snapshot']

    def write_request(self, values, mode='persistent', port=None, connection=None):
        current = self.snapshot(port, connection)
        fields = {f['id']: f['raw'] for f in current['fields']}
        return request('write', snapshotId=current['snapshotId'], writeMode=mode,
                       **({'connection': connection} if connection else {}),
                       changes=[dict(id=key, expectedRaw=fields[key], raw=raw) for key, raw in values.items()])

    def action_request(self, action_id, value, duration=0, port=None):
        return request('action', snapshotId=self.snapshot(port)['snapshotId'], actionId=action_id,
                       value=value, durationMs=duration, confirmed=True)

    def test_catalog_exact_manual_nonreserved_coverage(self):
        # Independent address inventory from the manual, not generated from the implementation.
        expected = set(range(0x58, 0x61)) | set(range(0x64, 0x67)) | set(range(0x68, 0x7A)) | set(range(0x84, 0x88))
        expected |= set(range(0x89, 0x8D)) | set(range(0xB0, 0xB4)) | set(range(0xC8, 0xCC))
        expected |= set(range(0xCD, 0xD0)) | set(range(0xD2, 0xD6)) | set(range(0xE0, 0x106))
        observed = [r for field in bridge.FIELDS for r in range(field.address, field.address + field.words)]
        self.assertEqual(set(observed), expected)
        self.assertEqual(len(observed), len(set(observed)))
        self.assertEqual(len(bridge.FIELDS), 69)
        self.assertEqual(len(bridge.ACTION_SPECS), 8)
        self.assertEqual({r for a in bridge.ACTION_SPECS for r in range(a['address'],a['address']+a['words'])}, set(range(0x40,0x4A)))
        from motor_register_catalog import READ_ONLY_FIELDS
        self.assertEqual(len(READ_ONLY_FIELDS),22)
        self.assertEqual({r for f in READ_ONLY_FIELDS for r in range(f['address'],f['address']+f['words'])},
            set(range(0,3)) | set(range(8,0xE)) | set(range(0xF,0x18)) | set(range(0x19,0x21)))

    def test_all_69_persistent_and_50_temporary_fields_are_reachable(self):
        seed = self.snapshot()
        raw_seed = {f['id']: f['raw'] for f in seed['fields']}
        for field in bridge.FIELDS:
            if field.choices:
                number = next(value for value,_ in field.choices if value != bridge.number(raw_seed[field.id],field.signed))
            elif field.packed_phase: number = 0x0601
            elif field.signed: number = -150
            elif field.group == 'pid': number = 0xFFFFFFFF
            elif field.id == 'max_output_current': number = 1200
            elif field.id.endswith('parking_current'): number = 100
            elif field.id == 'overvoltage_threshold': number = 59
            elif field.id == 'undervoltage_threshold': number = 9
            else:
                number = max(field.minimum, min(field.maximum, 50))
                if number == bridge.number(raw_seed[field.id]): number += 1
            raw = bridge.encode_number(number,field.words)
            for mode in (('persistent','temporary') if field.supports_temporary else ('persistent',)):
                with self.subTest(field=field.id, mode=mode):
                    result = bridge.process(self.write_request({field.id:raw},mode),simulate=True)
                    expected = 'acknowledged_unverified' if field.id in bridge.LINK_FIELDS else 'verified'
                    self.assertEqual(result['results'][0]['status'],expected,result['error'])
                    if mode == 'temporary' and field.words == 2:
                        order = bridge.temporary_word_order(field,raw_seed[field.id],raw)
                        self.assertEqual(result['simulationWrites'], [dict(function=6,address=field.address+i,raw=[raw[i]]) for i in order])
                    else:
                        self.assertEqual(result['simulationWrites'], [dict(function=16 if mode=='persistent' else 6,address=field.address,raw=raw)])

    def test_temporary_fc06_and_persistent_fc10_have_distinct_device_effects(self):
        for mode,function in (('temporary',6),('persistent',16)):
            port=bridge.SimulatedPort()
            result=bridge.process(self.write_request({'closed_loop_acceleration':[250]},mode),simulate=True,transport_factory=lambda:port)
            self.assertTrue(result['ok'],result['error'])
            self.assertEqual(result['simulationWrites'][0]['function'],function)
            self.assertEqual(port.registers[0x5F],250)
            self.assertEqual(port.persisted[0x5F],250 if mode=='persistent' else 200)
        for field in ('pid_speed_p','pid_hall_parking_speed_p'):
            result=bridge.process(self.write_request({field:[0,99]},'temporary'),simulate=True)
            self.assertEqual(result['error']['code'],'temporary_not_supported')
            self.assertFalse(result['writeMayHaveOccurred'])

    def test_temporary_double_all_three_fields_cross_word_boundaries_without_persistence(self):
        self.assertEqual(sum(f.supports_temporary for f in bridge.FIELDS), 50)
        for field_id in ('max_forward_speed', 'max_reverse_speed', 'min_speed'):
            field = bridge.FIELD_BY_ID[field_id]
            for old, target, first_index in ((100, 200, 0), (65000, 100000, 1), (110000, 65535, 0),
                                              (65535, 65536, 1), (65536, 65535, 0), (0, 110000, 0)):
                with self.subTest(field=field_id, old=old, target=target):
                    class ObservedPort(bridge.SimulatedPort):
                        def __init__(self):
                            super().__init__(); self.intermediates = []
                        def write(self, payload, deadline):
                            super().write(payload, deadline)
                            if payload[1] in (6, 16):
                                self.intermediates.append(bridge.number([self.registers[field.address], self.registers[field.address+1]]))
                    port = ObservedPort()
                    old_words, target_words = bridge.encode_number(old, 2), bridge.encode_number(target, 2)
                    port.registers.update({field.address+i: word for i, word in enumerate(old_words)})
                    port.persisted = dict(port.registers)
                    req = self.write_request({field_id: target_words}, 'temporary', port=port)
                    result = bridge.process(req, simulate=True, transport_factory=lambda: port)
                    self.assertTrue(result['ok'], result['error'])
                    self.assertEqual(result['results'][0]['status'], 'verified')
                    self.assertEqual(result['simulationWrites'], [dict(function=6, address=field.address+i, raw=[target_words[i]])
                                     for i in (first_index, 1-first_index)])
                    self.assertTrue(all(0 <= value <= 110000 for value in port.intermediates))
                    self.assertEqual([port.persisted[field.address+i] for i in range(2)], old_words)
                    self.assertEqual([port.registers[field.address+i] for i in range(2)], target_words)

    def test_temporary_double_ack_read_or_motion_failure_stops_without_retry_or_rollback(self):
        for failure, expected_writes in (('first_ack',1), ('first_read',1), ('moving',1),
                                          ('changed',1), ('second_ack',2), ('second_read',2), ('final_read',2)):
            with self.subTest(failure=failure):
                class FaultPort(bridge.SimulatedPort):
                    def __init__(self):
                        super().__init__(); self.temp_count = 0; self.read_after_second = 0
                    def write(self, payload, deadline):
                        super().write(payload, deadline)
                        if payload[1] == 6:
                            self.temp_count += 1
                            if (failure == 'first_ack' and self.temp_count == 1 or
                                    failure == 'second_ack' and self.temp_count == 2): self.buffer = b''
                            if failure == 'moving' and self.temp_count == 1: self.registers[10] = 20
                            if failure == 'changed' and self.temp_count == 1: self.registers[0x5A] += 1
                        elif payload[1] == 3 and self.temp_count:
                            if failure == 'first_read' and self.temp_count == 1: self.buffer = b''
                            if self.temp_count == 2:
                                self.read_after_second += 1
                                if failure == 'second_read' or failure == 'final_read' and self.read_after_second == 2:
                                    self.buffer = b''
                port = FaultPort()
                req = self.write_request({'max_forward_speed': [1,34464], 'closed_loop_acceleration': [250]}, 'temporary')
                result = bridge.process(req, simulate=True, transport_factory=lambda: port)
                self.assertFalse(result['ok'])
                self.assertTrue(result['writeMayHaveOccurred'])
                self.assertEqual(result['results'][0]['status'], 'write_outcome_unknown')
                self.assertEqual(result['results'][1]['status'], 'not_attempted')
                self.assertEqual(port.temp_count, expected_writes)
                self.assertTrue(all(w['function'] == 6 for w in result['simulationWrites']))
                self.assertEqual(port.persisted[0x59], 0)
                self.assertEqual(port.persisted[0x5A], 100)
                self.assertEqual(port.registers[0x5F], 200)

    def test_temporary_double_rejects_no_safe_intermediate_and_arbitrary_word_access(self):
        port = bridge.SimulatedPort(); port.registers[0x59] = port.registers[0x5A] = 65535
        req = self.write_request({'max_forward_speed': [1,34464]}, 'temporary', port=port)
        result = bridge.process(req, simulate=True, transport_factory=lambda: port)
        self.assertEqual(result['error']['code'], 'temporary_no_safe_word_order')
        self.assertFalse(result['writeMayHaveOccurred'])
        client = bridge.Modbus(bridge.SimulatedPort())
        for address in (0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x61):
            with self.assertRaises(bridge.ConfigError): client.write(address, [1], mode='temporary')

    def test_signed_angles_and_complete_signed_telemetry(self):
        port=bridge.SimulatedPort()
        for a,value in {0xD:0xFFF6,0x17:0xFFFB,0xF:0xFFFF,0x10:0xFFE2,0x19:0x8000,0x1A:0,
                        0x11:1,0x1B:1,0x1F:1,0x20:0,8:123,0x12:456,0x6E:0xFF9C}.items(): port.registers[a]=value
        current=self.snapshot(port)
        o=current['observation']
        self.assertEqual((o['rightTemperatureC'],o['leftTemperatureC']),(-10,-5))
        self.assertEqual((o['rightDisplacementDegrees'],o['leftDisplacementDegrees']),(-30,-2147483648))
        self.assertEqual((o['rightCurrentRaw'],o['leftCurrentRaw']),(123,456))
        self.assertEqual((o['rightReverse'],o['leftReverse'],o['in1'],o['in2']),(1,1,1,0))
        self.assertEqual(next(f['value'] for f in current['fields'] if f['id']=='right_foc_compensation_angle'),-1)

    def test_phase_bytes_enum_gaps_and_word_width_conflicts_are_not_silently_truncated(self):
        for field,raw in [('right_phase_1_2',[0x0701]),('left_phase_5_6',[0x0107]),('system_mode',[2]),
                          ('differential_turn_max_speed',[65536]),('stall_stop_time',[66635]),('rs485_timeout_s',[66635])]:
            result=bridge.process(self.write_request({field:raw}),simulate=True)
            self.assertFalse(result['ok'])
            self.assertFalse(result['writeMayHaveOccurred'])
        result=bridge.process(self.write_request({'differential_turn_max_speed':[65535]}),simulate=True)
        self.assertTrue(result['ok'])

    def test_pid_precision_signed_words_and_suggested_bounds_are_not_hard_limits(self):
        for field in bridge.FIELDS:
            if field.group == 'pid':
                self.assertEqual(field.words,2)
                self.assertEqual(field.maximum,0xFFFFFFFF)
                self.assertEqual(field.divisor,1 if field.id=='pid_hall_parking_speed_p' else 32768)
        self.assertEqual(bridge.encode_number(-500,2),[65535,65036])
        self.assertEqual(bridge.encode_number(-18000,1),[47536])

    def test_old_out_of_range_configuration_can_be_repaired_using_exact_expected_words(self):
        port=bridge.SimulatedPort();port.registers[0x87]=0
        req=self.write_request({'system_mode':[1]},port=port)
        fresh=bridge.SimulatedPort();fresh.registers[0x87]=0
        result=bridge.process(req,simulate=True,transport_factory=lambda:fresh)
        self.assertTrue(result['ok'],result['error'])

    def test_current_batch_orders_intermediate_states_safely(self):
        port=bridge.SimulatedPort();port.registers[0x6D]=900
        req=self.write_request({'max_output_current':[800],'right_parking_current':[700]},port=port)
        fresh=bridge.SimulatedPort();fresh.registers[0x6D]=900
        result=bridge.process(req,simulate=True,transport_factory=lambda:fresh)
        self.assertTrue(result['ok'],result['error'])
        self.assertEqual([w['address'] for w in result['simulationWrites']],[0x6D,0x58])
        req=self.write_request({'left_parking_current':[1100],'max_output_current':[1200]})
        result=bridge.process(req,simulate=True)
        self.assertTrue(result['ok'],result['error'])
        self.assertEqual([w['address'] for w in result['simulationWrites']],[0x58,0x76])

    def test_communication_change_is_single_field_ack_only_without_old_link_readback(self):
        for field,new in [('rs485_address',2),('rs485_baud_rate_code',3),('rs485_parity_code',1)]:
            port=bridge.SimulatedPort()
            result=bridge.process(self.write_request({field:[new]}),simulate=True,transport_factory=lambda:port)
            self.assertFalse(result['ok'])
            self.assertTrue(result['reconnectRequired'])
            self.assertEqual(result['results'][0]['status'],'acknowledged_unverified')
            self.assertIsNone(result['snapshot'])
            self.assertEqual(port.requests[-1][1],16)
        req=self.write_request({'rs485_address':[2],'closed_loop_acceleration':[250]})
        result=bridge.process(req,simulate=True)
        self.assertEqual(result['error']['code'],'communication_change_requires_single_field')
        self.assertFalse(result['writeMayHaveOccurred'])

    def test_lost_ack_for_link_change_or_factory_reset_still_requires_reconnection(self):
        result=bridge.process(self.write_request({'rs485_address':[2]}),simulate=True,
                              transport_factory=lambda:InjectPort('ack_timeout'))
        self.assertTrue(result['reconnectRequired'])
        self.assertEqual(result['results'][0]['status'],'write_outcome_unknown')
        class ResetAckLost(bridge.SimulatedPort):
            def write(self,payload,deadline):
                super().write(payload,deadline)
                if payload[1]==6 and payload[2:4]==b'\x00H': self.buffer=b''
        result=bridge.process(self.action_request('factory_reset',1),simulate=True,transport_factory=ResetAckLost)
        self.assertTrue(result['reconnectRequired'])
        self.assertTrue(result['actionResult']['reconnectRequired'])
        self.assertEqual(result['actionResult']['outcome'],'write_outcome_unknown')

    def test_limited_connection_parameters_correlate_snapshot_and_frame_slave(self):
        link=dict(slave=42,baudRate=9600,parity='E',stopBits=1)
        snapshot=self.snapshot(connection=link)
        self.assertEqual(snapshot['connection'],link)
        self.assertNotEqual(snapshot['snapshotId'],self.snapshot()['snapshotId'])
        result=bridge.process(self.write_request({'closed_loop_acceleration':[250]},connection=link),simulate=True)
        self.assertTrue(result['ok'])
        for invalid in (dict(link,slave=0),dict(link,baudRate=123),dict(link,parity='E',stopBits=2),dict(link,port='/dev/ttyS3')):
            result=bridge.process(request(connection=invalid),simulate=True)
            self.assertEqual(result['error']['code'],'invalid_connection')

    def test_all_action_interfaces_acknowledge_without_claiming_physical_completion(self):
        for action,value,duration in [('right_stop',0,0),('left_stop',1,0),('right_brake',1,0),('left_brake',0,0),
            ('right_target_speed',20,100),('left_target_speed',-20,100),('factory_reset',1,0),('phase_learning',1,100)]:
            with self.subTest(action=action):
                result=bridge.process(self.action_request(action,value,duration),simulate=True)
                self.assertTrue(result['ok'],result['error'])
                self.assertEqual(result['actionResult']['outcome'],'acknowledged')
                if duration:
                    self.assertTrue(result['actionResult']['cleanupConfirmed'])
                    self.assertEqual(result['simulationWrites'][-1]['raw'],[0])
                elif action=='factory_reset': self.assertTrue(result['reconnectRequired'])

    def test_stops_and_cancel_are_usable_when_motion_or_learning_is_observed(self):
        for action,value in [('right_stop',1),('left_stop',0),('phase_learning',0)]:
            port=bridge.SimulatedPort();port.registers[0xA]=10;port.registers[0x1E]=7
            req=self.action_request(action,value,port=port)
            fresh=bridge.SimulatedPort();fresh.registers[0xA]=10;fresh.registers[0x1E]=7
            result=bridge.process(req,simulate=True,transport_factory=lambda:fresh)
            self.assertTrue(result['ok'],result['error'])
        port=bridge.SimulatedPort();port.registers[0xA]=10
        req=self.action_request('right_brake',1,port=port)
        fresh=bridge.SimulatedPort();fresh.registers[0xA]=10
        result=bridge.process(req,simulate=True,transport_factory=lambda:fresh)
        self.assertFalse(result['writeMayHaveOccurred'])

    def test_actions_require_confirmation_and_bounded_duration_before_any_write(self):
        req=self.action_request('right_target_speed',20,100)
        for changes in ({'confirmed':False},{'durationMs':0},{'durationMs':3001},{'value':110001}):
            result=bridge.process(dict(req,**changes),simulate=True)
            self.assertFalse(result['writeMayHaveOccurred'])
        req=self.action_request('right_target_speed',101,100)
        result=bridge.process(req,simulate=True)
        self.assertEqual(result['error']['code'],'target_exceeds_configured_limit')
        self.assertFalse(result['writeMayHaveOccurred'])

    def test_owner_loss_still_sends_cleanup_and_preserves_first_failure(self):
        req=self.action_request('phase_learning',2,100)
        with patch.object(bridge.ActionLifetime,'wait',side_effect=bridge.ConfigError('action_owner_lost')):
            result=bridge.process(req,simulate=True)
        self.assertFalse(result['ok'])
        self.assertEqual(result['error']['code'],'action_owner_lost')
        self.assertTrue(result['actionResult']['cleanupConfirmed'])
        self.assertEqual([w['raw'] for w in result['simulationWrites']],[[2],[0]])

    def test_differential_turn_cleanup_uses_effective_right_stop_register(self):
        port=bridge.SimulatedPort();port.registers[0x1D]=4;port.registers[0x87]=4;port.registers[0x8A]=100
        req=self.action_request('left_target_speed',20,100,port=port)
        fresh=bridge.SimulatedPort();fresh.registers[0x1D]=4;fresh.registers[0x87]=4;fresh.registers[0x8A]=100
        result=bridge.process(req,simulate=True,transport_factory=lambda:fresh)
        self.assertTrue(result['ok'],result['error'])
        self.assertEqual([w['address'] for w in result['simulationWrites']],[0x45,0x40])
        self.assertEqual(result['snapshot']['observation']['leftSpeedRpm'],0)

    def test_snapshot_batches_only_contiguous_nonreserved_addresses(self):
        port=bridge.SimulatedPort();self.snapshot(port)
        self.assertEqual(len(port.requests),21)
        self.assertTrue(all(frame[1]==3 for frame in port.requests))
        covered={a for start,count in bridge.SNAPSHOT_BLOCKS for a in range(start,start+count)}
        self.assertEqual(covered,{a for f in bridge.FIELDS for a in range(f.address,f.address+f.words)})

    def test_speed_ack_timeout_does_not_retry_target_and_attempts_only_cleanup(self):
        req=self.action_request('right_target_speed',20,100)
        port=InjectPort('ack_timeout')
        result=bridge.process(req,simulate=True,transport_factory=lambda:port)
        self.assertFalse(result['ok'])
        self.assertEqual(result['actionResult']['outcome'],'write_outcome_unknown')
        self.assertTrue(result['actionResult']['cleanupConfirmed'])
        self.assertEqual([w['address'] for w in result['simulationWrites']],[0x41,0x40])

    def test_cleanup_failure_is_not_hidden_by_action_ack(self):
        class CleanupFails(bridge.SimulatedPort):
            def write(self,payload,deadline):
                super().write(payload,deadline)
                if payload[1]==6 and payload[2:4]==b'\x00I' and payload[4:6]==b'\x00\x00': self.buffer=b''
        result=bridge.process(self.action_request('phase_learning',1,100),simulate=True,transport_factory=CleanupFails)
        self.assertFalse(result['ok'])
        self.assertEqual(result['actionResult']['outcome'],'acknowledged')
        self.assertFalse(result['actionResult']['cleanupConfirmed'])
        self.assertEqual(result['error']['code'],'action_cleanup_unconfirmed')

    def test_all_documented_modbus_exceptions_and_fc06_echo_validation(self):
        for function in (3,6,16):
            for code in (1,2,3,4,5,6,8,10,11):
                class ExceptionPort(bridge.SimulatedPort):
                    def write(self,payload,deadline):
                        super().write(payload,deadline)
                        self.buffer=bridge.frame(bytes((1,function|0x80,code)))
                client=bridge.Modbus(ExceptionPort())
                with self.subTest(function=function,code=code), self.assertRaisesRegex(bridge.ConfigError,f'device_exception_{code}$'):
                    if function==3: client.read(0,3)
                    else: client.write(0x5F,[250],mode='temporary' if function==6 else 'persistent')
                self.assertTrue(client.poisoned)
        class WrongEcho(bridge.SimulatedPort):
            def write(self,payload,deadline):
                super().write(payload,deadline)
                self.buffer=bridge.frame(payload[:4]+b'\x00\x01')
        client=bridge.Modbus(WrongEcho())
        with self.assertRaisesRegex(bridge.ConfigError,'write_ack_mismatch'):
            client.write(0x5F,[250],mode='temporary')
        self.assertTrue(client.poisoned)

    def test_manual_wire_examples_use_valid_crc_and_word_order(self):
        # PDF page 16 prints D7 67 for the first example. Standard Modbus CRC and
        # the existing vendor GUI both produce 36 ED; never send the typo.
        for address,raw,expected in ((0x41,[0,1000],'01100041000204000003E836ED'),
                                     (0x45,[65535,65036],'01100045000204FFFFFE0C77E1'),
                                     (0x40,[0],'010600400000881E')):
            port=bridge.SimulatedPort()
            bridge.Modbus(port).write(address,raw,action=True)
            self.assertEqual(port.requests[0].hex().upper(),expected)

    def test_signal_and_parent_loss_cancel_bounded_owner_without_leaving_handlers(self):
        old_handlers={}
        installed={}
        def getsignal(sig):
            old_handlers[sig]='previous-handler'
            return old_handlers[sig]
        def signal_(sig,handler): installed[sig]=handler
        with patch.object(bridge.signal,'getsignal',side_effect=getsignal), \
             patch.object(bridge.signal,'signal',side_effect=signal_):
            with bridge.ActionLifetime(False,lambda _:None) as lifetime:
                callback=next(iter(installed.values()))
                callback(0,None)
                with self.assertRaisesRegex(bridge.ConfigError,'action_owner_lost'): lifetime.wait(100)
            self.assertEqual(installed,old_handlers)
        with bridge.ActionLifetime(True,lambda _:None) as lifetime:
            lifetime.simulate=False
            with patch.object(bridge.os,'getppid',return_value=-1):
                with self.assertRaisesRegex(bridge.ConfigError,'action_owner_lost'): lifetime.wait(100)

    def test_learning_simulation_does_not_sleep_or_open_real_paths(self):
        req=self.action_request('phase_learning',1,60000)
        with patch.object(bridge,'SerialPort',side_effect=AssertionError('serial')), \
             patch.object(bridge,'load_system',side_effect=AssertionError('guard')), \
             patch.object(bridge.time,'sleep',side_effect=AssertionError('sleep')):
            result=bridge.process(req,simulate=True)
        self.assertTrue(result['ok'],result['error'])



if __name__ == '__main__':
    unittest.main()
