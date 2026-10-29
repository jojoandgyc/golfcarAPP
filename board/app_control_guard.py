"""Read-only control-idle observations and cooperative exclusive locks.

No networking, motor protocol, process termination or lock-file creation API.
Used by the independent motor configuration bridge; existing workers are unchanged.
"""
from contextlib import contextmanager
import os
from pathlib import Path
import stat
import subprocess
import json

BRIDGE = '/home/topeet/Desktop/rk_car_runtime_module/app_follow_bridge.py'
LOCK_PATHS = (Path('/home/topeet/lianzhan/.app_joystick.lock'),
              Path('/home/topeet/Desktop/rk_car_runtime_module/.follow_session.lock'))
CONTROL_PROGRAMS = frozenset({
    'app_follow_bridge.py', 'app_follow_worker.py', 'app_joystick_bridge.py',
    # Same-kind configuration sessions are excluded by both flocks. Listing the
    # script here would mistake our own sudo parent/monitor argv for another owner.
    'keyboard_drive.py', 'drive_server.py', 'motor_gui.py',
    'control_motor.py', 'request_0513_modular.py', 'request_0428_modular.py',
    'request_continuous_follow.py', 'icm20600.py', 'motor_direction_control.py',
    'motor_direction_controlauto.py', 'motor_direction_controlautobrake.py',
    'motor_direction_control_new.py',
})


class GuardError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def run_command(arguments, timeout):
    try:
        return subprocess.run(arguments, stdin=subprocess.DEVNULL, capture_output=True, text=True,
                              timeout=timeout, close_fds=True, env=dict(os.environ, LC_ALL='C', LANG='C'))
    except (OSError, subprocess.TimeoutExpired):
        raise GuardError('control_observation_unavailable') from None


class ControlGuard:
    def __init__(self, runner=run_command, proc_root=Path('/proc'), lock_paths=LOCK_PATHS,
                 serial_ports=(Path('/dev/ttyS0'), Path('/dev/ttyS3'))):
        self.run, self.proc_root, self.lock_paths, self.serial_ports = runner, proc_root, lock_paths, serial_ports

    @staticmethod
    def is_root():
        return os.name == 'posix' and os.geteuid() == 0

    def inspect_bridge(self):
        result = self.run(['/usr/bin/python3', '-B', '-u', BRIDGE, '--inspect'], 6)
        if result.returncode or len(result.stdout) > 16384:
            raise GuardError('control_inspection_failed')
        try:
            reply = json.loads(result.stdout)
        except ValueError:
            raise GuardError('control_inspection_failed') from None
        if not isinstance(reply, dict) or reply.get('type') != 'availability':
            raise GuardError('control_inspection_failed')
        if reply.get('controlAvailable') is not True:
            raise GuardError('control_busy_or_unconfirmed')

    def verify_control_processes(self):
        try:
            entries = list(self.proc_root.iterdir())
        except OSError:
            raise GuardError('process_check_unavailable') from None
        for entry in entries:
            if not entry.name.isdigit() or int(entry.name) == os.getpid():
                continue
            try:
                arguments = (entry / 'cmdline').read_bytes().split(b'\0')
            except FileNotFoundError:
                continue
            except OSError:
                raise GuardError('process_check_unavailable') from None
            if {os.path.basename(os.fsdecode(argument)) for argument in arguments} & CONTROL_PROGRAMS:
                raise GuardError('control_process_running')

    def verify_hardware_idle(self):
        self.verify_control_processes()
        for index, port in enumerate(self.serial_ports):
            if index and not port.exists():
                continue
            if not port.is_char_device():
                raise GuardError('motor_serial_check_failed')
            result = self.run(['/usr/bin/fuser', port.as_posix()], 3)
            if result.returncode == 0 or result.stdout.strip():
                raise GuardError('motor_serial_in_use')
            if result.returncode != 1 or result.stderr.strip():
                raise GuardError('motor_serial_check_failed')

    def verify_open_serial_owner(self):
        """Recheck after exclusive-open, before termios changes or any serial bytes."""
        self.verify_control_processes()
        port = self.serial_ports[0].as_posix()
        result = self.run(['/usr/bin/fuser', port], 3)
        tokens = result.stdout.split()
        if (result.returncode != 0 or not tokens or any(not token.isdigit() for token in tokens)
                or result.stderr.strip() not in ('', port + ':')):
            raise GuardError('serial_owner_unconfirmed')
        owners = {int(token) for token in tokens}
        if owners != {os.getpid()}:
            raise GuardError('motor_serial_in_use')

    @contextmanager
    def idle_guard(self):
        if not self.is_root():
            raise GuardError('root_required_for_complete_idle_probe')
        try:
            if any(not stat.S_ISREG(path.lstat().st_mode) for path in self.lock_paths):
                raise GuardError('invalid_control_lock')
        except OSError:
            raise GuardError('control_lock_busy_or_unavailable') from None
        # Existing --inspect uses O_CREAT internally, so paths must already exist.
        self.inspect_bridge()
        import fcntl
        descriptors = []
        try:
            try:
                for path in self.lock_paths:
                    fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC)
                    descriptors.append(fd)
                    if not stat.S_ISREG(os.fstat(fd).st_mode):
                        raise GuardError('invalid_control_lock')
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise GuardError('control_lock_busy_or_unavailable') from None
            self.verify_hardware_idle()
            yield
        finally:
            for fd in reversed(descriptors):
                os.close(fd)
