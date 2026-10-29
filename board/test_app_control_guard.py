"""Pure process fixtures and temporary-file locks; never uses a real motor port."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from app_control_guard import ControlGuard, GuardError


class ControlGuardTests(unittest.TestCase):
    def test_missing_lock_files_are_never_created_or_inspected(self):
        with tempfile.TemporaryDirectory() as directory:
            locks = (Path(directory)/'motor.lock', Path(directory)/'follow.lock')
            calls = []
            guard = ControlGuard(runner=lambda *args: calls.append(args), lock_paths=locks)
            with patch.object(guard, 'is_root', return_value=True):
                with self.assertRaisesRegex(GuardError, 'control_lock_busy_or_unavailable'):
                    with guard.idle_guard():
                        self.fail('Missing locks are not idle')
            self.assertEqual(calls, [])
            self.assertTrue(all(not path.exists() for path in locks))

    def test_inspect_requires_explicit_availability_true(self):
        for output in ['{}', '{"type":"availability","controlAvailable":"true"}',
                       '{"type":"availability","controlAvailable":false}',
                       'noise\n{"type":"availability","controlAvailable":true}']:
            guard = ControlGuard(runner=lambda args, timeout: subprocess.CompletedProcess(args, 0, output, ''))
            with self.assertRaises(GuardError):
                guard.inspect_bridge()

    def test_actual_worker_process_refuses_even_without_lock_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            (proc/'1234567').mkdir()
            (proc/'1234567/cmdline').write_bytes(b'python3\0/home/topeet/app_follow_worker.py\0--execute\0')
            calls = []
            guard = ControlGuard(proc_root=proc, runner=lambda *args: calls.append(args))
            with self.assertRaisesRegex(GuardError, 'control_process_running'):
                guard.verify_hardware_idle()
            self.assertEqual(calls, [])

    def test_fuser_failures_and_secondary_port_owners_refuse(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(Path, 'is_char_device', return_value=True), \
             patch.object(Path, 'exists', return_value=True):
            for returncode, output, error in [(1, '', 'denied'), (2, '', ''), (0, '123', '')]:
                calls = []
                def run(args, timeout):
                    calls.append(args)
                    if args[-1].endswith('S0'):
                        return subprocess.CompletedProcess(args, 1, '', '')
                    return subprocess.CompletedProcess(args, returncode, output, error)
                guard = ControlGuard(proc_root=Path(directory), runner=run)
                with self.assertRaises(GuardError):
                    guard.verify_hardware_idle()
                self.assertEqual([args[-1] for args in calls], ['/dev/ttyS0', '/dev/ttyS3'])

    def test_unprivileged_probe_never_claims_complete_idle_observation(self):
        guard = ControlGuard()
        with patch.object(guard, 'is_root', return_value=False):
            with self.assertRaisesRegex(GuardError, 'root_required_for_complete_idle_probe'):
                with guard.idle_guard():
                    self.fail('Unprivileged observations can omit owners')

    def test_own_sudo_launcher_is_not_a_motor_control_program(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            (proc/'1234567').mkdir()
            (proc/'1234567/cmdline').write_bytes(b'sudo\0-n\0python3\0/home/topeet/lianzhan/scripts/app_motor_config_bridge.py\0--request\0')
            ControlGuard(proc_root=proc).verify_control_processes()

    def test_post_open_race_accepts_only_this_process_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            for owners, stderr, expected in [(str(os.getpid()), '/dev/ttyS0:', None),
                                              (str(os.getpid())+' 1234567', '/dev/ttyS0:', 'motor_serial_in_use'),
                                              ('', '', 'serial_owner_unconfirmed'),
                                              (str(os.getpid()), 'permission denied', 'serial_owner_unconfirmed')]:
                guard = ControlGuard(proc_root=Path(directory), runner=lambda args, timeout:
                                     subprocess.CompletedProcess(args, 0, owners, stderr))
                if expected:
                    with self.assertRaisesRegex(GuardError, expected):
                        guard.verify_open_serial_owner()
                else:
                    guard.verify_open_serial_owner()

    @unittest.skipUnless(os.name == 'posix', 'POSIX flock/NOFOLLOW')
    def test_both_locks_held_for_whole_session_and_released_on_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            locks = (root/'motor.lock', root/'follow.lock')
            for path in locks:
                path.touch()
            proc = root/'proc'
            proc.mkdir()
            def run(args, timeout):
                if '--inspect' in args:
                    return subprocess.CompletedProcess(args, 0, json.dumps(dict(type='availability', controlAvailable=True)), '')
                return subprocess.CompletedProcess(args, 1, '', '')
            first = ControlGuard(runner=run, proc_root=proc, lock_paths=locks, serial_ports=(root/'ttyS0',))
            second = ControlGuard(runner=run, proc_root=proc, lock_paths=locks, serial_ports=(root/'ttyS0',))
            with patch.object(ControlGuard, 'is_root', return_value=True), patch.object(Path, 'is_char_device', return_value=True):
                with self.assertRaisesRegex(RuntimeError, 'test exit'):
                    with first.idle_guard():
                        with self.assertRaisesRegex(GuardError, 'control_lock_busy_or_unavailable'):
                            with second.idle_guard():
                                self.fail('Concurrent owner entered')
                        raise RuntimeError('test exit')
                with second.idle_guard():
                    pass  # Both descriptors must have been released after exception.

    @unittest.skipUnless(os.name == 'posix', 'POSIX symlink support')
    def test_symlink_lock_refused_before_inspect(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)/'existing'
            target.touch()
            link = Path(directory)/'link'
            link.symlink_to(target)
            calls = []
            guard = ControlGuard(lock_paths=(link,), runner=lambda *args: calls.append(args))
            with patch.object(guard, 'is_root', return_value=True):
                with self.assertRaisesRegex(GuardError, 'invalid_control_lock'):
                    with guard.idle_guard():
                        self.fail('Symlink lock entered')
            self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
