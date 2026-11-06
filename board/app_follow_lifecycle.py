"""Hardware-free startup deadlines shared by the App worker and supervisor."""
from __future__ import annotations

import threading
import time


STARTUP_TIMEOUT_SECONDS = 30.0
STAGE_TIMEOUT_SECONDS = {
    "worker_start": 5., "environment": 5., "preflight": 10.,
    "runtime_import": 15., "configuration": 15., "runtime_construct": 10.,
    "gestures": 20., "detector": 15., "reid": 15., "gallery": 10.,
    "camera": 15., "services": 15., "motor": 10.,
}


class StartupTimeout(RuntimeError):
    pass


class StartupProgress:
    def __init__(self, clock=None):
        self.clock = clock or time.monotonic
        self.started_at = self.stage_at = self.clock()
        self.stage = "worker_start"
        self.completed_at = None
        self.lock = threading.Lock()

    def _expired(self, now):
        if self.completed_at is not None:
            return None
        if now - self.started_at >= STARTUP_TIMEOUT_SECONDS:
            return f"跟随初始化超时（总期限 {STARTUP_TIMEOUT_SECONDS:g}s，阶段 {self.stage}），已请求停止"
        if now - self.stage_at >= STAGE_TIMEOUT_SECONDS[self.stage]:
            return f"跟随初始化阶段超时（{self.stage}，{STAGE_TIMEOUT_SECONDS[self.stage]:g}s），已请求停止"
        return None

    def expired(self):
        with self.lock:
            return self._expired(self.clock())

    def begin(self, stage):
        if stage not in STAGE_TIMEOUT_SECONDS:
            raise ValueError(f"Unknown startup stage: {stage}")
        with self.lock:
            now = self.clock()
            if error := self._expired(now):
                raise StartupTimeout(error)
            if self.completed_at is not None:
                return
            # Repeated notifications cannot renew a deadline. Regressions cannot
            # grant a second budget either; keep the furthest observed stage.
            stages = tuple(STAGE_TIMEOUT_SECONDS)
            if stages.index(stage) > stages.index(self.stage):
                self.stage, self.stage_at = stage, now

    def complete(self):
        with self.lock:
            now = self.clock()
            if error := self._expired(now):
                raise StartupTimeout(error)
            if self.completed_at is None:
                self.completed_at = now

    def status(self):
        with self.lock:
            now = self.completed_at if self.completed_at is not None else self.clock()
            return dict(stage=self.stage, stageElapsedMs=int(max(0., now - self.stage_at) * 1000),
                totalElapsedMs=int(max(0., now - self.started_at) * 1000),
                stageTimeoutMs=int(STAGE_TIMEOUT_SECONDS[self.stage] * 1000),
                totalTimeoutMs=int(STARTUP_TIMEOUT_SECONDS * 1000),
                completed=self.completed_at is not None)
