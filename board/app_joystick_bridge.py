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

try:
    from app_diagnostic_log import DiagnosticLog, NullDiagnosticLog
except Exception:
    DiagnosticLog = None

    class NullDiagnosticLog:
        """A missing optional logger must never disable parking or heartbeat handling."""
        available = False

        def event(self, *_args, **_kwargs):
            pass

        def failure(self, *_args, **_kwargs):
            pass

        def close(self, *_args, **_kwargs):
            pass


class BestEffortDiagnostic:
    """Even an unavailable optional writer cannot interrupt STOP or control cleanup."""

    def __init__(self, writer):
        self.writer = writer

    def event(self, *args, **kwargs):
        with contextlib.suppress(Exception):
            self.writer.event(*args, **kwargs)

    def failure(self, *args, **kwargs):
        with contextlib.suppress(Exception):
            self.writer.failure(*args, **kwargs)

    def close(self, *args, **kwargs):
        with contextlib.suppress(Exception):
            self.writer.close(*args, **kwargs)

MAX_RPM = 100
WATCHDOG_SECONDS = 0.6
NETWORK_GRACE_SECONDS = 5.0
PARKING_TRANSACTION_SECONDS = 2.0
MAX_PARKING_CURRENT = 60
SAFETY_VERSION = 2
FEEDBACK_INTERVAL_SECONDS = 0.1
FEEDBACK_MAX_AGE_SECONDS = 0.25
STOP_REFRESH_SECONDS = 0.2
STANDSTILL_TIMEOUT_SECONDS = 1.5
MOTION_RECHECK_MAX_RPM = 2
MOTION_RECHECK_SECONDS = 0.6
SERIAL_TIMEOUT = 0.15
IR_POLL_SECONDS = 0.02
MAX_LINE_BYTES = 512
PROJECT_ROOT = Path(__file__).resolve().parents[1]
REQUEST_KEYS = {"seq", "token", "cmd", "left", "right"}
PARKING_REQUEST_KEYS = REQUEST_KEYS | {"parkingEnabled"}


class SessionError(Exception):
    pass


class MotionLeaseExpired(SessionError):
    """Only an input deadline can enter network grace; other errors remain terminal."""
    pass


class InfraredInterlock:
    """Read the three board IR inputs without writing to the SSH JSON stream."""

    DEVICES = {"forward": 3, "right": 4, "left": 5}

    def __init__(self, root: Path = Path("/sys/bus/iio/devices"), *, trigger_value: int = 0) -> None:
        if trigger_value not in (0, 1):
            raise ValueError("IR trigger value must be 0 or 1")
        self.root = Path(root)
        self.trigger_value = trigger_value

    def blocked_directions(self) -> set[str]:
        blocked = set()
        for direction, device in self.DEVICES.items():
            try:
                raw = int((self.root / f"iio:device{device}" / "in_proximity_raw").read_text(encoding="ascii").strip())
                if raw not in (0, 1):
                    raise ValueError("IR value must be 0 or 1")
                triggered = raw == self.trigger_value
            except (OSError, ValueError):
                triggered = True
            if triggered:
                blocked.add(direction)
        return blocked


class Motors:
    """Session control, with read-only parking configuration and measured standstill.

    No path in this class writes configuration registers. A parking switch selects
    NORMAL/EMERGENCY stop mode; it never invents a current or clears saved settings.
    """

    def __init__(self, client: Any = None, *, infrared: InfraredInterlock | None = None) -> None:
        self.client, self.infrared = client, infrared
        self.last_target: tuple[int, int] | None = None
        self.requested_target = (0, 0)
        self.ir_latched_directions: set[str] = set()
        self.parking_enabled = False
        self.emergency_hold = True
        self.parking_left_current = self.parking_right_current = 5.0 if client is None else 0.0
        self.parking_max_current = 60.0 if client is None else 0.0
        self.feedback = (0, 0, 0, 0)  # left/right RPM, left/right fault code
        self.feedback_at: float | None = None
        self.zero_observations: list[float] = []
        self.zero_samples = 0
        self.last_zero_at: float | None = None
        self.last_stop_at: float | None = None
        self.stop_requested_at: float | None = None
        self.standstill_seen = False
        self.motion_recheck_at: float | None = None
        self.motion_recovery_required = False
        self.standstill_wait_at: float | None = None
        self.standstill_pending = False
        self.fault_reason: str | None = None
        self.diagnostic = BestEffortDiagnostic(NullDiagnosticLog())

    def _require_healthy(self) -> None:
        if self.fault_reason:
            raise SessionError(self.fault_reason)

    def _write_stop(self, *, emergency: bool) -> None:
        """Attempt every zero/stop even if one side fails; never read or write settings."""
        previous = self.last_target
        if emergency:
            self.emergency_hold = True
        mode = 0 if self.parking_enabled and not emergency else 1
        errors = []
        if self.client is not None:
            for name, operation in (
                ("left zero", lambda: self.client.set_left_speed(0)),
                ("right zero", lambda: self.client.set_right_speed(0)),
                ("left stop", lambda: self.client.stop("left", mode)),
                ("right stop", lambda: self.client.stop("right", mode)),
            ):
                try:
                    operation()
                except Exception as error:
                    errors.append(f"{name}: {type(error).__name__}: {error}")
        now = time.monotonic()
        self.last_stop_at = now
        self.last_target = None if errors else (0, 0)
        if previous != (0, 0) or self.stop_requested_at is None:
            self.stop_requested_at = now
            self.zero_samples = 0
            self.last_zero_at = None
        if errors:
            raise SessionError("Stop write was not confirmed: " + "; ".join(errors))

    def _trip(self, reason: str) -> None:
        # Keep the initiating feedback evidence; cleanup must not replace it.
        if self.fault_reason is None:
            self.fault_reason = reason
            self.diagnostic.event("drive.hardware_fault", critical=True, reason=reason,
                                  target=self.last_target, **self.feedback_state())
        self.parking_enabled = False
        self.zero_samples = 0
        try:
            self._write_stop(emergency=True)
        except Exception as error:
            self.diagnostic.event("drive.fault_stop_failed", critical=True,
                                  errorClass=type(error).__name__)
        raise SessionError(self.fault_reason)

    def _enter_standstill_wait(self) -> None:
        """Retain the emergency response to slow stopping, without ending ownership."""
        if self.standstill_pending:
            return
        self.standstill_wait_at = time.monotonic()
        self.standstill_pending = True
        parking_was_enabled = self.parking_enabled
        self.parking_enabled = False
        self.emergency_hold = True
        self.diagnostic.event("drive.standstill_wait_started", critical=True,
            reason="normal_stop_confirmation_timeout", parkingWasEnabled=parking_was_enabled,
            **self.feedback_state(), **self.parking_state())
        try:
            self._write_stop(emergency=True)
        except Exception as error:
            self._trip(f"Standstill emergency stop failed: {error}")

    @staticmethod
    def _signed32(high: int, low: int) -> int:
        value = (high << 16) | low
        return value - 2**32 if value >= 2**31 else value

    def _sample_feedback(self, *, force: bool = False, cleanup: bool = False,
                         allow_standstill_wait: bool = True) -> None:
        now = time.monotonic()
        if not cleanup and self.motion_recheck_at is not None and (
            now - self.motion_recheck_at >= MOTION_RECHECK_SECONDS
        ):
            self._trip(f"Motion feedback recheck timed out: left={self.feedback[0]}, right={self.feedback[1]} RPM")
        if not force and self.feedback_at is not None and now - self.feedback_at < FEEDBACK_INTERVAL_SECONDS:
            return
        try:
            if self.client is None:
                left, right = self.last_target or (0, 0)
                values = (left, right, 0, 0)
            else:
                # One contiguous FC03 gives both signed speeds and fault codes.
                words = self.client.read_holding_registers(0x0009, 13)
                if not isinstance(words, (list, tuple)) or len(words) != 13 or any(
                    type(word) is not int or not 0 <= word <= 65535 for word in words
                ):
                    raise SessionError("Invalid motor feedback registers")
                values = (self._signed32(words[10], words[11]), self._signed32(words[0], words[1]),
                          words[12], words[2])
        except Exception as error:
            self.feedback_at = None
            self.zero_samples = 0
            if cleanup:
                raise SessionError("Cleanup feedback unavailable") from error
            self._trip(f"Motor feedback unavailable: {type(error).__name__}: {error}")
        observed_at = time.monotonic()
        if self.last_zero_at is not None and observed_at - self.last_zero_at > FEEDBACK_MAX_AGE_SECONDS:
            # A single new zero reading must not revive an old pair of samples.
            self.zero_samples, self.last_zero_at = 0, None
        self.feedback, self.feedback_at = values, observed_at
        left, right, left_error, right_error = values
        if left_error or right_error:
            self.zero_samples = 0
            if cleanup:
                raise SessionError(f"Motor fault remains during cleanup: {left_error}/{right_error}")
            self._trip(f"Motor fault feedback: left={left_error}, right={right_error}")
        # A low-amplitude episode has a fixed deadline. A late zero or a forced
        # duplicate read cannot extend it or clear it without two fresh samples.
        if not cleanup and self.motion_recheck_at is not None and (
            observed_at - self.motion_recheck_at >= MOTION_RECHECK_SECONDS
        ):
            self._trip(f"Motion feedback recheck timed out: left={left}, right={right} RPM")
        if self.last_target != (0, 0):
            self.zero_samples, self.last_zero_at = 0, None
            return
        if left != 0 or right != 0:
            self.zero_samples, self.last_zero_at = 0, None
            if not cleanup and self.standstill_seen:
                if max(abs(left), abs(right)) > MOTION_RECHECK_MAX_RPM:
                    self._trip(f"Unexpected motion after standstill: left={left}, right={right} RPM")
                if self.motion_recheck_at is None:
                    self.motion_recheck_at = observed_at
                    self.motion_recovery_required = True
                    self.diagnostic.event("drive.feedback_recheck_started", critical=True,
                        maxRpm=MOTION_RECHECK_MAX_RPM, timeoutMs=int(MOTION_RECHECK_SECONDS * 1000),
                        **self.feedback_state())
            if not cleanup and not self.standstill_seen and self.stop_requested_at is not None and (
                self.feedback_at - self.stop_requested_at >= STANDSTILL_TIMEOUT_SECONDS
            ):
                if allow_standstill_wait:
                    self._enter_standstill_wait()
                else:
                    self._trip(f"Standstill timeout: left={left}, right={right} RPM")
            return
        # Distinct hardware observations are separated in time, not duplicate cached reads.
        if self.last_zero_at is None or self.client is None or (
            self.feedback_at - self.last_zero_at >= FEEDBACK_INTERVAL_SECONDS
        ):
            self.zero_observations.append(self.feedback_at)
            self.zero_observations = self.zero_observations[-2:]
            self.last_zero_at = self.feedback_at
        if self.zero_samples >= 2:
            self.standstill_seen = True
            if self.standstill_wait_at is not None:
                elapsed = observed_at - self.standstill_wait_at
                self.standstill_wait_at = None
                self.diagnostic.event("drive.standstill_wait_zero_confirmed", critical=True,
                    elapsedMs=int(elapsed * 1000), releaseRequired=self.standstill_pending,
                    **self.feedback_state())
            if self.motion_recheck_at is not None:
                elapsed = observed_at - self.motion_recheck_at
                self.motion_recheck_at = None
                self.diagnostic.event("drive.feedback_recheck_zero_confirmed", critical=True,
                    elapsedMs=int(elapsed * 1000), releaseRequired=self.motion_recovery_required,
                    **self.feedback_state())

    @property
    def zero_samples(self) -> int:
        now = time.monotonic()
        return sum(0 <= now - observed <= FEEDBACK_MAX_AGE_SECONDS for observed in self.zero_observations)

    @zero_samples.setter
    def zero_samples(self, value: int) -> None:
        if value != 0:
            raise ValueError("Zero samples must come from distinct feedback observations")
        self.zero_observations = []

    @property
    def stopped_confirmed(self) -> bool:
        return (self.fault_reason is None and self.last_target == (0, 0) and self.zero_samples >= 2
                and self.motion_recheck_at is None
                and self.standstill_wait_at is None
                and self.feedback == (0, 0, 0, 0) and self.feedback_at is not None
                and time.monotonic() - self.feedback_at <= FEEDBACK_MAX_AGE_SECONDS)

    def feedback_state(self) -> dict[str, Any]:
        age = None if self.feedback_at is None else max(0, int((time.monotonic() - self.feedback_at) * 1000))
        return {"feedbackLeftRpm": self.feedback[0], "feedbackRightRpm": self.feedback[1],
                "feedbackLeftError": self.feedback[2], "feedbackRightError": self.feedback[3],
                "feedbackAgeMs": age, "feedbackSamples": self.zero_samples,
                "feedbackSource": "simulated" if self.client is None else "hardware"}

    @property
    def feedback_recovery_required(self) -> bool:
        return self.motion_recovery_required or self.standstill_pending

    def release_motion_recovery(self) -> None:
        if self.motion_recheck_at is not None or self.standstill_wait_at is not None or not self.stopped_confirmed:
            return
        if self.standstill_pending:
            self.standstill_pending = False
            self.emergency_hold = False
            self.diagnostic.event("drive.standstill_wait_released", critical=True, **self.feedback_state())
        if self.motion_recovery_required:
            self.motion_recovery_required = False
            self.diagnostic.event("drive.feedback_recheck_released", critical=True, **self.feedback_state())

    def await_standstill(self, *, cleanup: bool = False, deadline: float | None = None) -> None:
        deadline = min(time.monotonic() + STANDSTILL_TIMEOUT_SECONDS,
                       deadline if deadline is not None else float("inf"))
        while True:
            if time.monotonic() >= deadline:
                if cleanup:
                    raise SessionError("Cleanup standstill not confirmed")
                self._trip("Standstill confirmation timed out")
            # Startup, parking transitions and terminal cleanup retain their
            # bounded confirmation contract; only normal session polling may wait.
            self._sample_feedback(force=True, cleanup=cleanup, allow_standstill_wait=False)
            measured = self.last_target == (0, 0) and self.zero_samples >= 2 and self.feedback == (0, 0, 0, 0)
            if time.monotonic() >= deadline:
                if cleanup:
                    raise SessionError("Cleanup standstill not confirmed")
                self._trip("Standstill confirmation timed out")
            if measured:
                return
            if self.last_stop_at is None or time.monotonic() - self.last_stop_at >= STOP_REFRESH_SECONDS:
                self._write_stop(emergency=cleanup or not self.parking_enabled)
            if self.client is not None:
                time.sleep(FEEDBACK_INTERVAL_SECONDS)

    def initialize(self) -> None:
        self.parking_enabled = False
        self._write_stop(emergency=True)
        self.await_standstill()
        if self.client is not None:
            try:
                values = [self.client.read_register(name) for name in
                          ("left_parking_current", "right_parking_current", "max_output_current")]
                if any(type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 60
                       for value in values) or values[2] <= 0:
                    raise SessionError("Invalid saved parking/current-limit configuration")
                self.parking_left_current, self.parking_right_current, self.parking_max_current = map(float, values)
            except Exception as error:
                self._trip(f"Parking configuration read failed: {type(error).__name__}: {error}")
        # Config reads may consume a few transactions; READY still needs fresh feedback.
        self.await_standstill()
        self.diagnostic.event("parking.configuration_read", critical=True,
                              **self.parking_state(), **self.feedback_state())

    def drive(self, left: int, right: int) -> tuple[int, int]:
        self._require_healthy()
        if (left, right) == (0, 0):
            self.stop()
            return (0, 0)
        self.poll_safety()
        # Even after feedback recovers, discard held/queued motion until an
        # explicit neutral request has acknowledged the new zero-speed evidence.
        if self.feedback_recovery_required:
            return (0, 0)
        self.requested_target = (left, right)
        if self.check_infrared():
            return (0, 0)
        if self.last_target == (left, right):
            return self.last_target
        self.zero_samples, self.last_zero_at = 0, None
        self.standstill_seen = False
        self.stop_requested_at = None
        try:
            if self.client is not None:
                self.client.set_left_speed(left)
                self.client.set_right_speed(right)
            self.last_target = (left, right)
        except Exception as error:
            self._trip(f"Motor target write failed: {type(error).__name__}: {error}")
        self.check_infrared()
        return self.last_target

    def check_infrared(self) -> bool:
        if self.infrared is None or self.requested_target == (0, 0):
            return False
        directions = self.requested_directions()
        self.ir_latched_directions.update(directions & self.infrared.blocked_directions())
        if not directions & self.ir_latched_directions:
            return False
        if self.last_target != (0, 0):
            self.stop(emergency=True, release_interlock=False)
        return True

    def requested_directions(self) -> set[str]:
        left, right = self.requested_target
        directions = set()
        # Raw motor signs on this board match keyboard --swap-turns:
        # W=(+,-), D=(+,+), A=(-,-). Test both components for curved paths.
        if left - right > 0:
            directions.add("forward")
        if left + right > 0:
            directions.add("right")
        elif left + right < 0:
            directions.add("left")
        return directions

    def infrared_state(self) -> dict[str, Any]:
        # Pure snapshot. Protocol formatting never performs another sysfs read.
        directions = sorted(self.requested_directions() & self.ir_latched_directions)
        blocked = (self.infrared is not None and self.requested_target != (0, 0)
                   and self.last_target == (0, 0) and bool(directions))
        return {"infraredBlocked": blocked, "infraredDirections": directions if blocked else []}

    def stop(self, *, force: bool = False, emergency: bool = False, release_interlock: bool = True) -> None:
        refresh = (force or emergency or self.last_target != (0, 0) or self.last_stop_at is None
                   or time.monotonic() - self.last_stop_at >= STOP_REFRESH_SECONDS)
        if not emergency:
            self._require_healthy()
            if refresh and self.last_target == (0, 0):
                # A queued neutral heartbeat can bypass InputPump.poll. Inspect the
                # motor before a refresh could erase unexpected-motion evidence.
                self._sample_feedback(force=True)
        if release_interlock:
            self.requested_target = (0, 0)
            self.ir_latched_directions.clear()
            self.emergency_hold = emergency or self.standstill_pending
        if refresh:
            try:
                self._write_stop(emergency=emergency or self.emergency_hold)
            except Exception as error:
                self._trip(f"Stop transaction failed: {error}")

    def poll_safety(self) -> None:
        self._require_healthy()
        self.check_infrared()
        # Check feedback BEFORE refreshing stop, so an unexpected movement is not
        # erased from evidence by a successful corrective write.
        self._sample_feedback()
        if self.last_target == (0, 0):
            self.stop(release_interlock=False)

    def set_parking(self, enabled: bool) -> None:
        self._require_healthy()
        transaction_deadline = time.monotonic() + PARKING_TRANSACTION_SECONDS
        if type(enabled) is not bool:
            raise SessionError("Parking requires a boolean switch; current configuration is read-only")
        if self.standstill_pending:
            raise SessionError("Parking unavailable until standstill recovery is released")
        if self.last_target != (0, 0):
            raise SessionError("Parking requires zero targets and measured standstill")
        self.await_standstill(deadline=transaction_deadline)
        if not self.stopped_confirmed:
            raise SessionError("Parking requires fresh measured standstill")
        if enabled and (not 0 < self.parking_left_current <= self.parking_max_current
                        or not 0 < self.parking_right_current <= self.parking_max_current):
            raise SessionError("Parking current must be configured for both motors within the output-current limit")
        self.diagnostic.event("parking.mode_requested", critical=True, enabled=enabled,
                              **self.parking_state(), **self.feedback_state())
        self.parking_enabled = enabled
        self.emergency_hold = not enabled
        # Both ON and OFF reissue zero speed and stop; never modify current parameters.
        self.stop(force=True, emergency=not enabled, release_interlock=False)
        # Require two NEW observations after the mode change, retaining the prior
        # standstill latch: bounded tiny feedback gets rechecked; larger motion faults.
        self.zero_samples, self.last_zero_at = 0, None
        self.await_standstill(deadline=transaction_deadline)
        self.diagnostic.event("parking.mode_confirmed", critical=True,
                              **self.parking_state(), **self.feedback_state())

    def shutdown(self) -> None:
        """Emergency stop plus physical feedback, preserving every saved parameter."""
        self.parking_enabled = False
        self.requested_target = (0, 0)
        self.ir_latched_directions.clear()
        self._write_stop(emergency=True)
        self.zero_samples, self.last_zero_at = 0, None
        self.await_standstill(cleanup=True)

    def parking_state(self) -> dict[str, Any]:
        return {"parkingEnabled": self.parking_enabled,
                "parkingLeftCurrentAmps": self.parking_left_current,
                "parkingRightCurrentAmps": self.parking_right_current,
                "parkingMaxCurrentAmps": self.parking_max_current, "parkingCurrentReadOnly": True}


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
        self.run_id = secrets.token_hex(16)
        self.recovery_required = False

    @property
    def recovery_active(self) -> bool:
        return self.recovery_required or self.motors.feedback_recovery_required

    def pause_for_network(self) -> None:
        # Do not use shutdown(): network grace must not write parking registers.
        # Keep any IR latch until an explicit neutral request releases it. Neither
        # IR polling nor a delayed held target is allowed to restart motion.
        self.recovery_required = True
        self.motors.stop(force=True, emergency=True, release_interlock=False)

    def ready(self) -> dict[str, Any]:
        self.motors.initialize()
        return {"type": "ready", "token": self.token, "dryRun": self.dry_run, "runId": self.run_id,
                "port": self.port, "maxRpm": MAX_RPM,
                "watchdogMs": int(WATCHDOG_SECONDS * 1000), "parkingSupported": True,
                "safetyVersion": SAFETY_VERSION, "left": 0, "right": 0,
                "targetStopped": True, "stopped": self.motors.stopped_confirmed,
                **self.motors.feedback_state(),
                "networkGraceMs": int(NETWORK_GRACE_SECONDS * 1000),
                "motionRecheckMaxRpm": MOTION_RECHECK_MAX_RPM,
                "motionRecheckMs": int(MOTION_RECHECK_SECONDS * 1000),
                "standstillWaitSupported": True,
                "infraredSupported": self.motors.infrared is not None,
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
        feedback_recheck = self.motors.feedback_recovery_required
        standstill_pending = self.motors.standstill_pending
        recovering = self.recovery_active
        neutral = command == "stop" or (command == "drive" and (left, right) == (0, 0))
        if command == "parking":
            self.motors.set_parking(message["parkingEnabled"])
        elif closing:
            self.motors.shutdown()
        elif recovering:
            # A delayed held joystick target must never restart motion after the lease expired.
            if neutral:
                # The network pause used emergency stop even when parking was enabled.
                # Reapply its confirmed normal-stop mode without rewriting its registers.
                # Network recovery reapplies the stop once. A long physical
                # standstill wait uses the existing 200 ms refresh cadence.
                self.motors.stop(force=self.recovery_required or not self.motors.standstill_pending)
                self.recovery_required = False
            left, right = 0, 0
        elif command != "drive" or (left, right) == (0, 0):
            self.motors.stop()
            left, right = 0, 0
        else:
            left, right = self.motors.drive(left, right)
        if closing or command == "parking":
            # These transactions already ended with two fresh zero observations.
            # A redundant sample must not turn their success into an unconfirmed
            # terminal ACK that would skip cleanup on the way out.
            if not self.motors.stopped_confirmed:
                raise SessionError("Terminal or parking standstill confirmation is no longer fresh")
        else:
            self.motors._sample_feedback(force=True)
        # The recheck can start in drive.poll or in the final feedback sample,
        # after the original request branch was chosen. Its ACK must still
        # explain a suppressed target and discard stale input on the App.
        feedback_recheck = feedback_recheck or self.motors.feedback_recovery_required
        standstill_pending = standstill_pending or self.motors.standstill_pending
        recovering = recovering or self.recovery_active
        if neutral:
            self.motors.release_motion_recovery()
        infrared = (self.motors.infrared_state() if command == "drive" else
                    {"infraredBlocked": False, "infraredDirections": []})
        self.next_seq += 1
        self.token = secrets.token_hex(16)
        return {"type": "ack", "safetyVersion": SAFETY_VERSION, "seq": seq, "token": self.token, "left": left,
                "right": right, "targetStopped": (left, right) == (0, 0),
                "stopped": (left, right) == (0, 0) and self.motors.stopped_confirmed,
                **self.motors.feedback_state(),
                # Neutral clears the latch for subsequent requests, but its own ACK must
                # still tell the App to discard the held target and require fresh input.
                "recoveryRequired": recovering,
                "feedbackRecheck": feedback_recheck,
                "standstillPending": standstill_pending,
                **self.motors.parking_state(), **infrared}, closing


class InputPump:
    """Bounded input buffering; a partial line cannot keep the lease alive."""

    def __init__(self, fd: int) -> None:
        self.fd = fd
        self.poll = lambda: None
        self.chunks: queue.Queue[bytes | Exception] = queue.Queue(maxsize=1)
        self.buffer = bytearray()
        self.max_poll_seconds = 0.0
        self.last_poll_seconds = 0.0
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
                raise MotionLeaseExpired("Control watchdog expired")
            # Run on the motor-owning thread even when no new complete request
            # arrives. A partial line must not postpone IR protection.
            poll_started = time.monotonic()
            try:
                self.poll()
            finally:
                self.last_poll_seconds = time.monotonic() - poll_started
                self.max_poll_seconds = max(self.max_poll_seconds, self.last_poll_seconds)
            # A sensor read/stop may consume the remaining lease. Never use the
            # old remaining value or accept a buffered request after expiry.
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise MotionLeaseExpired("Control watchdog expired")
            if stopped.is_set():
                raise SessionError("Session terminated")
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
                chunk = self.chunks.get(timeout=min(remaining, IR_POLL_SECONDS))
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


def run_session(motors: Motors, port: str, dry_run: bool, stopped: threading.Event,
                *, diagnostic_directory: Path | None = None, diagnostic: Any = None) -> int:
    output = JsonOutput(sys.stdout.fileno())
    session = ProtocolSession(motors, port=port, dry_run=dry_run)
    if diagnostic is None:
        directory = diagnostic_directory or (os.environ.get("APP_DRIVE_DIAGNOSTIC_TEST_DIR") if dry_run else None)
        try:
            diagnostic = (DiagnosticLog(Path(directory or PROJECT_ROOT / "app_drive_diagnostic_logs"), session.run_id)
                          if DiagnosticLog is not None else NullDiagnosticLog())
        except Exception:
            diagnostic = NullDiagnosticLog()
    diagnostic = BestEffortDiagnostic(diagnostic)
    motors.diagnostic = diagnostic
    diagnostic.event("drive.session_started", critical=True, dryRun=dry_run, parentPid=os.getppid(),
                     watchdogMs=int(WATCHDOG_SECONDS * 1000), networkGraceMs=int(NETWORK_GRACE_SECONDS * 1000),
                     infraredSupported=motors.infrared is not None)
    failed = False
    error_message = None
    cleanup_confirmed = False
    phase = "ready"
    pump = None
    deadline = 0.0
    session_deadline = 0.0
    motion_expired = False
    last_received = None
    last_ack_at = None
    last_ack_seq = 0
    last_request = None
    request = None
    received = None
    processed_ms = None
    request_gap_ms = None
    prior_ir = False
    prior_standstill_pending = False
    previous_command = None
    summary_at = time.monotonic()
    sample_count = 0
    max_processing_ms = 0.0
    max_request_gap_ms = 0.0
    try:
        output.send(session.ready())
        diagnostic.event("drive.ready_sent", critical=True)
        ready_at = time.monotonic()
        deadline = ready_at + WATCHDOG_SECONDS
        session_deadline = ready_at + NETWORK_GRACE_SECONDS
        pump = InputPump(sys.stdin.fileno())
        pump.poll = motors.poll_safety
        while True:
            phase = "request_wait"
            try:
                line = pump.line(session_deadline if motion_expired else deadline, stopped)
            except MotionLeaseExpired:
                if not motion_expired:
                    phase = "network_soft_stop"
                    session.pause_for_network()
                    motion_expired = True
                    diagnostic.event("drive.network_paused", critical=True, recoveryRequired=True,
                        lastAckSeq=last_ack_seq, leaseExpiredMonotonicMs=deadline * 1000,
                        stoppedMonotonicMs=time.monotonic() * 1000,
                        sessionDeadlineMonotonicMs=session_deadline * 1000,
                        lastValidRequest=last_request)
                if time.monotonic() >= session_deadline:
                    raise SessionError("Control watchdog network grace expired")
                continue
            message = strict_message(line)
            received = time.monotonic()
            if stopped.is_set():
                raise SessionError("Session terminated")
            if received >= session_deadline:
                raise SessionError("Control watchdog network grace expired")
            if received >= deadline and not motion_expired:
                # Also cover expiry between InputPump returning and request dispatch.
                phase = "network_soft_stop"
                session.pause_for_network()
                motion_expired = True
                diagnostic.event("drive.network_paused", critical=True, recoveryRequired=True,
                    lastAckSeq=last_ack_seq, leaseExpiredMonotonicMs=deadline * 1000,
                    stoppedMonotonicMs=time.monotonic() * 1000,
                    sessionDeadlineMonotonicMs=session_deadline * 1000,
                    lastValidRequest=last_request)
                if time.monotonic() >= session_deadline:
                    raise SessionError("Control watchdog network grace expired")
            # Only bounded primitive metadata is retained. No request object or token
            # can leak into this session's diagnostic timeline.
            request = {key: message[key] if type(message[key]) is int and
                       (1 <= message[key] <= 2**63 - 1 if key == "seq" else -MAX_RPM <= message[key] <= MAX_RPM)
                       else None for key in ("seq", "left", "right")}
            request["cmd"] = message["cmd"] if message["cmd"] in (
                "drive", "stop", "estop", "close", "parking") else "invalid"
            request_gap_ms = None if last_received is None else (received - last_received) * 1000
            phase = "motor_transaction"
            response, closing = session.accept(message)
            # Terminal acceptance already verified emergency stop and measured standstill.
            # No configuration registers are changed, including on cleanup.
            cleanup_confirmed = closing and response["stopped"]
            completed = time.monotonic()
            processed_ms = (completed - received) * 1000
            parking = message["cmd"] == "parking"
            # Only configuration with already stopped motors may outlive the motion lease.
            # Serial transactions themselves remain bounded by SERIAL_TIMEOUT.
            limit = PARKING_TRANSACTION_SECONDS if parking else WATCHDOG_SECONDS
            if not closing and completed >= received + limit:
                raise SessionError("Parking transaction exceeded timeout" if parking else
                                   "Motor transaction exceeded control watchdog")
            if not closing and session.recovery_active and completed >= session_deadline:
                raise SessionError("Control watchdog network grace expired")
            if stopped.is_set():
                raise SessionError("Session terminated")
            phase = "ack_write"
            output.send(response)
            ack_at = time.monotonic()
            last_ack_at = ack_at
            last_ack_seq = response["seq"]
            last_request = request
            last_received = received
            if response["recoveryRequired"] and not response["standstillPending"]:
                diagnostic.event("drive.feedback_recheck_ack" if response["feedbackRecheck"] else
                    "drive.network_recovery_ack", critical=True, request=request,
                    recoveryRequired=True, neutralAccepted=not session.recovery_active,
                    stopped=response["stopped"], ackMonotonicMs=ack_at * 1000,
                    sessionDeadlineMonotonicMs=session_deadline * 1000)
            sample_count += 1
            max_processing_ms = max(max_processing_ms, processed_ms)
            max_request_gap_ms = max(max_request_gap_ms, request_gap_ms or 0.0)
            blocked = response["infraredBlocked"]
            pending_changed = response["standstillPending"] != prior_standstill_pending
            if closing or blocked != prior_ir or pending_changed or previous_command != message["cmd"] or completed - summary_at >= 1:
                diagnostic.event("drive.ack_sent", critical=closing or blocked != prior_ir or pending_changed,
                    lastValidRequest=last_request, lastAckSeq=last_ack_seq,
                    receivedMonotonicMs=received * 1000, ackMonotonicMs=ack_at * 1000,
                    requestGapMs=request_gap_ms, processingMs=processed_ms,
                    sampleCount=sample_count, maxProcessingMs=max_processing_ms,
                    maxRequestGapMs=max_request_gap_ms,
                    maxIrPollMs=getattr(pump, "max_poll_seconds", 0) * 1000,
                    infraredBlocked=blocked, infraredDirections=response["infraredDirections"],
                    targetStopped=response["targetStopped"], stopped=response["stopped"],
                    feedbackRecheck=response["feedbackRecheck"],
                    standstillPending=response["standstillPending"],
                    **motors.feedback_state(), **motors.parking_state())
                summary_at, sample_count = completed, 0
                max_processing_ms = max_request_gap_ms = 0.0
            prior_ir, previous_command = blocked, message["cmd"]
            prior_standstill_pending = response["standstillPending"]
            if closing:
                break
            # Only an authenticated original neutral request may keep a physical
            # standstill wait alive. A suppressed nonzero target is not neutral;
            # neither it nor a parking request can extend recovery deadlines.
            standstill_heartbeat = response["standstillPending"] and (
                message["cmd"] == "stop" or
                message["cmd"] == "drive" and (message["left"], message["right"]) == (0, 0))
            if not session.recovery_active or standstill_heartbeat:
                deadline = (completed if parking else received) + WATCHDOG_SECONDS
                session_deadline = (completed if parking else received) + NETWORK_GRACE_SECONDS
                motion_expired = False
    except Exception as error:
        failed = True
        error_message = f"{type(error).__name__}: {error}"
        now = time.monotonic()
        source = ("signal" if stopped.is_set() else
                  "network_grace_expired" if "network grace expired" in str(error) else
                  "motor_transaction_timeout" if phase == "motor_transaction" and "watchdog" in str(error) else
                  "parking_transaction_timeout" if phase == "motor_transaction" and "Parking transaction exceeded" in str(error) else
                  "heartbeat_timeout" if "watchdog" in str(error)
                  else "input_eof" if "input closed" in str(error) else "control_error")
        diagnostic.failure("drive.failure", error, phase=phase, stopSource=source,
            request=request, lastValidRequest=last_request, lastAckSeq=last_ack_seq,
            requestGapMs=request_gap_ms, processingMs=processed_ms,
            lastRequestAgeMs=None if last_received is None else (now - last_received) * 1000,
            lastAckAgeMs=None if last_ack_at is None else (now - last_ack_at) * 1000,
            leaseRemainingMs=(deadline - now) * 1000,
            networkGraceRemainingMs=(session_deadline - now) * 1000,
            recoveryRequired=session.recovery_active,
            feedbackRecheck=motors.feedback_recovery_required,
            standstillPending=motors.standstill_pending,
            lastIrPollMs=getattr(pump, "last_poll_seconds", 0) * 1000,
            maxIrPollMs=getattr(pump, "max_poll_seconds", 0) * 1000,
            infrared=motors.infrared_state())
    finally:
        diagnostic.event("drive.cleanup_started", critical=True, alreadyStopped=cleanup_confirmed)
        if not cleanup_confirmed:
            try:
                motors.shutdown()
                cleanup_confirmed = True
            except Exception as error:
                failed = True
                error_message = f"{error_message + '; ' if error_message else ''}{error}"
                diagnostic.failure("drive.cleanup_failed", error)
        if error_message:
            with contextlib.suppress(Exception):
                output.send({"type": "error", "error": error_message[:700], "runId": session.run_id})
        diagnostic.event("drive.finished", critical=True, exitCode=1 if failed else 0,
                         cleanupConfirmed=cleanup_confirmed)
        diagnostic.close(timeout=.1)  # Bounded flush after motor cleanup, never on a heartbeat.
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
def open_hardware(port_choice: str, *, infrared: InfraredInterlock | None = None):
    import fcntl
    import termios

    source = PROJECT_ROOT / "src"
    sys.path.insert(0, str(source))
    from lz30ema_rs485 import LZ30EMAClient
    import serial

    # Hardware sessions always enable IR; no launcher flag is required.
    if infrared is None:
        infrared = InfraredInterlock()
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
                    yield Motors(client, infrared=infrared), port
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
    parser.add_argument("--require-safety-version", type=int, choices=(SAFETY_VERSION,))
    parser.add_argument("--port", default="auto", choices=("auto", "/dev/ttyS0", "/dev/ttyS3"))
    parser.add_argument(
        "--ir-sysfs-root", type=Path, default=os.environ.get("LZ_IR_SYSFS_ROOT"),
        help="IR IIO directory (hardware default: /sys/bus/iio/devices); also enables simulated IR in dry-run.",
    )
    parser.add_argument(
        "--ir-trigger-value", type=int, choices=(0, 1), default=os.environ.get("LZ_IR_TRIGGER_VALUE", "0"),
        help="Digital IR trigger value. Default: 0 (active low).",
    )
    args = parser.parse_args()
    if not args.dry_run and args.require_safety_version != SAFETY_VERSION:
        parser.error("Hardware control requires --require-safety-version 2; update the Android app")
    stopped = threading.Event()
    for name in ("SIGTERM", "SIGINT", "SIGHUP"):
        value = getattr(signal, name, None)
        if value is not None:
            signal.signal(value, lambda *_: stopped.set())
    try:
        if args.dry_run:
            infrared = (InfraredInterlock(args.ir_sysfs_root, trigger_value=args.ir_trigger_value)
                        if args.ir_sysfs_root is not None else None)
            return run_session(Motors(infrared=infrared), "dry-run", True, stopped)
        infrared = InfraredInterlock(args.ir_sysfs_root or Path("/sys/bus/iio/devices"),
                                     trigger_value=args.ir_trigger_value)
        with open_hardware(args.port, infrared=infrared) as (motors, port):
            return run_session(motors, port, False, stopped)
    except Exception as error:
        with contextlib.suppress(Exception):
            JsonOutput(sys.stdout.fileno()).send({"type": "error", "error": f"{type(error).__name__}: {error}"[:700]})
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
