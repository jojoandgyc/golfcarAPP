"""Delayed SSH recovery: virtual serial/clock and an isolated --dry-run child only."""
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
from tempfile import TemporaryDirectory
import unittest

import app_joystick_bridge as bridge
import test_app_joystick_bridge as fixtures


class ExtendedNetworkGraceTests(unittest.TestCase):
    def scripted(self, actions):
        return fixtures.BridgeProtocolTests.run_scripted_grace(self, actions)

    def test_ready_distinguishes_fifteen_second_session_from_six_hundred_ms_motion(self):
        ready = bridge.ProtocolSession(bridge.Motors(), port="fake", dry_run=True).ready()
        self.assertEqual(ready["networkGraceMs"], 15000)
        self.assertEqual(ready["watchdogMs"], 600)

    def test_six_second_gap_retains_session_but_requires_neutral_before_fresh_motion(self):
        for neutral in ("stop", "drive"):
            with self.subTest(neutral=neutral):
                result, messages, _deadlines, events, _elapsed, _motors, client = self.scripted([
                    (.1, "drive"), "deadline", (6.1, "drive", 50, -50),
                    (6.2, neutral, 0, 0), (6.3, "drive", 20, -20), (6.4, "close")])
                self.assertEqual(result, 0)
                acks = [message for message in messages if message["type"] == "ack"]
                self.assertEqual([ack["seq"] for ack in acks], [1, 2, 3, 4, 5])
                self.assertEqual((acks[1]["left"], acks[1]["right"]), (0, 0))
                self.assertTrue(acks[1]["recoveryRequired"])
                self.assertTrue(acks[2]["recoveryRequired"], "neutral ACK still discards old input")
                self.assertFalse(acks[3]["recoveryRequired"])
                self.assertEqual((acks[3]["left"], acks[3]["right"]), (20, -20))
                self.assertNotIn(("left", 50), client.calls)
                self.assertIn(("left", 20), client.calls)
                self.assertFalse(any(call[0] == "write" for call in client.calls))
                paused = [event for event in events if event["event"] == "drive.network_paused"]
                self.assertEqual(len(paused), 1)
                self.assertAlmostEqual(paused[0]["clock"], .7)
                self.assertTrue(acks[-1]["stopped"])

    def test_repeated_authenticated_held_motion_cannot_move_or_extend_fifteen_second_deadline(self):
        result, messages, _deadlines, events, elapsed, _motors, client = self.scripted([
            (.1, "drive"), "deadline", (6, "drive", 50, -50),
            (10, "drive", 50, -50), (14.9, "drive", 50, -50), "deadline"])
        self.assertEqual(result, 1)
        self.assertAlmostEqual(elapsed, 15.1)
        acks = [message for message in messages if message["type"] == "ack"]
        self.assertEqual(len(acks), 4)
        for ack in acks[1:]:
            self.assertTrue(ack["recoveryRequired"])
            self.assertEqual((ack["left"], ack["right"]), (0, 0))
        self.assertNotIn(("left", 50), client.calls)
        failure = next(event for event in events if event["event"] == "drive.failure")
        self.assertEqual(failure["stopSource"], "network_grace_expired")
        self.assertEqual(failure["lastAckSeq"], 4)

    def test_neutral_arriving_after_absolute_deadline_does_not_resurrect_session(self):
        result, messages, _deadlines, events, _elapsed, _motors, _client = self.scripted([
            (.1, "drive"), "deadline", (15.101, "stop")])
        self.assertEqual(result, 1)
        self.assertEqual([m["type"] for m in messages], ["ready", "ack", "error"])
        failure = next(event for event in events if event["event"] == "drive.failure")
        self.assertEqual(failure["stopSource"], "network_grace_expired")
        self.assertEqual(failure["lastAckSeq"], 1)

    def test_eof_after_old_five_second_boundary_is_still_immediately_terminal(self):
        result, _messages, _deadlines, events, elapsed, _motors, _client = self.scripted([
            (.1, "drive"), "deadline", (6.1, bridge.SessionError("Control input closed"))])
        self.assertEqual(result, 1)
        self.assertAlmostEqual(elapsed, 6.1)
        failure = next(event for event in events if event["event"] == "drive.failure")
        self.assertEqual(failure["stopSource"], "input_eof")
        self.assertGreater(failure["networkGraceRemainingMs"], 8000)

    def test_close_during_extended_grace_remains_bounded_and_preserves_configuration(self):
        result, messages, _deadlines, _events, elapsed, _motors, client = self.scripted([
            (.1, "drive"), "deadline", (6.1, "close")])
        self.assertEqual(result, 0)
        self.assertLess(elapsed, 6.4)
        self.assertTrue(messages[-1]["stopped"])
        self.assertTrue(messages[-1]["stopCommandConfirmed"])
        self.assertFalse(any(call[0] == "write" for call in client.calls))


@unittest.skipUnless(os.name == "posix", "Real bridge stdout pipe requires POSIX nonblocking writes")
class ExtendedGraceSubprocessTests(unittest.TestCase):
    def test_real_dry_run_stays_alive_past_five_seconds_and_neutral_recovers_same_session(self):
        with TemporaryDirectory() as directory:
            environment = dict(os.environ, APP_DRIVE_DIAGNOSTIC_TEST_DIR=directory,
                               PYTHONDONTWRITEBYTECODE="1")
            environment.pop("LZ_IR_SYSFS_ROOT", None)
            process = subprocess.Popen(
                [sys.executable, "-B", "-u", str(Path(bridge.__file__)), "--dry-run"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                env=environment)
            frames = queue.Queue()

            def read_stdout():
                for line in process.stdout:
                    frames.put(line)
                frames.put(None)

            reader = threading.Thread(target=read_stdout, daemon=True)
            reader.start()

            def receive():
                line = frames.get(timeout=3)
                self.assertIsNotNone(line, "dry-run exited before its reply")
                return json.loads(line)

            def exchange(prior, seq, command, left=0, right=0):
                request = dict(seq=seq, token=prior["token"], cmd=command, left=left, right=right)
                process.stdin.write((json.dumps(request) + "\n").encode())
                process.stdin.flush()
                reply = receive()
                self.assertEqual(reply["type"], "ack")
                self.assertEqual(reply["seq"], seq)
                return reply

            try:
                ready = receive()
                self.assertTrue(ready["dryRun"])
                self.assertEqual(ready["port"], "dry-run")
                self.assertEqual((ready["watchdogMs"], ready["networkGraceMs"]), (600, 15000))
                first = exchange(ready, 1, "drive", 30, -30)
                time.sleep(6.05)
                self.assertIsNone(process.poll(), "stopped recovery session must outlive five seconds")
                held = exchange(first, 2, "drive", 50, -50)
                self.assertEqual((held["left"], held["right"]), (0, 0))
                self.assertTrue(held["recoveryRequired"])
                self.assertTrue(held["stopped"])
                neutral = exchange(held, 3, "stop")
                self.assertTrue(neutral["recoveryRequired"])
                fresh = exchange(neutral, 4, "drive", 20, -20)
                self.assertFalse(fresh["recoveryRequired"])
                self.assertEqual((fresh["left"], fresh["right"]), (20, -20))
                closed = exchange(fresh, 5, "close")
                self.assertTrue(closed["stopped"])
                self.assertEqual(process.wait(timeout=3), 0)
                rows = [json.loads(line) for path in Path(directory).glob("*.jsonl")
                        for line in path.read_text(encoding="utf-8").splitlines()]
                rows = [row for row in rows if row["runId"] == ready["runId"]]
                self.assertEqual(sum(row["event"] == "drive.session_started" for row in rows), 1)
                paused = next(row for row in rows if row["event"] == "drive.network_paused")
                self.assertEqual(paused["lastAckSeq"], 1)
                self.assertLess(paused["stoppedMonotonicMs"] - paused["leaseExpiredMonotonicMs"], 500)
                self.assertAlmostEqual(paused["sessionDeadlineMonotonicMs"] -
                                       paused["leaseExpiredMonotonicMs"], 14400, delta=1)
                self.assertFalse(any(row["event"] == "drive.failure" for row in rows))
                self.assertTrue(next(row for row in rows if row["event"] == "drive.finished")["cleanupConfirmed"])
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=3)
                reader.join(timeout=1)
                for stream in (process.stdin, process.stdout, process.stderr):
                    stream.close()


if __name__ == "__main__":
    unittest.main()
