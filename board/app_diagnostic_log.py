"""Best-effort, bounded follow diagnostics. Never writes to the control stdout stream."""
from __future__ import annotations

from collections import deque
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import stat
import threading
import time


MAX_BYTES = 20 * 1024 * 1024
MAX_AGE_SECONDS = 7 * 24 * 60 * 60
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_ERROR_FRAMES = 24
_SECRET_KEY = re.compile(r"password|passphrase|secret|credential|token|rawrequest|rawresponse", re.I)
_HEX_SECRET = re.compile(r"(?<![0-9a-fA-F])[0-9a-fA-F]{32,}(?![0-9a-fA-F])")
_NAMED_SECRET = re.compile(r"(?i)\b(password|passphrase|token|secret|authorization)\b\s*[:=]\s*([^\s,;]+)")


def safe_text(value, limit=4096):
    value = str(value)
    value = _NAMED_SECRET.sub(lambda match: match[1] + "=[REDACTED]", value)
    value = _HEX_SECRET.sub("[REDACTED]", value)
    if "-----BEGIN" in value and "PRIVATE KEY-----" in value:
        return "[REDACTED PRIVATE KEY]"
    return value[:limit]


def safe_value(value, key="", depth=0):
    if _SECRET_KEY.search(key):
        return "[REDACTED]"
    if depth > 4:
        return "[TRUNCATED]"
    if isinstance(value, _FailureSnapshot):
        # This formatting runs only on the writer, never on the control caller.
        value = value.render()
    if value is None or type(value) in (bool, int):
        return value
    if type(value) is float:
        return value if math.isfinite(value) else None
    if isinstance(value, str):
        # runId is a public correlation ID, never the rolling authorization token.
        return value[:64] if key == "runId" and re.fullmatch(r"[0-9a-f]{32}", value) else safe_text(value)
    if isinstance(value, dict):
        return {str(k)[:80]: safe_value(v, str(k), depth + 1) for k, v in list(value.items())[:40]}
    if isinstance(value, (list, tuple)):
        return [safe_value(v, depth=depth + 1) for v in value[:40]]
    return safe_text(type(value).__name__)


class _FailureSnapshot(dict):
    """Bounded primitive data only: no exception, traceback, frame or local references."""

    def __init__(self, event, error, occurred_ns):
        super().__init__(event=event, errorType=type(error).__name__,
                         monotonicMs=occurred_ns // 1_000_000, monotonicNs=occurred_ns)
        self.frames = []
        if isinstance(error, BaseException):
            # Use the built-in descriptors rather than user-defined __str__/__getattribute__.
            args = BaseException.args.__get__(error)
            tb = BaseException.__traceback__.__get__(error)
            for _ in range(MAX_ERROR_FRAMES):
                if tb is None:
                    break
                code = tb.tb_frame.f_code
                self.frames.append((code.co_filename[:256], tb.tb_lineno, code.co_name[:80]))
                tb = tb.tb_next
        else:
            args = (error,)
        self.parts = tuple(value[:1024] if type(value) is str else "<large int>"
                           if type(value) is int and value.bit_length() > 64 else value
                           if type(value) in (int, float, bool, type(None))
                           else "<" + type(value).__name__[:80] + ">" for value in args[:8])

    def render(self):
        # Do not use traceback/linecache: even the writer needs no source file reads.
        message = safe_text("; ".join(str(value) for value in self.parts))
        result = dict(self, message=message)
        if self.frames:
            lines = [f'  File "{filename}", line {line}, in {function}'
                     for filename, line, function in self.frames]
            lines.append(self["errorType"] + ": " + message)
            result["stack"] = safe_text("\n".join(lines), 6000)
        return result


def _stderr_report(message):
    """Best-effort Linux pipe/tty warning with no Python buffered-stream lock.

    Opening /proc/self/fd creates a separate open-file description: O_NONBLOCK
    cannot change the SSH protocol stdout/stderr flags, even if they were dup'ed.
    Regular-file stderr is deliberately skipped because O_NONBLOCK cannot make
    disk writes nonblocking. Unsupported platforms/sinks may drop the warning.
    """
    if os.name != "posix":
        return
    fd = None
    try:
        sink = os.fstat(2).st_mode
        if not (stat.S_ISFIFO(sink) or stat.S_ISCHR(sink)):
            return
        fd = os.open("/proc/self/fd/2", os.O_WRONLY | os.O_NONBLOCK |
                     getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOCTTY", 0))
        payload = (safe_text(message, 384) + "\n").encode("utf-8", "replace")[:512]
        os.write(fd, payload)  # At most PIPE_BUF; a full pipe drops this warning.
    except (OSError, ValueError):
        pass
    finally:
        if fd is not None:
            os.close(fd)


def report_unavailable_async():
    """Constructor failure must not synchronously write stderr while holding motor ownership."""
    try:
        threading.Thread(target=_stderr_report,
                         args=("FOLLOW_DIAGNOSTIC_UNAVAILABLE: initialization failed",),
                         name="follow-diagnostic-warning", daemon=True).start()
    except Exception:
        # If a thread cannot be created there is no safe synchronous fallback.
        pass


class DiagnosticLog:
    """Callers only enqueue; disk access, rotation and stderr reporting run on one daemon."""

    def __init__(self, directory: Path, run_id: str, *, capacity=512,
                 max_bytes=MAX_BYTES, max_age_seconds=MAX_AGE_SECONDS,
                 max_file_bytes=MAX_FILE_BYTES, writer=None, report=None):
        if not re.fullmatch(r"[0-9a-f]{32}", run_id):
            raise ValueError("Invalid diagnostic run ID")
        self.directory, self.run_id = Path(directory), run_id
        self.capacity = max(1, capacity)
        self.max_bytes, self.max_age_seconds = max_bytes, max_age_seconds
        self.max_file_bytes = min(max_file_bytes, max_bytes)
        self.first_stop = None
        self.first_failure = None
        self.dropped = 0
        self.available = True
        self.error = None
        self._queue = deque()
        self._lock = threading.Lock()
        # Only an O(1) timestamp comparison/reference assignment occurs under this lock.
        # No queue, formatter, writer, report callback or I/O may acquire it.
        self._cause_lock = threading.Lock()
        self._cause_sequence = 0
        self._wake = threading.Event()
        self._closing = False
        self._sequence = 0
        self._file_index = 0
        self._current_path = None
        self._reported_dropped = 0
        self._writer = writer or self._write
        self._report = report or _stderr_report
        self._last_warning = 0.
        self._thread = threading.Thread(target=self._consume, name="follow-diagnostic-log", daemon=True)
        self._thread.start()

    def event(self, event: str, *, critical=False, **fields):
        # No blocking lock acquisition or file operation is allowed on the control path.
        if not self._lock.acquire(blocking=False):
            self.dropped += 1
            return
        try:
            if self._closing:
                self.dropped += 1
                return
            self._sequence += 1
            row = dict(schemaVersion=1, time=datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
                       monotonicMs=time.monotonic_ns() // 1_000_000, pid=os.getpid(),
                       runId=self.run_id, eventIndex=self._sequence, event=event, **fields)
            if len(self._queue) >= self.capacity:
                if not critical:
                    self.dropped += 1
                    return
                ordinary = next((i for i, (priority, _) in enumerate(self._queue) if not priority), None)
                if ordinary is None:
                    self._queue.popleft()
                else:
                    del self._queue[ordinary]
                self.dropped += 1
            self._queue.append((critical, row))
        finally:
            self._lock.release()
        self._wake.set()

    def stop_requested(self, source: str, **fields):
        occurred_ns, order = self._capture_cause_order()
        detail = dict(source=source[:128], monotonicMs=occurred_ns // 1_000_000,
                      monotonicNs=occurred_ns, causeOrder=order)
        self._remember_first("first_stop", detail)
        self.event("stop_requested", critical=True, stopSource=source,
                   firstStop=self.first_stop, **fields)

    def failure(self, event: str, error: BaseException | str, **fields):
        occurred_ns, order = self._capture_cause_order()
        detail = _FailureSnapshot(event, error, occurred_ns)
        detail["causeOrder"] = order
        self._remember_first("first_failure", detail)
        self.event(event, critical=True, error=detail, firstFailure=self.first_failure, **fields)

    def _capture_cause_order(self):
        with self._cause_lock:
            self._cause_sequence += 1
            return time.monotonic_ns(), self._cause_sequence

    def _remember_first(self, attribute, detail):
        # Capture time precedes snapshot construction. A preempted earlier caller must
        # still win when a later caller queues its fault first.
        with self._cause_lock:
            current = getattr(self, attribute)
            # The sequence resolves equal timestamps on coarse monotonic clocks.
            if current is None or (detail["monotonicNs"], detail["causeOrder"]) < \
                    (current["monotonicNs"], current["causeOrder"]):
                setattr(self, attribute, detail)

    def close(self, timeout=.5):
        # Only called after worker cleanup. Never wait for this from heartbeat/STOP paths.
        self._closing = True
        self._wake.set()
        self._thread.join(timeout)
        return not self._thread.is_alive()

    def _consume(self):
        while True:
            with self._lock:
                row = self._queue.popleft()[1] if self._queue else None
            if row is None:
                if self._closing:
                    return
                self._wake.wait(.2)
                self._wake.clear()
                continue
            try:
                dropped = self.dropped
                if dropped != self._reported_dropped:
                    row["droppedEvents"] = dropped
                self._writer(safe_value(row))
                self._reported_dropped = dropped
                self.available = True
                self.error = None
            except Exception as error:
                self.available = False
                self.error = safe_text(error, 300)
                self.dropped += 1
                now = time.monotonic()
                if not self._last_warning or now - self._last_warning >= 30:
                    self._last_warning = now
                    try:
                        self._report("FOLLOW_DIAGNOSTIC_UNAVAILABLE: " + self.error)
                    except Exception:
                        pass

    @contextmanager
    def _directory_lock(self):
        # Concurrent bridge processes share retention without ever locking control work.
        fd = os.open(self.directory / ".writer.lock", os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
        try:
            if os.name == "posix":
                import fcntl
                fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            os.close(fd)

    def _new_path(self):
        self._file_index += 1
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        self._current_path = self.directory / f"{stamp}_{self.run_id}_{self._file_index:04d}.jsonl"

    def _write(self, row):
        payload = (json.dumps(row, ensure_ascii=True, allow_nan=False, separators=(",", ":")) + "\n").encode()
        if len(payload) > self.max_bytes:
            raise OSError("Diagnostic event exceeds storage budget")
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        if os.name == "posix":
            self.directory.chmod(0o700)
        with self._directory_lock():
            if (self._current_path is None or not self._current_path.exists() or
                    self._current_path.stat().st_size + len(payload) > self.max_file_bytes):
                self._new_path()
            now = time.time()
            files = []
            for path in self.directory.glob("*.jsonl"):
                stat = path.stat()
                if now - stat.st_mtime > self.max_age_seconds:
                    path.unlink()
                else:
                    files.append((stat.st_mtime_ns, path, stat.st_size))
            size = sum(item[2] for item in files)
            for _, path, length in sorted(files):
                if size + len(payload) <= self.max_bytes:
                    break
                path.unlink()
                size -= length
            fd = os.open(self._current_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND | getattr(os, "O_NOFOLLOW", 0), 0o600)
            try:
                # A single line is kept intact even if os.write only accepts a prefix.
                remaining = memoryview(payload)
                while remaining:
                    written = os.write(fd, remaining)
                    if written <= 0:
                        raise OSError("Diagnostic file write made no progress")
                    remaining = remaining[written:]
            finally:
                os.close(fd)


class NullDiagnosticLog:
    """Used by pure protocol tests and a failed logger constructor; never touches disk."""
    first_stop = None
    first_failure = None
    dropped = 0

    def event(self, *_args, **_kwargs):
        pass

    def failure(self, *_args, **_kwargs):
        pass

    def stop_requested(self, *_args, **_kwargs):
        pass

    def close(self, *_args, **_kwargs):
        return True
