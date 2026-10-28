#!/usr/bin/env python3
"""Owned continuous-follow child. No HTTP listener and no independent motor writes.

The authenticated bridge supplies a lease over stdin. An in-process guard checks
that lease at device-open and motion-write boundaries as well as from a watchdog.
Only --dry-run skips the runtime, camera, model and motor imports entirely.
"""
from __future__ import annotations

import argparse
import configparser
import contextlib
import importlib.util
import json
import math
import os
from pathlib import Path
import signal
import sys
import tempfile
import threading
import time
from typing import Any

RUNTIME_ROOT = Path("/home/topeet/Desktop/rk_car_runtime_module")
MOTOR_LOCK = Path("/home/topeet/lianzhan/.app_joystick.lock")
WATCHDOG_SECONDS = 2.0


class LeaseExpired(RuntimeError):
    pass


class LeaseGuard:
    def __init__(self, parent_pid: int, timeout: float = WATCHDOG_SECONDS):
        self.parent_pid = parent_pid
        self.timeout = timeout
        self.last_pulse = time.monotonic()
        self.stopped = threading.Event()
        self.reason: str | None = None
        self.pending_signal: str | None = None
        self.runtime: Any = None

    def pulse(self):
        if not self.stopped.is_set():
            self.last_pulse = time.monotonic()

    def request_stop(self, reason: str):
        if not self.stopped.is_set():
            self.reason = reason
            self.stopped.set()
        runtime = self.runtime
        if runtime is not None:
            runtime.request_stop(reason=self.reason or reason)

    def attach(self, runtime: Any):
        self.runtime = runtime
        if self.stopped.is_set():
            runtime.request_stop(reason=self.reason or "app_lease_expired")

    def ensure_live(self):
        if self.pending_signal is not None:
            self.request_stop(self.pending_signal)
        if os.getppid() != self.parent_pid:
            self.request_stop("app_parent_exited")
        if time.monotonic() - self.last_pulse >= self.timeout:
            self.request_stop("app_heartbeat_timeout")
        if self.stopped.is_set():
            raise LeaseExpired(self.reason or "app_session_ended")

    def start(self, stream):
        def read_lease():
            try:
                try:
                    fd = stream.fileno()
                except (AttributeError, OSError):
                    fd = None
                buffer = bytearray()
                while not self.stopped.is_set():
                    # Raw reads avoid retaining stdin's buffered lock at interpreter exit.
                    chunk = os.read(fd, 64) if fd is not None else stream.read(64)
                    if not chunk:
                        self.request_stop("app_lease_closed")
                        return
                    buffer.extend(chunk)
                    while b"\n" in buffer:
                        value, _, rest = buffer.partition(b"\n")
                        buffer[:] = rest
                        if value == b"H":
                            # A late pulse cannot revive an expired session.
                            self.ensure_live()
                            self.pulse()
                        elif value == b"S":
                            self.request_stop("app_stop_requested")
                            return
                        else:
                            self.request_stop("app_lease_invalid")
                            return
                    if len(buffer) > 8:
                        self.request_stop("app_lease_invalid")
                        return
            except Exception:
                self.request_stop("app_lease_failed")

        def watch():
            while not self.stopped.wait(.05):
                try:
                    self.ensure_live()
                except LeaseExpired:
                    return

        threading.Thread(target=read_lease, name="app-follow-lease", daemon=True).start()
        threading.Thread(target=watch, name="app-follow-watchdog", daemon=True).start()


def scalar(value):
    if value is None or type(value) in (bool, int):
        return value
    if isinstance(value, str):
        return value[:300]
    if type(value) is float and math.isfinite(value):
        return value
    return None


def fields(value, names):
    if value is None:
        return None
    return {name: scalar(value.get(name) if isinstance(value, dict) else getattr(value, name, None))
            for name in names}


class FollowSnapshot:
    """Small snapshots only; emit never serializes diagnostics, images or candidates."""
    def __init__(self, camera: str, execute: bool):
        self.camera, self.execute = camera, execute
        self.lock = threading.Lock()
        self.deferred: dict[str, dict] = {}
        self.state = "STARTING"
        self.target = self.range = self.command = self.feedback = None
        self.stop_requested = False
        self.stop_ack: bool | None = None
        self.fault: str | None = None
        self.reason: str | None = None
        self.behavior: str | None = None
        self.log_dir: str | None = None
        self.target_ttl = self.range_ttl = .6

    def note(self, event: str, values: dict):
        # A status reader can never delay the motor/capture thread. Losing one
        # snapshot is preferable; data ages will expose stale observations.
        if not self.lock.acquire(blocking=False):
            # Do not lose a one-shot startup/failure/cleanup fact under contention.
            # A bounded mailbox coalesces these facts; the publisher consumes it.
            if event in ("startup", "runtime_failure", "process_failure", "shutdown"):
                self.deferred[event] = values
            return
        try:
            if event == "startup":
                self.state = "STOPPING" if self.stop_requested else "RUNNING"
                controller = values.get("controller", {})
                for key, attribute in (("target_ttl_sec", "target_ttl"), ("range_ttl_sec", "range_ttl")):
                    value = controller.get(key)
                    if type(value) in (float, int) and math.isfinite(value) and value > 0:
                        setattr(self, attribute, min(float(value), 2.))
            elif event == "target":
                self.target = fields(values.get("target"),
                    ("track_id", "detected", "status", "captured_at", "binding", "session_id"))
            elif event == "range":
                self.range = fields(values.get("range"),
                    ("distance_m", "sampled_at", "binding", "session_id"))
            elif event in ("intent", "intent_revision", "write"):
                intent = fields(values.get("intent"),
                    ("left_rpm", "right_rpm", "behavior", "reason", "stop", "issued_at", "expires_at"))
                if intent:
                    self.command = intent
                    self.behavior, self.reason = intent["behavior"], intent["reason"]
                    if event == "write" and intent["stop"] is True and intent["reason"] == "shutdown":
                        self.stop_requested = True
                        receipt = values.get("receipt") or {}
                        # A simulated or suppressed write is not a real STOP acknowledgement.
                        if self.execute and values.get("simulated") is False:
                            self.stop_ack = receipt.get("acknowledged") is True and receipt.get("dispatched") is True
            elif event == "feedback":
                self.feedback = fields(values, ("left_rpm", "right_rpm", "timestamp"))
            elif event in ("runtime_failure", "process_failure"):
                self.fault = f"{values.get('stage', event)}: {values.get('error', 'unknown failure')}"[:600]
                self.state = "FAULT"
            elif event == "shutdown":
                self.stop_requested = True
                self.reason = str(values.get("reason") or self.reason or "shutdown")[:300]
                problems = values.get("cleanup_errors") or values.get("unfinished_workers")
                if problems:
                    self.fault = ("Cleanup incomplete: " + str(problems))[:600]
                    self.state = "FAULT"
        finally:
            self.lock.release()

    def stopping(self, reason: str | None = None):
        with self.lock:
            self.stop_requested = True
            if self.state != "FAULT":
                self.state = "STOPPING"
            if reason:
                self.reason = reason[:300]

    def failed(self, error: str):
        with self.lock:
            self.fault = error[:600]
            self.state = "FAULT"

    @staticmethod
    def age(stamp, now):
        if type(stamp) not in (int, float) or not math.isfinite(stamp) or stamp > now:
            return None
        return (now - stamp) * 1000

    def status(self):
        now = time.monotonic()
        for event in tuple(self.deferred):
            values = self.deferred.pop(event, None)
            if values is not None:
                self.note(event, values)
        with self.lock:
            target = range_value = feedback = command = None
            target_age = self.age((self.target or {}).get("captured_at"), now)
            if target_age is not None and target_age <= self.target_ttl * 1000:
                target = dict(trackId=self.target["track_id"], detected=self.target["detected"],
                              status=self.target["status"], ageMs=int(target_age))
            range_age = self.age((self.range or {}).get("sampled_at"), now)
            if (target is not None and target["detected"] is True and self.range is not None
                    and self.range.get("binding") is not None and self.range.get("session_id") is not None
                    and self.range["binding"] == self.target["binding"]
                    and self.range["session_id"] == self.target["session_id"]
                    and range_age is not None and range_age <= self.range_ttl * 1000
                    and type(self.range["distance_m"]) in (float, int) and self.range["distance_m"] > 0):
                range_value = dict(distanceM=self.range["distance_m"], ageMs=int(range_age))
            feedback_age = self.age((self.feedback or {}).get("timestamp"), now)
            if feedback_age is not None and feedback_age <= 600:
                feedback = dict(leftRpm=self.feedback["left_rpm"], rightRpm=self.feedback["right_rpm"],
                                ageMs=int(feedback_age))
            if self.command and type(self.command["expires_at"]) in (float, int) and self.command["expires_at"] >= now:
                command = dict(leftRpm=self.command["left_rpm"], rightRpm=self.command["right_rpm"],
                               simulated=not self.execute, stop=self.command["stop"])
            return dict(runState=self.state, execute=self.execute, camera=self.camera,
                target=target, range=range_value, command=command, feedback=feedback,
                behavior=self.behavior if command is not None else None,
                reason=self.reason, logDir=self.log_dir, statusAgeMs=0, processExited=False,
                stop=dict(requested=self.stop_requested, acknowledged=self.stop_ack, physicalStopped=None),
                fault=self.fault)


class StatusPublisher:
    def __init__(self, fd: int, snapshot: FollowSnapshot, guard: LeaseGuard):
        self.fd, self.snapshot, self.guard = fd, snapshot, guard
        self.finished = threading.Event()
        self.write_lock = threading.Lock()
        os.set_blocking(fd, False)

    def publish(self):
        if self.guard.stopped.is_set():
            self.snapshot.stopping(self.guard.reason)
        data = (json.dumps(self.snapshot.status(), ensure_ascii=True, allow_nan=False,
                           separators=(",", ":")) + "\n").encode()
        if len(data) > 4096:
            raise RuntimeError("Follow status exceeds bounded IPC size")
        with self.write_lock:
            if os.write(self.fd, data) != len(data):
                raise RuntimeError("Follow status pipe blocked")

    def start(self):
        def run():
            while not self.finished.is_set():
                try:
                    self.publish()
                except (OSError, RuntimeError):
                    self.guard.request_stop("app_status_pipe_failed")
                    return
                self.finished.wait(.1)
        self.thread = threading.Thread(target=run, name="app-follow-status", daemon=True)
        self.thread.start()

    def close(self):
        self.finished.set()
        self.thread.join(.5)
        with contextlib.suppress(Exception):
            self.publish()


def prepare_environment(camera: str):
    """Equivalent to the launcher's pre-continuous-dispatch environment preparation."""
    threads = os.environ.get("FOLLOW_NUMERIC_THREADS", "1")
    if threads not in {"inherit", "1", "2", "4", "8"}:
        raise ValueError("invalid FOLLOW_NUMERIC_THREADS")
    os.environ["FOLLOW_NUMERIC_THREADS"] = threads
    if threads != "inherit":
        for name in ("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS", "BLIS_NUM_THREADS"):
            os.environ[name] = threads
    solver = os.environ.get("FOLLOW_KALMAN_SOLVER", "numpy")
    if solver not in {"numpy", "triangular"}:
        raise ValueError("invalid FOLLOW_KALMAN_SOLVER")
    os.environ["FOLLOW_KALMAN_SOLVER"] = solver
    os.environ["FOLLOW_CAMERA_PROFILE"] = "" if camera == "config" else camera
    os.environ.setdefault("MMWAVE_AT2410_PORT", "/dev/serial/by-id/usb-SIPEED_UARTx4_HS_FactoryAIOT_Prog_Serial-if00")
    user_site = Path(f"/home/topeet/.local/lib/python{sys.version_info.major}.{sys.version_info.minor}/site-packages")
    if user_site.is_dir():
        sys.path.insert(0, str(user_site))
    sys.path.insert(0, str(RUNTIME_ROOT))
    os.chdir(RUNTIME_ROOT)


def preflight():
    # These checks never open a device and never stop an existing owner.
    sys.path.insert(0, "/home/topeet/lianzhan/scripts")
    from app_joystick_bridge import reject_conflicting_processes, reject_other_serial_owners
    import configparser
    reject_conflicting_processes()
    config = configparser.ConfigParser()
    if not config.read(RUNTIME_ROOT / "car_control_modular/config/reid_runtime.ini", encoding="utf-8"):
        raise RuntimeError("Follow base configuration is missing")
    port = config.get("motor", "rs485_port", fallback="/dev/ttyS0")
    reject_other_serial_owners(port)


def install_runtime_hooks(guard: LeaseGuard, snapshot: FollowSnapshot):
    import continuous_follow.hardware as hardware
    import continuous_follow.runtime as runtime_module
    import continuous_follow.telemetry as telemetry

    original_events = telemetry.EventLog
    class AppEventLog(original_events):
        def __init__(self, path, *args, **kwargs):
            snapshot.log_dir = str(Path(path).parent)[:500]
            super().__init__(path, *args, **kwargs)

        def emit(self, event, **values):
            snapshot.note(event, values)
            super().emit(event, **values)
    telemetry.EventLog = AppEventLog

    original_init = runtime_module.ContinuousRuntime.__init__
    def initialize(self, *args, **kwargs):
        guard.ensure_live()
        original_init(self, *args, **kwargs)
        guard.attach(self)
    runtime_module.ContinuousRuntime.__init__ = initialize

    original_run = runtime_module.ContinuousRuntime.run
    def run(self, *args, **kwargs):
        # The original entry installs its handlers immediately before run().
        # Replace them with a lock-free marker: the lease thread requests the
        # same graceful runtime shutdown outside Python's signal context.
        for name in ("SIGTERM", "SIGINT", "SIGHUP"):
            if hasattr(signal, name):
                signal.signal(getattr(signal, name), lambda *_: setattr(guard, "pending_signal", "app_signal_stop"))
        guard.ensure_live()
        return original_run(self, *args, **kwargs)
    runtime_module.ContinuousRuntime.run = run

    def guarded_open(original):
        def opened(self, *args, **kwargs):
            guard.ensure_live()
            result = original(self, *args, **kwargs)
            guard.ensure_live()
            return result
        return opened
    for cls in (hardware.BoardInput, hardware.BoardMotor, hardware.ShadowMotor):
        cls.open = guarded_open(cls.open)
    for cls in (hardware.BoardMotor, hardware.ShadowMotor):
        original = cls.write
        def write(self, intent, _original=original):
            if not intent.stop:
                guard.ensure_live()
            return _original(self, intent)
        cls.write = write


@contextlib.contextmanager
def runtime_settings(target_distance: float | None):
    baseline = RUNTIME_ROOT / "continuous_follow/baseline.ini"
    if target_distance is None:
        yield baseline
        return
    config = configparser.ConfigParser(interpolation=None)
    with baseline.open("r", encoding="utf-8") as source:
        config.read_file(source)
    if not config.has_section("continuous_follow"):
        raise RuntimeError("Follow baseline has no continuous_follow section")
    config.set("continuous_follow", "target_distance_m", str(target_distance))
    fd, name = tempfile.mkstemp(prefix=".app_follow_", suffix=".ini", dir=baseline.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            config.write(output)
        yield temporary
    finally:
        temporary.unlink(missing_ok=True)


def runtime_arguments(args, settings):
    result = ["--config", str(RUNTIME_ROOT / "car_control_modular/config/reid_runtime.ini"),
              "--camera", args.camera, "--settings", str(settings),
              "--gestures" if args.gestures else "--no-gestures",
              "--gesture-resume", args.gesture_resume,
              "--avoidance", args.avoidance,
              "--reverse-follow" if args.reverse_follow else "--no-reverse-follow"]
    if args.reverse_distance is not None:
        result.extend(("--reverse-distance", str(args.reverse_distance)))
    if args.execute:
        result.append("--execute")
    if args.check_config:
        result.append("--check-config")
    return result


def run_real(args, guard: LeaseGuard, snapshot: FollowSnapshot):
    camera = args.camera
    prepare_environment(camera)
    guard.ensure_live()
    preflight()
    guard.ensure_live()
    install_runtime_hooks(guard, snapshot)
    guard.ensure_live()
    spec = importlib.util.spec_from_file_location("app_owned_continuous_entry", RUNTIME_ROOT / "request_continuous_follow.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    guard.ensure_live()
    # Python/library output is diagnostic only; IPC uses its own inherited fd.
    with runtime_settings(args.target_distance) as settings, contextlib.redirect_stdout(sys.stderr):
        return module.main(runtime_arguments(args, settings))


def check_configuration(args):
    # The runtime's --check-config returns before opening devices or writing logs.
    prepare_environment(args.camera)
    spec = importlib.util.spec_from_file_location(
        "app_owned_continuous_entry", RUNTIME_ROOT / "request_continuous_follow.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with runtime_settings(args.target_distance) as settings:
        return module.main(runtime_arguments(args, settings)) or 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--camera", choices=("config", "astra-pro", "gemini335"), default="config")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--check-config", action="store_true")
    gestures = parser.add_mutually_exclusive_group()
    gestures.add_argument("--gestures", dest="gestures", action="store_true", default=False)
    gestures.add_argument("--no-gestures", dest="gestures", action="store_false")
    parser.add_argument("--gesture-resume", choices=("Closed_Fist", "Thumb_Up"), default="Closed_Fist")
    parser.add_argument("--target-distance", type=float)
    parser.add_argument("--avoidance", choices=("off", "shadow", "on"), default="off")
    reverse = parser.add_mutually_exclusive_group()
    reverse.add_argument("--reverse-follow", dest="reverse_follow", action="store_true", default=False)
    reverse.add_argument("--no-reverse-follow", dest="reverse_follow", action="store_false")
    parser.add_argument("--reverse-distance", type=float)
    parser.add_argument("--parent-pid", type=int)
    parser.add_argument("--lock-fd", type=int)
    parser.add_argument("--status-fd", type=int, default=1)
    args = parser.parse_args()
    if args.dry_run and args.execute:
        parser.error("dry-run cannot execute motors")
    if args.check_config and (args.dry_run or args.execute):
        parser.error("check-config cannot use dry-run or execute")
    for name, value in (("target-distance", args.target_distance),
                        ("reverse-distance", args.reverse_distance)):
        if value is not None and (not math.isfinite(value) or not .5 <= value <= 5.):
            parser.error(f"{name} must be a finite distance from 0.5 to 5 metres")
    if args.avoidance == "on" and args.camera != "astra-pro":
        parser.error("avoidance on requires camera astra-pro")
    if args.check_config:
        return check_configuration(args)
    if args.parent_pid is None:
        parser.error("--parent-pid is required for a follow session")
    if not args.dry_run:
        if os.name != "posix" or args.lock_fd is None:
            parser.error("real follow requires an inherited Linux motor ownership lock")
        actual = os.fstat(args.lock_fd)
        expected = MOTOR_LOCK.stat()
        if (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino):
            parser.error("invalid inherited motor ownership lock")
    guard = LeaseGuard(args.parent_pid)
    snapshot = FollowSnapshot(args.camera, args.execute)
    guard.start(sys.stdin.buffer)
    for name in ("SIGTERM", "SIGINT", "SIGHUP"):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), lambda *_: setattr(guard, "pending_signal", "app_signal_stop"))
    publisher = StatusPublisher(args.status_fd, snapshot, guard)
    publisher.start()
    try:
        guard.ensure_live()
        if args.dry_run:
            # Give STOP-during-STARTING a deterministic, device-free path.
            if not guard.stopped.wait(.15):
                guard.ensure_live()
                snapshot.note("startup", {})
            guard.stopped.wait()
            snapshot.stopping(guard.reason)
            return 0
        return run_real(args, guard, snapshot) or 0
    except LeaseExpired:
        snapshot.stopping(guard.reason)
        return 0
    except BaseException as error:
        snapshot.failed(f"{type(error).__name__}: {error}")
        return 1
    finally:
        publisher.close()


if __name__ == "__main__":
    raise SystemExit(main())
