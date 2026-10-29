#!/usr/bin/env python3
"""Opt-in legacy Wi-Fi client of an Android Wi-Fi Direct GO. Default: read-only check.

Never starts/stops car control, scans for peers, or manages wpa_supplicant itself.
Only --join may create/activate its own NetworkManager profile. Credentials are
accepted on stdin, never as arguments. This helper is not automatically detached.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid


INTERFACE = "wlan0"
NMCLI = "/usr/bin/nmcli"
BRIDGE = "/home/topeet/Desktop/rk_car_runtime_module/app_follow_bridge.py"
LOCK_PATHS = (Path("/home/topeet/lianzhan/.app_joystick.lock"),
              Path("/home/topeet/Desktop/rk_car_runtime_module/.follow_session.lock"))
RESULT_PATH = Path("/home/topeet/.local/state/followingcar-wifi-direct/last-result.json")
CONTROL_PROGRAMS = frozenset({
    "app_follow_bridge.py", "app_follow_worker.py", "app_joystick_bridge.py",
    "keyboard_drive.py", "drive_server.py", "motor_gui.py", "control_motor.py",
    "request_0513_modular.py", "request_0428_modular.py", "request_continuous_follow.py",
    "icm20600.py", "motor_direction_control.py", "motor_direction_controlauto.py",
    "motor_direction_controlautobrake.py", "motor_direction_control_new.py",
})


class JoinError(RuntimeError):
    def __init__(self, code):
        self.code = code  # Fixed codes only. Do not include command output or input.
        super().__init__(code)


@dataclass(frozen=True)
class Credentials:
    ssid: str
    psk: str = field(repr=False)


class JoinSignals:
    def __init__(self):
        self.caller_disconnected = False
        self.cancel_requested = False

    def received(self, number, _frame=None):
        # Never perform I/O or unwind a NetworkManager transaction in a signal handler.
        if number == getattr(signal, "SIGHUP", None):
            self.caller_disconnected = True
        else:
            self.cancel_requested = True

    def check_cancelled(self):
        if self.cancel_requested:
            raise JoinError("join_cancelled")


@contextmanager
def join_signal_monitor():
    state, previous = JoinSignals(), {}
    try:
        for name in ("SIGHUP", "SIGTERM", "SIGINT"):
            if hasattr(signal, name):
                number = getattr(signal, name)
                previous[number] = signal.signal(number, state.received)
        yield state
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)


def read_credentials(stream):
    payload = stream.read(2049)
    if len(payload) > 2048:
        raise JoinError("input_too_large")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise JoinError("duplicate_input_field")
            result[key] = value
        return result
    try:
        data = json.loads(payload, object_pairs_hook=unique)
    except (ValueError, UnicodeError):
        raise JoinError("invalid_json") from None
    if not isinstance(data, dict) or set(data) != {"ssid", "psk"}:
        raise JoinError("expected_ssid_and_psk_only")
    ssid, psk = data["ssid"], data["psk"]
    if (type(ssid) is not str or not re.fullmatch(r"DIRECT-[A-Za-z0-9]{2}[^\x00-\x1f\x7f]*", ssid)
            or len(ssid.encode("utf-8")) > 32):
        raise JoinError("invalid_direct_ssid")
    if type(psk) is not str or not (re.fullmatch(r"[\x20-\x7e]{8,63}", psk)
                                   or re.fullmatch(r"[0-9a-fA-F]{64}", psk)):
        raise JoinError("invalid_wpa2_psk")
    return Credentials(ssid, psk)


def public_result(value, secret=None):
    if isinstance(value, dict):
        return {key: public_result(item, secret) for key, item in value.items()
                if key.lower() not in {"psk", "password", "passphrase"}}
    if isinstance(value, list):
        return [public_result(item, secret) for item in value]
    if isinstance(value, str):
        return value.replace(secret, "[REDACTED]") if secret else value
    return value


def run_command(arguments, timeout=6):
    # No shell, no inherited stdin, and no raw stderr/argv in errors or records.
    try:
        return subprocess.run(arguments, stdin=subprocess.DEVNULL, capture_output=True,
                              text=True, timeout=timeout, close_fds=True,
                              env=dict(os.environ, LC_ALL="C", LANG="C"))
    except subprocess.TimeoutExpired:
        raise JoinError("command_timeout") from None
    except OSError:
        raise JoinError("command_unavailable") from None


def running_control_processes(proc_root=Path("/proc")):
    found = []
    try:
        entries = list(proc_root.iterdir())
    except OSError:
        raise JoinError("process_check_unavailable") from None
    for entry in entries:
        if not entry.name.isdigit() or int(entry.name) == os.getpid():
            continue
        try:
            arguments = (entry / "cmdline").read_bytes().split(b"\0")
        except FileNotFoundError:
            continue  # The process exited during the read.
        except OSError:
            raise JoinError("process_check_unavailable") from None
        names = {os.path.basename(os.fsdecode(argument)) for argument in arguments}
        if names & CONTROL_PROGRAMS:
            found.append(int(entry.name))  # Never return complete argv.
    return found


class BoardSystem:
    def __init__(self, runner=run_command, proc_root=Path("/proc"), lock_paths=LOCK_PATHS,
                 sys_root=Path("/sys"), secret_root=Path("/run"),
                 profile_root=Path("/run/NetworkManager/system-connections")):
        self.run, self.proc_root, self.lock_paths = runner, proc_root, lock_paths
        self.sys_root, self.secret_root = sys_root, secret_root
        self.profile_root, self.created_profile_files = Path(profile_root), {}

    @staticmethod
    def is_root():
        return os.name == "posix" and os.geteuid() == 0

    def checked(self, arguments, timeout=6):
        result = self.run(arguments, timeout)
        if result.returncode:
            raise JoinError("networkmanager_command_failed")
        return result.stdout

    def network(self):
        devices = self.checked([NMCLI, "-t", "--escape", "no", "-f", "DEVICE,TYPE", "device", "status"])
        wireless = [line.split(":", 1)[0] for line in devices.splitlines() if line.endswith(":wifi")]
        if wireless != [INTERFACE]:
            raise JoinError("expected_only_wlan0_wifi_interface")
        output = self.checked([NMCLI, "-t", "--escape", "no", "-f",
            "GENERAL.DEVICE,GENERAL.TYPE,GENERAL.STATE,GENERAL.NM-MANAGED,GENERAL.CON-UUID,"
            "GENERAL.CONNECTION,IP4.ADDRESS,WIFI-PROPERTIES.2GHZ,WIFI-PROPERTIES.5GHZ",
            "device", "show", INTERFACE])
        fields = dict(line.split(":", 1) for line in output.splitlines() if ":" in line)
        if fields.get("GENERAL.DEVICE") != INTERFACE or fields.get("GENERAL.TYPE") != "wifi":
            raise JoinError("unexpected_wifi_device")
        state = fields.get("GENERAL.STATE", "").split(" ", 1)[0]
        if not state.isdigit() or fields.get("GENERAL.NM-MANAGED") != "yes":
            raise JoinError("wifi_not_managed_or_state_unknown")
        current_uuid = fields.get("GENERAL.CON-UUID", "--")
        ssid = None
        if current_uuid != "--":
            try:
                current_uuid = str(uuid.UUID(current_uuid))
            except ValueError:
                raise JoinError("invalid_current_profile_uuid") from None
            ssid = self.checked([NMCLI, "-g", "802-11-wireless.ssid", "connection", "show",
                                 "uuid", current_uuid]).strip()
        addresses = []
        for key, value in fields.items():
            if key.startswith("IP4.ADDRESS"):
                try:
                    addresses.append(str(ipaddress.IPv4Interface(value).ip))
                except ValueError:
                    raise JoinError("invalid_ip_observation") from None
        return dict(interface=INTERFACE, state=int(state), managed=True,
                    profileUuid=None if current_uuid == "--" else current_uuid,
                    profileName=fields.get("GENERAL.CONNECTION"), ssid=ssid, addresses=addresses,
                    supports2GHz=fields.get("WIFI-PROPERTIES.2GHZ") == "yes",
                    supports5GHz=fields.get("WIFI-PROPERTIES.5GHZ") == "yes")

    def capability(self):
        driver_path = self.sys_root / "class/net/wlan0/device/driver"
        driver = driver_path.resolve().name if driver_path.is_symlink() else None
        count = None
        if driver == "rtl8723du":
            try:
                config = (self.proc_root / "net/rtl8723du/drv_cfg").read_text()
                match = re.search(r"CONFIG_IFACE_NUMBER\s*=\s*(\d+)", config)
                count = int(match[1]) if match else None
            except OSError:
                pass
        return dict(driver=driver, driverInterfaceLimit=count, mode="legacy_wifi_client_of_android_go",
                    parallelStaP2p=False if count == 1 else None,
                    whyCannotParallel="driver_single_interface" if count == 1 else "concurrency_not_verified",
                    changesExistingSta=True, requiredGoBand="2.4GHz", nativeP2pJoin=False)

    def inspect_bridge(self):
        result = self.run(["/usr/bin/python3", "-B", "-u", BRIDGE, "--inspect"], 6)
        if result.returncode or len(result.stdout) > 16_384:
            raise JoinError("control_inspection_failed")
        try:
            reply = json.loads(result.stdout)
        except ValueError:
            raise JoinError("control_inspection_failed") from None
        if not isinstance(reply, dict) or reply.get("type") != "availability":
            raise JoinError("control_inspection_failed")
        if reply.get("controlAvailable") is not True:
            raise JoinError("control_busy_or_unconfirmed")

    @contextmanager
    def idle_guard(self):
        # Inspect first: calling it while we own the same locks would report our own lock.
        # The deployed inspect command uses O_CREAT, so refuse missing paths beforehand.
        try:
            if any(not stat.S_ISREG(path.lstat().st_mode) for path in self.lock_paths):
                raise JoinError("invalid_control_lock")
        except OSError:
            raise JoinError("control_lock_busy_or_unavailable") from None
        self.inspect_bridge()
        if os.name != "posix":
            raise JoinError("linux_required")
        import fcntl
        descriptors = []
        try:
            try:
                for path in self.lock_paths:
                    # Missing/symlink/non-regular lock is unknown, never assume idle/create it.
                    fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW)
                    descriptors.append(fd)
                    if not stat.S_ISREG(os.fstat(fd).st_mode):
                        raise JoinError("invalid_control_lock")
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise JoinError("control_lock_busy_or_unavailable") from None
            self.verify_hardware_idle()
            yield
        finally:
            for fd in reversed(descriptors):
                os.close(fd)

    def verify_hardware_idle(self):
        if running_control_processes(self.proc_root):
            raise JoinError("control_process_running")
        if not Path("/dev/ttyS0").is_char_device():
            raise JoinError("motor_serial_check_failed")
        ports = [Path("/dev/ttyS0")]
        if Path("/dev/ttyS3").exists():
            ports.append(Path("/dev/ttyS3"))
        for port in ports:
            if not port.is_char_device():
                raise JoinError("motor_serial_check_failed")
            result = self.run(["/usr/bin/fuser", port.as_posix()], 3)
            if result.returncode == 0 or result.stdout.strip():
                raise JoinError("motor_serial_in_use")
            if result.returncode != 1 or result.stderr.strip():
                raise JoinError("motor_serial_check_failed")

    def profile_uuids(self):
        output = self.checked([NMCLI, "-t", "-f", "UUID", "connection", "show"])
        try:
            return {str(uuid.UUID(line)) for line in output.splitlines() if line}
        except ValueError:
            raise JoinError("profile_list_unconfirmed") from None

    def active_uuids(self):
        output = self.checked([NMCLI, "-t", "-f", "UUID", "connection", "show", "--active"])
        try:
            return {str(uuid.UUID(line)) for line in output.splitlines() if line}
        except ValueError:
            raise JoinError("active_profile_list_unconfirmed") from None

    def owns_profile(self, profile_uuid, ssid):
        result = self.run([NMCLI, "-t", "--escape", "no", "-f",
            "connection.id,connection.uuid,connection.interface-name,802-11-wireless.ssid",
            "connection", "show", "uuid", profile_uuid], 6)
        if result.returncode == 10:
            return False
        if result.returncode:
            raise JoinError("profile_ownership_unconfirmed")
        fields = dict(line.split(":", 1) for line in result.stdout.splitlines() if ":" in line)
        return fields == {"connection.id": "followingcar-direct-" + profile_uuid,
                          "connection.uuid": profile_uuid, "connection.interface-name": INTERFACE,
                          "802-11-wireless.ssid": ssid}

    def add_profile(self, profile_uuid, ssid):
        # NM 1.36's connection add generates an immutable UUID before processing
        # properties. Load a private runtime keyfile to preserve the chosen identity.
        # It contains no PSK and cannot autoconnect; reboot removes the /run profile.
        if str(uuid.UUID(profile_uuid)) != profile_uuid or profile_uuid in self.profile_uuids():
            raise JoinError("profile_uuid_collision")
        if (not self.profile_root.is_dir()
                or any(path.is_symlink() for path in (self.profile_root, *self.profile_root.parents))):
            raise JoinError("unsafe_profile_directory")
        path = self.profile_root / ("followingcar-direct-" + profile_uuid + ".nmconnection")
        # A byte array prevents SSID characters from becoming keyfile syntax.
        encoded_ssid = "".join(str(value) + ";" for value in ssid.encode("utf-8"))
        content = ("[connection]\nid=followingcar-direct-" + profile_uuid + "\nuuid=" + profile_uuid
                   + "\ntype=wifi\ninterface-name=" + INTERFACE + "\nautoconnect=false\n"
                   "\n[wifi]\nmode=infrastructure\nband=bg\nssid=" + encoded_ssid
                   + "\n\n[wifi-security]\nkey-mgmt=wpa-psk\nproto=rsn;\npsk-flags=2\n"
                   "\n[ipv4]\nmethod=auto\nnever-default=true\n\n[ipv6]\nmethod=disabled\n")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        info = os.fstat(fd)
        self.created_profile_files[profile_uuid] = (path, info.st_dev, info.st_ino)
        try:
            with os.fdopen(fd, "w", encoding="ascii") as stream:
                fd = None
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            self.checked([NMCLI, "--wait", "10", "connection", "load", str(path)], 15)
            if not self.owns_profile(profile_uuid, ssid):
                raise JoinError("loaded_profile_identity_unconfirmed")
        except BaseException:
            self.remove_created_profile_file(profile_uuid)
            raise
        finally:
            if fd is not None:
                os.close(fd)

    def remove_created_profile_file(self, profile_uuid):
        created = self.created_profile_files.get(profile_uuid)
        if created is None:
            return
        path, device, inode = created
        try:
            current = path.lstat()
        except FileNotFoundError:
            self.created_profile_files.pop(profile_uuid, None)
            return
        if not stat.S_ISREG(current.st_mode) or (current.st_dev, current.st_ino) != (device, inode):
            raise JoinError("profile_file_ownership_unconfirmed")
        path.unlink()
        self.created_profile_files.pop(profile_uuid, None)

    @contextmanager
    def password_file(self, psk):
        fd, path = tempfile.mkstemp(prefix="followingcar-direct-", suffix=".secret", dir=self.secret_root)
        try:
            os.fchmod(fd, 0o600) if hasattr(os, "fchmod") else os.chmod(path, 0o600)
            with os.fdopen(fd, "w", encoding="ascii") as stream:
                fd = None
                stream.write("802-11-wireless-security.psk:" + psk + "\n")
                stream.flush()
            yield path
        finally:
            if fd is not None:
                os.close(fd)
            try:
                os.unlink(path)
            except FileNotFoundError:
                pass

    def activate(self, profile_uuid, secret_path):
        self.checked([NMCLI, "--wait", "45", "connection", "up", "uuid", profile_uuid,
                      "ifname", INTERFACE, "passwd-file", str(secret_path)], 50)

    def delete_own_profile(self, profile_uuid):
        self.checked([NMCLI, "--wait", "10", "connection", "delete", "uuid", profile_uuid], 15)
        self.remove_created_profile_file(profile_uuid)

    def cleanup_profile(self, profile_uuid, ssid):
        # An add timeout/failure does not prove we created a profile. Verify ownership,
        # including name/interface/SSID, before touching that UUID even after add succeeds.
        if not self.owns_profile(profile_uuid, ssid):
            return "not_owned_or_absent"
        if profile_uuid in self.active_uuids():
            self.checked([NMCLI, "--wait", "10", "connection", "down", "uuid", profile_uuid], 15)
        self.delete_own_profile(profile_uuid)
        deadline = time.monotonic() + 12
        while True:
            network = self.network()
            if (profile_uuid not in self.active_uuids() and profile_uuid not in self.profile_uuids()
                    and network["profileUuid"] != profile_uuid and network["state"] in (20, 30, 100, 120)):
                return "deleted_and_transition_settled"
            if time.monotonic() >= deadline:
                raise JoinError("network_transition_unconfirmed")
            time.sleep(.2)


class ResultStore:
    def __init__(self, path=RESULT_PATH, owner="topeet"):
        self.path, self.owner = Path(path), owner

    def write(self, value):
        # Only --join calls this. --check/--status create no files.
        for parent in (self.path.parent, *self.path.parents):
            if parent.is_symlink():
                raise JoinError("unsafe_result_path")
        self.path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        self.path.parent.chmod(0o700)
        uid = gid = None
        if self.owner is not None and os.name == "posix":
            import pwd
            account = pwd.getpwnam(self.owner)
            uid, gid = account.pw_uid, account.pw_gid
            os.chown(self.path.parent, uid, gid)
        fd, temporary = tempfile.mkstemp(prefix=".result-", dir=self.path.parent)
        try:
            os.fchmod(fd, 0o600) if hasattr(os, "fchmod") else os.chmod(temporary, 0o600)
            if uid is not None:
                os.fchown(fd, uid, gid)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                fd = None
                json.dump(value, stream, ensure_ascii=True)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if fd is not None:
                os.close(fd)
            if os.path.exists(temporary):
                os.unlink(temporary)


def check(system):
    result = dict(schemaVersion=1, action="check", ok=False, capability=system.capability(),
                  resultFile=str(RESULT_PATH), joinRequiresRoot=True, controlAvailable=False)
    try:
        result["currentNetwork"] = system.network()
        if not system.is_root():
            # fuser and /proc observations can be incomplete for another user's owner.
            raise JoinError("root_required_for_complete_idle_probe")
        with system.idle_guard():
            result["controlAvailable"] = True
        result["ok"] = True
    except Exception as error:
        result["error"] = error.code if isinstance(error, JoinError) else "check_unavailable"
    return result


def join_group(credentials, system, store, *, signal_monitor=None,
               method="legacy_wifi_client_of_android_go"):
    profile_uuid = str(uuid.uuid4())
    result = dict(schemaVersion=1, action="join", ok=False, phase="preflight",
                  startedAt=datetime.now(timezone.utc).isoformat(), profileUuid=profile_uuid,
                  requestedSsid=credentials.ssid, method=method,
                  interface=INTERFACE, autoRestore=False)
    signals = None
    def save():
        if signals is not None:
            result["callerDisconnected"] = signals.caller_disconnected
        store.write(public_result(result, credentials.psk))
    try:
        if not system.is_root():
            raise JoinError("join_requires_root")
        with (signal_monitor or join_signal_monitor)() as signals, system.idle_guard():
            current = system.network()
            if (current["state"] not in (30, 100) or not current["supports2GHz"]
                    or current["state"] == 100 and not current["profileUuid"]):
                raise JoinError("wifi_not_ready_for_switch")
            result["previousConnectionUuid"] = current["profileUuid"]
            if profile_uuid in system.profile_uuids():
                raise JoinError("profile_uuid_collision")
            save()  # Cannot record the operation: do not mutate networking.
            attempted = False
            activation_confirmed = False
            try:
                with system.password_file(credentials.psk) as secret_path:
                    signals.check_cancelled()
                    attempted = True
                    result["phase"] = "creating_profile"
                    system.add_profile(profile_uuid, credentials.ssid)
                    result["profileCreationConfirmed"] = True
                    signals.check_cancelled()
                    result["phase"] = "activating"
                    save()  # Survives SSH loss; not evidence that activation completed.
                    system.activate(profile_uuid, secret_path)
                    signals.check_cancelled()
                    network = system.network()
                    if network["state"] != 100 or network["profileUuid"] != profile_uuid or not network["addresses"]:
                        raise JoinError("activation_not_confirmed")
                    activation_confirmed = True
                    result.update(ok=True, phase="connected", currentNetwork=network,
                                  finishedAt=datetime.now(timezone.utc).isoformat())
            except BaseException:
                if activation_confirmed:
                    # A secret-file cleanup problem must not tear down a verified link.
                    result["secretCleanup"] = "unconfirmed_manual_cleanup_required"
                elif attempted:
                    try:
                        result["ownProfileCleanup"] = system.cleanup_profile(profile_uuid, credentials.ssid)
                    except Exception:
                        result["ownProfileCleanup"] = "unconfirmed_manual_cleanup_required"
                    raise
                else:
                    raise
            # After confirmed activation, recording failure is distinct from join failure.
            try:
                save()
            except Exception:
                result["resultRecording"] = "unavailable"
    except BaseException as error:
        result.update(ok=False, failedPhase=result["phase"], phase="failed",
                      error=error.code if isinstance(error, JoinError) else "operation_failed",
                      finishedAt=datetime.now(timezone.utc).isoformat())
        try:
            save()
        except Exception:
            result["resultRecording"] = "unavailable"
    return public_result(result, credentials.psk)


class SafeParser(argparse.ArgumentParser):
    def error(self, _message):
        super().error("invalid options; credentials must be supplied only as JSON on stdin")


def main(argv=None, system=None, store=None, stdin=None):
    parser = SafeParser(description=__doc__)
    options = parser.add_mutually_exclusive_group()
    options.add_argument("--check", action="store_true", help="read-only capability/network/idle check (default)")
    options.add_argument("--status", action="store_true", help="same read-only check")
    options.add_argument("--join", action="store_true", help="explicitly switch wlan0 using stdin JSON {ssid,psk}")
    args = parser.parse_args(argv)
    system = system or BoardSystem()
    if args.join:
        try:
            credentials = read_credentials(stdin if stdin is not None else sys.stdin.buffer)
            result = join_group(credentials, system, store or ResultStore())
        except Exception as error:
            result = dict(schemaVersion=1, action="join", ok=False,
                          error=error.code if isinstance(error, JoinError) else "invalid_input")
    else:
        result = check(system)
    try:
        print(json.dumps(result, ensure_ascii=True), flush=True)
    except (BrokenPipeError, OSError):
        pass  # The --join result was persisted before writing to a possibly lost SSH channel.
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
