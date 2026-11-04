#!/usr/bin/env python3
"""Opt-in LAN DHCP recovery and one isolated DNS-SD advertisement.

Default --plan is read-only. --launch-dhcp starts this same helper in a detached
systemd unit; only --apply-dhcp changes the selected NetworkManager profile.
No motor device, camera, worker or control-service API is used here.
"""
from __future__ import annotations

import argparse
import base64
import configparser
from contextlib import contextmanager
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time
import uuid

BOARD_ID = 'followingcar-rk-140761'
SSH_FINGERPRINT = 'SHA256:vRTxsHA9fDeRZQ6YTXKYPyngRhvcAJEIF9+XnfxPx0g'
PERMANENT_MAC_SHA256 = '68ba46569b9131f9296f12560d2be96e7ebe86b0174cfe60e9d9870bc2f58ff1'
PROFILE_UUID = '2f73c1af-5e3c-42f5-8093-e0a7f656e37f'
CONFLICT_ADDRESS = '172.16.16.82'
INTERFACE = 'wlan0'
STATE_DIRECTORY = Path('/var/lib/followingcar-network')
AVAHI_FILE = Path('/etc/avahi/services/followingcar-rk-140761.service')
NM_DIRECTORY = Path('/etc/NetworkManager/system-connections')
HELPER_PATH = Path('/opt/followingcar-network/app_lan_recovery.py')
LEASE_WAIT_SECONDS = 45.0
AVAHI_XML = f'''<?xml version="1.0" standalone="no"?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name>{BOARD_ID}</name>
  <service protocol="any">
    <type>_followingcar._tcp</type>
    <port>22</port>
    <txt-record>v=1</txt-record>
    <txt-record>id={BOARD_ID}</txt-record>
    <txt-record>sshfp={SSH_FINGERPRINT}</txt-record>
  </service>
</service-group>
'''.encode('utf-8')


class RecoveryError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def code(error):
    value = getattr(error, 'code', '')
    return value if isinstance(value, str) and re.fullmatch(r'[a-z0-9_]{1,80}', value) else 'recovery_io_failed'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical_id(value):
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise RecoveryError('invalid_request_id') from None
    return value


def require_identity(observation):
    if observation['sshFingerprint'] != SSH_FINGERPRINT:
        raise RecoveryError('wrong_board_host_key')
    if observation['permanentMacSha256'] != PERMANENT_MAC_SHA256:
        raise RecoveryError('wrong_wireless_hardware')


def require_original(observation):
    require_identity(observation)
    if observation['profileUuid'] != PROFILE_UUID or observation['state'] != 100:
        raise RecoveryError('target_profile_not_active')
    if observation['method'] != 'manual':
        raise RecoveryError('original_profile_is_not_static')
    if observation['addresses'] != [CONFLICT_ADDRESS + '/22']:
        raise RecoveryError('original_address_changed')


def good_lease(observation):
    require_identity(observation)
    if observation['profileUuid'] != PROFILE_UUID or observation['state'] != 100:
        return False
    if observation['method'] != 'auto' or observation['dadTimeout'] != 3000:
        return False
    lease = observation.get('leaseAddress')
    server = observation.get('dhcpServer')
    try:
        address, server_ip = ipaddress.IPv4Address(lease), ipaddress.IPv4Address(server)
        if any(value.is_unspecified or value.is_loopback or value.is_multicast or value.is_link_local
               for value in (address, server_ip)):
            return False
        interfaces = [ipaddress.IPv4Interface(item) for item in observation['addresses']]
    except (ValueError, TypeError):
        return False
    return (lease != CONFLICT_ADDRESS and all(str(item.ip) != CONFLICT_ADDRESS for item in interfaces)
            and any(item.ip == address and address not in (item.network.network_address, item.network.broadcast_address)
                    for item in interfaces))


def public_observation(observation):
    # Never include profile keyfiles, SSID, PSK, raw permanent MAC or NM error output.
    return {name: observation.get(name) for name in
            ('profileUuid', 'state', 'method', 'addresses', 'ipv6Addresses', 'dadTimeout',
             'leaseAddress', 'dhcpServer', 'sshFingerprint')}


def apply_dhcp(system, request_id):
    """Own the existing two control locks throughout update, verification and rollback."""
    canonical_id(request_id)
    result = dict(type='lan_recovery', schemaVersion=1, requestId=request_id,
                  ok=False, changed=False, rollback='not_needed', errorCode=None)
    backup = None
    try:
        system.require_root()
        with system.transaction(request_id):
            with system.idle_guard():
                original = system.observe()
                require_original(original)
                result['before'] = public_observation(original)
                backup = system.backup_profile(original, request_id)
                result['backupSha256'] = backup['sha256']
                # Backup and durable pending state precede the first possible change.
                system.save_result(request_id, dict(result, phase='prepared'))
                try:
                    require_original(system.observe())
                    system.verify_backup_current(backup)
                    result['changed'] = True  # Even a failed nmcli call may have committed.
                    system.save_result(request_id, dict(result, phase='applying'))
                    system.modify_profile(original)
                    system.verify_only_allowed_changes(backup)
                    # Reapply does not restart NetworkManager or sshd. A reconnect is
                    # necessary on some NM versions; the detached helper survives it.
                    result['activation'] = system.activate(reapply_first=True)
                    deadline = system.monotonic() + LEASE_WAIT_SECONDS
                    previous = None
                    while system.monotonic() < deadline:
                        system.check_cancelled()
                        current = system.observe()
                        if system.monotonic() >= deadline:
                            raise RecoveryError('dhcp_lease_unconfirmed')
                        if current['profileUuid'] not in (None, PROFILE_UUID):
                            raise RecoveryError('external_profile_activated')
                        if good_lease(current):
                            key = (current['leaseAddress'], tuple(current['addresses']), current['dhcpServer'])
                            if key == previous:
                                system.verify_only_allowed_changes(backup)
                                result.update(ok=True, after=public_observation(current), phase='committed',
                                    ipv6Retained=bool(set(original['ipv6Addresses']) & set(current['ipv6Addresses'])))
                                system.save_result(request_id, result)
                                return result
                            previous = key
                        else:
                            previous = None
                        system.sleep(.5)
                    raise RecoveryError('dhcp_lease_unconfirmed')
                except Exception as error:
                    result['ok'] = False
                    result['errorCode'] = code(error)
                    if result['changed']:
                        try:
                            system.restore_profile(backup)
                            restored = system.observe()
                            require_original(restored)
                            result['rollback'] = 'confirmed'
                            result['after'] = public_observation(restored)
                        except Exception as rollback_error:
                            result['rollback'] = 'unconfirmed'
                            result['rollbackErrorCode'] = code(rollback_error)
                    result['phase'] = 'failed'
                    system.save_result(request_id, result)
    except Exception as error:
        result['errorCode'] = result['errorCode'] or code(error)
        result['phase'] = 'failed'
        # A detached caller still needs a result when the control-idle preflight
        # rejected the request. No network mutation is needed for this record.
        try: system.save_result(request_id, result)
        except Exception: pass
    return result


def profile_values(data):
    parser = configparser.ConfigParser(interpolation=None, strict=True)
    try:
        parser.read_string(data.decode('utf-8'))
        if parser.get('connection', 'uuid') != PROFILE_UUID:
            raise ValueError()
    except (ValueError, UnicodeError, configparser.Error):
        raise RecoveryError('profile_file_unconfirmed') from None
    return {(section, key): value for section in parser.sections() for key, value in parser.items(section)
            if value != ''}


def unchanged_profile_values(before, after):
    """Compare every other saved key, including all credentials and DNS settings."""
    allowed = {('connection', 'timestamp'), ('ipv4', 'method'), ('ipv4', 'gateway'),
               ('ipv4', 'dad-timeout'), ('wifi', 'mac-address'), ('wifi', 'cloned-mac-address')}
    def retained(values):
        return {key: value for key, value in values.items() if key not in allowed
                and not (key[0] == 'ipv4' and re.fullmatch(r'address[0-9]+', key[1]))}
    return retained(profile_values(before)) == retained(profile_values(after))


def safe_regular(path, *, maximum=65536):
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise RecoveryError('unsafe_path')
    info = path.stat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1
            or info.st_size > maximum or info.st_mode & 0o022):
        raise RecoveryError('unsafe_file')
    return info


def atomic_write(path, data, mode=0o600, uid=0, gid=0):
    temporary = path.with_name('.' + path.name + '.new-' + uuid.uuid4().hex)
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        os.fchown(descriptor, uid, gid)
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
        descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)
    finally:
        if temporary.exists(): temporary.unlink()


class BoardSystem:
    def __init__(self):
        self.cancelled = False
        self.monotonic, self.sleep = time.monotonic, time.sleep
        self.transaction_path = None

    def require_root(self):
        if os.name != 'posix' or os.geteuid() != 0:
            raise RecoveryError('root_required')

    def check_cancelled(self):
        if self.cancelled: raise RecoveryError('recovery_cancelled')

    def run(self, arguments, timeout=8, *, tolerate=False):
        try:
            result = subprocess.run(arguments, stdin=subprocess.DEVNULL, capture_output=True,
                text=True, timeout=timeout, env=dict(os.environ, LC_ALL='C', LANG='C'), close_fds=True)
        except (OSError, subprocess.TimeoutExpired):
            raise RecoveryError('network_command_timeout') from None
        if result.returncode and not tolerate: raise RecoveryError('network_command_failed')
        return result

    def observe(self):
        import dbus
        name = 'org.freedesktop.NetworkManager'; props = 'org.freedesktop.DBus.Properties'
        bus = dbus.SystemBus()
        deadline = self.monotonic() + 8
        def call(method, *arguments):
            remaining = deadline - self.monotonic()
            if remaining <= 0: raise RecoveryError('network_observation_timeout')
            result = method(*arguments, timeout=min(2, remaining))
            if self.monotonic() >= deadline: raise RecoveryError('network_observation_timeout')
            return result
        def proxy(path): return bus.get_object(name, path, introspect=False)
        def properties(path): return dbus.Interface(proxy(path), props)
        manager = dbus.Interface(proxy('/org/freedesktop/NetworkManager'), name)
        device = properties(call(manager.GetDeviceByIpIface, INTERFACE))
        permanent = str(call(device.Get, name + '.Device.Wireless', 'PermHwAddress')).lower()
        if not re.fullmatch(r'[0-9a-f]{2}(?::[0-9a-f]{2}){5}', permanent):
            raise RecoveryError('permanent_mac_unavailable')
        active = call(device.Get, name + '.Device', 'ActiveConnection')
        state = int(call(device.Get, name + '.Device', 'State'))
        settings = dbus.Interface(proxy('/org/freedesktop/NetworkManager/Settings'), name + '.Settings')
        target_path = call(settings.GetConnectionByUuid, PROFILE_UUID)
        target = proxy(target_path)
        values = call(dbus.Interface(target, name + '.Settings.Connection').GetSettings)
        filename = str(call(properties(target_path).Get, name + '.Settings.Connection', 'Filename'))
        active_uuid = str(call(properties(active).Get, name + '.Connection.Active', 'Uuid')) if str(active) != '/' else None
        addresses, ipv6 = [], []
        for key, output in (('Ip4Config', addresses), ('Ip6Config', ipv6)):
            path = call(device.Get, name + '.Device', key)
            if str(path) != '/':
                group = name + ('.IP4Config' if key == 'Ip4Config' else '.IP6Config')
                output.extend(str(item['address']) + '/' + str(item['prefix'])
                              for item in call(properties(path).Get, group, 'AddressData'))
        path = call(device.Get, name + '.Device', 'Dhcp4Config')
        options = call(properties(path).Get, name + '.DHCP4Config', 'Options') if str(path) != '/' else {}
        public_key = Path('/etc/ssh/ssh_host_ecdsa_key.pub').read_text().split()[1]
        fingerprint = 'SHA256:' + base64.b64encode(hashlib.sha256(base64.b64decode(public_key)).digest()).decode().rstrip('=')
        return dict(profileUuid=active_uuid, state=state, filename=filename, permanentMac=permanent,
            permanentMacSha256=sha(permanent.encode()), sshFingerprint=fingerprint,
            method=str(values['ipv4']['method']), dadTimeout=int(values['ipv4'].get('dad-timeout', -1)),
            addresses=sorted(addresses), ipv6Addresses=sorted(ipv6),
            leaseAddress=str(options.get('ip_address', '')), dhcpServer=str(options.get('dhcp_server_identifier', '')))

    @contextmanager
    def transaction(self, request_id):
        self.require_root()
        STATE_DIRECTORY.mkdir(mode=0o700, parents=False, exist_ok=True)
        info = STATE_DIRECTORY.stat()
        if STATE_DIRECTORY.is_symlink() or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o700:
            raise RecoveryError('unsafe_state_directory')
        import fcntl
        lock = os.open(STATE_DIRECTORY / 'operation.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError: raise RecoveryError('network_recovery_busy') from None
            for prior in STATE_DIRECTORY.glob('*/result.json'):
                safe_regular(prior)
                record = json.loads(prior.read_text())
                if record.get('phase') in ('prepared', 'applying') or record.get('rollback') == 'unconfirmed':
                    raise RecoveryError('previous_recovery_unconfirmed')
            self.transaction_path = STATE_DIRECTORY / request_id
            self.transaction_path.mkdir(mode=0o700, exist_ok=False)
            yield
        finally:
            os.close(lock)

    def idle_guard(self):
        # Reuse the deployed guard without replacing any control code.
        sys.path.insert(0, '/home/topeet/lianzhan/scripts')
        from app_control_guard import ControlGuard
        return ControlGuard().idle_guard()

    def save_result(self, request_id, result):
        if self.transaction_path != STATE_DIRECTORY / request_id:
            raise RecoveryError('transaction_unavailable')
        atomic_write(self.transaction_path / 'result.json', json.dumps(result, sort_keys=True).encode())

    def backup_profile(self, observation, request_id):
        path = Path(observation['filename'])
        if path.parent != NM_DIRECTORY or path.suffix != '.nmconnection':
            raise RecoveryError('profile_file_outside_expected_directory')
        info = safe_regular(path)
        data = path.read_bytes(); profile_values(data)
        backup = self.transaction_path / 'profile.nmconnection'
        atomic_write(backup, data)
        result = dict(path=str(path), backup=str(backup), sha256=sha(data), mode=stat.S_IMODE(info.st_mode), uid=info.st_uid, gid=info.st_gid)
        atomic_write(self.transaction_path / 'backup-metadata.json', json.dumps(result).encode())
        return result

    def verify_backup_current(self, backup):
        safe_regular(Path(backup['path']))
        if sha(Path(backup['path']).read_bytes()) != backup['sha256']:
            raise RecoveryError('profile_changed_before_apply')

    def modify_profile(self, original):
        self.check_cancelled()
        self.run(['/usr/bin/nmcli', '--wait', '10', 'connection', 'modify', 'uuid', PROFILE_UUID,
            'ipv4.method', 'auto', 'ipv4.addresses', '', 'ipv4.gateway', '', 'ipv4.dad-timeout', '3000',
            '802-11-wireless.mac-address', original['permanentMac'],
            '802-11-wireless.cloned-mac-address', 'permanent'], 15)

    def verify_only_allowed_changes(self, backup):
        safe_regular(Path(backup['path']))
        if not unchanged_profile_values(Path(backup['backup']).read_bytes(), Path(backup['path']).read_bytes()):
            raise RecoveryError('unrelated_profile_setting_changed')

    def activate(self, *, reapply_first):
        if reapply_first and self.run(['/usr/bin/nmcli', '--wait', '10', 'device', 'reapply', INTERFACE], 15, tolerate=True).returncode == 0:
            return 'device_reapply'
        self.run(['/usr/bin/nmcli', '--wait', '45', 'connection', 'up', 'uuid', PROFILE_UUID, 'ifname', INTERFACE], 50)
        return 'same_profile_reconnect'

    def restore_profile(self, backup):
        original = Path(backup['backup']).read_bytes()
        if sha(original) != backup['sha256']: raise RecoveryError('backup_hash_mismatch')
        path = Path(backup['path']); safe_regular(path)
        # Do not overwrite an unrelated replacement or a user's concurrent changes.
        self.verify_only_allowed_changes(backup)
        active = self.observe()['profileUuid']
        atomic_write(path, original, backup['mode'], backup['uid'], backup['gid'])
        self.run(['/usr/bin/nmcli', 'connection', 'load', str(path)], 10)
        if active not in (None, PROFILE_UUID):
            raise RecoveryError('external_profile_kept_active')
        self.activate(reapply_first=active == PROFILE_UUID)
        deadline = self.monotonic() + 15
        while self.monotonic() < deadline:
            observation = self.observe()
            try:
                require_original(observation)
                return
            except RecoveryError:
                self.sleep(.5)
        raise RecoveryError('rollback_address_unconfirmed')


def install_discovery(system):
    """Only one project XML file; never rewrite avahi-daemon.conf or restart Avahi."""
    system.require_root()
    require_identity(system.observe())
    for parent in (AVAHI_FILE.parent, *AVAHI_FILE.parent.parents):
        if parent.is_symlink(): raise RecoveryError('unsafe_avahi_directory')
    if AVAHI_FILE.is_symlink(): raise RecoveryError('unsafe_avahi_file')
    original = None
    if AVAHI_FILE.exists():
        safe_regular(AVAHI_FILE)
        original = AVAHI_FILE.read_bytes()
        if original == AVAHI_XML:
            return dict(ok=True, changed=False, sha256=sha(AVAHI_XML))
        # Refuse a preexisting unknown service rather than replacing somebody's work.
        raise RecoveryError('discovery_file_already_exists')
    try:
        atomic_write(AVAHI_FILE, AVAHI_XML, 0o644)
        system.run(['/usr/bin/systemctl', 'reload', 'avahi-daemon.service'], 10)
    except Exception:
        if AVAHI_FILE.exists() and AVAHI_FILE.read_bytes() == AVAHI_XML:
            AVAHI_FILE.unlink()
            system.run(['/usr/bin/systemctl', 'reload', 'avahi-daemon.service'], 10, tolerate=True)
        raise
    return dict(ok=True, changed=True, sha256=sha(AVAHI_XML), path=str(AVAHI_FILE))


def launch_dhcp(system, request_id):
    canonical_id(request_id); system.require_root()
    if Path(__file__).resolve() != HELPER_PATH:
        raise RecoveryError('helper_not_at_installed_path')
    safe_regular(HELPER_PATH, maximum=131072)
    require_original(system.observe())
    # The child performs the definitive idle/identity checks under both locks.
    unit = 'followingcar-lan-recovery-' + request_id
    system.run(['/usr/bin/systemd-run', '--unit=' + unit, '--collect',
        '--property=Type=oneshot', '--property=TimeoutStartSec=240', '--property=UMask=0077',
        '--property=KillMode=control-group', '--property=NoNewPrivileges=yes',
        '/usr/bin/python3', '-B', str(HELPER_PATH), '--apply-dhcp', '--request-id', request_id], 10)
    return dict(ok=True, accepted=True, requestId=request_id, unit=unit,
                resultPath=str(STATE_DIRECTORY / request_id / 'result.json'))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--plan', action='store_true')
    group.add_argument('--install-discovery', action='store_true')
    group.add_argument('--launch-dhcp', action='store_true')
    group.add_argument('--apply-dhcp', action='store_true')
    parser.add_argument('--request-id')
    args = parser.parse_args(argv); system = BoardSystem()
    for number in (signal.SIGTERM, signal.SIGINT):
        signal.signal(number, lambda _number, _frame: setattr(system, 'cancelled', True))
    try:
        if args.install_discovery: result = install_discovery(system)
        elif args.launch_dhcp: result = launch_dhcp(system, canonical_id(args.request_id))
        elif args.apply_dhcp: result = apply_dhcp(system, canonical_id(args.request_id))
        else:
            observation = system.observe(); require_original(observation)
            result = dict(ok=True, changed=False, boardId=BOARD_ID, observation=public_observation(observation),
                          changes=['ipv4.method', 'ipv4.addresses', 'ipv4.gateway', 'ipv4.dad-timeout',
                                   '802-11-wireless.mac-address', '802-11-wireless.cloned-mac-address'])
    except Exception as error:
        result = dict(ok=False, changed=False, errorCode=code(error))
    print(json.dumps(result, ensure_ascii=True, separators=(',', ':')), flush=True)
    return 0 if result['ok'] else 1


if __name__ == '__main__': raise SystemExit(main())
