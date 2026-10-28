#!/usr/bin/env python3
"""One SSH-owned joystick session using the board's existing LZ30EMA library.

Deploy beside keyboard_drive_auto_115200_linux.sh. Nothing listens on a TCP port.
--dry-run works without pyserial or the board library and never opens a device.
"""

from __future__ import annotations

import argparse
import contextlib
import hmac
import json
import math
import os
from pathlib import Path
import queue
import secrets
import signal
import subprocess
import sys
import threading
import time
from typing import Any

MAX_RPM = 100
WATCHDOG_SECONDS = 0.6
PARKING_TRANSACTION_SECONDS = 2.0
MAX_PARKING_CURRENT = 60
SERIAL_TIMEOUT = 0.15
MAX_LINE_BYTES = 512
PROJECT_ROOT = Path(__file__).resolve().parents[1]
REQUEST_KEYS = {"seq", "token", "cmd", "left", "right"}
PARKING_REQUEST_KEYS = REQUEST_KEYS | {"parkingEnabled", "parkingCurrent"}


class SessionError(Exception):
    pass


class Motors:
    """Control motion and the launcher's verified electronic parking-current setting."""

    def __init__(self, client: Any = None) -> None:
        self.client = client
        self.last_target: tuple[int, int] | None = None
        self.parking_enabled = False
        self.parking_current = 5

    def drive(self, left: int, right: int) -> None:
        if self.last_target == (left, right):
            return
        if self.client is not None:
            self.client.set_left_speed(left)
            self.client.set_right_speed(right)
        self.last_target = (left, right)

    def stop(self, *, force: bool = False, emergency: bool = False) -> None:
        if self.last_target == (0, 0) and not force and not emergency:
            return
        # Electronic parking locks the phase only after NORMAL stop (0).
        mode = 0 if self.parking_enabled and not emergency else 1
        errors = []
        if self.client is not None:
            # A timeout on one transaction MUST NOT skip either motor's other stops.
            operations = (
                ("left zero", lambda: self.client.set_left_speed(0)),
                ("right zero", lambda: self.client.set_right_speed(0)),
                ("left stop", lambda: self.client.stop("left", mode)),
                ("right stop", lambda: self.client.stop("right", mode)),
            )
            for name, operation in operations:
                try:
                    operation()
                except Exception as error:
                    errors.append(f"{name}: {type(error).__name__}: {error}")
        self.last_target = None if errors else (0, 0)
        if errors:
            raise SessionError("Stop was not confirmed: " + "; ".join(errors))

    def set_parking(self, enabled: bool, current: int) -> None:
        if type(enabled) is not bool or type(current) is not int or not 0 <= current <= MAX_PARKING_CURRENT:
            raise SessionError("Parking requires a boolean switch and integer current in [0, 60] A")
        if enabled and current == 0:
            raise SessionError("Enabled parking requires current above 0 A")
        if self.last_target != (0, 0):
            raise SessionError("Parking settings require confirmed stopped motors")
        changed = enabled != self.parking_enabled or (enabled and current != self.parking_current)
        if changed:
            self._write_parking_current(current if enabled else 0)
        self.parking_enabled = enabled
        self.parking_current = current
        if changed and enabled:
            # A prior emergency stop will not engage a newly configured parking current.
            self.stop(force=True)

    def _write_parking_current(self, current: int) -> None:
        if self.client is None:
            return
        errors = []
        registers = ("right_parking_current", "left_parking_current")
        for register in registers:
            try:
                # Match keyboard_drive.py: 0x10 persistence prevents old values returning
                # after a driver reset. Only explicit changes/initialization/exit write it.
                self.client.write_register(register, current, persist=True)
            except Exception as error:
                errors.append(f"{register} write: {type(error).__name__}: {error}")
        for register in registers:
            try:
                actual = float(self.client.read_register(register))
                if not math.isfinite(actual) or abs(actual - current) > 0.005:
                    raise SessionError(f"readback mismatch: requested={current} A actual={actual} A")
            except Exception as error:
                errors.append(f"{register} read: {type(error).__name__}: {error}")
        if errors:
            raise SessionError("Parking current was not confirmed: " + "; ".join(errors))

    def shutdown(self) -> None:
        """Always attempt both emergency stops AND both parking-current clears."""
        errors = []
        try:
            self.stop(force=True, emergency=True)
        except Exception as error:
            errors.append(str(error))
        try:
            self._write_parking_current(0)
            self.parking_enabled = False
        except Exception as error:
            errors.append(str(error))
        if errors:
            raise SessionError("; ".join(errors))

    def parking_state(self) -> dict[str, Any]:
        return {"parkingEnabled": self.parking_enabled, "parkingCurrent": self.parking_current}


def validate_request_keys(message: Any) -> None:
    if not isinstance(message, dict):
        raise SessionError("Request must be a JSON object")
    expected = PARKING_REQUEST_KEYS if message.get("cmd") == "parking" else REQUEST_KEYS
    if set(message) != expected:
        raise SessionError("Unexpected request fields for command")


def strict_message(line: bytes) -> dict[str, Any]:
    if not line or len(line) > MAX_LINE_BYTES:
        raise SessionError("Invalid request line length")

    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result = {}
        for name, value in pairs:
            if name in result:
                raise SessionError("Duplicate JSON field")
            result[name] = value
        return result

    def invalid_constant(value: str) -> None:
        raise SessionError(f"Invalid JSON constant: {value}")

    try:
        message = json.loads(line.decode("utf-8"), object_pairs_hook=unique_object,
                             parse_constant=invalid_constant)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise SessionError("Malformed JSON request") from error
    validate_request_keys(message)
    return message


class ProtocolSession:
    def __init__(self, motors: Motors, *, port: str, dry_run: bool) -> None:
        self.motors = motors
        self.port = port
        self.dry_run = dry_run
        self.token = secrets.token_hex(16)
        self.next_seq = 1

    def ready(self) -> dict[str, Any]:
        # Match the keyboard launcher's initial parking OFF state, with readback.
        self.motors.shutdown()
        return {"type": "ready", "token": self.token, "dryRun": self.dry_run,
                "port": self.port, "maxRpm": MAX_RPM,
                "watchdogMs": int(WATCHDOG_SECONDS * 1000), "parkingSupported": True,
                **self.motors.parking_state()}

    def accept(self, message: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        validate_request_keys(message)
        seq, token = message["seq"], message["token"]
        if type(seq) is not int or seq != self.next_seq or seq > 2**63 - 1:
            raise SessionError("Unexpected request sequence")
        if not isinstance(token, str) or len(token) != 32 or not token.isascii() or \
                not hmac.compare_digest(token, self.token):
            raise SessionError("Stale or invalid request token")
        command = message["cmd"]
        left, right = message["left"], message["right"]
        if type(left) is not int or type(right) is not int or \
                not -MAX_RPM <= left <= MAX_RPM or not -MAX_RPM <= right <= MAX_RPM:
            raise SessionError(f"Motor RPM must be integers in [-{MAX_RPM}, {MAX_RPM}]")
        if command not in ("drive", "stop", "estop", "close", "parking"):
            raise SessionError("Unknown command")
        if command != "drive" and (left != 0 or right != 0):
            raise SessionError("Non-drive commands require zero motor targets")
        closing = command in ("estop", "close")
        if command == "parking":
            self.motors.set_parking(message["parkingEnabled"], message["parkingCurrent"])
        elif closing:
            self.motors.shutdown()
        elif command != "drive" or (left, right) == (0, 0):
            self.motors.stop()
            left, right = 0, 0
        else:
            self.motors.drive(left, right)
        self.next_seq += 1
        self.token = secrets.token_hex(16)
        return {"type": "ack", "seq": seq, "token": self.token, "left": left,
                "right": right, "stopped": (left, right) == (0, 0),
                **self.motors.parking_state()}, closing


class InputPump:
    """Bounded input buffering; a partial line cannot keep the lease alive."""

    def __init__(self, fd: int) -> None:
        self.fd = fd
        self.chunks: queue.Queue[bytes | Exception] = queue.Queue(maxsize=1)
        self.buffer = bytearray()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        try:
            while True:
                chunk = os.read(self.fd, MAX_LINE_BYTES + 1)
                self.chunks.put(chunk)
                if not chunk:
                    return
        except Exception as error:
            self.chunks.put(error)

    def line(self, deadline: float, stopped: threading.Event) -> bytes:
        while True:
            if stopped.is_set():
                raise SessionError("Session terminated")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise SessionError("Control watchdog expired")
            split = self.buffer.find(b"\n")
            if split >= 0:
                if split > MAX_LINE_BYTES:
                    raise SessionError("Request line too long")
                line = bytes(self.buffer[:split])
                del self.buffer[:split + 1]
                return line
            if len(self.buffer) > MAX_LINE_BYTES:
                raise SessionError("Request line too long")
            try:
                chunk = self.chunks.get(timeout=min(remaining, 0.05))
            except queue.Empty:
                continue
            if isinstance(chunk, Exception):
                raise SessionError("Control input failed") from chunk
            if not chunk:
                raise SessionError("Control input closed")
            self.buffer.extend(chunk)


class JsonOutput:
    def __init__(self, fd: int) -> None:
        self.fd = fd
        # stdout is an SSH pipe. Never wait for a stalled client while motors run.
        os.set_blocking(fd, False)

    def send(self, payload: dict[str, Any]) -> None:
        data = (json.dumps(payload, ensure_ascii=True, separators=(",", ":")) + "\n").encode()
        if len(data) > 1024:
            raise SessionError("Response too large")
        if os.write(self.fd, data) != len(data):
            raise SessionError("Control output blocked")


def run_session(motors: Motors, port: str, dry_run: bool, stopped: threading.Event) -> int:
    output = JsonOutput(sys.stdout.fileno())
    session = ProtocolSession(motors, port=port, dry_run=dry_run)
    failed = False
    error_message = None
    cleanup_confirmed = False
    try:
        output.send(session.ready())
        deadline = time.monotonic() + WATCHDOG_SECONDS
        pump = InputPump(sys.stdin.fileno())
        while True:
            message = strict_message(pump.line(deadline, stopped))
            received = time.monotonic()
            if received >= deadline or stopped.is_set():
                raise SessionError("Control watchdog expired or session terminated")
            response, closing = session.accept(message)
            # Terminal acceptance already verified emergency stop and both current clears.
            # Even if sending its ACK fails, repeating persistent writes adds no safety.
            cleanup_confirmed = closing
            completed = time.monotonic()
            parking = message["cmd"] == "parking"
            # Only configuration with already stopped motors may outlive the motion lease.
            # Serial transactions themselves remain bounded by SERIAL_TIMEOUT.
            limit = PARKING_TRANSACTION_SECONDS if parking else WATCHDOG_SECONDS
            if not closing and completed >= received + limit:
                raise SessionError("Parking transaction exceeded timeout" if parking else
                                   "Motor transaction exceeded control watchdog")
            if stopped.is_set():
                raise SessionError("Session terminated")
            output.send(response)
            if closing:
                break
            deadline = (completed if parking else received) + WATCHDOG_SECONDS
    except Exception as error:
        failed = True
        error_message = f"{type(error).__name__}: {error}"
    finally:
        if not cleanup_confirmed:
            try:
                motors.shutdown()
            except Exception as error:
                failed = True
                error_message = f"{error_message + '; ' if error_message else ''}{error}"
        if error_message:
            with contextlib.suppress(Exception):
                output.send({"type": "error", "error": error_message[:700]})
    return 1 if failed else 0


def reject_conflicting_processes() -> None:
    # These are the current launcher's conflicts plus other known motor entrypoints.
    names = {"keyboard_drive.py", "drive_server.py", "motor_gui.py", "control_motor.py",
             "request_0513_modular.py", "request_0428_modular.py", "request_continuous_follow.py",
             "icm20600.py", "motor_direction_control.py", "motor_direction_controlauto.py",
             "motor_direction_controlautobrake.py", "motor_direction_control_new.py"}
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit() or int(entry.name) == os.getpid():
            continue
        try:
            args = (entry / "cmdline").read_bytes().split(b"\0")
        except (OSError, PermissionError):
            continue
        for arg in args:
            name = os.path.basename(os.fsdecode(arg))
            if name in names:
                raise SessionError(f"Motor/IMU process already running: PID {entry.name}, {name}")


def reject_other_serial_owners(port: str) -> None:
    # TIOCEXCL prevents future opens but does not evict an already-open descriptor.
    # Check before AND after acquiring it, without stopping any other process.
    result = subprocess.run(["fuser", port], capture_output=True, text=True, timeout=1.0)
    if result.returncode not in (0, 1):
        raise SessionError(f"Cannot check serial ownership for {port}")
    owners = [int(value) for value in result.stdout.split() if value.isdigit()]
    if any(owner != os.getpid() for owner in owners):
        raise SessionError(f"Another process already owns {port}")


@contextlib.contextmanager
def open_hardware(port_choice: str):
    import fcntl
    import termios

    source = PROJECT_ROOT / "src"
    sys.path.insert(0, str(source))
    from lz30ema_rs485 import LZ30EMAClient
    import serial

    lock_path = PROJECT_ROOT / ".app_joystick.lock"
    with lock_path.open("a") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise SessionError("Another App joystick session owns the motors") from error
        reject_conflicting_processes()
        candidates = ("/dev/ttyS0", "/dev/ttyS3") if port_choice == "auto" else (port_choice,)
        selected = None
        failures = []
        try:
            for port in candidates:
                transport = None
                try:
                    reject_other_serial_owners(port)
                    transport = serial.Serial(port=port, baudrate=115200, bytesize=8, parity="N",
                                              stopbits=1, timeout=SERIAL_TIMEOUT,
                                              write_timeout=SERIAL_TIMEOUT, exclusive=True)
                    # Prevent later non-cooperating open() calls as well as cooperating flock users.
                    fcntl.ioctl(transport.fileno(), termios.TIOCEXCL)
                    reject_other_serial_owners(port)
                    client = LZ30EMAClient(transport, slave=1)
                    client.read_device_info()  # Probe only; never initialize settings or telemetry.
                    selected = client
                    yield Motors(client), port
                    return
                except (SessionError, KeyboardInterrupt):
                    raise
                except Exception as error:
                    if selected is not None:
                        raise
                    failures.append(f"{port}: {type(error).__name__}: {error}")
                finally:
                    if transport is not None:
                        transport.close()
            raise SessionError("No RS485 driver found; " + "; ".join(failures))
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--port", default="auto", choices=("auto", "/dev/ttyS0", "/dev/ttyS3"))
    args = parser.parse_args()
    stopped = threading.Event()
    for name in ("SIGTERM", "SIGINT", "SIGHUP"):
        value = getattr(signal, name, None)
        if value is not None:
            signal.signal(value, lambda *_: stopped.set())
    try:
        if args.dry_run:
            return run_session(Motors(), "dry-run", True, stopped)
        with open_hardware(args.port) as (motors, port):
            return run_session(motors, port, False, stopped)
    except Exception as error:
        with contextlib.suppress(Exception):
            JsonOutput(sys.stdout.fileno()).send({"type": "error", "error": f"{type(error).__name__}: {error}"[:700]})
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
