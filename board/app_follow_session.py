#!/usr/bin/env python3
"""Opt-in gesture follow sessions; SSH is an attachment, not the worker's parent.

The original bridge remains the default. Only this entry point owns a persistent
session, and only an explicit --offline-until-gesture --gestures launch enables
it. Local worker leases and status deadlines remain enforced by FollowProcess.
"""
from __future__ import annotations

import argparse
import contextlib
import json
import os
from pathlib import Path
import re
import secrets
import select
import signal
import socket
import stat
import struct
import subprocess
import sys
import time

from app_diagnostic_log import DiagnosticLog, NullDiagnosticLog, safe_value
from app_follow_bridge import (FOLLOW_LOCK, MAX_LINE, RUNTIME_ROOT, WATCHDOG_SECONDS,
    FollowProcess, JsonOutput, ProtocolSession, SessionError, SignalFlag,
    motor_ownership, strict_request)
from app_follow_options import FollowOptions, add_follow_arguments, validate_follow_arguments

SESSION_DIRECTORY = RUNTIME_ROOT / ".app_follow_sessions"
TEST_DIRECTORY_ENV = "APP_FOLLOW_SESSION_TEST_DIR"
MAX_RECORD_BYTES = 64 * 1024
MAX_ARCHIVE_BYTES = 20 * 1024 * 1024
MAX_ARCHIVE_AGE = 7 * 24 * 60 * 60
LOCAL_PULSE_SECONDS = .25
RUN_ID = re.compile(r"[0-9a-f]{32}\Z")


class SessionUnavailable(SessionError):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def validate_run_id(run_id):
    if not isinstance(run_id, str) or RUN_ID.fullmatch(run_id) is None:
        raise SessionUnavailable("session_invalid")
    return run_id


def encode(value, limit=MAX_RECORD_BYTES):
    data = (json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":")) + "\n").encode()
    if len(data) > limit:
        raise SessionError("Follow session response too large")
    return data


def send_socket(connection, value):
    # A slow attachment may detach; it must never block worker health pulses.
    data = encode(value, 16 * 1024)
    if connection.send(data) != len(data):
        raise SessionError("Follow output blocked")


class SessionStore:
    """Private, bounded records with filenames derived exclusively from run IDs."""
    def __init__(self, directory=SESSION_DIRECTORY):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, exist_ok=True)
        self.fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        info = os.fstat(self.fd)
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            os.close(self.fd)
            raise SessionUnavailable("session_invalid")

    def close(self):
        os.close(self.fd)

    def socket_path(self, run_id):
        return str(self.directory / (validate_run_id(run_id) + ".sock"))

    def verify_socket(self, run_id):
        info = os.stat(validate_run_id(run_id) + ".sock", dir_fd=self.fd, follow_symlinks=False)
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
            raise SessionUnavailable("session_invalid")

    def read(self, run_id):
        name = validate_run_id(run_id) + ".json"
        try:
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=self.fd)
        except FileNotFoundError:
            return None
        try:
            info = os.fstat(fd)
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                    or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > MAX_RECORD_BYTES):
                raise SessionUnavailable("session_invalid")
            data = bytearray()
            while len(data) <= MAX_RECORD_BYTES:
                part = os.read(fd, MAX_RECORD_BYTES + 1 - len(data))
                if not part:
                    break
                data.extend(part)
        finally:
            os.close(fd)
        try:
            record = json.loads(data)
            if not isinstance(record, dict) or not isinstance(record.get("ready"), dict):
                raise ValueError("invalid archive")
            ready = record["ready"]
            options = ready["options"]
            status_value = ready["status"]
            if not isinstance(options, dict) or not isinstance(status_value, dict):
                raise ValueError("invalid archive")
            if (type(record["schemaVersion"]) is not int or record["schemaVersion"] != 1
                    or record["runId"] != run_id or ready["runId"] != run_id or "token" in ready
                    or status_value["processExited"] is not True
                    or status_value.get("finalStatusPending", False)
                    or status_value["runState"] not in ("STOPPED", "FAULT")
                    or set(options) != set(FollowOptions().response())
                    or options["gesturesEnabled"] is not True
                    or options["resumeGesture"] not in ("Closed_Fist", "Thumb_Up")
                    or options["avoidance"] not in ("off", "on")
                    or type(options["reverseEnabled"]) is not bool
                    or any(value is not None and (type(value) not in (float, int) or not .5 <= value <= 5.)
                           for value in (options["targetDistanceM"], options["reverseDistanceM"]))
                    or ready["camera"] not in ("config", "astra-pro", "gemini335")
                    or type(ready["execute"]) is not bool or type(ready["dryRun"]) is not bool
                    or (ready["execute"] and ready["dryRun"])
                    or ready["heartbeatMs"] != 500 or ready["watchdogMs"] != 2000
                    or ready["offlineUntilGesture"] is not True or ready["reconnectable"] is not True):
                raise ValueError("invalid archive")
            return record
        except (ValueError, KeyError, TypeError, RecursionError) as error:
            raise SessionUnavailable("session_invalid") from error

    def finish(self, run_id, ready, diagnostic, worker_pid, last_request):
        ready = dict(ready)
        ready.pop("token", None)
        record = dict(schemaVersion=1, runId=run_id, finishedAt=time.time(), ready=ready,
            workerPid=worker_pid, lastValidRequest=last_request,
            firstStop=safe_value(diagnostic.first_stop), firstFailure=safe_value(diagnostic.first_failure))
        data = encode(record)
        temporary = validate_run_id(run_id) + "." + secrets.token_hex(8) + ".tmp"
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=self.fd)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(data)
                output.flush()
                os.fsync(output.fileno())
            os.rename(temporary, run_id + ".json", src_dir_fd=self.fd, dst_dir_fd=self.fd)
        finally:
            with contextlib.suppress(FileNotFoundError):
                os.unlink(temporary, dir_fd=self.fd)
        self.rotate(exclude=run_id)

    def rotate(self, exclude=None):
        # Only completed regular records are rotated. Never remove active sockets.
        records = []
        now = time.time()
        for name in os.listdir(self.fd):
            if not name.endswith(".json") or RUN_ID.fullmatch(name[:-5]) is None or name[:-5] == exclude:
                continue
            try:
                info = os.stat(name, dir_fd=self.fd, follow_symlinks=False)
                if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
                    continue
                if now - info.st_mtime > MAX_ARCHIVE_AGE:
                    os.unlink(name, dir_fd=self.fd)
                else:
                    records.append((info.st_mtime, info.st_size, name))
            except FileNotFoundError:
                pass
        total = sum(item[1] for item in records)
        if exclude:
            total += os.stat(exclude + ".json", dir_fd=self.fd, follow_symlinks=False).st_size
        for _, size, name in sorted(records):
            if total <= MAX_ARCHIVE_BYTES:
                break
            with contextlib.suppress(FileNotFoundError):
                os.unlink(name, dir_fd=self.fd)
            total -= size


class AttachmentProtocol(ProtocolSession):
    def __init__(self, process, run_id, resumed):
        super().__init__(process)
        self.run_id = validate_run_id(run_id)
        self.resumed = resumed

    def ready(self):
        return dict(super().ready(), reconnectable=True, offlineUntilGesture=True,
                    resumed=self.resumed)


class ArchivedProcess:
    """A terminal record can be read repeatedly, but can never create a worker."""
    def __init__(self, ready):
        self.saved = ready
        self.camera, self.execute, self.dry_run = ready["camera"], ready["execute"], ready["dryRun"]
        options = ready["options"]
        self.options = FollowOptions(options["gesturesEnabled"], options["resumeGesture"],
            options["targetDistanceM"], options["avoidance"], options["reverseEnabled"], options["reverseDistanceM"])
        self.stop_at = 0.0  # Explicitly not None: ProtocolSession.start cannot restart.
        self.process = object()

    def status(self):
        return dict(self.saved["status"])

    def pulse(self):
        pass

    def request_stop(self, reason):
        pass


class SessionSupervisor:
    def __init__(self, process, run_id, store, diagnostic=None, clock=time.monotonic):
        if not process.options.gestures:
            raise SessionError("Offline follow requires gestures")
        self.process, self.run_id, self.store = process, validate_run_id(run_id), store
        self.diagnostic = diagnostic or NullDiagnosticLog()
        self.process.diagnostic = self.diagnostic
        self.clock = clock
        self.connection = None
        self.protocol = None
        self.buffer = bytearray()
        self.deadline = clock() + 5.0  # An abandoned launch never leaves an unstarted owner.
        self.next_pulse = clock()
        self.attach_count = 0
        self.last_request = None
        self.network_detached = False
        self.archived = False
        self.terminal_sent = False
        self.worker_stop_logged = False

    def attach(self, connection):
        connection.setblocking(False)
        if hasattr(socket, "SO_PEERCRED"):
            _, uid, _ = struct.unpack("3i", connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            if uid != os.getuid():
                connection.close()
                return
        # Expiry wins before accepting a new attachment; stale EOF cannot later
        # detach this replacement because the supervisor has just one reader.
        if self.connection is not None and self.clock() >= self.deadline:
            self.detach("heartbeat_timeout")
        if self.connection is not None:
            with contextlib.suppress(Exception):
                send_socket(connection, dict(type="error", code="session_busy", error="Follow session already attached"))
            connection.close()
            return
        self.connection = connection
        self.protocol = AttachmentProtocol(self.process, self.run_id, self.attach_count > 0)
        self.protocol.diagnostic = self.diagnostic
        self.attach_count += 1
        self.buffer.clear()
        self.deadline = self.clock() + WATCHDOG_SECONDS
        try:
            send_socket(connection, self.protocol.ready())
            self.diagnostic.event("attachment_ready", critical=True, resumed=self.protocol.resumed,
                                  attachment=self.attach_count)
        except Exception:
            self.detach("output_failure")

    def detach(self, source):
        if self.connection is not None:
            self.connection.close()
        self.connection = self.protocol = None
        self.buffer.clear()
        self.diagnostic.event("attachment_detached", critical=True, trigger=source,
                              attachment=self.attach_count)
        if self.process.process is None:
            self.process.request_stop("app_unstarted_attachment_closed")
        elif self.process.stop_at is None:
            self.network_detached = True
            self.process.set_network_detached(True)
        # An accepted stop is sticky, even when its ACK is lost.

    def receive(self):
        try:
            chunk = self.connection.recv(MAX_LINE + 2)
        except BlockingIOError:
            return
        except OSError:
            self.detach("input_failure")
            return
        if not chunk:
            self.detach("input_eof")
            return
        self.buffer.extend(chunk)
        try:
            while b"\n" in self.buffer:
                line, _, rest = self.buffer.partition(b"\n")
                self.buffer = bytearray(rest)
                if self.clock() >= self.deadline:
                    self.detach("heartbeat_timeout")
                    return
                request = strict_request(line + b"\n")
                # A worker gesture can have stopped the runtime since the last
                # tick. Observe that fact before a new App stop sets the bridge's
                # generic stop reason. Still send the requested S immediately;
                # only the already-established first reason is retained.
                self.observe_worker_stop(self.process.status())
                preserve_worker_reason = (self.worker_stop_logged and self.process.stop_at is None
                                          and request["cmd"] in ("stop", "close"))
                reply = self.protocol.accept(request)
                if preserve_worker_reason and self.process.reason == "app_" + request["cmd"] + "_requested":
                    self.process.reason = "app_offline_stop_gesture"
                    reply["status"]["reason"] = "app_offline_stop_gesture"
                self.last_request = self.protocol.last_valid_request
                self.deadline = self.clock() + WATCHDOG_SECONDS
                if request["cmd"] == "heartbeat" and self.network_detached and self.process.stop_at is None:
                    self.process.set_network_detached(False)
                    self.network_detached = False
                    self.diagnostic.event("attachment_resumed", critical=True, attachment=self.attach_count)
                # Archive terminal state before attempting a possibly lost ACK.
                if reply["terminal"]:
                    self.archive()
                try:
                    send_socket(self.connection, reply)
                    self.diagnostic.event("ack_sent", critical=reply["terminal"] or request["cmd"] != "heartbeat",
                        seq=reply["seq"], command=request["cmd"], terminal=reply["terminal"],
                        runState=reply["status"]["runState"], processExited=reply["status"]["processExited"])
                except Exception:
                    self.detach("output_failure")
                    return
                if reply["terminal"]:
                    self.terminal_sent = True
                    return
            if len(self.buffer) > MAX_LINE:
                raise SessionError("Invalid follow request length")
        except Exception as error:
            self.diagnostic.failure("session_protocol_failed", error, trigger="protocol_error",
                                    lastValidRequest=self.last_request)
            self.diagnostic.stop_requested("protocol_error")
            self.process.request_stop("app_protocol_error")
            with contextlib.suppress(Exception):
                send_socket(self.connection, dict(type="error", code="session_invalid",
                    error="Invalid follow request", status=self.process.status(), terminal=False))
            self.detach("protocol_error")

    def archive(self):
        if self.archived:
            return
        status = self.process.status()
        self.observe_worker_stop(status)
        if not status["processExited"] or status.get("finalStatusPending", False):
            return
        protocol = AttachmentProtocol(self.process, self.run_id, True)
        self.store.finish(self.run_id, protocol.ready(), self.diagnostic,
            getattr(self.process.process, "pid", None), self.last_request)
        self.archived = True
        self.diagnostic.event("session_archived", critical=True, workerPid=getattr(self.process.process, "pid", None),
                              exitCode=status.get("exitCode"), runState=status["runState"])

    def observe_worker_stop(self, status_value):
        if (not self.worker_stop_logged and status_value.get("stop", {}).get("requested") is True
                and status_value.get("reason") == "app_offline_stop_gesture"):
            self.worker_stop_logged = True
            # Preserve the actual worker cause before cleanup's generic request.
            # DiagnosticLog retains any still-earlier stop/failure automatically.
            self.diagnostic.stop_requested("app_offline_stop_gesture", origin="worker")

    def tick(self):
        now = self.clock()
        if self.connection is not None and now >= self.deadline:
            self.detach("heartbeat_timeout")
        elif self.attach_count == 0 and now >= self.deadline:
            self.process.request_stop("app_launch_abandoned")
        if now >= self.next_pulse:
            self.process.pulse()
            self.next_pulse = now + LOCAL_PULSE_SECONDS
        status = self.process.status()
        self.observe_worker_stop(status)
        terminal = (status["processExited"] and not status.get("finalStatusPending", False)
                    and (self.process.process is not None or self.process.stop_at is not None))
        if terminal:
            self.archive()
        return terminal and (self.connection is None or self.terminal_sent)

    def run(self, stopped, ready_fd=None):
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        path = self.store.socket_path(self.run_id)
        bound = False
        try:
            listener.bind(path)
            bound = True
            os.chmod(path, 0o600)
            listener.listen(4)
            listener.setblocking(False)
            if ready_fd is not None:
                os.write(ready_fd, encode(dict(ok=True)))
                os.close(ready_fd)
                ready_fd = None
            while not self.tick():
                if stopped.is_set():
                    self.diagnostic.stop_requested("supervisor_signal")
                    self.process.request_stop("app_supervisor_signal")
                readers = [listener] + ([self.connection] if self.connection is not None else [])
                readable, _, _ = select.select(readers, [], [], .05)
                # Handle the old owner before new accepts (EOF/expiry race).
                if self.connection is not None and self.connection in readable:
                    self.receive()
                if listener in readable:
                    connection, _ = listener.accept()
                    self.attach(connection)
        finally:
            if ready_fd is not None:
                with contextlib.suppress(OSError):
                    os.close(ready_fd)
            listener.close()
            if self.connection is not None:
                self.connection.close()
            if bound:
                with contextlib.suppress(FileNotFoundError):
                    os.unlink(self.run_id + ".sock", dir_fd=self.store.fd)
            # This is supervisor termination, never a mere attachment EOF.
            self.process.cleanup()
            self.archive()


def serve_archive(record, expected_dry_run=False):
    if expected_dry_run and record["ready"]["dryRun"] is not True:
        raise SessionUnavailable("session_invalid")
    from app_follow_bridge import InputPump
    protocol = AttachmentProtocol(ArchivedProcess(record["ready"]), record["runId"], True)
    output = JsonOutput(sys.stdout.fileno())
    output.send(protocol.ready())
    pump = InputPump(sys.stdin.buffer)
    deadline = time.monotonic() + WATCHDOG_SECONDS
    while time.monotonic() < deadline:
        line = pump.poll(.05)
        if line is not None:
            output.send(protocol.accept(strict_request(line)))
            return 0
    return 1


def proxy(store, run_id, expected_dry_run=False):
    try:
        store.verify_socket(run_id)
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(1.)
        connection.connect(store.socket_path(run_id))
    except (FileNotFoundError, ConnectionRefusedError):
        with contextlib.suppress(UnboundLocalError):
            connection.close()
        record = store.read(run_id)
        if record is None:
            raise SessionUnavailable("session_not_found")
        return serve_archive(record, expected_dry_run)
    signal_flag = SignalFlag()
    # sshd may terminate its child on transport loss. Only an authenticated
    # stop/close frame is an explicit motor stop; proxy signals detach as EOF.
    for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(number, lambda signum, _frame: signal_flag.request(signum))
    connection.setblocking(False)
    output_fd = sys.stdout.fileno()
    os.set_blocking(output_fd, False)
    pending = bytearray()
    first = True
    try:
        while True:
            if signal_flag.is_set():
                return 1
            readable, _, _ = select.select([sys.stdin.fileno(), connection], [], [], .1)
            if connection in readable:
                chunk = connection.recv(16 * 1024 + 1)
                if not chunk:
                    return 0
                pending.extend(chunk)
                if len(pending) > 16 * 1024:
                    raise SessionUnavailable("session_invalid")
                while b"\n" in pending:
                    line, _, rest = pending.partition(b"\n")
                    pending = bytearray(rest)
                    if first:
                        value = json.loads(line)
                        if expected_dry_run and value.get("type") == "ready" and value.get("dryRun") is not True:
                            raise SessionUnavailable("session_invalid")
                        first = False
                    data = line + b"\n"
                    if os.write(output_fd, data) != len(data):
                        return 1
            if sys.stdin.fileno() in readable:
                chunk = os.read(sys.stdin.fileno(), MAX_LINE + 2)
                if not chunk:
                    return 0
                if connection.send(chunk) != len(chunk):
                    return 1
    except (OSError, ValueError):
        return 1
    finally:
        connection.close()


def create_diagnostic(run_id, dry_run):
    directory = RUNTIME_ROOT / "app_diagnostic_logs"
    if dry_run:
        directory = Path(os.environ.get("APP_FOLLOW_DIAGNOSTIC_TEST_DIR", directory))
    try:
        return DiagnosticLog(directory, run_id)
    except Exception:
        return NullDiagnosticLog()


def supervise(args, store):
    stopped = SignalFlag()
    for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(number, lambda signum, _frame: stopped.request(signum))
    diagnostic = create_diagnostic(args.run_id, args.dry_run)
    run_entered = False
    try:
        with contextlib.ExitStack() as stack:
            lock_fd = None if args.dry_run else stack.enter_context(motor_ownership())
            if not args.dry_run:
                with motor_ownership(FOLLOW_LOCK):
                    pass
            process = FollowProcess(args.camera, args.execute, args.dry_run, lock_fd,
                                    FollowOptions.from_namespace(args), offline_until_gesture=True)
            diagnostic.event("session_started", critical=True, reconnectable=True, offlineUntilGesture=True,
                camera=args.camera, execute=args.execute, dryRun=args.dry_run, supervisorPid=os.getpid())
            supervisor = SessionSupervisor(process, args.run_id, store, diagnostic)
            run_entered = True
            supervisor.run(stopped, args.ready_fd)
        return 0
    except Exception as error:
        diagnostic.failure("supervisor_failed", error)
        return 1
    finally:
        # run() owns and closes this FD once entered. Its numeric FD may have
        # since been reused by the worker; never close/write that number again.
        if not run_entered:
            with contextlib.suppress(OSError):
                os.close(args.ready_fd)
        diagnostic.close()


def launch(args, store):
    run_id = secrets.token_hex(16)
    read_fd, write_fd = os.pipe()
    command = [sys.executable, "-u", str(Path(__file__).resolve()), "--supervise", "--run-id", run_id,
        "--ready-fd", str(write_fd), "--offline-until-gesture", "--camera", args.camera]
    command.extend(FollowOptions.from_namespace(args).command_args())
    if args.dry_run:
        command.append("--dry-run")
    if args.execute:
        command.append("--execute")
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, pass_fds=(write_fd,), start_new_session=True, close_fds=True)
    finally:
        os.close(write_fd)
    try:
        readable, _, _ = select.select([read_fd], [], [], 5.)
        data = os.read(read_fd, 1024) if readable else b""
        try:
            ready = json.loads(data)
        except (ValueError, UnicodeError):
            ready = {}
        if ready.get("ok") is not True:
            # No SSH attachment/start can exist yet. Stop only our own child.
            if process.poll() is None:
                process.terminate()
            with contextlib.suppress(subprocess.TimeoutExpired):
                process.wait(2.)
            raise SessionUnavailable("session_start_failed")
    finally:
        os.close(read_fd)
    return proxy(store, run_id, args.dry_run)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--launch", action="store_true")
    action.add_argument("--attach-run")
    action.add_argument("--supervise", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--run-id", help=argparse.SUPPRESS)
    parser.add_argument("--ready-fd", type=int, help=argparse.SUPPRESS)
    parser.add_argument("--offline-until-gesture", action="store_true")
    add_follow_arguments(parser)
    args = parser.parse_args(argv)
    validate_follow_arguments(parser, args)
    if args.check_config:
        parser.error("Use app_follow_bridge.py --check-config")
    if args.attach_run:
        validate_run_id(args.attach_run)
        if args.execute or args.offline_until_gesture or args.gestures or args.run_id or args.ready_fd is not None:
            parser.error("Attach cannot change session options")
    elif not args.offline_until_gesture or not args.gestures:
        parser.error("Persistent follow requires --offline-until-gesture --gestures")
    if args.supervise:
        validate_run_id(args.run_id)
        if args.ready_fd is None or args.ready_fd < 3:
            parser.error("Supervisor requires its launch channel")
    directory = Path(os.environ.get(TEST_DIRECTORY_ENV, SESSION_DIRECTORY)) if args.dry_run else SESSION_DIRECTORY
    store = None
    try:
        store = SessionStore(directory)
        if args.supervise:
            return supervise(args, store)
        if args.attach_run:
            return proxy(store, args.attach_run, args.dry_run)
        return launch(args, store)
    except Exception as error:
        code = error.code if isinstance(error, SessionUnavailable) else "session_start_failed"
        with contextlib.suppress(Exception):
            JsonOutput(sys.stdout.fileno()).send(dict(type="error", code=code, error=code))
        return 1
    finally:
        if store is not None:
            store.close()


if __name__ == "__main__":
    raise SystemExit(main())
