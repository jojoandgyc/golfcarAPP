"""Dedicated parking operations. No arbitrary registers, retries, rollback or mode readback."""
from __future__ import annotations
import re
import time

CURRENT_REGISTERS = {'left': 0x76, 'right': 0x6D}
MAX_CURRENT_RAW = 6000
TRANSACTION_SECONDS = 2.0

class ParkingError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)

def error_code(error):
    code = getattr(error, 'code', None)
    return code if isinstance(code, str) and re.fullmatch(r'[a-z0-9_]{1,80}', code) else 'parking_io_failed'

def response(request):
    ident=request.get('requestId')
    op=request.get('op')
    return dict(type='parking', schemaVersion=1,
        requestId=ident if type(ident) is str and len(ident)<=64 else None,
        op=op if op in ('read','mode','currents') else None,
        ok=False, simulation=False, mode=None, modeSource='unknown', leftRaw=None, rightRaw=None, maxRaw=None,
        leftRpm=None, rightRpm=None, leftError=None, rightError=None,
        writeMayHaveOccurred=False, errorCode=None, results=[])

def validate_request(request, allow_sequence_id=False):
    if type(request) is not dict: raise ParkingError('invalid_request')
    op = request.get('op')
    extra = {'enabled'} if op == 'mode' else {'leftRaw', 'rightRaw'} if op == 'currents' else set()
    if op not in ('read', 'mode', 'currents') or set(request) != {'schemaVersion','requestId','op'} | extra:
        raise ParkingError('invalid_request_fields')
    if type(request['schemaVersion']) is not int or request['schemaVersion'] != 1:
        raise ParkingError('unsupported_schema_version')
    ident = request['requestId']
    if type(ident) is not str or not (re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', ident)
            or allow_sequence_id and re.fullmatch(r'[1-9][0-9]{0,18}', ident)):
        raise ParkingError('invalid_request_id')
    if op == 'mode' and type(request['enabled']) is not bool: raise ParkingError('invalid_mode')
    if op == 'currents' and any(type(request[k]) is not int or not 0 <= request[k] <= MAX_CURRENT_RAW for k in ('leftRaw','rightRaw')):
        raise ParkingError('invalid_current')
    return request

def words(io, address, count):
    value = io.read(address, count)
    if not isinstance(value, (tuple,list)) or len(value) != count or any(type(v) is not int or not 0 <= v <= 65535 for v in value):
        raise ParkingError('invalid_feedback')
    return value

def signed32(high, low):
    value = high << 16 | low
    return value - 2**32 if value >= 2**31 else value

def observe(io, result):
    right, left = words(io, 8, 6), words(io, 0x12, 6)
    bus = words(io, 0x1C, 5)
    result.update(leftRpm=signed32(*left[1:3]), rightRpm=signed32(*right[1:3]),
                  leftError=left[3], rightError=right[3])
    return bus[2]

def execute(request, io, mode=None, mode_source='unknown', *, allow_sequence_id=False):
    result = response(request if isinstance(request,dict) else {})
    deadline = time.monotonic() + TRANSACTION_SECONDS
    def check():
        if time.monotonic() >= deadline: raise ParkingError('parking_transaction_timeout')
    backend=io
    class BoundedIO:
        def read(self,address,count):
            check(); value=backend.read(address,count); check(); return value
        def zero_targets(self):
            check(); backend.zero_targets(); check()
        def stop(self,enabled):
            check(); backend.stop(enabled); check()
        def write_current(self,side,raw):
            check(); backend.write_current(side,raw); check()
    io=BoundedIO()
    def observe_current():
        started=time.monotonic()
        control=observe(io,result)
        # Conservatively date the group from its first RPM read, not the later writes.
        backend.feedback_observed_at=started
        return control
    try:
        validate_request(request, allow_sequence_id)
        result.update(mode=mode, modeSource=mode_source if type(mode) is bool else 'unknown')
        if request['op'] == 'currents':
            result['results'] = [dict(side=side,status='not_attempted',requestedRaw=request[side+'Raw'],actualRaw=None) for side in ('left','right')]
        # Stop existing motion before any potentially slow configuration reads.
        if request["op"] != "read":
            result["writeMayHaveOccurred"] = True
            io.zero_targets(); check()
        # Read-only requests never issue zero targets, mode changes or cleanup writes.
        capability = words(io,0,3)[2]; check()
        result['maxRaw'] = words(io,0x58,1)[0]; check()
        for side,address in CURRENT_REGISTERS.items():
            result[side+'Raw'] = words(io,address,1)[0]; check()
        control = observe_current(); check()
        if not 50 <= result['maxRaw'] <= min(MAX_CURRENT_RAW, capability): raise ParkingError('invalid_current_limit')
        if any(not 0 <= result[k] <= MAX_CURRENT_RAW for k in ('leftRaw','rightRaw')): raise ParkingError('invalid_saved_current')
        if request['op'] == 'read':
            result['ok'] = True
            return result
        if result['leftError'] or result['rightError']: raise ParkingError('motor_fault')
        if control & 7: raise ParkingError('external_control_source')
        if request['op'] == 'currents' and any(request[k] > result['maxRaw'] for k in ('leftRaw','rightRaw')):
            raise ParkingError('current_limit_exceeded')
        if request['op'] == 'mode' and request['enabled'] and any(result[k] > result['maxRaw'] for k in ('leftRaw','rightRaw')):
            raise ParkingError('current_limit_exceeded')
        if request['op'] == 'mode':
            result.update(mode=None,modeSource='unknown')
            io.stop(request['enabled']); check()
            result.update(mode=request['enabled'],modeSource='command_ack',ok=True)
            return result
        for entry in result['results']:
            check()
            side = entry['side']; address=CURRENT_REGISTERS[side]
            # Recheck faults/source/limit for each side, but no exact-zero or PWM gate.
            control=observe_current(); check()
            maximum=words(io,0x58,1)[0]; check()
            if result['leftError'] or result['rightError']: raise ParkingError('motor_fault')
            if control & 7: raise ParkingError('external_control_source')
            if maximum != result['maxRaw']: raise ParkingError('current_limit_changed')
            actual=words(io,address,1)[0]; check()
            if actual != entry['requestedRaw']:
                entry['status']='unknown'; result[side+'Raw']=None
                io.write_current(side,entry['requestedRaw']); check()
                actual=words(io,address,1)[0]; check()
            entry['actualRaw']=actual; result[side+'Raw']=actual
            if actual != entry['requestedRaw']: raise ParkingError('readback_mismatch')
            entry['status']='verified'
        result['ok']=True
    except Exception as error:
        result['errorCode']=error_code(error)
    return result

class DriverIO:
    """Adapter around the already owned vendor client. Never opens a second port."""
    def __init__(self,client): self.client=client
    def read(self,address,count): return self.client.read_holding_registers(address,count)
    def zero_targets(self):
        errors=[]
        for method in (self.client.set_left_speed,self.client.set_right_speed):
            try: method(0)
            except Exception as e: errors.append(e)
        if errors: raise ParkingError('zero_target_unconfirmed')
    def stop(self,enabled):
        errors=[]
        for side in ('left','right'):
            try:self.client.stop(side,0 if enabled else 2)
            except Exception as e:errors.append(e)
        if errors:raise ParkingError('mode_write_unconfirmed')
    def write_current(self,side,raw): self.client.write_multiple_registers(CURRENT_REGISTERS[side],[raw])
    def close(self):pass

class ModbusIO:
    """Reuse the existing guarded fixed-UART codec without its general configuration gate."""
    def __init__(self,client,transport):self.client,self.transport=client,transport
    def read(self,address,count):return self.client.read(address,count)
    def zero_targets(self):
        errors=[]
        for address in (0x45,0x41):
            try:self.client.write(address,[0,0],action=True)
            except Exception as e:errors.append(e)
        if errors:raise ParkingError('zero_target_unconfirmed')
    def stop(self,enabled):
        errors=[]
        for address in (0x44,0x40):
            try:self.client.write(address,[0 if enabled else 2],action=True)
            except Exception as e:errors.append(e)
        if errors:raise ParkingError('mode_write_unconfirmed')
    def write_current(self,side,raw):self.client.write(CURRENT_REGISTERS[side],[raw],mode='persistent')
    def close(self):self.transport.close()

class SimulatedIO:
    """Explicit simulation: only in-memory words and a write trace, no imports of hardware."""
    def __init__(self):
        self.registers={i:0 for i in range(0x100)}
        self.registers.update({2:6000,0x58:1500,0x76:800,0x6D:800,0x1D:1,0x1E:0x38})
        self.trace=[]
    def read(self,address,count):return [self.registers[address+i] for i in range(count)]
    def zero_targets(self):
        self.trace.extend([(16,0x45,[0,0]),(16,0x41,[0,0])])
    def stop(self,enabled):self.trace.extend([(6,0x44,[0 if enabled else 2]),(6,0x40,[0 if enabled else 2])])
    def write_current(self,side,raw):
        address=CURRENT_REGISTERS[side];self.trace.append((16,address,[raw]));self.registers[address]=raw
    def close(self):pass
