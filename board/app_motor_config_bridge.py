#!/usr/bin/env python3
"""Complete allowlisted LZ-30EMA interfaces over one bounded JSON request.

Configuration and actions require exclusive idle ownership. Bounded speed/learning
operations always attempt their stop/cancel on exit. --simulate uses only in-memory
registers and never imports a real control guard or opens serial.
"""
from __future__ import annotations

import argparse
from contextlib import nullcontext
from datetime import datetime, timezone
import hashlib
import json
import os
import re
import select
import signal
import struct
import sys
import time

from motor_register_catalog import FIELDS, FIELD_BY_ID, ACTION_SPECS, ACTION_BY_ID

PORT, BAUD, SLAVE = '/dev/ttyS0', 115200, 1
VERSION = 2
DEFAULT_CONNECTION = dict(slave=SLAVE, baudRate=BAUD, parity='N', stopBits=1)
LINK_FIELDS = {'rs485_address', 'rs485_baud_rate_code', 'rs485_parity_code'}
# Read only documented locations, not the reserved holes inside a convenient block.
READ_RANGES = {(f.address, f.words) for f in FIELDS} | {(0, 3), (8, 6), (0xF, 3), (0x12, 6), (0x19, 3), (0x1C, 5)}
SNAPSHOT_BLOCKS = ((0x58, 9), (0x64, 3), (0x68, 18), (0x84, 4), (0x89, 4),
                   (0xB0, 4), (0xC8, 4), (0xCD, 3), (0xD2, 4), (0xE0, 38))
READ_RANGES |= set(SNAPSHOT_BLOCKS)
WRITE_RANGES = {(f.address, f.words) for f in FIELDS}
ACTION_RANGES = {(a['address'], a['words']) for a in ACTION_SPECS}
TEMPORARY_DOUBLE_IDS = {'max_forward_speed', 'max_reverse_speed', 'min_speed'}


class ConfigError(Exception):
    def __init__(self, code, field=None):
        self.code, self.field = code, field
        super().__init__(code)


def connection_settings(value=None):
    if value is None: return dict(DEFAULT_CONNECTION)
    if type(value) is not dict or set(value) != set(DEFAULT_CONNECTION):
        raise ConfigError('invalid_connection')
    if (type(value['slave']) is not int or not 1 <= value['slave'] <= 254 or
            type(value['baudRate']) is not int or value['baudRate'] not in (9600, 19200, 38400, 57600, 115200) or
            value['parity'] not in ('N', 'E', 'O') or type(value['stopBits']) is not int or
            value['stopBits'] not in (1, 2) or (value['parity'] != 'N' and value['stopBits'] != 1)):
        raise ConfigError('invalid_connection')
    return dict(value)


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
    def __init__(self, owner_check=None, connection=None):
        if owner_check is None:
            raise ConfigError('serial_owner_check_required')
        if os.name != 'posix':
            raise ConfigError('linux_required')
        connection = connection_settings(connection)
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
            if connection['parity'] != 'N':
                attrs[2] |= termios.PARENB
                if connection['parity'] == 'O': attrs[2] |= termios.PARODD
            if connection['stopBits'] == 2: attrs[2] |= termios.CSTOPB
            attrs[4] = attrs[5] = getattr(termios, 'B' + str(connection['baudRate']))
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

    def discard_input(self):
        self.termios.tcflush(self.fd, self.termios.TCIFLUSH)

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
    """Strict, sequential FC03/FC06/FC10. Failed transactions poison the session."""
    def __init__(self, transport, timeout=.6, connection=None):
        self.transport, self.timeout, self.poisoned = transport, timeout, False
        self.connection = connection_settings(connection)
        self.slave = self.connection['slave']

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
            if prefix[0] != self.slave or prefix[1] not in (function, function | 0x80):
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
        response = self.transact(frame(struct.pack('>BBHH', self.slave, 3, address, count)), 3, count)
        return unpack_words(response[3:-2])

    def write(self, address, values, mode='persistent', action=False):
        allowed = ACTION_RANGES if action else WRITE_RANGES
        if (address, len(values)) not in allowed or any(type(v) is not int or not 0 <= v <= 65535 for v in values):
            raise ConfigError('register_write_not_allowed')
        single = mode == 'temporary' or action and len(values) == 1
        if single:
            if len(values) != 1:
                raise ConfigError('temporary_requires_single_register')
            request = frame(struct.pack('>BBHH', self.slave, 6, address, values[0]))
            response = self.transact(request, 6)
            expected = request[2:6]
        else:
            body = struct.pack('>BBHHB', self.slave, 16, address, len(values), len(values) * 2)
            request = frame(body + struct.pack('>' + 'H' * len(values), *values))
            response = self.transact(request, 16)
            expected = struct.pack('>HH', address, len(values))
        if response[2:6] != expected:
            self.poisoned = True
            raise ConfigError('write_ack_mismatch')

    def write_temporary_double(self, field, before, after, order):
        # This is a logical-field operation, never an exposed arbitrary word write.
        # FC06 itself has no atomic double-word variant. Check each intermediate
        # value and ACK/readback, and stop on the first doubt without rollback.
        if tuple(order) != temporary_word_order(field, before, after):
            raise ConfigError('temporary_word_order_invalid', field.id)
        intermediate = list(before)
        for step, index in enumerate(order):
            if step:
                fresh_gate = gate([observation(self)])
                if not fresh_gate['writeAllowed']:
                    raise ConfigError(fresh_gate['reason'], field.id)
                if self.read(field.address, field.words) != intermediate:
                    raise ConfigError('expected_value_changed', field.id)
            request = frame(struct.pack('>BBHH', self.slave, 6, field.address + index, after[index]))
            response = self.transact(request, 6)
            if response[2:6] != request[2:6]:
                self.poisoned = True
                raise ConfigError('write_ack_mismatch', field.id)
            intermediate[index] = after[index]
            if self.read(field.address, field.words) != intermediate:
                raise ConfigError('readback_mismatch', field.id)


class SimulatedPort:
    """Fresh deterministic device per invocation, including volatile/persistent distinction."""
    def __init__(self):
        self.registers = {address: 0 for address in range(0x106)}
        self.registers.update({0: 0x30EA, 1: 0x0100, 2: 3000, 0x58: 1000, 0x5A: 100,
                               0x5C: 100, 0x5F: 200, 0x60: 300, 0x1C: 24, 0x1D: 1,
                               0x1E: 0x38, 0x84: 60, 0x85: 8, 0x86: 1, 0x87: 1,
                               0xC8: 1, 0xC9: 4, 0xD2: 1, 0xD3: 4, 0x8C: 1000})
        for f in FIELDS:
            if f.group == 'pid': self.registers[f.address + 1] = 100
        self.persisted = dict(self.registers)
        self.buffer, self.closed, self.requests = b'', False, []

    def write(self, request, deadline):
        self.requests.append(bytes(request))
        if crc16(request[:-2]) != struct.unpack('<H', request[-2:])[0]:
            raise AssertionError('simulator received bad request CRC')
        slave, function, address, count = struct.unpack('>BBHH', request[:6])
        if function == 3:
            body = bytes((slave, function, count * 2))
            body += struct.pack('>' + 'H' * count, *(self.registers[address + n] for n in range(count)))
        elif function in (6, 16):
            values = [count] if function == 6 else unpack_words(request[7:-2])
            if function == 16: assert len(values) == count and request[6] == count * 2
            self.registers.update({address + n: value for n, value in enumerate(values)})
            if function == 16 and (address, len(values)) in WRITE_RANGES:
                self.persisted.update({address + n: value for n, value in enumerate(values)})
            if address in (0x41, 0x45):
                self.registers.update({(9 if address == 0x41 else 0x13) + n: v for n, v in enumerate(values)})
                if address == 0x41 and self.registers[0x1D] == 3:
                    self.registers.update({0x13 + n: v for n, v in enumerate(values)})
            elif address in (0x40, 0x44):
                for a in ((9, 10) if address == 0x40 and self.registers[0x1D] in (0, 1) else
                          (0x13, 0x14) if address == 0x44 and self.registers[0x1D] in (0, 1) else
                          (9, 10, 0x13, 0x14) if address == 0x40 else ()):
                    self.registers[a] = 0
            elif address == 0x87: self.registers[0x1D] = values[0]
            elif address == 0x49: self.registers[0x1E] = 0x07 if values[0] else 0x38
            body = request[:6]
        else:
            raise AssertionError('unsupported function was sent')
        self.buffer = frame(body)

    def read(self, count, deadline):
        data, self.buffer = self.buffer[:count], self.buffer[count:]
        return data

    def close(self): self.closed = True



def validate_words(values, field, target=True):
    if (not isinstance(values, list) or len(values) != field.words or
            any(type(value) is not int or not 0 <= value <= 65535 for value in values)):
        raise ConfigError('invalid_raw_words', field.id)
    raw = number(values, field.signed)
    if target and (not field.minimum <= raw <= field.maximum or
                   field.choices and raw not in {choice[0] for choice in field.choices} or
                   field.packed_phase and (raw & 255 > 6 or raw >> 8 > 6)):
        raise ConfigError('value_out_of_range', field.id)
    return raw


def temporary_word_order(field, before, after):
    if field.id not in TEMPORARY_DOUBLE_IDS or field != FIELD_BY_ID[field.id]:
        raise ConfigError('temporary_not_supported', field.id)
    validate_words(before, field, target=False)
    validate_words(after, field)
    for first in (0, 1):
        intermediate = list(before)
        intermediate[first] = after[first]
        if field.minimum <= number(intermediate) <= field.maximum:
            return first, 1 - first
    raise ConfigError('temporary_no_safe_word_order', field.id)


def validate_request(request):
    if type(request) is not dict or type(request.get('schemaVersion')) is not int or request['schemaVersion'] != VERSION:
        raise ConfigError('invalid_schema')
    if type(request.get('requestId')) is not str or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', request['requestId']):
        raise ConfigError('invalid_request_id')
    operation = request.get('op')
    keys = {'schemaVersion', 'op', 'requestId'} | ({'connection'} if 'connection' in request else set())
    connection_settings(request.get('connection'))
    if operation == 'write': keys |= {'snapshotId', 'changes', 'writeMode'}
    elif operation == 'action': keys |= {'snapshotId', 'actionId', 'value', 'durationMs', 'confirmed'}
    elif operation != 'snapshot': raise ConfigError('operation_not_allowed')
    if set(request) != keys: raise ConfigError('unexpected_request_fields')
    if operation != 'snapshot':
        if type(request.get('snapshotId')) is not str or not re.fullmatch(r'[0-9a-f]{64}', request['snapshotId']):
            raise ConfigError('invalid_snapshot_id')
    if operation == 'write':
        if request['writeMode'] not in ('persistent', 'temporary'): raise ConfigError('invalid_write_mode')
        changes = request['changes']
        if type(changes) is not list or not 1 <= len(changes) <= len(FIELDS): raise ConfigError('invalid_changes')
        seen = set()
        for change in changes:
            if type(change) is not dict or set(change) != {'id', 'expectedRaw', 'raw'}: raise ConfigError('invalid_change')
            field = FIELD_BY_ID.get(change['id']) if type(change.get('id')) is str else None
            if field is None or field.id in seen: raise ConfigError('field_not_writable')
            seen.add(field.id)
            validate_words(change['raw'], field)
            validate_words(change['expectedRaw'], field, target=False)
            if request['writeMode'] == 'temporary' and not field.supports_temporary:
                raise ConfigError('temporary_not_supported', field.id)
        if seen & LINK_FIELDS and len(changes) != 1:
            raise ConfigError('communication_change_requires_single_field')
    elif operation == 'action':
        spec = ACTION_BY_ID.get(request['actionId']) if type(request['actionId']) is str else None
        if spec is None: raise ConfigError('action_not_allowed')
        if request['confirmed'] is not True: raise ConfigError('action_confirmation_required')
        value, duration = request['value'], request['durationMs']
        if type(value) is not int or not spec['minimum'] <= value <= spec['maximum']:
            raise ConfigError('action_value_out_of_range')
        if type(duration) is not int: raise ConfigError('invalid_action_duration')
        bounded = spec['bounded'] and not (spec['id'] == 'phase_learning' and value == 0)
        if bounded and not 100 <= duration <= (60000 if spec['id'] == 'phase_learning' else 3000):
            raise ConfigError('invalid_action_duration')
        if not bounded and duration != 0: raise ConfigError('invalid_action_duration')
    return request


def observation(client):
    right, left, bus = client.read(8, 6), client.read(0x12, 6), client.read(0x1C, 5)
    right_extra, left_extra = client.read(0xF, 3), client.read(0x19, 3)
    return dict(leftSpeedRpm=number(left[1:3], True), rightSpeedRpm=number(right[1:3], True),
                leftPwmRaw=left[4], rightPwmRaw=right[4], leftError=left[3], rightError=right[3],
                leftCurrentRaw=left[0], rightCurrentRaw=right[0], leftTemperatureC=number([left[5]], True),
                rightTemperatureC=number([right[5]], True), leftDisplacementDegrees=number(left_extra[:2], True),
                rightDisplacementDegrees=number(right_extra[:2], True), leftReverse=left_extra[2], rightReverse=right_extra[2],
                supplyVoltageV=bus[0], runtimeSystemMode=bus[1], controlMode=bus[2], in1=bus[3], in2=bus[4])


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
    registers = {}
    for address, count in SNAPSHOT_BLOCKS:
        registers.update({address + i: raw for i, raw in enumerate(client.read(address, count))})
    fields = []
    for field in FIELDS:
        raw = [registers[field.address + i] for i in range(field.words)]
        fields.append(dict(id=field.id, address=field.address, label=field.label, group=field.group,
                           unit=field.unit, raw=raw, value=number(raw, field.signed) / field.divisor,
                           words=field.words, signed=field.signed, scale=field.scale, divisor=field.divisor,
                           writable=True, minRaw=field.minimum, maxRaw=field.maximum, readOnlyReason=None,
                           supportsTemporary=field.supports_temporary, packedPhase=field.packed_phase,
                           note=field.note, choices=[dict(value=v,label=label) for v,label in field.choices]))
    samples = [observation(client)]
    pause(.12)
    samples.append(observation(client))
    identity = {'device': info, 'fields': {item['id']: item['raw'] for item in fields},
                'schema': VERSION, 'connection': client.connection}
    snapshot_id = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return dict(snapshotId=snapshot_id, capturedAt=datetime.now(timezone.utc).isoformat(),
                port=PORT, baudRate=client.connection['baudRate'], slave=client.slave, connection=client.connection,
                device=dict(deviceId=info[0], versionRaw=info[1], version=f'{info[1] >> 8}.{info[1] & 255}',
                            maxCurrentRaw=info[2], maxCurrentA=info[2] / 100),
                fields=fields, observation=samples[-1], gate=gate(samples))


def check_current_bounds(maximum, left, right, capability):
    if not 50 <= maximum <= min(6000, capability): raise ConfigError('device_current_limit_exceeded', 'max_output_current')
    if left > maximum or right > maximum: raise ConfigError('current_below_parking_current', 'max_output_current')


def check_changes(request, current):
    if request['snapshotId'] != current['snapshotId']: raise ConfigError('snapshot_changed_refresh_required')
    raw = {field['id']: field['raw'] for field in current['fields']}
    for change in request['changes']:
        if raw[change['id']] != change['expectedRaw']: raise ConfigError('expected_value_changed', change['id'])
    requested = {change['id']: change['raw'] for change in request['changes']}
    check_current_bounds(*(number(requested.get(key, raw[key])) for key in
                           ('max_output_current', 'left_parking_current', 'right_parking_current')),
                         current['device']['maxCurrentRaw'])
    if not current['gate']['writeAllowed']: raise ConfigError(current['gate']['reason'])



def error_code(error):
    if isinstance(error, ConfigError):
        return dict(code=error.code, field=error.field)
    # Shared idle guard errors are fixed codes, never interpolate arbitrary exception text.
    code = getattr(error, 'code', None)
    if isinstance(code, str) and re.fullmatch(r'[a-z0-9_]{1,80}', code):
        return dict(code=code, field=None)
    return dict(code='operation_unavailable', field=None)


class ActionLifetime:
    """Bounded owner lifetime; simulated requests neither register signals nor sleep."""
    def __init__(self, simulate, pause):
        self.simulate, self.pause, self.cancelled = simulate, pause, False
        self.parent, self.handlers = os.getppid(), {}

    def __enter__(self):
        if not self.simulate:
            import threading
            if threading.current_thread() is threading.main_thread():
                for name in ('SIGINT', 'SIGTERM', 'SIGHUP'):
                    sig = getattr(signal, name, None)
                    if sig is not None:
                        self.handlers[sig] = signal.getsignal(sig)
                        signal.signal(sig, lambda *_: setattr(self, 'cancelled', True))
        return self

    def wait(self, duration_ms):
        if self.simulate: return
        until = time.monotonic() + duration_ms / 1000
        while True:
            if self.cancelled or os.getppid() != self.parent: raise ConfigError('action_owner_lost')
            remaining = until - time.monotonic()
            if remaining <= 0: return
            self.pause(min(.05, remaining))

    def __exit__(self, *_):
        for sig, handler in self.handlers.items(): signal.signal(sig, handler)


def encode_number(value, words):
    return [(value >> (16 * i)) & 65535 for i in range(words - 1, -1, -1)]


def execute_action(request, client, current, result, simulate, pause):
    spec = ACTION_BY_ID[request['actionId']]
    action_id, value = spec['id'], request['value']
    entry = dict(actionId=action_id, value=value, durationMs=request['durationMs'], outcome='not_attempted',
                 cleanupConfirmed=None, reconnectRequired=False, error=None)
    result['actionResult'] = entry
    if current['snapshotId'] != request['snapshotId']: raise ConfigError('snapshot_changed_refresh_required')
    fresh = observation(client)
    stop = action_id in ('left_stop', 'right_stop') or action_id == 'phase_learning' and value == 0
    if not stop:
        fresh_gate = gate([fresh])
        if not fresh_gate['writeAllowed']: raise ConfigError(fresh_gate['reason'])
    mode = fresh['runtimeSystemMode']
    if action_id == 'left_stop' and mode in (2, 3, 4): raise ConfigError('action_ineffective_in_system_mode')
    if action_id.endswith('target_speed'):
        if mode not in (1, 3, 4) or action_id == 'left_target_speed' and mode == 3:
            raise ConfigError('action_ineffective_in_system_mode')
        limits = {f['id']: number(f['raw']) for f in current['fields']}
        limit = limits['max_forward_speed' if value >= 0 else 'max_reverse_speed']
        if mode == 4 and action_id == 'left_target_speed': limit = min(limit, limits['differential_turn_max_speed'])
        if abs(value) > limit: raise ConfigError('target_exceeds_configured_limit')
    bounded = spec['bounded'] and not (action_id == 'phase_learning' and value == 0)
    cleanup_address = (0x49 if action_id == 'phase_learning' else
                       0x40 if action_id == 'right_target_speed' or mode == 4 else 0x44)
    sent = False
    try:
        with ActionLifetime(simulate, pause) as lifetime:
            try:
                # Mark before entering write: a partial write has an unknown effect.
                sent = True
                result['writeMayHaveOccurred'] = True
                entry['outcome'] = 'write_outcome_unknown'
                if action_id == 'factory_reset':
                    entry['reconnectRequired'] = result['reconnectRequired'] = True
                client.write(spec['address'], encode_number(value, spec['words']), action=True)
                entry['outcome'] = 'acknowledged'
                if bounded: lifetime.wait(request['durationMs'])
            finally:
                if sent and bounded:
                    entry['cleanupConfirmed'] = False
                    try:
                        # Never retry the requested operation. On a damaged protocol session,
                        # only the bounded stop/cancel is attempted using a fresh reader.
                        cleanup_client = client
                        if client.poisoned:
                            if hasattr(client.transport, 'discard_input'): client.transport.discard_input()
                            cleanup_client = Modbus(client.transport, timeout=.6, connection=client.connection)
                        cleanup_client.write(cleanup_address, [0], action=True)
                        entry['cleanupConfirmed'] = True
                    except Exception:
                        entry['error'] = 'action_cleanup_unconfirmed'
    except BaseException as error:
        entry['error'] = error_code(error)['code']
        result['error'] = error_code(error)
        return result
    if entry['cleanupConfirmed'] is False:
        result['error'] = dict(code='action_cleanup_unconfirmed', field=None)
        return result
    if action_id == 'factory_reset':
        entry['reconnectRequired'] = result['reconnectRequired'] = True
        result['ok'] = True  # Only command ACK is confirmed; no reset completion/readback claim.
        return result
    try: result['snapshot'] = snapshot(client, pause)
    except Exception as error:
        # Stop/cancel ACK can be valid even when subsequent telemetry is unavailable.
        entry['error'] = error_code(error)['code']
        result['error'] = error_code(error)
        return result
    result['ok'] = True
    return result


def process(request, system=None, transport_factory=None, simulate=False, pause=time.sleep):
    result = dict(type='motor_config', schemaVersion=VERSION, requestId=None, op=None,
                  ok=False, simulation=simulate, snapshot=None, results=[], actionResult=None,
                  connection=dict(DEFAULT_CONNECTION), reconnectRequired=False, writeMayHaveOccurred=False, error=None)
    transport = None
    if simulate: pause = lambda _: None
    try:
        validate_request(request)  # Validate all fields/ranges before acquiring locks or opening serial.
        connection = connection_settings(request.get('connection'))
        result.update(requestId=request['requestId'], op=request['op'], connection=connection)
        if request['op'] == 'write':
            result['results'] = [dict(id=item['id'], status='not_attempted', requestedRaw=item['raw'],
                                      actualRaw=None, error=None) for item in request['changes']]
        if not simulate and (system is None or not system.is_root()):
            raise ConfigError('root_required_for_complete_idle_probe')
        with nullcontext() if simulate else system.idle_guard():
            factory = transport_factory or (SimulatedPort if simulate else
                    lambda: SerialPort(owner_check=system.verify_open_serial_owner, connection=connection))
            transport = factory()
            try:
                client = Modbus(transport, connection=connection)
                current = snapshot(client, pause)
                if request['op'] == 'snapshot':
                    result.update(ok=True, snapshot=current)
                    return result
                if request['op'] == 'action':
                    return execute_action(request, client, current, result, simulate, pause)
                check_changes(request, current)
                pending = list(zip(request['changes'], result['results']))
                # Keep every intermediate current/parking combination valid, not just the final batch.
                def order(pair):
                    change = pair[0]
                    if change['id'] == 'max_output_current': return 0 if number(change['raw']) > number(change['expectedRaw']) else 2
                    if change['id'] in ('left_parking_current', 'right_parking_current'):
                        return 0 if number(change['raw']) < number(change['expectedRaw']) else 2
                    return 1
                pending.sort(key=order)
                for change, entry in pending:
                    field = FIELD_BY_ID[change['id']]
                    split_temporary = request['writeMode'] == 'temporary' and field.words == 2
                    try:
                        fresh_gate = gate([observation(client)])
                        if not fresh_gate['writeAllowed']: raise ConfigError(fresh_gate['reason'], field.id)
                        if client.read(field.address, field.words) != change['expectedRaw']:
                            raise ConfigError('expected_value_changed', field.id)
                        if split_temporary:
                            word_order = temporary_word_order(field, change['expectedRaw'], change['raw'])
                        if field.id in ('max_output_current', 'left_parking_current', 'right_parking_current'):
                            values = {key: number(change['raw']) if key == field.id else number(client.read(FIELD_BY_ID[key].address, 1))
                                      for key in ('max_output_current', 'left_parking_current', 'right_parking_current')}
                            check_current_bounds(values['max_output_current'], values['left_parking_current'],
                                                 values['right_parking_current'], current['device']['maxCurrentRaw'])
                    except Exception as error:
                        entry.update(status='failed', error=error_code(error)['code'])
                        result['error'] = error_code(error)
                        return result
                    if change['raw'] == change['expectedRaw']:
                        entry.update(status='verified', actualRaw=change['raw'])
                        continue
                    result['writeMayHaveOccurred'] = True
                    entry['status'] = 'write_outcome_unknown'
                    if field.id in LINK_FIELDS:
                        result['reconnectRequired'] = True
                    try:
                        if split_temporary:
                            client.write_temporary_double(field, change['expectedRaw'], change['raw'], word_order)
                        else:
                            client.write(field.address, change['raw'], mode=request['writeMode'])
                    except Exception as error:
                        if split_temporary: entry['status'] = 'write_outcome_unknown'
                        entry['error'] = error_code(error)['code']
                        result['error'] = error_code(error)
                        return result
                    entry['status'] = 'acknowledged_unverified'
                    if field.id in LINK_FIELDS:
                        result['reconnectRequired'] = True
                        entry['error'] = 'communication_reconnect_required'
                        result['error'] = dict(code='communication_reconnect_required', field=field.id)
                        return result
                    try:
                        actual = client.read(field.address, field.words)
                        entry['actualRaw'] = actual
                        if actual != change['raw']: raise ConfigError('readback_mismatch', field.id)
                    except Exception as error:
                        if split_temporary: entry['status'] = 'write_outcome_unknown'
                        entry['error'] = error_code(error)['code']
                        result['error'] = error_code(error)
                        return result
                    entry['status'] = 'verified'
                result['snapshot'] = snapshot(client, pause)
                result['ok'] = True
                return result
            finally:
                if simulate:
                    result['simulationWrites'] = [dict(function=frame_[1], address=struct.unpack('>H', frame_[2:4])[0],
                        raw=[struct.unpack('>H', frame_[4:6])[0]] if frame_[1] == 6 else unpack_words(frame_[7:-2]))
                        for frame_ in getattr(transport, 'requests', []) if frame_[1] in (6,16)]
                transport.close()
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
    data = stream.read(32769)
    if len(data) > 32768:
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
                      requestId=None, op=None, snapshot=None, results=[], actionResult=None, connection=dict(DEFAULT_CONNECTION),
                      reconnectRequired=False, writeMayHaveOccurred=False,
                      error=error_code(error))
    try:
        print(json.dumps(result, ensure_ascii=True, allow_nan=False), flush=True)
    except OSError:
        pass
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
