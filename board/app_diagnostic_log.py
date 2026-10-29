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
import sys
import threading
import time
import traceback


MAX_BYTES = 20 * 1024 * 1024
MAX_AGE_SECONDS = 7 * 24 * 60 * 60
MAX_FILE_BYTES = 2 * 1024 * 1024
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
        self._wake = threading.Event()
        self._closing = False
        self._sequence = 0
        self._file_index = 0
        self._current_path = None
        self._reported_dropped = 0
        self._writer = writer or self._write
        self._report = report or (lambda message: print(message, file=sys.stderr, flush=True))
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
        # These calls belong to the supervisor's serial control loop. Preserve the earliest
        # reason separately, even if a later cleanup/duplicate request has a different one.
        if self.first_stop is None:
            self.first_stop = dict(source=safe_text(source), monotonicMs=time.monotonic_ns() // 1_000_000)
        self.event("stop_requested", critical=True, stopSource=source,
                   firstStop=self.first_stop, **fields)

    def failure(self, event: str, error: BaseException | str, **fields):
        detail = dict(event=event, errorType=type(error).__name__, message=safe_text(error),
                      monotonicMs=time.monotonic_ns() // 1_000_000)
        if isinstance(error, BaseException):
            detail["stack"] = safe_text("".join(traceback.format_exception(type(error), error, error.__traceback__)), 6000)
        if self.first_failure is None:
            self.first_failure = detail
        self.event(event, critical=True, error=detail, firstFailure=self.first_failure, **fields)

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
