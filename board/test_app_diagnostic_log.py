"""Diagnostics fault injection and --dry-run sessions; no camera or motor access."""
import contextlib
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest

from app_diagnostic_log import DiagnosticLog, safe_value
from app_follow_bridge import FollowProcess


RUN_ID = "a" * 32
SCRIPT = Path(__file__).with_name("app_follow_bridge.py")


def wait_for(predicate, timeout=2):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.01)
    raise AssertionError("Condition did not become true")


class DiagnosticStorageTests(unittest.TestCase):
    def test_slow_disk_and_full_queue_never_block_control_and_prioritize_stop(self):
        entered, release = threading.Event(), threading.Event()
        rows = []
        def slow(row):
            entered.set()
            release.wait(3)
            rows.append(row)
        log = DiagnosticLog(Path("unused"), RUN_ID, capacity=3, writer=slow)
        try:
            log.event("first")
            self.assertTrue(entered.wait(1))
            began = time.monotonic()
            for seq in range(1000):
                log.event("heartbeat", seq=seq)
            log.stop_requested("USER_STOP")
            log.stop_requested("cleanup")
            self.assertLess(time.monotonic() - began, .5)
            self.assertGreater(log.dropped, 0)
        finally:
            release.set()
            self.assertTrue(log.close(2))
        stop_rows = [row for row in rows if row["event"] == "stop_requested"]
        self.assertEqual([row["stopSource"] for row in stop_rows], ["USER_STOP", "cleanup"])
        self.assertTrue(all(row["firstStop"]["source"] == "USER_STOP" for row in stop_rows))
        self.assertTrue(any(row.get("droppedEvents", 0) > 0 for row in rows))

    def test_disk_failure_is_reported_without_protocol_output_or_control_exception(self):
        warnings = []
        def fail(_row):
            raise OSError("disk failed password=do-not-log " + "b" * 32)
        log = DiagnosticLog(Path("unused"), RUN_ID, writer=fail, report=warnings.append)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            log.event("control_stopped", critical=True)
            wait_for(lambda: not log.available)
            self.assertTrue(log.close(1))
        self.assertEqual(output.getvalue(), "")
        self.assertEqual(len(warnings), 1)
        self.assertIn("FOLLOW_DIAGNOSTIC_UNAVAILABLE", warnings[0])
        self.assertNotIn("do-not-log", warnings[0])
        self.assertNotIn("b" * 32, warnings[0])

    def test_rotation_bounds_active_runs_and_deletes_expired_files(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            old = folder / "old.jsonl"
            old.write_text("expired\n")
            os.utime(old, (time.time() - 8 * 86400,) * 2)
            log = DiagnosticLog(folder, RUN_ID, capacity=200, max_bytes=4096, max_file_bytes=1024)
            for seq in range(80):
                log.event("heartbeat", seq=seq, durationMs=12)
            self.assertTrue(log.close(3))
            self.assertFalse(old.exists())
            paths = list(folder.glob("*.jsonl"))
            self.assertGreater(len(paths), 1)
            self.assertLessEqual(sum(path.stat().st_size for path in paths), 4096)
            rows = [json.loads(line) for path in paths for line in path.read_text().splitlines()]
            self.assertTrue(any(row.get("seq") == 79 for row in rows))
            self.assertTrue(all(row["runId"] == RUN_ID for row in rows))
            if os.name == "posix":
                self.assertEqual(folder.stat().st_mode & 0o777, 0o700)
                self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in paths))

    def test_first_failure_is_preserved_and_secrets_are_redacted(self):
        rows = []
        log = DiagnosticLog(Path("unused"), RUN_ID, writer=rows.append)
        log.failure("ack_timeout", RuntimeError("token=" + "b" * 32 + " password=hidden"), phase="ack")
        log.failure("cleanup_failed", OSError("later"), phase="cleanup")
        self.assertTrue(log.close(1))
        self.assertEqual(log.first_failure["event"], "ack_timeout")
        self.assertEqual(rows[-1]["firstFailure"]["event"], "ack_timeout")
        encoded = json.dumps(rows)
        self.assertNotIn("hidden", encoded)
        self.assertNotIn("b" * 32, encoded)
        self.assertIn(RUN_ID, encoded)
        self.assertEqual(safe_value({"token": "secret-value"})["token"], "[REDACTED]")

    def test_worker_status_failure_keeps_original_stop_source(self):
        class Alive:
            pid = 42
            def poll(self):
                return None
        rows = []
        log = DiagnosticLog(Path("unused"), RUN_ID, writer=rows.append)
        process = FollowProcess("config", False, True)
        process.diagnostic = log
        process.process = Alive()
        process.started_at = time.monotonic() - 6
        # There is no child stdin in this fake. Keep the existing control semantics but
        # capture its request source without sending a real signal.
        process.request_stop = lambda reason: log.stop_requested(reason)
        self.assertEqual(process.status()["runState"], "FAULT")
        log.stop_requested("app_session_closed")
        self.assertTrue(log.close(1))
        self.assertEqual(log.first_failure["event"], "worker_status_failed")
        self.assertEqual(log.first_stop["source"], "app_status_failed")


class DiagnosticSessionTests(unittest.TestCase):
    def start(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        p = subprocess.Popen([sys.executable, "-u", str(SCRIPT), "--dry-run"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=dict(os.environ, APP_FOLLOW_DIAGNOSTIC_TEST_DIR=directory.name))
        def cleanup():
            if p.poll() is None:
                with contextlib.suppress(Exception):
                    p.stdin.close()
                try:
                    p.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    p.kill()  # Only this test-created --dry-run process.
                    p.wait()
            for stream in (p.stdin, p.stdout, p.stderr):
                with contextlib.suppress(Exception):
                    stream.close()
        self.addCleanup(cleanup)
        ready = json.loads(p.stdout.readline())
        self.assertEqual(ready["type"], "ready")
        return p, ready, Path(directory.name)

    def send(self, process, reply, command, seq, **changes):
        request = dict(token=reply["token"], runId=reply["runId"], cmd=command, seq=seq)
        request.update(changes)
        process.stdin.write((json.dumps(request) + "\n").encode())
        process.stdin.flush()
        return json.loads(process.stdout.readline())

    def records(self, directory):
        return [json.loads(line) for path in sorted(directory.glob("*.jsonl"))
                for line in path.read_text(encoding="utf-8").splitlines()]

    def test_normal_stop_logs_full_chain_without_tokens_or_stdout_pollution(self):
        p, ready, directory = self.start()
        tokens = [ready["token"]]
        reply = self.send(p, ready, "start", 1)
        tokens.append(reply["token"])
        reply = self.send(p, reply, "stop", 2)
        seq = 2
        while not reply["terminal"]:
            tokens.append(reply["token"])
            seq += 1
            time.sleep(.05)
            reply = self.send(p, reply, "heartbeat", seq)
        self.assertEqual(p.wait(timeout=5), 0)
        self.assertEqual(p.stdout.read(), b"")
        rows = self.records(directory)
        events = [row["event"] for row in rows]
        for event in ("session_started", "ready_sent", "request_accepted", "worker_started",
                      "stop_requested", "worker_stop_sent", "ack_sent", "terminal_sent",
                      "worker_exited", "cleanup_completed", "session_finished"):
            self.assertIn(event, events)
        self.assertEqual(rows[-1]["firstStop"]["source"], "app_stop_requested")
        self.assertIsNone(rows[-1]["firstFailure"])
        self.assertEqual(rows[-1]["workerExitCode"], 0)
        self.assertTrue(all(row["runId"] == ready["runId"] for row in rows))
        text = json.dumps(rows)
        self.assertTrue(all(token not in text for token in tokens))

    def test_eof_and_watchdog_and_protocol_failure_have_distinct_root_reasons(self):
        for trigger in ("input_eof", "heartbeat_timeout", "protocol_error"):
            with self.subTest(trigger=trigger):
                p, ready, directory = self.start()
                reply = self.send(p, ready, "start", 1)
                if trigger == "input_eof":
                    p.stdin.close()
                elif trigger == "protocol_error":
                    error = self.send(p, reply, "heartbeat", 2, token="c" * 32)
                    self.assertEqual(error["type"], "error")
                else:
                    self.assertEqual(json.loads(p.stdout.readline())["type"], "error")
                self.assertNotEqual(p.wait(timeout=6), 0)
                # Any remaining stdout must still be a protocol JSON response.
                for line in p.stdout.read().splitlines():
                    self.assertEqual(json.loads(line)["type"], "error")
                rows = self.records(directory)
                failed = next(row for row in rows if row["event"] == "session_failed")
                self.assertEqual(failed["trigger"], trigger)
                self.assertEqual(rows[-1]["firstStop"]["source"], trigger)
                self.assertEqual(rows[-1]["firstFailure"]["event"], "session_failed")
                self.assertEqual(rows[-1]["lastValidRequest"]["seq"], 1)
                self.assertEqual(rows[-1]["exitCode"], 1)

    @unittest.skipUnless(os.name == "posix", "POSIX signal handling")
    def test_signal_is_logged_before_cleanup_replaces_the_surface_reason(self):
        p, ready, directory = self.start()
        self.send(p, ready, "start", 1)
        p.send_signal(signal.SIGTERM)
        self.assertNotEqual(p.wait(timeout=6), 0)
        rows = self.records(directory)
        self.assertEqual(rows[-1]["firstStop"]["source"], "signal")
        failure = next(row for row in rows if row["event"] == "session_failed")
        self.assertEqual(failure["signalNumber"], signal.SIGTERM)


if __name__ == "__main__":
    unittest.main()
