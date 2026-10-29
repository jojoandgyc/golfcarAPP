#!/usr/bin/env python3
"""Idle-only, allowlisted LZ-30EMA configuration over one JSON request.

No movement, stop, brake, calibration, address or baud-rate write exists here.
FC10 is the manual's persistent write; readback does not test a power cycle.
--check never opens serial. --simulate uses only fresh in-memory registers.
"""
from __future__ import annotations

import argparse
from contextlib import nullcontext
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
import re
import select
import struct
import sys
import time

PORT, BAUD, SLAVE = '/dev/ttyS0', 115200, 1
VERSION = 1


class ConfigError(Exception):
    def __init__(self, code, field=None):
        self.code, self.field = code, field
        super().__init__(code)


@dataclass(frozen=True)
class Field:
    id: str
    address: int
    label: str
    unit: str = ''
    words: int = 1
    scale: float = 1
    minimum: int | None = None
    maximum: int | None = None
    reason: str | None = 'read_only'

    @property
    def writable(self):
        return self.reason is None


FIELDS = (
    Field('max_output_current', 0x58, '最大输出电流（单路）', 'A', scale=.01, minimum=50, maximum=6000, reason=None),
    Field('max_forward_speed', 0x59, '正转最大速度', 'RPM', 2, minimum=0, maximum=110000, reason=None),
    Field('max_reverse_speed', 0x5B, '反转最大速度', 'RPM', 2, minimum=0, maximum=110000, reason=None),
    Field('closed_loop_acceleration', 0x5F, '闭环加速度', 'RPM/s', minimum=10, maximum=65535, reason=None),
    Field('closed_loop_deceleration', 0x60, '闭环减速度', 'RPM/s', minimum=10, maximum=65535, reason=None),
    Field('left_parking_current', 0x76, '左电机电子驻车电流', 'A', scale=.01, reason='parking_semantics_not_verified'),
    Field('right_parking_current', 0x6D, '右电机电子驻车电流', 'A', scale=.01, reason='parking_semantics_not_verified'),
    Field('system_mode', 0x87, '系统模式', reason='control_mode_change_excluded'),
    Field('differential_turn_max_speed', 0x8A, '差速转弯最大速度', 'RPM', reason='manual_range_conflict'),
    Field('stall_stop_time', 0x8B, '堵转停机时间', 'ms', reason='manual_range_conflict'),
    Field('rs485_address', 0xC8, 'RS485 地址', reason='communication_change_excluded'),
    Field('rs485_baud_rate_code', 0xC9, 'RS485 波特率代码', reason='communication_change_excluded'),
    Field('rs485_parity_code', 0xCA, 'RS485 校验代码', reason='communication_change_excluded'),
    Field('rs485_timeout_s', 0xCB, 'RS485 中断制动时间', 's', reason='communication_change_excluded'),
    Field('left_phase_learning_state', 0x75, '左电机相序学习状态', reason='calibration_excluded'),
    Field('right_phase_learning_state', 0x6C, '右电机相序学习状态', reason='calibration_excluded'),
    Field('left_phase_sequence', 0x72, '左电机相序', words=3, reason='calibration_excluded'),
    Field('right_phase_sequence', 0x69, '右电机相序', words=3, reason='calibration_excluded'),
)
FIELD_BY_ID = {field.id: field for field in FIELDS}
WRITE_RANGES = {(field.address, field.words) for field in FIELDS if field.writable}
READ_RANGES = {(field.address, field.words) for field in FIELDS} | {(0, 3), (8, 6), (0x12, 6), (0x1C, 5)}


def crc16(data):
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def frame(payload):
    return payload + struct.pack('<H', crc16(payload))


def unpack_words(data):
    return list(struct.unpack('>' + 'H' * (len(data) // 2), data))


def number(words, signed=False):
    result = 0
    for word in words:
        result = (result << 16) | word
    return result - (1 << (16 * len(words))) if signed and words[0] & 0x8000 else result


class SerialPort:
    """Fixed POSIX UART settings, bounded nonblocking I/O, no motor commands."""
    def __init__(self, owner_check=None):
        if owner_check is None:
            raise ConfigError('serial_owner_check_required')
        if os.name != 'posix':
            raise ConfigError('linux_required')
        import fcntl
        import termios
        self.termios, self.fcntl, self.exclusive_owned = termios, fcntl, False
        self.fd = os.open(PORT, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK | os.O_CLOEXEC)
        try:
            # root may open a tty despite somebody else's TIOCEXCL. Never clear
            # their flag; fail closed if its prior state cannot be inspected.
            prior = fcntl.ioctl(self.fd, getattr(termios, 'TIOCGEXCL', 0x80045440), bytes(4))
            if struct.unpack('I', prior)[0]:
                raise ConfigError('motor_serial_in_use')
            # Kernel exclusion complements the pre-open /proc/fuser observations.
            fcntl.ioctl(self.fd, termios.TIOCEXCL)
            self.exclusive_owned = True
            # TIOCEXCL does not evict descriptors opened during the preflight race.
            # Refuse those before tcsetattr/ioctl configuration or protocol writes.
            owner_check()
            attrs = termios.tcgetattr(self.fd)
            attrs[0], attrs[1], attrs[2], attrs[3] = 0, 0, termios.CLOCAL | termios.CREAD | termios.CS8, 0
            attrs[4] = attrs[5] = termios.B115200
            attrs[6][termios.VMIN] = attrs[6][termios.VTIME] = 0
            termios.tcsetattr(self.fd, termios.TCSANOW, attrs)
            # Same automatic RS485 convention as the existing vendor GUI transport.
            try:
                current = bytearray(32)
                fcntl.ioctl(self.fd, 0x542E, current, True)
                settings = list(struct.unpack('8I', current))
                settings[0] |= 3
                fcntl.ioctl(self.fd, 0x542F, struct.pack('8I', *settings))
            except OSError:
                pass  # Existing board also permits UARTs without RS485 ioctls.
            termios.tcflush(self.fd, termios.TCIFLUSH)
        except BaseException:
            self.close()
            raise

    def write(self, payload, deadline):
        remaining = memoryview(payload)
        while remaining:
            wait = deadline - time.monotonic()
            if wait <= 0 or not select.select([], [self.fd], [], wait)[1]:
                raise ConfigError('serial_write_timeout')
            try:
                written = os.write(self.fd, remaining)
            except BlockingIOError:
                continue
            if written <= 0:
                raise ConfigError('serial_write_failed')
            remaining = remaining[written:]

    def read(self, count, deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([self.fd], [], [], remaining)[0]:
            return b''
        try:
            return os.read(self.fd, count)
        except BlockingIOError:
            return b''

    def close(self):
        if self.fd is not None:
            try:
                # An owner found during the post-open race may still hold another
                # descriptor. Remove only our newly-set exclusion flag before close.
                if self.exclusive_owned:
                    self.fcntl.ioctl(self.fd, self.termios.TIOCNXCL)
            finally:
                try:
                    os.close(self.fd)
                finally:
                    self.fd, self.exclusive_owned = None, False


class Modbus:
    """Strict, sequential FC03/FC10 only. Any failed transaction poisons the session."""
    def __init__(self, transport, timeout=.6):
        self.transport, self.timeout, self.poisoned = transport, timeout, False

    def transact(self, request, function, count=None):
        if self.poisoned:
            raise ConfigError('serial_session_unusable')
        deadline = time.monotonic() + self.timeout
        def exact(size):
            data = b''
            while len(data) < size:
                chunk = self.transport.read(size - len(data), deadline)
                if not chunk:
                    raise ConfigError('serial_read_timeout')
                data += chunk
            return data
        try:
            self.transport.write(request, deadline)
            prefix = exact(2)
            if prefix[0] != SLAVE or prefix[1] not in (function, function | 0x80):
                raise ConfigError('response_mismatch')
            if prefix[1] & 0x80:
                response = prefix + exact(3)
            elif function == 3:
                length = exact(1)
                if length[0] != count * 2:
                    raise ConfigError('response_length_mismatch')
                response = prefix + length + exact(length[0] + 2)
            else:
                response = prefix + exact(6)
            if crc16(response[:-2]) != struct.unpack('<H', response[-2:])[0]:
                raise ConfigError('crc_mismatch')
            if response[1] & 0x80:
                raise ConfigError('device_exception_' + str(response[2]))
            return response
        except BaseException:
            self.poisoned = True
            raise

    def read(self, address, count):
        if (address, count) not in READ_RANGES:
            raise ConfigError('register_read_not_allowed')
        response = self.transact(frame(struct.pack('>BBHH', SLAVE, 3, address, count)), 3, count)
        return unpack_words(response[3:-2])

    def write(self, address, values):
        if (address, len(values)) not in WRITE_RANGES:
            raise ConfigError('register_write_not_allowed')
        body = struct.pack('>BBHHB', SLAVE, 16, address, len(values), len(values) * 2)
        request = frame(body + struct.pack('>' + 'H' * len(values), *values))
        response = self.transact(request, 16)
        if response[2:6] != struct.pack('>HH', address, len(values)):
            self.poisoned = True
            raise ConfigError('write_ack_mismatch')


class SimulatedPort:
    """A new deterministic device for each invocation; no disk or hardware state."""
    def __init__(self):
        self.registers = {address: 0 for address in range(0xD0)}
        self.registers.update({0: 0x30EA, 1: 0x0100, 2: 3000, 0x58: 1000, 0x5A: 100,
                               0x5C: 100, 0x5F: 200, 0x60: 300, 0x1C: 24, 0x1D: 1,
                               0x1E: 0x20, 0x87: 1, 0xC8: 1, 0xC9: 4})
        self.buffer, self.closed, self.requests = b'', False, []

    def write(self, request, deadline):
        self.requests.append(bytes(request))
        if crc16(request[:-2]) != struct.unpack('<H', request[-2:])[0]:
            raise AssertionError('simulator received bad request CRC')
        slave, function, address, count = struct.unpack('>BBHH', request[:6])
        if function == 3:
            body = bytes((slave, function, count * 2))
            body += struct.pack('>' + 'H' * count, *(self.registers[address + n] for n in range(count)))
        elif function == 16:
            values = unpack_words(request[7:-2])
            assert len(values) == count and request[6] == count * 2
            self.registers.update({address + n: value for n, value in enumerate(values)})
            body = request[:6]
        else:
            raise AssertionError('movement/unsupported function was sent')
        self.buffer = frame(body)

    def read(self, count, deadline):
        data, self.buffer = self.buffer[:count], self.buffer[count:]
        return data

    def close(self):
        self.closed = True


def validate_request(request):
    if not isinstance(request, dict) or type(request.get('schemaVersion')) is not int or request['schemaVersion'] != VERSION:
        raise ConfigError('invalid_schema')
    request_id = request.get('requestId')
    if type(request_id) is not str or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', request_id):
        raise ConfigError('invalid_request_id')
    operation = request.get('op')
    keys = {'schemaVersion', 'op', 'requestId'}
    if operation == 'write':
        keys |= {'snapshotId', 'changes'}
    elif operation != 'snapshot':
        raise ConfigError('operation_not_allowed')
    if set(request) != keys:
        raise ConfigError('unexpected_request_fields')
    if operation == 'write':
        if type(request.get('snapshotId')) is not str or not re.fullmatch(r'[0-9a-f]{64}', request['snapshotId']):
            raise ConfigError('invalid_snapshot_id')
        changes = request.get('changes')
        if not isinstance(changes, list) or not 1 <= len(changes) <= 5:
            raise ConfigError('invalid_changes')
        seen = set()
        for change in changes:
            if not isinstance(change, dict) or set(change) != {'id', 'expectedRaw', 'raw'}:
                raise ConfigError('invalid_change')
            field = FIELD_BY_ID.get(change['id']) if type(change.get('id')) is str else None
            if field is None or not field.writable or field.id in seen:
                raise ConfigError('field_not_writable')
            seen.add(field.id)
            for key in ('raw', 'expectedRaw'):
                values = change[key]
                if (not isinstance(values, list) or len(values) != field.words
                        or any(type(value) is not int or not 0 <= value <= 65535 for value in values)):
                    raise ConfigError('invalid_raw_words', field.id)
            if not field.minimum <= number(change['raw']) <= field.maximum:
                raise ConfigError('value_out_of_range', field.id)
    return request


def observation(client):
    right, left, bus = client.read(8, 6), client.read(0x12, 6), client.read(0x1C, 5)
    return dict(leftSpeedRpm=number(left[1:3], True), rightSpeedRpm=number(right[1:3], True),
                leftPwmRaw=left[4], rightPwmRaw=right[4], leftError=left[3], rightError=right[3],
                supplyVoltageV=bus[0], runtimeSystemMode=bus[1], controlMode=bus[2])


def gate(samples):
    zero_speed = all(sample['leftSpeedRpm'] == sample['rightSpeedRpm'] == 0 for sample in samples)
    zero_pwm = all(sample['leftPwmRaw'] == sample['rightPwmRaw'] == 0 for sample in samples)
    communication = all(sample['controlMode'] & 7 == 0 for sample in samples)
    reason = ('motor_motion_observed' if not zero_speed else 'motor_pwm_active' if not zero_pwm else
              'external_control_source' if not communication else None)
    return dict(controlAvailable=True, zeroSpeedObserved=zero_speed, zeroPwmObserved=zero_pwm,
                communicationControl=communication, disabledVerified=None, writeAllowed=reason is None,
                reason=reason, samples=len(samples))


def snapshot(client, pause=time.sleep):
    info = client.read(0, 3)
    fields = []
    for field in FIELDS:
        raw = client.read(field.address, field.words)
        fields.append(dict(id=field.id, label=field.label, unit=field.unit, raw=raw,
                           value=round(number(raw) * field.scale, 2) if field.words <= 2 else None,
                           words=field.words, scale=field.scale, writable=field.writable,
                           minRaw=field.minimum, maxRaw=field.maximum, readOnlyReason=field.reason))
    samples = [observation(client)]
    pause(.12)
    samples.append(observation(client))
    identity = {'device': info, 'fields': {item['id']: item['raw'] for item in fields}, 'schema': VERSION}
    snapshot_id = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return dict(snapshotId=snapshot_id, capturedAt=datetime.now(timezone.utc).isoformat(),
                port=PORT, baudRate=BAUD, slave=SLAVE,
                device=dict(deviceId=info[0], versionRaw=info[1], version=f'{info[1] >> 8}.{info[1] & 255}',
                            maxCurrentRaw=info[2], maxCurrentA=round(info[2] * .01, 2)),
                fields=fields, observation=samples[-1], gate=gate(samples))


def check_changes(request, current):
    if request['snapshotId'] != current['snapshotId']:
        raise ConfigError('snapshot_changed_refresh_required')
    raw = {field['id']: field['raw'] for field in current['fields']}
    for change in request['changes']:
        if raw[change['id']] != change['expectedRaw']:
            raise ConfigError('expected_value_changed', change['id'])
    requested = {change['id']: change['raw'] for change in request['changes']}
    maximum = number(requested.get('max_output_current', raw['max_output_current']))
    if not 50 <= maximum <= min(6000, current['device']['maxCurrentRaw']):
        raise ConfigError('device_current_limit_exceeded', 'max_output_current')
    if any(number(raw[key]) > maximum for key in ('left_parking_current', 'right_parking_current')):
        raise ConfigError('current_below_parking_current', 'max_output_current')
    if not current['gate']['writeAllowed']:
        raise ConfigError(current['gate']['reason'])


def error_code(error):
    if isinstance(error, ConfigError):
        return dict(code=error.code, field=error.field)
    # Shared idle guard errors are fixed codes, never interpolate arbitrary exception text.
    code = getattr(error, 'code', None)
    if isinstance(code, str) and re.fullmatch(r'[a-z0-9_]{1,80}', code):
        return dict(code=code, field=None)
    return dict(code='operation_unavailable', field=None)


def process(request, system=None, transport_factory=None, simulate=False, pause=time.sleep):
    result = dict(type='motor_config', schemaVersion=VERSION, requestId=None, op=None,
                  ok=False, simulation=simulate, snapshot=None, results=[], writeMayHaveOccurred=False, error=None)
    transport = None
    try:
        validate_request(request)  # All fields/ranges validated before any lock or serial open.
        result.update(requestId=request['requestId'], op=request['op'])
        if request['op'] == 'write':
            result['results'] = [dict(id=item['id'], status='not_attempted', requestedRaw=item['raw'],
                                      actualRaw=None, error=None) for item in request['changes']]
        if not simulate and (system is None or not system.is_root()):
            raise ConfigError('root_required_for_complete_idle_probe')
        with nullcontext() if simulate else system.idle_guard():
            factory = transport_factory or (SimulatedPort if simulate else
                                             lambda: SerialPort(owner_check=system.verify_open_serial_owner))
            transport = factory()
            try:
                client = Modbus(transport)
                current = snapshot(client, pause)
                if request['op'] == 'snapshot':
                    result.update(ok=True, snapshot=current)
                    return result
                check_changes(request, current)
                for change, entry in zip(request['changes'], result['results']):
                    field = FIELD_BY_ID[change['id']]
                    try:
                        # Reobserve before every write; preceding I/O is never a motion proof.
                        fresh_gate = gate([observation(client)])
                        if not fresh_gate['writeAllowed']:
                            raise ConfigError(fresh_gate['reason'], field.id)
                        if client.read(field.address, field.words) != change['expectedRaw']:
                            raise ConfigError('expected_value_changed', field.id)
                    except Exception as error:
                        entry.update(status='failed', error=error_code(error)['code'])
                        result['error'] = error_code(error)
                        return result
                    if change['raw'] == change['expectedRaw']:
                        entry.update(status='verified', actualRaw=change['raw'])
                        continue  # Avoid unnecessary EEPROM writes.
                    result['writeMayHaveOccurred'] = True
                    entry['status'] = 'write_outcome_unknown'
                    try:
                        client.write(field.address, change['raw'])
                    except Exception as error:
                        entry['error'] = error_code(error)['code']
                        result['error'] = error_code(error)
                        return result
                    entry['status'] = 'acknowledged_unverified'
                    try:
                        actual = client.read(field.address, field.words)
                        entry['actualRaw'] = actual
                        if actual != change['raw']:
                            raise ConfigError('readback_mismatch', field.id)
                    except Exception as error:
                        entry['error'] = error_code(error)['code']
                        result['error'] = error_code(error)
                        return result
                    entry['status'] = 'verified'
                result['snapshot'] = snapshot(client, pause)
                result['ok'] = True
                return result
            finally:
                transport.close()  # Only closes UART. Never calls the GUI's shutdown routine.
    except Exception as error:
        result['error'] = error_code(error)
        return result


def load_system():
    from app_control_guard import ControlGuard
    return ControlGuard()


def check(system):
    result = dict(type='motor_config_check', schemaVersion=VERSION, ok=False, controlAvailable=False,
                  port=PORT, baudRate=BAUD, slave=SLAVE, serialOpened=False,
                  writableIds=[field.id for field in FIELDS if field.writable], error=None)
    try:
        if not system.is_root():
            raise ConfigError('root_required_for_complete_idle_probe')
        with system.idle_guard():
            result.update(ok=True, controlAvailable=True)
    except Exception as error:
        result['error'] = error_code(error)
    return result


def read_request(stream):
    data = stream.read(8193)
    if len(data) > 8192:
        raise ConfigError('request_too_large')
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ConfigError('duplicate_json_key')
            value[key] = item
        return value
    try:
        return json.loads(data, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ConfigError('invalid_number')))
    except (ValueError, UnicodeError):
        raise ConfigError('invalid_json') from None


def main(argv=None, stdin=None, system=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--request', action='store_true', help='one JSON stdin request (default)')
    mode.add_argument('--check', action='store_true', help='idle check without opening serial')
    parser.add_argument('--simulate', action='store_true', help='fixed in-memory device, no real locks or serial')
    args = parser.parse_args(argv)
    try:
        if args.check:
            if args.simulate:
                raise ConfigError('check_simulate_not_supported')
            result = check(system or load_system())
        else:
            request = read_request(stdin or sys.stdin.buffer)
            result = process(request, system=system or (None if args.simulate else load_system()), simulate=args.simulate)
    except Exception as error:
        result = dict(type='motor_config', schemaVersion=VERSION, ok=False, simulation=args.simulate,
                      requestId=None, op=None, snapshot=None, results=[], writeMayHaveOccurred=False,
                      error=error_code(error))
    try:
        print(json.dumps(result, ensure_ascii=True, allow_nan=False), flush=True)
    except OSError:
        pass
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
