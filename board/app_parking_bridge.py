#!/usr/bin/env python3
"""One bounded idle parking request; never starts video, joystick or follow programs."""
from contextlib import nullcontext
import argparse
import json
import sys
from app_parking_settings import (ParkingError, SimulatedIO, ModbusIO, error_code,
                                  execute, response, validate_request)

def process(request, *, simulate=False, system=None, io_factory=None):
    result=response(request if isinstance(request,dict) else {})
    result['simulation']=simulate
    try:
        validate_request(request)
        if not simulate and (system is None or not system.is_root()):raise ParkingError('root_required_for_complete_idle_probe')
        with nullcontext() if simulate else system.idle_guard():
            if io_factory:io=io_factory()
            elif simulate:io=SimulatedIO()
            else:
                from app_motor_config_bridge import SerialPort, Modbus
                transport=SerialPort(owner_check=system.verify_open_serial_owner)
                io=ModbusIO(Modbus(transport,timeout=.15),transport)
            try:
                result=execute(request,io)
                result["simulation"]=simulate
            finally:
                try:io.close()
                except Exception:
                    result["ok"]=False
                    result["errorCode"]=result["errorCode"] or "parking_close_failed"
            return result
    except Exception as e:
        result['errorCode']=error_code(e)
        return result

def read_request(stream):
    # SSH may keep stdin open after flushing a request; newline completes this
    # single-request process without waiting for transport EOF.
    raw=stream.readline(2049)
    if len(raw)>2048:raise ParkingError('request_too_large')
    def unique(pairs):
        result={}
        for k,v in pairs:
            if k in result:raise ParkingError('duplicate_json_key')
            result[k]=v
        return result
    try:return json.loads(raw,object_pairs_hook=unique,parse_constant=lambda _:(_ for _ in ()).throw(ParkingError('invalid_number')))
    except (ValueError,UnicodeError):raise ParkingError('invalid_json') from None

def main(argv=None,stdin=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request',action='store_true')
    parser.add_argument('--simulate',action='store_true')
    args=parser.parse_args(argv)
    try:
        request=read_request(stdin or sys.stdin.buffer)
        system=None
        if not args.simulate:
            from app_control_guard import ControlGuard
            system=ControlGuard()
        result=process(request,simulate=args.simulate,system=system)
    except Exception as e:
        result=response({});result['simulation']=args.simulate;result['errorCode']=error_code(e)
    if not args.simulate:
        try:
            from app_diagnostic_log import DiagnosticLog
            from pathlib import Path
            import secrets
            log=DiagnosticLog(Path(__file__).resolve().parents[1]/'app_parking_diagnostic_logs',secrets.token_hex(16))
            log.event('parking.settings_result',critical=True,requestId=result['requestId'],op=result['op'],
                ok=result['ok'],errorCode=result['errorCode'],writeMayHaveOccurred=result['writeMayHaveOccurred'],
                mode=result['mode'],modeSource=result['modeSource'],results=result['results'])
            log.close(timeout=.1)
        except Exception:
            pass
    print(json.dumps(result,ensure_ascii=True,separators=(',',':'),allow_nan=False),flush=True)
    return 0 if result['ok'] else 1

if __name__=='__main__':raise SystemExit(main())
