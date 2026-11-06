"""Offline follow persistence tests: fake state and explicit --dry-run only."""
import contextlib
import json
import os
from pathlib import Path
import select
import signal
import socket
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import app_follow_session as sessions
from app_diagnostic_log import DiagnosticLog
from app_follow_options import FollowOptions

SCRIPT = Path(__file__).with_name("app_follow_session.py")


class FakeProcess:
    camera, execute, dry_run = "config", False, True
    options = FollowOptions(gestures=True)

    def __init__(self):
        self.process = self.stop_at = None
        self.starts = self.pulses = 0
        self.detached = []
        self.reason = None
        self.worker_reason = None
        self.worker_stop_requested = False
        self.stop_requests = []
        self.exited = False
        self.final_pending = False

    def start(self):
        if self.process is None:
            self.process = SimpleNamespace(pid=12345, returncode=None)
            self.starts += 1

    def pulse(self):
        self.pulses += 1

    def set_network_detached(self, value):
        self.detached.append(value)

    def request_stop(self, reason):
        self.stop_requests.append(reason)
        if self.stop_at is None:
            self.stop_at = time.monotonic()
            self.reason = reason

    def finish(self, reason=None):
        self.exited = True
        if self.process:
            self.process.returncode = 0
        if reason:
            self.worker_reason = reason
            self.worker_stop_requested = True

    def status(self):
        exited = self.exited or self.process is None
        return dict(runState="STOPPED" if exited else "STOPPING" if self.stop_at or self.worker_stop_requested else "RUNNING",
            processExited=exited, finalStatusPending=self.final_pending, exitCode=0 if exited else None,
            reason=self.reason or self.worker_reason,
            stop=dict(requested=self.stop_at is not None or self.worker_stop_requested, acknowledged=None, physicalStopped=None))

@unittest.skipUnless(os.name == "posix", "Linux session ownership and UNIX sockets")
class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="follow-session-test-")
        self.store = sessions.SessionStore(Path(self.temporary.name) / "sessions")
        self.process = FakeProcess()
        self.now = 100.
        self.run_id = "a" * 32
        self.supervisor = sessions.SessionSupervisor(self.process, self.run_id, self.store, clock=lambda: self.now)
        self.peers = []

    def tearDown(self):
        if self.supervisor.connection:
            self.supervisor.connection.close()
        for peer in self.peers:
            peer.close()
        self.store.close()
        self.temporary.cleanup()

    def attach(self):
        server, client = socket.socketpair()
        client.settimeout(1.)
        self.peers.append(client)
        self.supervisor.attach(server)
        return client, json.loads(client.recv(16384))

    def send(self, client, command="heartbeat", **changes):
        protocol = self.supervisor.protocol
        request = dict(seq=protocol.sequence, runId=protocol.run_id, token=protocol.token, cmd=command)
        request.update(changes)
        client.sendall(sessions.encode(request))
        self.supervisor.receive()
        return json.loads(client.recv(16384))

    def test_eof_detaches_same_worker_and_new_nonce_sequence_resume_only_on_heartbeat(self):
        client, ready = self.attach()
        self.assertFalse(ready["resumed"])
        self.send(client, "start")
        worker = self.process.process
        client.close()
        self.supervisor.receive()
        self.assertIsNone(self.process.stop_at)
        self.assertEqual(self.process.detached, [True])
        client2, ready2 = self.attach()
        self.assertTrue(ready2["resumed"])
        self.assertEqual(ready2["runId"], ready["runId"])
        self.assertNotEqual(ready2["token"], ready["token"])
        self.assertEqual(self.supervisor.protocol.sequence, 1)
        self.assertEqual(self.process.detached, [True])
        self.send(client2)
        self.assertEqual(self.process.detached, [True, False])
        self.assertIs(self.process.process, worker)
        self.assertEqual(self.process.starts, 1)

    def test_half_packets_do_not_renew_lease_and_local_pulses_continue_offline(self):
        client, _ = self.attach()
        self.send(client, "start")
        for _ in range(4):
            self.now += .4
            client.sendall(b" ")
            self.supervisor.receive()
            self.supervisor.tick()
        self.now += .5
        self.assertFalse(self.supervisor.tick())
        self.assertIsNone(self.supervisor.connection)
        self.assertEqual(self.process.detached, [True])
        pulses = self.process.pulses
        for _ in range(20):
            self.now += .25
            self.assertFalse(self.supervisor.tick())
        self.assertGreater(self.process.pulses, pulses)
        self.assertIsNone(self.process.stop_at)

    def test_no_start_disconnect_archives_without_starting_worker(self):
        client, _ = self.attach()
        client.close()
        self.supervisor.receive()
        self.assertTrue(self.supervisor.tick())
        self.assertEqual(self.process.starts, 0)
        record = self.store.read(self.run_id)
        self.assertTrue(record["ready"]["status"]["processExited"])
        self.assertNotIn("token", record["ready"])

    def test_second_attachment_busy_never_touches_original_owner(self):
        client, ready = self.attach()
        self.send(client, "start")
        _, error = self.attach()
        self.assertEqual(error["code"], "session_busy")
        self.assertEqual(self.supervisor.attach_count, 1)
        self.assertEqual(self.process.detached, [])
        self.assertEqual(self.send(client)["runId"], ready["runId"])

    def test_expired_attachment_can_be_replaced_without_stale_eof_detaching_replacement(self):
        client, _ = self.attach()
        self.send(client, "start")
        self.now += 2.1
        client2, ready2 = self.attach()
        client.close()
        self.assertTrue(ready2["resumed"])
        self.send(client2)
        self.assertEqual(self.process.detached, [True, False])
        self.assertIsNotNone(self.supervisor.connection)

    def test_bad_sequence_or_token_requests_stop_instead_of_offline_continuation(self):
        for changes in (dict(seq=99), dict(token="b" * 32)):
            with self.subTest(changes=changes):
                self.process = FakeProcess()
                self.supervisor = sessions.SessionSupervisor(self.process, self.run_id, self.store, clock=lambda: self.now)
                client, _ = self.attach()
                self.send(client, "start")
                reply = self.send(client, **changes)
                self.assertEqual(reply["type"], "error")
                self.assertEqual(self.process.reason, "app_protocol_error")
                self.assertEqual(self.process.detached, [])

    def test_accepted_stop_with_lost_ack_remains_stopping_and_archive_cannot_restart(self):
        client, _ = self.attach()
        self.send(client, "start")
        request = dict(seq=self.supervisor.protocol.sequence, runId=self.run_id,
                       token=self.supervisor.protocol.token, cmd="stop")
        client.sendall(sessions.encode(request))
        with patch.object(sessions, "send_socket", side_effect=BrokenPipeError):
            self.supervisor.receive()
        self.assertEqual(self.process.reason, "app_stop_requested")
        self.assertEqual(self.process.detached, [])
        self.process.finish()
        self.assertTrue(self.supervisor.tick())
        record = self.store.read(self.run_id)
        archived = sessions.AttachmentProtocol(sessions.ArchivedProcess(record["ready"]), self.run_id, True)
        for command in ("start", "heartbeat", "stop"):
            ack = archived.accept(dict(cmd=command, seq=archived.sequence, token=archived.token, runId=self.run_id))
            self.assertTrue(ack["terminal"])
            self.assertEqual(ack["status"]["reason"], "app_stop_requested")
        self.assertEqual(self.process.starts, 1)

    def test_ordinary_output_failure_detaches_without_stopping(self):
        client, _ = self.attach()
        self.send(client, "start")
        protocol = self.supervisor.protocol
        client.sendall(sessions.encode(dict(seq=protocol.sequence, runId=self.run_id, token=protocol.token, cmd="heartbeat")))
        with patch.object(sessions, "send_socket", side_effect=BlockingIOError):
            self.supervisor.receive()
        self.assertIsNone(self.process.stop_at)
        self.assertEqual(self.process.detached, [True])

    def test_gesture_terminal_reason_is_preserved_and_final_status_drain_is_required(self):
        client, _ = self.attach()
        self.send(client, "start")
        client.close()
        self.supervisor.receive()
        self.process.finish("app_offline_stop_gesture")
        self.process.final_pending = True
        self.assertFalse(self.supervisor.tick())
        self.assertIsNone(self.store.read(self.run_id))
        self.process.final_pending = False
        self.assertTrue(self.supervisor.tick())
        self.assertEqual(self.store.read(self.run_id)["ready"]["status"]["reason"], "app_offline_stop_gesture")

    def test_worker_gesture_is_archive_first_stop_before_generic_cleanup(self):
        diagnostic = DiagnosticLog(Path(self.temporary.name) / "diagnostics", self.run_id)
        self.supervisor.diagnostic = self.process.diagnostic = diagnostic
        try:
            client, _ = self.attach()
            self.send(client, "start")
            client.close()
            self.supervisor.receive()
            self.process.finish("app_offline_stop_gesture")
            self.assertTrue(self.supervisor.tick())
            diagnostic.stop_requested("app_session_closed")
            self.assertEqual(diagnostic.first_stop["source"], "app_offline_stop_gesture")
            self.assertEqual(self.store.read(self.run_id)["firstStop"]["source"], "app_offline_stop_gesture")
        finally:
            diagnostic.close()

    def test_new_app_stop_observes_worker_gesture_first_without_skipping_stop_delivery(self):
        diagnostic = DiagnosticLog(Path(self.temporary.name) / "diagnostics", self.run_id)
        self.supervisor.diagnostic = self.process.diagnostic = diagnostic
        try:
            client, _ = self.attach()
            self.send(client, "start")
            # No supervisor tick has observed this already accepted worker stop.
            self.process.worker_reason = "app_offline_stop_gesture"
            self.process.worker_stop_requested = True
            self.assertFalse(self.supervisor.worker_stop_logged)
            reply = self.send(client, "stop")
            self.assertEqual(self.process.stop_requests, ["app_stop_requested"])
            self.assertIsNotNone(self.process.stop_at)
            self.assertEqual(reply["status"]["reason"], "app_offline_stop_gesture")
            self.assertEqual(diagnostic.first_stop["source"], "app_offline_stop_gesture")
            self.process.finish()
            self.send(client, "heartbeat")
            archived = self.store.read(self.run_id)
            self.assertEqual(archived["ready"]["status"]["reason"], "app_offline_stop_gesture")
            self.assertEqual(archived["firstStop"]["source"], "app_offline_stop_gesture")
        finally:
            diagnostic.close()

    def test_earlier_app_stop_is_not_relabelled_by_a_later_worker_gesture(self):
        client, _ = self.attach()
        self.send(client, "start")
        self.send(client, "stop")
        self.process.worker_reason = "app_offline_stop_gesture"
        self.process.worker_stop_requested = True
        reply = self.send(client, "close")
        self.assertEqual(reply["status"]["reason"], "app_stop_requested")
        self.assertFalse(self.supervisor.worker_stop_logged)

    def test_store_rejects_traversal_symlinks_and_world_accessible_records(self):
        for value in ("../abc", "A" * 32, "a" * 31, "a" * 33):
            with self.assertRaises(sessions.SessionUnavailable):
                self.store.read(value)
        target = Path(self.temporary.name) / "target"
        target.write_text("{}")
        path = self.store.directory / (self.run_id + ".json")
        path.symlink_to(target)
        with self.assertRaises(OSError):
            self.store.read(self.run_id)
        path.unlink()
        path.write_text("{}")
        path.chmod(0o644)
        with self.assertRaises(sessions.SessionUnavailable):
            self.store.read(self.run_id)

    def test_store_rotation_only_removes_completed_regular_records(self):
        client, _ = self.attach()
        client.close()
        self.supervisor.receive()
        self.supervisor.tick()
        stale = self.store.directory / ("b" * 32 + ".json")
        stale.write_text("{}")
        os.utime(stale, (0, 0))
        active = self.store.directory / ("c" * 32 + ".sock")
        active.write_text("active-marker")
        self.store.rotate(exclude=self.run_id)
        self.assertFalse(stale.exists())
        self.assertTrue(active.exists())
        self.assertIsNotNone(self.store.read(self.run_id))

    def test_archive_requires_terminal_status_and_complete_original_options(self):
        client, _ = self.attach()
        client.close()
        self.supervisor.receive()
        self.supervisor.tick()
        path = self.store.directory / (self.run_id + ".json")
        original = path.read_text()
        for mutation in ("runState", "gestures", "options", "dryRun"):
            with self.subTest(mutation=mutation):
                record = json.loads(original)
                if mutation == "runState":
                    record["ready"]["status"]["runState"] = "RUNNING"
                elif mutation == "gestures":
                    record["ready"]["options"]["gesturesEnabled"] = False
                elif mutation == "options":
                    record["ready"]["options"] = []
                else:
                    record["ready"]["dryRun"] = "true"
                path.write_text(json.dumps(record))
                with self.assertRaises(sessions.SessionUnavailable):
                    self.store.read(self.run_id)


@unittest.skipUnless(os.name == "posix", "Offline subprocesses require Linux process/session semantics")
class PersistentProcessTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="follow-persistent-")
        self.directory = Path(self.temporary.name)
        self.environment = dict(os.environ, APP_FOLLOW_SESSION_TEST_DIR=str(self.directory / "sessions"),
                                APP_FOLLOW_DIAGNOSTIC_TEST_DIR=str(self.directory / "diagnostics"))
        self.children = []
        self.run_ids = []

    def tearDown(self):
        for process in self.children:
            if process.poll() is None:
                with contextlib.suppress(Exception):
                    process.stdin.close()
                with contextlib.suppress(subprocess.TimeoutExpired):
                    process.wait(1.)
        # Cleanup only the explicitly dry-run sessions created by this fixture.
        for run_id in self.run_ids:
            try:
                process, ready = self.attach(run_id)
                if ready.get("type") == "ready":
                    self.close_session(process, ready)
            except Exception:
                pass
        for process in self.children:
            if process.poll() is None:
                process.terminate()
                process.wait(3.)
            for stream in (process.stdin, process.stdout, process.stderr):
                with contextlib.suppress(Exception):
                    stream.close()
        self.temporary.cleanup()

    def spawn(self, args):
        process = subprocess.Popen([sys.executable, "-u", str(SCRIPT), *args], env=self.environment,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
        self.children.append(process)
        return process

    def read(self, process, timeout=4.):
        deadline = time.monotonic() + timeout
        data = bytearray()
        while time.monotonic() < deadline:
            readable, _, _ = select.select([process.stdout], [], [], max(0., deadline - time.monotonic()))
            if not readable:
                break
            chunk = os.read(process.stdout.fileno(), 1)
            if not chunk:
                self.fail("Dry-run proxy ended before response: " + process.stderr.read().decode()[:800])
            data.extend(chunk)
            if chunk == b"\n":
                return json.loads(data)
        self.fail("Dry-run follow response timed out")

    def launch(self):
        process = self.spawn(["--launch", "--offline-until-gesture", "--gestures", "--dry-run"])
        ready = self.read(process, 7.)
        self.assertEqual(ready.get("type"), "ready", ready)
        self.assertTrue(ready["dryRun"])
        self.run_ids.append(ready["runId"])
        return process, ready

    def attach(self, run_id):
        process = self.spawn(["--attach-run", run_id, "--dry-run"])
        return process, self.read(process)

    def request(self, process, previous, command, seq=None):
        message = dict(cmd=command, runId=previous["runId"], token=previous["token"],
                       seq=seq if seq is not None else previous.get("seq", 0) + 1)
        process.stdin.write(sessions.encode(message))
        process.stdin.flush()
        return self.read(process)

    def running(self, process, ready):
        reply = self.request(process, ready, "start")
        deadline = time.monotonic() + 3.
        while reply["status"]["runState"] != "RUNNING" and time.monotonic() < deadline:
            time.sleep(.1)
            reply = self.request(process, reply, "heartbeat")
        self.assertEqual(reply["status"]["runState"], "RUNNING")
        return reply

    def close_session(self, process, previous):
        reply = self.request(process, previous, "stop")
        deadline = time.monotonic() + 4.
        while not reply.get("terminal") and time.monotonic() < deadline:
            time.sleep(.1)
            reply = self.request(process, reply, "heartbeat")
        self.assertTrue(reply["terminal"], reply)
        process.stdin.close()
        process.wait(3.)
        return reply

    def worker_pids(self, run_id):
        result = set()
        for path in (self.directory / "diagnostics").glob("*.jsonl"):
            for line in path.read_text().splitlines():
                event = json.loads(line)
                if event.get("runId") == run_id and event.get("event") == "worker_started":
                    fields = event.get("fields", event)
                    if fields.get("workerPid"):
                        result.add(fields["workerPid"])
        return result

    def test_ssh_eof_keeps_worker_beyond_old_lease_and_reconnects_same_run_then_archives(self):
        process, ready = self.launch()
        self.running(process, ready)
        process.stdin.close()
        process.wait(2.)
        time.sleep(2.3)
        resumed, ready2 = self.attach(ready["runId"])
        self.assertEqual(ready2["runId"], ready["runId"])
        self.assertTrue(ready2["resumed"])
        self.assertEqual(ready2["status"]["runState"], "RUNNING")
        self.assertNotEqual(ready2["token"], ready["token"])
        ack = self.request(resumed, ready2, "heartbeat")
        self.assertEqual(ack["seq"], 1)
        self.close_session(resumed, ack)
        archived, ready3 = self.attach(ready["runId"])
        self.assertEqual(ready3["status"]["runState"], "STOPPED")
        terminal = self.request(archived, ready3, "start")
        self.assertTrue(terminal["terminal"])
        archived.wait(2.)
        pids = self.worker_pids(ready["runId"])
        self.assertEqual(len(pids), 1, pids)

    def test_heartbeat_timeout_detaches_even_when_ssh_process_remains_alive(self):
        process, ready = self.launch()
        self.running(process, ready)
        time.sleep(2.3)
        process.wait(2.)
        resumed, ready2 = self.attach(ready["runId"])
        self.assertEqual(ready2["status"]["runState"], "RUNNING")
        self.close_session(resumed, ready2)

    def test_simultaneous_attach_is_busy_then_original_can_stop(self):
        process, ready = self.launch()
        ack = self.running(process, ready)
        second, error = self.attach(ready["runId"])
        self.assertEqual(error.get("code"), "session_busy")
        second.wait(2.)
        self.close_session(process, ack)

    def test_sshd_proxy_termination_signal_detaches_without_signalling_worker(self):
        process, ready = self.launch()
        self.running(process, ready)
        process.terminate()
        process.wait(2.)
        resumed, ready2 = self.attach(ready["runId"])
        self.assertEqual(ready2["status"]["runState"], "RUNNING")
        self.close_session(resumed, ready2)

    def test_bad_protocol_stops_worker_not_a_detached_continuation(self):
        process, ready = self.launch()
        ack = self.running(process, ready)
        error = self.request(process, ack, "heartbeat", seq=99)
        self.assertEqual(error["code"], "session_invalid")
        process.wait(2.)
        time.sleep(.3)
        resumed, ready2 = self.attach(ready["runId"])
        terminal = self.request(resumed, ready2, "heartbeat")
        while not terminal.get("terminal"):
            time.sleep(.1)
            terminal = self.request(resumed, terminal, "heartbeat")
        self.assertEqual(terminal["status"]["runState"], "STOPPED")

    def test_no_gestures_and_unknown_run_never_launch_worker(self):
        process = self.spawn(["--launch", "--offline-until-gesture", "--dry-run"])
        _, stderr = process.communicate(timeout=3.)
        self.assertNotEqual(process.returncode, 0)
        self.assertIn(b"requires", stderr)
        missing, error = self.attach("e" * 32)
        self.assertEqual(error["code"], "session_not_found")
        missing.wait(2.)


if __name__ == "__main__":
    unittest.main()
