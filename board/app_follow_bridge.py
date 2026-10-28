#!/usr/bin/env python3
"""SSH-owned, nonce-checked automatic-follow supervisor. Never opens hardware itself."""
from __future__ import annotations

import argparse
import contextlib
from dataclasses import dataclass
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

RUNTIME_ROOT = Path("/home/topeet/Desktop/rk_car_runtime_module")
MOTOR_LOCK = Path("/home/topeet/lianzhan/.app_joystick.lock")
FOLLOW_LOCK = RUNTIME_ROOT / ".follow_session.lock"
WATCHDOG_SECONDS = 2.0
STOP_TIMEOUT_SECONDS = 8.0
REQUEST_KEYS = {"seq", "token", "cmd", "runId"}
MAX_LINE = 1024


@dataclass(frozen=True)
class FollowOptions:
    gestures: bool = False
    gesture_resume: str = "Closed_Fist"
    target_distance: float | None = None
    avoidance: str = "off"
    reverse_follow: bool = False
    reverse_distance: float | None = None

    def command_args(self):
        result = ["--gestures" if self.gestures else "--no-gestures",
                  "--gesture-resume", self.gesture_resume,
                  "--avoidance", self.avoidance,
                  "--reverse-follow" if self.reverse_follow else "--no-reverse-follow"]
        if self.target_distance is not None:
            result.extend(("--target-distance", str(self.target_distance)))
        if self.reverse_distance is not None:
            result.extend(("--reverse-distance", str(self.reverse_distance)))
        return result

    def response(self):
        return dict(targetDistanceM=self.target_distance, gesturesEnabled=self.gestures,
                    resumeGesture=self.gesture_resume, avoidance=self.avoidance,
                    reverseEnabled=self.reverse_follow, reverseDistanceM=self.reverse_distance)


class SessionError(RuntimeError):
    pass


class SignalFlag:
    requested = False

    def is_set(self):
        return self.requested


def strict_request(line: bytes):
    if not line or len(line) > MAX_LINE:
        raise SessionError("Invalid follow request length")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON field")
            result[key] = value
        return result
    def invalid(_value):
        raise ValueError("Invalid JSON constant")
    try:
        result = json.loads(line.decode("utf-8"), object_pairs_hook=unique, parse_constant=invalid)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise SessionError("Malformed follow request") from error
    if not isinstance(result, dict) or set(result) != REQUEST_KEYS:
        raise SessionError("Follow request requires exactly seq, token, cmd, runId")
    return result


@contextlib.contextmanager
def motor_ownership(path=MOTOR_LOCK):
    import fcntl
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise SessionError("手动遥控或自动跟随正在占用小车") from error
        yield fd
    finally:
        # Do not LOCK_UN: an inherited copy in a living worker must retain ownership.
        os.close(fd)


def inspect_availability(motor_lock=MOTOR_LOCK, follow_lock=FOLLOW_LOCK, check_processes=None):
    try:
        if check_processes is None:
            sys.path.insert(0, "/home/topeet/lianzhan/scripts")
            from app_joystick_bridge import reject_conflicting_processes
            check_processes = reject_conflicting_processes
        # Fixed lock order, matching a worker launched while its bridge owns motor_lock.
        with motor_ownership(motor_lock), motor_ownership(follow_lock):
            check_processes()
        return dict(type="availability", controlAvailable=True, reason=None)
    except Exception as error:
        return dict(type="availability", controlAvailable=False, reason=str(error)[:600])


class InputPump:
    def __init__(self, stream):
        self.messages = queue.Queue(maxsize=1)
        def read():
            try:
                fd = stream.fileno()
                buffer = bytearray()
                while True:
                    chunk = os.read(fd, MAX_LINE + 2)
                    if not chunk:
                        self.messages.put(b"")
                        return
                    buffer.extend(chunk)
                    while b"\n" in buffer:
                        value, _, rest = buffer.partition(b"\n")
                        buffer[:] = rest
                        self.messages.put(bytes(value) + b"\n")
                    if len(buffer) > MAX_LINE:
                        self.messages.put(SessionError("Follow request line too long"))
                        return
            except Exception as error:
                self.messages.put(error)
        threading.Thread(target=read, name="app-follow-input", daemon=True).start()

    def poll(self, timeout=.05):
        try:
            result = self.messages.get(timeout=timeout)
        except queue.Empty:
            return None
        if isinstance(result, Exception):
            raise SessionError("Follow input failed") from result
        if not result:
            raise SessionError("Follow input closed")
        if not result.endswith(b"\n") or len(result) > MAX_LINE + 1:
            raise SessionError("Follow request line too long")
        return result[:-1]


class JsonOutput:
    def __init__(self, fd):
        self.fd = fd
        os.set_blocking(fd, False)

    def send(self, value):
        payload = (json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":")) + "\n").encode()
        if len(payload) > 16 * 1024:
            raise SessionError("Follow response too large")
        if os.write(self.fd, payload) != len(payload):
            raise SessionError("Follow output blocked")


class FollowProcess:
    def __init__(self, camera, execute, dry_run, lock_fd=None, options=None):
        self.camera, self.execute, self.dry_run = camera, execute, dry_run
        self.options = options or FollowOptions()
        self.lock_fd = lock_fd
        self.process = None
        self.latest = None
        self.latest_at = 0.
        self.data_lock = threading.Lock()
        self.stderr_tail = b""
        self.protocol_error = None
        self.stop_at = None
        self.reason = None
        self.fault = None
        self.started_at = None

    def start(self):
        if self.process is not None:
            return
        worker = Path(__file__).with_name("app_follow_worker.py")
        command = [sys.executable, "-u", str(worker), "--camera", self.camera,
                   "--parent-pid", str(os.getpid())]
        command.extend(self.options.command_args())
        if self.dry_run:
            command.append("--dry-run")
        elif self.execute:
            command.append("--execute")
        kwargs = {}
        status_read = status_write = None
        if os.name == "posix":
            status_read, status_write = os.pipe()
            command.extend(["--status-fd", str(status_write)])
            inherited = [status_write]
            if self.lock_fd is not None:
                inherited.append(self.lock_fd)
                command.extend(["--lock-fd", str(self.lock_fd)])
            kwargs["pass_fds"] = tuple(inherited)
        elif not self.dry_run:
            raise SessionError("Hardware follow requires Linux")
        try:
            self.process = subprocess.Popen(command, stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL if status_read is not None else subprocess.PIPE,
                stderr=subprocess.PIPE, **kwargs)
        except Exception:
            if status_read is not None:
                os.close(status_read)
            raise
        finally:
            if status_write is not None:
                os.close(status_write)
        self.started_at = time.monotonic()
        os.set_blocking(self.process.stdin.fileno(), False)
        stream = os.fdopen(status_read, "rb") if status_read is not None else self.process.stdout
        def read_status():
            try:
                with stream:
                    while True:
                        line = stream.readline(16 * 1024 + 1)
                        if not line:
                            return
                        if len(line) > 16 * 1024 or not line.endswith(b"\n"):
                            raise SessionError("Worker status too large")
                        value = json.loads(line)
                        if not isinstance(value, dict) or value.get("runState") not in {
                                "STARTING", "RUNNING", "STOPPING", "STOPPED", "FAULT"}:
                            raise SessionError("Invalid worker status")
                        with self.data_lock:
                            self.latest, self.latest_at = value, time.monotonic()
            except Exception as error:
                self.protocol_error = str(error)[:500]
        def drain_errors():
            with self.process.stderr:
                while True:
                    chunk = self.process.stderr.read(1024)
                    if not chunk:
                        return
                    self.stderr_tail = (self.stderr_tail + chunk)[-4096:]
        self.reader = threading.Thread(target=read_status, name="follow-worker-status", daemon=True)
        self.reader.start()
        threading.Thread(target=drain_errors, name="follow-worker-stderr", daemon=True).start()

    def pulse(self):
        if self.process is not None and self.process.poll() is None and self.stop_at is None:
            try:
                if os.write(self.process.stdin.fileno(), b"H\n") != 2:
                    raise OSError("partial lease write")
            except OSError as error:
                self.fault = f"Worker lease failed: {error}"[:600]
                self.request_stop("app_lease_write_failed")

    def request_stop(self, reason="app_stop_requested"):
        if self.stop_at is None:
            self.stop_at, self.reason = time.monotonic(), reason
        process = self.process
        if process is None or process.poll() is not None:
            return
        try:
            os.write(process.stdin.fileno(), b"S\n")
        except OSError:
            pass
        # On Windows terminate is forced; the fake worker obeys its stdin lease instead.
        if os.name == "posix" and self.latest is not None:
            with contextlib.suppress(ProcessLookupError):
                process.send_signal(signal.SIGTERM)

    def status(self):
        now = time.monotonic()
        process = self.process
        exited = process is None or process.poll() is not None
        if exited and process is not None:
            self.reader.join(.05)  # Drain final IPC before reporting process exit.
        with self.data_lock:
            status = dict(self.latest) if self.latest is not None else dict(
                runState="STOPPED" if process is None else "STARTING", execute=self.execute,
                camera=self.camera, target=None, range=None, command=None, feedback=None,
                behavior=None, reason=None, logDir=None,
                stop=dict(requested=False, acknowledged=None, physicalStopped=None), fault=None)
            age = max(0., now - self.latest_at) if self.latest_at else 0.
        status["statusAgeMs"] = int(age * 1000)
        status["processExited"] = exited
        status["execute"], status["camera"] = self.execute, self.camera
        status["stop"] = dict(status["stop"], physicalStopped=None)
        for key in ("target", "range", "feedback"):
            row = status.get(key)
            if row is not None:
                row = dict(row, ageMs=row.get("ageMs", 0) + int(age * 1000))
                status[key] = row if row["ageMs"] <= 600 else None
        if status.get("target") is None:
            status["range"] = None
        if age > .6:
            status["command"] = status["behavior"] = None
        if self.stop_at is not None:
            status["stop"]["requested"] = True
            status["reason"] = self.reason
            if not exited:
                status["runState"] = "STOPPING"
                if now - self.stop_at >= STOP_TIMEOUT_SECONDS:
                    self.fault = "停止超时，跟随进程仍未退出；停车结果未确认"
        if self.protocol_error:
            self.fault = "Worker status failed: " + self.protocol_error
        if process is not None and not exited and self.latest_at and age > WATCHDOG_SECONDS:
            self.fault = "Worker status expired"
        if (process is not None and not exited and not self.latest_at
                and self.started_at is not None and now - self.started_at > 5.):
            self.fault = "跟随启动未回应，已请求停止"
        if self.fault and not exited and self.stop_at is None:
            self.request_stop("app_status_failed")
            status["stop"]["requested"] = True
        if exited and process is not None:
            code = process.returncode
            status["exitCode"] = code
            status["target"] = status["range"] = status["command"] = status["feedback"] = None
            status["behavior"] = None
            if code and not self.fault and not status.get("fault"):
                self.fault = (self.stderr_tail.decode("utf-8", errors="replace").strip()
                              or f"Follow worker exited with code {code}")[-600:]
            status["runState"] = "STOPPED"
        if self.fault or status.get("fault"):
            status["fault"] = self.fault or status["fault"]
            status["runState"] = "FAULT"
        return status

    def cleanup(self):
        self.request_stop("app_session_closed")
        if self.process is None:
            return
        with contextlib.suppress(Exception):
            self.process.stdin.close()
        # Never unlock or SIGKILL a still-running motor owner. The child also
        # owns the lock and independently observes stdin EOF/parent death/lease.
        while self.process.poll() is None:
            try:
                self.process.wait(timeout=.2)
            except subprocess.TimeoutExpired:
                pass


class ProtocolSession:
    def __init__(self, process: FollowProcess):
        self.process = process
        self.run_id = secrets.token_hex(16)
        self.token = secrets.token_hex(16)
        self.sequence = 1

    def ready(self):
        return dict(type="ready", runId=self.run_id, token=self.token,
            camera=self.process.camera, execute=self.process.execute, dryRun=self.process.dry_run,
            options=getattr(self.process, "options", FollowOptions()).response(),
            heartbeatMs=500, watchdogMs=2000, status=self.process.status())

    def accept(self, message):
        if not isinstance(message, dict) or set(message) != REQUEST_KEYS:
            raise SessionError("Unexpected follow request fields")
        if type(message["seq"]) is not int or message["seq"] != self.sequence or self.sequence > 2**63 - 1:
            raise SessionError("Unexpected follow request sequence")
        for key, expected in (("token", self.token), ("runId", self.run_id)):
            value = message[key]
            if not isinstance(value, str) or len(value) != 32 or not value.isascii() or not hmac.compare_digest(value, expected):
                raise SessionError("Stale or invalid follow " + key)
        command = message["cmd"]
        if command not in ("start", "heartbeat", "stop", "close"):
            raise SessionError("Unknown follow command")
        if command == "start" and self.process.stop_at is None:
            self.process.start()
        if command in ("stop", "close"):
            self.process.request_stop("app_" + command + "_requested")
        else:
            self.process.pulse()
        status = self.process.status()
        terminal = status["processExited"] and (self.process.process is not None or command in ("stop", "close"))
        seq = self.sequence
        self.sequence += 1
        self.token = secrets.token_hex(16)
        return dict(type="ack", seq=seq, token=self.token, runId=self.run_id, status=status, terminal=terminal)


def run_session(process, stopped: threading.Event):
    session = ProtocolSession(process)
    output = JsonOutput(sys.stdout.fileno())
    failed = False
    try:
        output.send(session.ready())
        pump = InputPump(sys.stdin.buffer)
        deadline = time.monotonic() + WATCHDOG_SECONDS
        while True:
            if stopped.is_set():
                raise SessionError("Follow session terminated")
            if time.monotonic() >= deadline:
                raise SessionError("Follow session watchdog expired")
            # Keep detecting worker status/stop timeout even while no request arrives.
            process.status()
            line = pump.poll()
            if line is None:
                continue
            if time.monotonic() >= deadline:
                raise SessionError("Follow session watchdog expired")
            reply = session.accept(strict_request(line))
            deadline = time.monotonic() + WATCHDOG_SECONDS
            output.send(reply)
            if reply["terminal"]:
                break
    except Exception as error:
        failed = True
        process.request_stop("app_protocol_or_connection_failure")
        with contextlib.suppress(Exception):
            output.send(dict(type="error", error=str(error)[:600], status=process.status(), terminal=False))
    finally:
        process.cleanup()
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--camera", choices=("config", "astra-pro", "gemini335"), default="config")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--inspect", action="store_true")
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
    args = parser.parse_args()
    if args.inspect:
        if len(sys.argv) != 2:
            parser.error("--inspect must be used alone")
        print(json.dumps(inspect_availability(), ensure_ascii=True))
        return 0
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
    options = FollowOptions(args.gestures, args.gesture_resume, args.target_distance,
                            args.avoidance, args.reverse_follow, args.reverse_distance)
    if args.check_config:
        worker = Path(__file__).with_name("app_follow_worker.py")
        command = [sys.executable, "-u", str(worker), "--check-config", "--camera", args.camera]
        command.extend(options.command_args())
        return subprocess.call(command)
    stopped = SignalFlag()
    for name in ("SIGTERM", "SIGINT", "SIGHUP"):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), lambda *_: setattr(stopped, "requested", True))
    try:
        if args.dry_run:
            return run_session(FollowProcess(args.camera, False, True, options=options), stopped)
        with motor_ownership() as lock_fd:
            # Original follow ownership is independently checked and retained by
            # request_continuous_follow.py before it opens any runtime devices.
            with motor_ownership(FOLLOW_LOCK):
                pass
            return run_session(FollowProcess(args.camera, args.execute, False, lock_fd, options), stopped)
    except Exception as error:
        with contextlib.suppress(Exception):
            JsonOutput(sys.stdout.fileno()).send(dict(type="error", error=str(error)[:600]))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
