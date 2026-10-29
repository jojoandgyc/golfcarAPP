import importlib.util
from pathlib import Path
import stat
import subprocess
import unittest
from unittest.mock import MagicMock, Mock, patch

SPEC = importlib.util.spec_from_file_location('synthetic_video_board', Path(__file__).with_name('synthetic_video_board.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SyntheticVideoIsolationTest(unittest.TestCase):
    def test_secret_configuration_is_private_and_has_no_extra_fields(self):
        path = Mock()
        path.lstat.return_value = Mock(st_mode=stat.S_IFREG | 0o600, st_size=100)
        path.read_text.return_value = '{"host":"car.nujabes.top","secret":"' + 'a' * 48 + '"}'
        self.assertEqual(MODULE.read_turn_config(path), ('car.nujabes.top', 'a' * 48))
        path.lstat.return_value.st_mode = stat.S_IFREG | 0o644
        with self.assertRaises(ValueError):
            MODULE.read_turn_config(path)
        path.lstat.return_value.st_mode = stat.S_IFLNK | 0o600
        with self.assertRaises(ValueError):
            MODULE.read_turn_config(path)

    def test_isolated_media_config_never_uses_production_ports_or_writable_admin_api(self):
        configuration = MODULE.config('car.nujabes.top', 'a' * 48, Path('/tmp/test-only'))
        self.assertEqual(configuration['webrtcAddress'], '127.0.0.1:19889')
        self.assertEqual(configuration['rtspAddress'], '127.0.0.1:18554')
        self.assertEqual(configuration['webrtcLocalUDPAddress'], ':18189')
        self.assertFalse(configuration['api'])
        self.assertEqual(configuration['authInternalUsers'][0]['ips'], ['127.0.0.1'])
        self.assertEqual(configuration['webrtcICEServers2'], [])
        environment = MODULE.turn_environment('car.nujabes.top', 'a' * 64)
        self.assertEqual(environment['MTX_WEBRTCICESERVERS2_0_URL'], 'turn:car.nujabes.top:3478?transport=udp')
        self.assertTrue(all(environment[f'MTX_WEBRTCICESERVERS2_{index}_CLIENTONLY'] == 'false' for index in range(3)))
        self.assertEqual(set(configuration['paths']), {'rgbd'})

    def test_cleanup_does_not_signal_a_process_that_already_exited(self):
        process = Mock()
        process.poll.return_value = 0
        MODULE.stop_owned(process)
        process.terminate.assert_not_called()
        process.kill.assert_not_called()

    def test_cleanup_waits_for_its_owned_handle_then_escalates_only_that_handle(self):
        process = Mock()
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired('synthetic', 5), 0]
        MODULE.stop_owned(process)
        process.terminate.assert_called_once_with()
        process.kill.assert_called_once_with()
        self.assertEqual(process.wait.call_count, 2)

    def test_upstream_only_config_has_no_http_ice_or_secret_and_uses_exact_idle_producer_endpoint(self):
        configuration = MODULE.config(None, None, Path('/tmp/test-only'), upstream_only=True)
        self.assertFalse(configuration['webrtc'])
        self.assertNotIn('webrtcAddress', configuration)
        self.assertNotIn('webrtcLocalUDPAddress', configuration)
        self.assertNotIn('webrtcICEServers2', configuration)
        self.assertEqual(configuration['rtspAddress'], '127.0.0.1:8554')
        with patch.dict(MODULE.os.environ, {'MTX_WEBRTC': 'yes', 'MTX_WEBRTCADDRESS': ':9999'}):
            environment = MODULE.turn_environment(None, None, upstream_only=True)
        self.assertFalse(any(key.startswith('MTX_') for key in environment))

    def test_upstream_only_probes_no_port_owned_by_remote_gateway(self):
        socket = MagicMock()
        with patch.object(MODULE.socket, 'socket', return_value=socket) as factory:
            MODULE.check_ports(upstream_only=True)
        self.assertEqual(factory.call_count, 1)
        socket.__enter__.return_value.bind.assert_called_once_with(('127.0.0.1', 8554))

    def test_upstream_only_refuses_active_original_video_and_never_stops_it(self):
        with patch.object(MODULE.subprocess, 'run', return_value=Mock(returncode=0, stdout='active\n')) as run:
            with self.assertRaises(RuntimeError):
                MODULE.require_original_video_idle()
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0], ['systemctl', 'show', '--value', '-p', 'ActiveState', 'rgbd-mediamtx.service'])

    def test_upstream_only_checks_both_original_services_even_when_media_is_inactive(self):
        with patch.object(MODULE.subprocess, 'run', side_effect=[Mock(returncode=0, stdout='inactive\n'),
                Mock(returncode=0, stdout='activating\n')]) as run:
            with self.assertRaises(RuntimeError):
                MODULE.require_original_video_idle()
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args.args[0][-1], 'rgbd-video.service')


if __name__ == '__main__':
    unittest.main()
