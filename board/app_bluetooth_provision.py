#!/usr/bin/env python3
"""Allowlisted, bonded Bluetooth RFCOMM hotspot provisioning. No motor operations.

The protocol is deliberately small: v1 newline JSON, status/provision only. Wi-Fi
credentials live in memory and a private /run passwd-file during activation. The
daemon never opens discoverability, changes power, or restarts Bluetooth. Initial
bonding is a separate, explicitly time-limited enrollment operation.
"""
from __future__ import annotations

import argparse
import atexit
from contextlib import nullcontext
from datetime import datetime, timezone
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import socket
import stat
import subprocess
import threading
import time
import uuid

from app_wifi_direct_join import BoardSystem, Credentials, JoinError, JoinSignals, ResultStore, join_group

SERVICE_UUID = "6d4f3a10-8f71-4c5e-9c2a-7b36f0e1d842"
PROFILE_PATH = "/com/followingcar/Provisioning"
MAX_FRAME = 4096
MAX_HISTORY = 16
DEFAULT_CONFIG = Path("/etc/followingcar-pairing.json")
DEFAULT_STATE = Path("/var/lib/followingcar-pairing/state.json")


class PageScanLease:
    """Own only the page-scan/fast-connectable changes made by this daemon."""
    def __init__(self, runner=None):
        self.runner = runner or self.run
        self.changed = False
        self.fast_changed = False

    @staticmethod
    def run(arguments):
        try:
            # BlueZ 5.64 btmgmt exits on stdin EOF before its asynchronous reply,
            # even with exit code 0. Keep an unused pipe open until the fixed,
            # bounded command completes; never inherit terminal input.
            with subprocess.Popen(arguments, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True,
                                  env=dict(os.environ, LC_ALL="C")) as process:
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                    raise
                output = process.stdout.read(16385)
                return_code = process.returncode
        except (OSError, subprocess.TimeoutExpired):
            raise JoinError("bluetooth_management_unavailable") from None
        if return_code or len(output) > 16384:
            raise JoinError("bluetooth_management_failed")
        return output

    def settings(self):
        output = self.runner(["/usr/bin/btmgmt", "--index", "0", "info"])
        current = re.findall(r"^[ \t]*current settings:[ \t]*(.*?)[ \t\r]*$", output, re.MULTILINE)
        supported = re.findall(r"^[ \t]*supported settings:[ \t]*(.*?)[ \t\r]*$", output, re.MULTILINE)
        if len(current) != 1 or len(supported) > 1:
            raise JoinError("bluetooth_state_unconfirmed")
        # Older management output may omit capabilities. Preserve basic page
        # scan in that case; never guess support for an optional controller bit.
        return set(current[0].split()), set(supported[0].split()) if supported else set()

    def flags(self):
        return self.settings()[0]

    def open(self):
        settings, supported = self.settings()
        if not {"powered", "br/edr"} <= settings:
            raise JoinError("bluetooth_not_ready")
        try:
            if "connectable" not in settings:
                # Mark ownership before sending: a timed-out command may still
                # have reached the controller and therefore needs rollback.
                self.changed = True
                self.runner(["/usr/bin/btmgmt", "--index", "0", "connectable", "on"])
                if "connectable" not in self.flags():
                    raise JoinError("bluetooth_page_scan_unconfirmed")
            if "fast-connectable" in supported and "fast-connectable" not in settings:
                self.fast_changed = True
                self.runner(["/usr/bin/btmgmt", "--index", "0", "fast-conn", "on"])
                if "fast-connectable" not in self.flags():
                    raise JoinError("bluetooth_fast_connectable_unconfirmed")
        except Exception:
            self.close()
            raise

    def close(self):
        # Restore fast scan while page scan is still available, then restore
        # connectability only if we enabled it. Existing true flags are not ours.
        # Restoration does not depend on another info query succeeding.
        for owned, command in (("fast_changed", "fast-conn"), ("changed", "connectable")):
            if not getattr(self, owned):
                continue
            try:
                self.runner(["/usr/bin/btmgmt", "--index", "0", command, "off"])
            except Exception:
                # Each command is bounded. Still attempt the other restoration;
                # retain ownership so a subsequent close/atexit can retry it.
                continue
            setattr(self, owned, False)


def code(error):
    return error.code if isinstance(error, JoinError) else "operation_unavailable"


def canonical_uuid(value):
    if type(value) is not str:
        raise JoinError("invalid_request_id")
    try:
        parsed = str(uuid.UUID(value))
    except ValueError:
        raise JoinError("invalid_request_id") from None
    if value != parsed:
        raise JoinError("invalid_request_id")
    return value


def validate_credentials(ssid, psk):
    # Different entry point from the Direct CLI: LocalOnlyHotspot names need not
    # start with DIRECT-. Preserve the Direct helper's narrower public contract.
    try:
        valid_ssid = (type(ssid) is str and 1 <= len(ssid.encode("utf-8")) <= 32
                      and not re.search(r"[\x00-\x1f\x7f]", ssid))
    except UnicodeError:
        valid_ssid = False
    if not valid_ssid:
        raise JoinError("invalid_hotspot_ssid")
    if type(psk) is not str or not (re.fullmatch(r"[\x20-\x7e]{8,63}", psk)
                                   or re.fullmatch(r"[0-9a-fA-F]{64}", psk)):
        raise JoinError("invalid_wpa2_psk")
    return Credentials(ssid, psk)


def parse_request(raw):
    if len(raw) > MAX_FRAME or not raw.endswith(b"\n"):
        raise JoinError("invalid_frame")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise JoinError("duplicate_field")
            result[key] = value
        return result
    try:
        request = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    except (ValueError, UnicodeError):
        raise JoinError("invalid_json") from None
    if type(request) is not dict or type(request.get("v")) is not int or request.get("v") != 1:
        raise JoinError("unsupported_protocol")
    canonical_uuid(request.get("requestId"))
    cmd = request.get("cmd")
    base = {"v", "requestId", "cmd"}
    if cmd == "status":
        if set(request) not in (base, base | {"queryRequestId"}):
            raise JoinError("unexpected_fields")
        if "queryRequestId" in request:
            canonical_uuid(request["queryRequestId"])
    elif cmd == "provision":
        if set(request) != base | {"ssid", "psk"}:
            raise JoinError("unexpected_fields")
        validate_credentials(request["ssid"], request["psk"])
    else:
        raise JoinError("unsupported_command")
    return request


def response(request_id, kind, **fields):
    return dict(v=1, requestId=request_id, type=kind, **fields)


class HotspotSystem(BoardSystem):
    """The same guarded NM transaction, with verified DHCP addresses for the App."""
    def address_result(self, profile_uuid):
        network = self.network()
        if network["state"] != 100 or network["profileUuid"] != profile_uuid:
            raise JoinError("network_address_unconfirmed")
        output = self.checked(["/usr/bin/nmcli", "-t", "--escape", "no", "-f",
                               "IP4.ADDRESS,DHCP4.OPTION", "device", "show", "wlan0"])
        addresses, routers, servers = [], [], []
        for line in output.splitlines():
            key, separator, value = line.partition(":")
            if not separator:
                continue
            if key.startswith("IP4.ADDRESS"):
                try:
                    addresses.append(ipaddress.IPv4Interface(value))
                except ValueError:
                    raise JoinError("network_address_unconfirmed") from None
            if key.startswith("DHCP4.OPTION") and value.startswith("routers = "):
                try:
                    routers.extend(ipaddress.IPv4Address(item) for item in value[10:].split())
                except ValueError:
                    raise JoinError("network_address_unconfirmed") from None
            if key.startswith("DHCP4.OPTION") and value.startswith("dhcp_server_identifier = "):
                try:
                    servers.append(ipaddress.IPv4Address(value.split(" = ", 1)[1]))
                except ValueError:
                    raise JoinError("network_address_unconfirmed") from None
        # Local-only hotspots need not advertise an Internet default route. Their
        # DHCP server is still the phone; the App must independently match this
        # address and prefix to its newly created hotspot interface before SSH.
        peers = routers or servers
        if len(addresses) != 1 or len(peers) != 1:
            raise JoinError("network_address_unconfirmed")
        address, gateway = addresses[0], peers[0]
        forbidden = {address.network.network_address, address.network.broadcast_address}
        if (address.network.prefixlen > 30 or gateway not in address.network
                or address.ip in forbidden or gateway in forbidden or address.ip == gateway
                or str(address.ip) not in network["addresses"]):
            raise JoinError("network_address_unconfirmed")
        return dict(ip=str(address.ip), gateway=str(gateway), prefixLength=address.network.prefixlen)


class PairingState:
    """Bounded non-secret operation history, including crash-unknown requests."""
    def __init__(self, path=DEFAULT_STATE, store=None):
        self.path = Path(path)
        self.store = store or ResultStore(self.path, owner=None)
        self.records = []
        if self.path.exists():
            info = self.path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_size > 32768:
                raise JoinError("unsafe_pairing_state")
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                if type(data) is not list or len(data) > MAX_HISTORY:
                    raise ValueError()
                for record in data:
                    canonical_uuid(record["requestId"])
                    if record.get("type") != "result":
                        # Never retry credentials or claim completion after a crash.
                        record = response(record["requestId"], "result", ok=False, definitive=False,
                                          error="outcome_unknown_after_restart", networkChanged=True)
                    self.records.append(record)
            except (ValueError, KeyError, TypeError):
                raise JoinError("pairing_state_unreadable") from None

    def get(self, request_id):
        return next((dict(record) for record in reversed(self.records)
                     if record["requestId"] == request_id), None)

    def put(self, record):
        values = [item for item in self.records if item["requestId"] != record["requestId"]]
        values.append(dict(record))
        values = values[-MAX_HISTORY:]
        self.store.write(values)  # Persist before making a Wi-Fi request visible.
        self.records = values


class JoinRecord:
    """Adapt join_group progress without copying network names or credentials."""
    def __init__(self, coordinator, request_id, emit):
        self.coordinator, self.request_id, self.emit = coordinator, request_id, emit

    def write(self, result):
        self.coordinator.join_store.write(dict(result, requestId=self.request_id))
        phase = result.get("phase")
        if phase == "activating":
            progress = response(self.request_id, "progress", stage="connecting")
            self.coordinator.save(progress)
            self.emit(progress)


class Provisioner:
    def __init__(self, board_id, system=None, state=None, start_thread=None, join_store=None):
        self.board_id, self.system = board_id, system or HotspotSystem()
        self.state = state or PairingState()
        self.join_store = join_store or ResultStore(self.state.path.with_name("network-last-result.json"), owner=None)
        self.lock = threading.RLock()
        self.active_id = None
        self.worker = None
        self.start_thread = start_thread or self._start_thread

    def _start_thread(self, target):
        self.worker = threading.Thread(target=target, name="wifi-provision", daemon=False)
        self.worker.start()

    def save(self, record):
        with self.lock:
            self.state.put(record)

    def status(self, request):
        with self.lock:
            active = self.active_id is not None
            previous = self.state.get(request.get("queryRequestId", ""))
            if (not active and previous is not None
                    and (previous.get("definitive") is False or previous.get("ok") is True)):
                previous = self.reconcile(previous)
        available, error = False, None
        if not active:
            try:
                with self.system.idle_guard():
                    available = True
            except Exception as failure:
                error = code(failure)
        return response(request["requestId"], "status", boardId=self.board_id,
                        controlAvailable=available, band="2.4GHz", operationInProgress=active,
                        lastResult=previous, error=error)

    def read_join_record(self):
        if hasattr(self.join_store, "read"):
            return self.join_store.read()  # Injected offline store.
        path = self.join_store.path
        if any(parent.is_symlink() for parent in path.parents):
            raise JoinError("unsafe_network_result")
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            info = os.fstat(descriptor)
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077
                    or info.st_size > 32768):
                raise JoinError("unsafe_network_result")
            with os.fdopen(descriptor, "rb") as stream:
                descriptor = None
                payload = stream.read(32769)
            if len(payload) > 32768:
                raise JoinError("unsafe_network_result")
            return json.loads(payload.decode("utf-8"))
        finally:
            if descriptor is not None:
                os.close(descriptor)

    def reconcile(self, previous):
        """Resolve only this operation from current observations; never change Wi-Fi."""
        fallback = previous
        if previous.get("ok") is True:
            # A completed operation is historical evidence, not proof that the
            # same Wi-Fi connection still exists after an external restoration.
            fallback = response(previous["requestId"], "result", ok=False, definitive=False,
                                networkChanged=True, error="current_network_unconfirmed",
                                previouslySucceeded=True)
            if "firstError" in previous:
                fallback["firstError"] = previous["firstError"]
        try:
            record = self.read_join_record()
            request_id = previous["requestId"]
            if (type(record) is not dict or record.get("requestId") != request_id
                    or record.get("schemaVersion") != 1 or record.get("action") != "join"
                    or record.get("method") != "bluetooth_hotspot" or record.get("interface") != "wlan0"):
                return fallback
            profile_uuid = canonical_uuid(record.get("profileUuid"))
            prior_uuid = canonical_uuid(record.get("previousConnectionUuid"))
            if profile_uuid == prior_uuid or type(record.get("requestedSsid")) is not str:
                return fallback
            with self.system.idle_guard():
                network = self.system.network()
                if network["state"] != 100 or not network.get("addresses"):
                    return fallback
                active_uuids = self.system.active_uuids()
                if (network["profileUuid"] == prior_uuid and prior_uuid in active_uuids
                        and profile_uuid not in active_uuids):
                    final = response(request_id, "result", ok=False, definitive=True,
                                     networkChanged=False, error="network_restored", reconciled=True)
                elif (network["profileUuid"] == profile_uuid and profile_uuid in active_uuids
                      and self.system.owns_profile(profile_uuid, record["requestedSsid"])):
                    final = response(request_id, "result", ok=True, definitive=True,
                                     networkChanged=True, reconciled=True,
                                     **self.system.address_result(profile_uuid))
                else:
                    return fallback
                first_error = previous.get("firstError") or previous.get("error")
                if type(first_error) is str and re.fullmatch(r"[a-z][a-z0-9_]{0,79}", first_error):
                    final["firstError"] = first_error
                self.state.put(final)
                return final
        except Exception:
            # Busy/unknown, mismatched or unavailable observations cannot unlock.
            return fallback

    def handle(self, request, emit):
        # The caller only passes parse_request output. Emission failure is never a
        # cancellation of an already accepted network operation.
        def safe_emit(value):
            try:
                emit(value)
            except (OSError, RuntimeError):
                pass
        if request["cmd"] == "status":
            safe_emit(self.status(request))
            return
        request_id = request["requestId"]
        credentials = validate_credentials(request["ssid"], request["psk"])
        with self.lock:
            previous = self.state.get(request_id)
            if previous is not None:
                if self.active_id is None and (previous.get("ok") is True or previous.get("definitive") is False):
                    previous = self.reconcile(previous)
                safe_emit(previous)
                return
            if self.active_id is not None:
                safe_emit(response(request_id, "result", ok=False, definitive=True,
                                   error="provisioning_busy", networkChanged=False))
                return
            # Fast preflight rejects busy control before accepting credentials.
            try:
                with self.system.idle_guard():
                    pass
                accepted = response(request_id, "progress", stage="accepted")
                self.state.put(accepted)
            except Exception as failure:
                safe_emit(response(request_id, "result", ok=False, definitive=True,
                                   error=code(failure), networkChanged=False))
                return
            self.active_id = request_id
            safe_emit(accepted)
            try:
                self.start_thread(lambda: self._provision(request_id, credentials, safe_emit))
            except Exception:
                self.active_id = None
                failed = response(request_id, "result", ok=False, definitive=True,
                                  error="worker_unavailable", networkChanged=False)
                self.state.put(failed)
                safe_emit(failed)

    def _provision(self, request_id, credentials, emit):
        result = None
        try:
            # Signal handlers belong to the main GLib thread; a worker finishes
            # its bounded transaction while the service stops accepting clients.
            result = join_group(credentials, self.system, JoinRecord(self, request_id, emit),
                                signal_monitor=lambda: nullcontext(JoinSignals()), method="bluetooth_hotspot")
            if result["ok"]:
                addresses = self.system.address_result(result["profileUuid"])
                final = response(request_id, "result", ok=True, definitive=True, networkChanged=True, **addresses)
            else:
                changed = result.get("failedPhase") not in ("preflight", "creating_profile")
                definitive = (not changed and result.get("ownProfileCleanup") != "unconfirmed_manual_cleanup_required")
                final = response(request_id, "result", ok=False, error=result.get("error", "join_failed"),
                                 networkChanged=changed, definitive=definitive,
                                 cleanup=result.get("ownProfileCleanup"))
        except Exception as failure:
            final = response(request_id, "result", ok=False, definitive=False, error=code(failure),
                             networkChanged=bool(result and result.get("ok")))
        finally:
            credentials = None
        with self.lock:
            try:
                self.state.put(final)
            except Exception:
                # The earlier accepted/connecting record survives a restart.
                # Keep the definitive result in memory but disclose persistence loss.
                final["resultRecording"] = "unavailable"
                self.state.records = (self.state.records + [final])[-MAX_HISTORY:]
            self.active_id = None
        emit(final)


def serve_socket(connection, provisioner, stop_event):
    """Bounded frames; disconnect does not cancel the independent accepted worker."""
    write_lock = threading.Lock()
    def emit(value):
        payload = json.dumps(value, separators=(",", ":"), ensure_ascii=True).encode("ascii") + b"\n"
        if len(payload) > MAX_FRAME:
            raise RuntimeError("reply_too_large")
        with write_lock:
            connection.sendall(payload)
    connection.settimeout(1.0)
    pending = bytearray()
    frame_start = None
    idle_deadline = time.monotonic() + 180
    try:
        while not stop_event.is_set():
            now = time.monotonic()
            if now > idle_deadline or frame_start is not None and now - frame_start > 10:
                break
            try:
                chunk = connection.recv(1024)
            except socket.timeout:
                now = time.monotonic()
                if now > idle_deadline or frame_start is not None and now - frame_start > 10:
                    break
                continue
            if not chunk:
                break
            if frame_start is not None and time.monotonic() - frame_start > 10:
                break
            if frame_start is None:
                frame_start = time.monotonic()
            pending.extend(chunk)
            while b"\n" in pending:
                end = pending.index(b"\n") + 1
                raw = bytes(pending[:end])
                del pending[:end]
                try:
                    request = parse_request(raw)
                except JoinError:
                    return  # No reflection of malformed/untrusted request text.
                provisioner.handle(request, emit)
                request = None
                idle_deadline = time.monotonic() + 180
                frame_start = time.monotonic() if pending else None
            if len(pending) > MAX_FRAME:
                break
    except (OSError, RuntimeError):
        pass
    finally:
        pending.clear()
        connection.close()


def load_config(path):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077
            or info.st_size > 4096):
        raise JoinError("unsafe_pairing_config")
    data = json.loads(path.read_text(encoding="utf-8"))
    if (type(data) is not dict or set(data) != {"boardId", "allowedDevices"}
            or type(data["boardId"]) is not str
            or not re.fullmatch(r"followingcar-[a-zA-Z0-9_-]{1,48}", data["boardId"])
            or type(data["allowedDevices"]) is not list
            or not 1 <= len(data["allowedDevices"]) <= 8
            or any(type(value) is not str or not re.fullmatch(r"(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", value)
                   for value in data["allowedDevices"])):
        raise JoinError("invalid_pairing_config")
    return data


def run_daemon(config, enrollment_seconds=0):
    # Linux-only dependencies stay out of protocol/unit-test imports.
    import dbus
    import dbus.service
    from dbus.mainloop.glib import DBusGMainLoop
    from gi.repository import GLib
    DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    loop, stopped = GLib.MainLoop(), threading.Event()
    provisioner = Provisioner(config["boardId"])
    allowed = set(config["allowedDevices"])
    connections = set()
    connection_lock = threading.Lock()
    enrollment_until = time.monotonic() + enrollment_seconds
    page_scan = PageScanLease()
    # Also covers startup failures before entering GLib's main loop. Existing
    # connectable=true belongs to another owner and is never turned off here.
    atexit.register(page_scan.close)
    page_scan.open()

    class Rejected(dbus.DBusException):
        _dbus_error_name = "org.bluez.Error.Rejected"

    def properties(device):
        return dbus.Interface(bus.get_object("org.bluez", device), "org.freedesktop.DBus.Properties")

    def permitted(device, require_bond=True):
        values = properties(device).GetAll("org.bluez.Device1")
        return (str(values.get("Address", "")).upper() in allowed
                and (not require_bond or bool(values.get("Paired")) and bool(values.get("Trusted"))))

    class Profile(dbus.service.Object):
        @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
        def Release(self):
            stop()

        @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
        def NewConnection(self, device, descriptor, _options):
            # BlueZ already authenticated the link. Recheck against the allowlist;
            # being generally trusted by a desktop session is not sufficient.
            if stopped.is_set() or not permitted(device):
                raise Rejected("Device not enrolled")
            with connection_lock:
                if connections:
                    raise Rejected("Provisioning connection busy")
                connection = socket.socket(fileno=descriptor.take())
                connections.add(connection)
            def run():
                try:
                    serve_socket(connection, provisioner, stopped)
                finally:
                    with connection_lock:
                        connections.discard(connection)
            threading.Thread(target=run, name="bluetooth-provision", daemon=True).start()

        @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
        def RequestDisconnection(self, _device):
            close_connections()

    class Agent(dbus.service.Object):
        def authorize(self, device):
            if time.monotonic() >= enrollment_until or not permitted(device, require_bond=False):
                raise Rejected("Enrollment closed or device not allowed")

        @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
        def Release(self):
            pass

        @dbus.service.method("org.bluez.Agent1", in_signature="ou", out_signature="")
        def RequestConfirmation(self, device, _passkey):
            # Limited-MAC enrollment is not a claim of user-verified numeric
            # comparison. The phone still presents its normal system prompt.
            self.authorize(device)

        @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="")
        def RequestAuthorization(self, device):
            self.authorize(device)

        @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
        def AuthorizeService(self, device, service):
            if str(service).lower() != SERVICE_UUID or not permitted(device):
                raise Rejected("Service not allowed")

        @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="s")
        def RequestPinCode(self, _device):
            raise Rejected("Legacy PIN pairing is not supported")

        @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="u")
        def RequestPasskey(self, _device):
            raise Rejected("Passkey entry is not supported")

        @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
        def Cancel(self):
            pass

    profile = Profile(bus, PROFILE_PATH)
    manager = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.ProfileManager1")
    manager.RegisterProfile(PROFILE_PATH, SERVICE_UUID, {
        "Name": "FollowingCar Wi-Fi Pairing", "Role": "server",
        "RequireAuthentication": dbus.Boolean(True), "RequireAuthorization": dbus.Boolean(True),
        "AutoConnect": dbus.Boolean(False), "Channel": dbus.UInt16(22),
    })
    agent, agent_manager = None, None

    def remove_agent():
        nonlocal agent
        if agent is not None:
            try:
                agent_manager.UnregisterAgent(PROFILE_PATH + "/Enrollment")
            except dbus.DBusException:
                pass
            agent.remove_from_connection()
            agent = None
        return False

    def bond_changed(interface, changes, _invalidated, path=None):
        if (interface == "org.bluez.Device1" and changes.get("Paired")
                and time.monotonic() < enrollment_until and permitted(path, require_bond=False)):
            # Persist trust only for an explicitly allowlisted newly bonded phone.
            properties(path).Set("org.bluez.Device1", "Trusted", dbus.Boolean(True))

    if enrollment_seconds:
        agent = Agent(bus, PROFILE_PATH + "/Enrollment")
        agent_manager = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.AgentManager1")
        agent_manager.RegisterAgent(PROFILE_PATH + "/Enrollment", "DisplayYesNo")
        # Explicit enrollment only. Normal service operation does not take over
        # the system default agent or make an adapter discoverable.
        agent_manager.RequestDefaultAgent(PROFILE_PATH + "/Enrollment")
        bus.add_signal_receiver(bond_changed, dbus_interface="org.freedesktop.DBus.Properties",
                                signal_name="PropertiesChanged", path_keyword="path")
        GLib.timeout_add_seconds(enrollment_seconds, remove_agent)

    def close_connections():
        with connection_lock:
            for connection in list(connections):
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def stop(*_args):
        stopped.set()
        close_connections()
        loop.quit()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        loop.run()
    finally:
        stopped.set()
        remove_agent()
        close_connections()
        try:
            manager.UnregisterProfile(PROFILE_PATH)
        except dbus.DBusException:
            pass
        if provisioner.worker is not None:
            # Do not interrupt a network switch midway or release its idle locks.
            provisioner.worker.join()
        profile.remove_from_connection()
        page_scan.close()
        atexit.unregister(page_scan.close)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--check", action="store_true", help="read-only configuration and idle check")
    parser.add_argument("--enrollment-seconds", type=int, default=0,
                        help="explicit allowlisted first-bond window, 1..300 seconds; default closed")
    args = parser.parse_args(argv)
    try:
        if os.geteuid() != 0 or not 0 <= args.enrollment_seconds <= 300:
            raise JoinError("root_or_valid_window_required")
        config = load_config(args.config)
        if args.check:
            reply = Provisioner(config["boardId"]).status(dict(requestId=str(uuid.uuid4()), cmd="status"))
            print(json.dumps(reply))
            return 0 if reply["controlAvailable"] else 1
        run_daemon(config, args.enrollment_seconds)
        return 0
    except Exception as failure:
        print(json.dumps({"ok": False, "error": code(failure)}), file=__import__("sys").stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
