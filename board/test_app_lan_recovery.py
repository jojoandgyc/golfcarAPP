"""Offline LAN recovery tests: fake NM/locks, temporary files, no sockets/devices."""
import copy
from contextlib import contextmanager
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import app_lan_recovery as recovery

RID = '12345678-1234-1234-1234-123456789abc'
PROFILE = (f'[connection]\nid=original wifi\nuuid={recovery.PROFILE_UUID}\ntype=wifi\ntimestamp=12\n'
           '[wifi]\nssid=private wireless\nmode=infrastructure\n'
           '[wifi-security]\nkey-mgmt=wpa-psk\npsk=FAKE_TEST_SECRET\n'
           '[ipv4]\nmethod=manual\naddress1=172.16.16.82/22,172.16.16.1\ndns=1.1.1.1;\n'
           'ignore-auto-dns=true\nroute-metric=600\n'
           '[ipv6]\nmethod=auto\naddr-gen-mode=stable-privacy\n').encode()


def original():
    return dict(sshFingerprint=recovery.SSH_FINGERPRINT,
        permanentMacSha256=recovery.PERMANENT_MAC_SHA256, permanentMac='fake-only',
        profileUuid=recovery.PROFILE_UUID, state=100, method='manual',
        addresses=['172.16.16.82/22'], ipv6Addresses=['fe80::123/64'], dadTimeout=-1,
        leaseAddress='', dhcpServer='', filename='/private/profile.nmconnection')


class FakeSystem:
    def __init__(self):
        self.current = original()
        self.calls, self.records = [], []
        self.now = 0
        self.locked = False
        self.busy = False
        self.restore_fails = False
        self.commit_record_fails = False
        self.modified = False
        self.activation_throws = False
        self.lease_address = '172.16.16.143'
        self.cancelled = False
        self.external_profile = False
        self.unrelated_changed = False
        self.original_changed = False

    def monotonic(self): return self.now
    def sleep(self, seconds): self.now += seconds
    def require_root(self): self.calls.append('root')
    def check_cancelled(self):
        if self.cancelled: raise recovery.RecoveryError('recovery_cancelled')
    @contextmanager
    def transaction(self, ident):
        self.calls.append('transaction')
        yield
    @contextmanager
    def idle_guard(self):
        self.calls.append('guard')
        if self.busy: raise recovery.RecoveryError('control_busy_or_unconfirmed')
        self.locked = True
        try: yield
        finally: self.locked = False; self.calls.append('unlock')
    def observe(self):
        self.calls.append('observe')
        return copy.deepcopy(self.current)
    def backup_profile(self, _observation, _ident):
        assert self.locked
        self.calls.append('backup')
        return dict(sha256=recovery.sha(PROFILE))
    def verify_backup_current(self, _backup):
        assert self.locked
        if self.original_changed: raise recovery.RecoveryError('profile_changed_before_apply')
    def save_result(self, _ident, record):
        assert self.locked
        self.records.append(copy.deepcopy(record))
        if self.commit_record_fails and record.get('phase') == 'committed':
            raise OSError('simulated private disk failure')
    def modify_profile(self, _original):
        assert self.locked
        self.calls.append('modify'); self.modified = True
        self.current.update(method='auto', dadTimeout=3000)
    def verify_only_allowed_changes(self, _backup):
        assert self.locked
        if self.unrelated_changed: raise recovery.RecoveryError('unrelated_profile_setting_changed')
    def activate(self, *, reapply_first):
        assert self.locked and reapply_first
        self.calls.append('activate')
        if self.activation_throws: raise recovery.RecoveryError('network_command_timeout')
        if self.external_profile: self.current['profileUuid'] = 'another-profile'
        self.current.update(addresses=[self.lease_address + '/22'], leaseAddress=self.lease_address,
                            dhcpServer='172.16.16.1')
        return 'device_reapply'
    def restore_profile(self, _backup):
        assert self.locked
        self.calls.append('restore')
        if self.restore_fails: raise recovery.RecoveryError('rollback_address_unconfirmed')
        self.current = original()


class TransactionTests(unittest.TestCase):
    def test_good_lease_two_observations_and_locks_cover_every_mutation(self):
        system = FakeSystem(); result = recovery.apply_dhcp(system, RID)
        self.assertTrue(result['ok']); self.assertTrue(result['changed'])
        self.assertTrue(result['ipv6Retained']); self.assertEqual(system.now, .5)
        self.assertEqual(result['after']['leaseAddress'], '172.16.16.143')
        self.assertLess(system.calls.index('guard'), system.calls.index('backup'))
        self.assertLess(system.calls.index('backup'), system.calls.index('modify'))
        self.assertEqual(system.calls[-1], 'unlock'); self.assertNotIn('restore', system.calls)
        self.assertEqual([item['phase'] for item in system.records], ['prepared', 'applying', 'committed'])
    def test_busy_control_never_observes_backups_or_changes_network(self):
        system = FakeSystem(); system.busy = True
        result = recovery.apply_dhcp(system, RID)
        self.assertFalse(result['ok']); self.assertFalse(result['changed'])
        self.assertEqual(system.calls, ['root', 'transaction', 'guard'])
    def test_wrong_identity_profile_or_existing_address_refuses_before_backup(self):
        for key, value in (('sshFingerprint', 'other-key'), ('permanentMacSha256', 'other-mac'),
                           ('profileUuid', 'other'), ('method', 'auto'), ('state', 40),
                           ('addresses', ['172.16.16.99/22'])):
            with self.subTest(key=key):
                system = FakeSystem(); system.current[key] = value
                result = recovery.apply_dhcp(system, RID)
                self.assertFalse(result['ok']); self.assertFalse(result['changed'])
                self.assertNotIn('backup', system.calls); self.assertNotIn('modify', system.calls)
    def test_conflicting_dhcp_address_rolls_back_without_claiming_success(self):
        system = FakeSystem(); system.lease_address = recovery.CONFLICT_ADDRESS
        result = recovery.apply_dhcp(system, RID)
        self.assertFalse(result['ok']); self.assertEqual(result['errorCode'], 'dhcp_lease_unconfirmed')
        self.assertEqual(result['rollback'], 'confirmed'); self.assertEqual(system.now, 45)
        self.assertEqual(system.current, original()); self.assertEqual(system.calls[-2:], ['observe', 'unlock'])
    def test_failed_activation_may_have_changed_and_restores_only_selected_profile(self):
        system = FakeSystem(); system.activation_throws = True
        result = recovery.apply_dhcp(system, RID)
        self.assertFalse(result['ok']); self.assertTrue(result['changed'])
        self.assertEqual(result['rollback'], 'confirmed'); self.assertEqual(system.calls.count('modify'), 1)
        self.assertEqual(system.calls.count('restore'), 1)
    def test_failed_rollback_explicitly_unconfirmed(self):
        system = FakeSystem(); system.activation_throws = True; system.restore_fails = True
        result = recovery.apply_dhcp(system, RID)
        self.assertFalse(result['ok']); self.assertEqual(result['rollback'], 'unconfirmed')
        self.assertEqual(result['rollbackErrorCode'], 'rollback_address_unconfirmed')
    def test_cancel_after_modify_rolls_back(self):
        system = FakeSystem(); system.cancelled = True
        result = recovery.apply_dhcp(system, RID)
        self.assertEqual(result['errorCode'], 'recovery_cancelled'); self.assertEqual(result['rollback'], 'confirmed')
    def test_concurrent_profile_change_before_modify_has_no_writes(self):
        system = FakeSystem(); system.original_changed = True
        result = recovery.apply_dhcp(system, RID)
        self.assertFalse(result['changed']); self.assertNotIn('modify', system.calls); self.assertNotIn('restore', system.calls)
    def test_unrelated_modified_settings_cannot_commit(self):
        system = FakeSystem(); system.unrelated_changed = True
        result = recovery.apply_dhcp(system, RID)
        self.assertEqual(result['errorCode'], 'unrelated_profile_setting_changed')
        self.assertFalse(result['ok']); self.assertNotIn('activate', system.calls)
    def test_commit_log_failure_is_failure_and_rolls_back(self):
        system = FakeSystem(); system.commit_record_fails = True
        result = recovery.apply_dhcp(system, RID)
        self.assertFalse(result['ok']); self.assertEqual(result['rollback'], 'confirmed')
        self.assertEqual(system.current, original())
    def test_external_profile_detected_and_reported(self):
        system = FakeSystem(); system.external_profile = True
        result = recovery.apply_dhcp(system, RID)
        self.assertEqual(result['errorCode'], 'external_profile_activated'); self.assertFalse(result['ok'])
    def test_summary_cannot_expose_credentials_or_raw_mac(self):
        system = FakeSystem(); system.current.update(psk='FAKE_TEST_SECRET', ssid='secret ssid')
        result = recovery.apply_dhcp(system, RID)
        serialized = json.dumps(result)
        for secret in ('FAKE_TEST_SECRET', 'secret ssid', 'fake-only', 'permanentMac', 'filename'):
            self.assertNotIn(secret, serialized)
    def test_malformed_id_rejected_without_any_system_call(self):
        system = FakeSystem()
        with self.assertRaises(recovery.RecoveryError): recovery.apply_dhcp(system, '../bad')
        self.assertEqual(system.calls, [])


class ValidationTests(unittest.TestCase):
    def test_lease_must_be_dhcp_nonconflicting_valid_and_bound_to_target(self):
        current = original(); current.update(method='auto', dadTimeout=3000,
            addresses=['172.16.16.143/22'], leaseAddress='172.16.16.143', dhcpServer='172.16.16.1')
        self.assertTrue(recovery.good_lease(current))
        for changes in (dict(leaseAddress=''), dict(dhcpServer=''), dict(leaseAddress='169.254.1.2'),
                        dict(addresses=['172.16.16.143/22', '172.16.16.82/22']), dict(dadTimeout=-1),
                        dict(profileUuid='other'), dict(addresses=['172.16.16.200/22']),
                        dict(addresses=['172.16.16.0/22'], leaseAddress='172.16.16.0')):
            with self.subTest(changes=changes): self.assertFalse(recovery.good_lease(dict(current, **changes)))
    def test_only_explicit_address_dad_mac_fields_may_change_dns_and_psk_preserved(self):
        updated = PROFILE.replace(b'method=manual', b'method=auto').replace(b'address1=172.16.16.82/22,172.16.16.1\n', b'dad-timeout=3000\n')
        self.assertTrue(recovery.unchanged_profile_values(PROFILE, updated))
        for old, new in ((b'FAKE_TEST_SECRET', b'changed'), (b'private wireless', b'changed'),
                         (b'dns=1.1.1.1;', b'dns=8.8.8.8;'), (b'route-metric=600', b'route-metric=1'),
                         (b'addr-gen-mode=stable-privacy', b'addr-gen-mode=eui64')):
            with self.subTest(old=old): self.assertFalse(recovery.unchanged_profile_values(PROFILE, updated.replace(old, new)))
    def test_mutation_argv_only_one_profile_and_no_service_or_radio_restart(self):
        system = recovery.BoardSystem(); commands = []
        system.run = lambda arguments, *_args, **_kwargs: commands.append(arguments)
        system.modify_profile(original())
        self.assertEqual(len(commands), 1)
        command = commands[0]; self.assertEqual(command[3:7], ['connection', 'modify', 'uuid', recovery.PROFILE_UUID])
        for forbidden in ('dns', 'psk', 'ssid', 'down', 'radio', 'restart'):
            self.assertFalse(any(forbidden in item for item in command))
    def test_reapply_then_only_own_profile_reconnect(self):
        from types import SimpleNamespace
        system = recovery.BoardSystem(); commands = []
        def run(arguments, *_args, **_kwargs):
            commands.append(arguments)
            return SimpleNamespace(returncode=1 if 'reapply' in arguments else 0)
        system.run = run
        self.assertEqual(system.activate(reapply_first=True), 'same_profile_reconnect')
        self.assertEqual(commands[0][3:], ['device', 'reapply', 'wlan0'])
        self.assertEqual(commands[1][3:], ['connection', 'up', 'uuid', recovery.PROFILE_UUID, 'ifname', 'wlan0'])


class DiscoveryTests(unittest.TestCase):
    def test_xml_has_one_public_authenticated_candidate_with_dual_stack(self):
        root = ET.fromstring(recovery.AVAHI_XML)
        self.assertEqual(root.findtext('name'), recovery.BOARD_ID)
        service, = root.findall('service')
        self.assertEqual(service.attrib, {'protocol': 'any'})
        self.assertEqual(service.findtext('type'), '_followingcar._tcp'); self.assertEqual(service.findtext('port'), '22')
        self.assertEqual(set(node.text for node in service.findall('txt-record')),
                         {'v=1', 'id=' + recovery.BOARD_ID, 'sshfp=' + recovery.SSH_FINGERPRINT})
    def test_install_only_one_xml_reload_and_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / recovery.AVAHI_FILE.name
            other = Path(directory) / 'other.service'; other.write_bytes(b'unrelated')
            system = FakeSystem(); system.run = lambda argv, *_args, **_kwargs: system.calls.append(argv)
            with patch.object(recovery, 'AVAHI_FILE', target), patch.object(recovery, 'safe_regular'):
                result = recovery.install_discovery(system)
                self.assertTrue(result['changed']); self.assertEqual(target.read_bytes(), recovery.AVAHI_XML)
                self.assertEqual(system.calls[-1], ['/usr/bin/systemctl', 'reload', 'avahi-daemon.service'])
                self.assertFalse(recovery.install_discovery(system)['changed'])
            self.assertEqual(other.read_bytes(), b'unrelated')
    def test_avahi_reload_failure_removes_only_our_new_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / recovery.AVAHI_FILE.name
            system = FakeSystem(); calls = []
            def run(argv, *_args, **kwargs):
                calls.append(argv)
                if not kwargs.get('tolerate'): raise recovery.RecoveryError('network_command_failed')
            system.run = run
            with patch.object(recovery, 'AVAHI_FILE', target), self.assertRaises(recovery.RecoveryError):
                recovery.install_discovery(system)
            self.assertFalse(target.exists()); self.assertEqual(len(calls), 2)
    def test_unknown_existing_discovery_file_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / recovery.AVAHI_FILE.name; target.write_bytes(b'unrelated')
            with patch.object(recovery, 'AVAHI_FILE', target), patch.object(recovery, 'safe_regular'), \
                    self.assertRaisesRegex(recovery.RecoveryError, 'already_exists'):
                recovery.install_discovery(FakeSystem())
            self.assertEqual(target.read_bytes(), b'unrelated')
    def test_default_plan_has_no_locks_no_writes_no_secrets(self):
        system = FakeSystem(); output = io.StringIO()
        with patch.object(recovery, 'BoardSystem', return_value=system), patch('sys.stdout', output), patch.object(recovery.signal, 'signal'):
            self.assertEqual(recovery.main([]), 0)
        self.assertEqual(system.calls, ['observe']); self.assertFalse(json.loads(output.getvalue())['changed'])
        self.assertNotIn('fake-only', output.getvalue())


class StorageAndLaunchTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.nm = self.directory / 'profiles'; self.nm.mkdir(mode=0o700)
        self.profile = self.nm / 'own.nmconnection'; self.profile.write_bytes(PROFILE); self.profile.chmod(0o600)
        self.state = self.directory / 'state'
        for patcher in (patch.object(recovery, 'STATE_DIRECTORY', self.state), patch.object(recovery, 'NM_DIRECTORY', self.nm)):
            patcher.start(); self.addCleanup(patcher.stop)
        self.system = recovery.BoardSystem()
        self.updated = PROFILE.replace(b'method=manual', b'method=auto').replace(
            b'address1=172.16.16.82/22,172.16.16.1\n', b'dad-timeout=3000\n')
    def backup(self): return self.system.backup_profile(dict(filename=str(self.profile)), RID)
    def test_backup_protected_atomic_and_only_owner_profile_is_restored(self):
        other = self.nm / 'unrelated.nmconnection'; other.write_bytes(b'OTHER_PRIVATE_PROFILE')
        with self.system.transaction(RID):
            backup = self.backup()
            self.assertEqual(Path(backup['backup']).read_bytes(), PROFILE)
            self.assertEqual(os.stat(self.state).st_mode & 0o777, 0o700)
            self.assertEqual(os.stat(Path(backup['backup'])).st_mode & 0o777, 0o600)
            self.profile.write_bytes(self.updated)
            calls = []
            self.system.observe = original
            def run(arguments, *_args, **_kwargs):
                from types import SimpleNamespace
                calls.append(arguments); return SimpleNamespace(returncode=0)
            self.system.run = run
            self.system.restore_profile(backup)
            self.assertEqual(self.profile.read_bytes(), PROFILE)
            self.assertEqual(calls[0], ['/usr/bin/nmcli', 'connection', 'load', str(self.profile)])
            self.assertEqual(calls[1][3:], ['device', 'reapply', recovery.INTERFACE])
        self.assertEqual(other.read_bytes(), b'OTHER_PRIVATE_PROFILE')
    def test_external_profile_is_not_disconnected_during_rollback(self):
        with self.system.transaction(RID):
            backup = self.backup(); self.profile.write_bytes(self.updated); calls = []
            self.system.observe = lambda: dict(original(), profileUuid='external-profile')
            self.system.run = lambda arguments, *_args, **_kwargs: calls.append(arguments)
            with self.assertRaisesRegex(recovery.RecoveryError, 'external_profile_kept_active'):
                self.system.restore_profile(backup)
            self.assertEqual(self.profile.read_bytes(), PROFILE)
            self.assertEqual(calls, [['/usr/bin/nmcli', 'connection', 'load', str(self.profile)]])
    def test_concurrent_unrelated_change_is_not_overwritten(self):
        with self.system.transaction(RID):
            backup = self.backup(); edited = self.updated.replace(b'FAKE_TEST_SECRET', b'EXTERNAL_UPDATED_SECRET')
            self.profile.write_bytes(edited)
            with self.assertRaisesRegex(recovery.RecoveryError, 'unrelated_profile_setting_changed'):
                self.system.restore_profile(backup)
            self.assertEqual(self.profile.read_bytes(), edited)
    def test_backup_tamper_refuses_rollback(self):
        with self.system.transaction(RID):
            backup = self.backup(); Path(backup['backup']).write_bytes(b'tampered')
            with self.assertRaisesRegex(recovery.RecoveryError, 'backup_hash_mismatch'):
                self.system.restore_profile(backup)
            self.assertEqual(self.profile.read_bytes(), PROFILE)
    def test_profile_symlink_and_outside_file_cannot_be_backed_up(self):
        with self.system.transaction(RID):
            link = self.nm / 'linked.nmconnection'; link.symlink_to(self.profile)
            for path in (link, self.directory / 'outside.nmconnection'):
                with self.subTest(path=path), self.assertRaises(recovery.RecoveryError):
                    self.system.backup_profile(dict(filename=str(path)), RID)
    def test_unresolved_prior_transaction_blocks_new_changes(self):
        with self.system.transaction(RID):
            self.system.save_result(RID, dict(phase='applying', rollback='not_needed'))
        with self.assertRaisesRegex(recovery.RecoveryError, 'previous_recovery_unconfirmed'):
            with recovery.BoardSystem().transaction('12345678-1234-1234-1234-123456789abd'): self.fail('accepted')
    def test_transaction_lock_prevents_two_workers(self):
        with self.system.transaction(RID):
            with self.assertRaisesRegex(recovery.RecoveryError, 'network_recovery_busy'):
                with recovery.BoardSystem().transaction('12345678-1234-1234-1234-123456789abd'): self.fail('accepted')
    def test_detached_launch_contains_no_wait_or_service_restart(self):
        installed = self.directory / 'app_lan_recovery.py'; installed.write_bytes(b'fake helper')
        system = FakeSystem(); commands = []
        system.run = lambda arguments, *_args, **_kwargs: commands.append(arguments)
        with patch.object(recovery, 'HELPER_PATH', installed), patch.object(recovery, '__file__', str(installed)):
            result = recovery.launch_dhcp(system, RID)
        self.assertTrue(result['accepted']); self.assertEqual(len(commands), 1)
        command = commands[0]; self.assertEqual(command[0], '/usr/bin/systemd-run')
        self.assertNotIn('--wait', command); self.assertNotIn('restart', command)
        self.assertEqual(command[-3:], ['--apply-dhcp', '--request-id', RID])
        self.assertIn('--property=TimeoutStartSec=240', command)


if __name__ == '__main__': unittest.main()
