import importlib.util
from pathlib import Path
import sys
import unittest

RELAY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RELAY))
from configuration import RelaySettings

SPEC = importlib.util.spec_from_file_location('install_video_gateway', RELAY / 'install_video_gateway.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RemoteVideoGatewayIsolationTest(unittest.TestCase):
    def test_gateway_only_pulls_encoded_loopback_stream_on_demand(self):
        configuration = MODULE.gateway_config(RelaySettings(), 'a' * 64)
        self.assertEqual(configuration['webrtcAddress'], '127.0.0.1:19889')
        self.assertEqual(configuration['webrtcLocalUDPAddress'], ':18189')
        self.assertEqual(set(configuration['paths']), {'rgbd'})
        source = configuration['paths']['rgbd']
        self.assertEqual(source['source'], 'rtsp://127.0.0.1:8554/rgbd')
        self.assertTrue(source['sourceOnDemand'])
        self.assertEqual(source['rtspTransport'], 'tcp')
        for value in ('api', 'rtsp', 'hls', 'srt', 'rtmp', 'moq', 'playback', 'pprof', 'metrics'):
            self.assertFalse(configuration[value])
        self.assertEqual(configuration['authInternalUsers'][0]['ips'], ['127.0.0.1'])
        self.assertEqual(configuration['authInternalUsers'][0]['permissions'], [{'action': 'read', 'path': 'rgbd'}])

    def test_remote_turn_configuration_does_not_allow_client_only_board_failure(self):
        configuration = MODULE.gateway_config(RelaySettings(), 'a' * 64)
        self.assertEqual(len(configuration['webrtcICEServers2']), 3)
        self.assertTrue(all(server['clientOnly'] is False for server in configuration['webrtcICEServers2']))
        self.assertTrue(all(server['username'] == 'AUTH_SECRET' for server in configuration['webrtcICEServers2']))
        with self.assertRaises(ValueError):
            MODULE.gateway_config(RelaySettings(), 'short')

    def test_service_cannot_open_devices_or_start_the_existing_camera_service(self):
        unit = MODULE.gateway_unit()
        self.assertIn('PrivateDevices=true', unit)
        self.assertIn('ProtectHome=true', unit)
        self.assertIn('User=followingcar-relay', unit)
        self.assertIn('CapabilityBoundingSet=\n', unit)
        self.assertIn('RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK', unit)
        self.assertNotIn('videoctl', unit)
        self.assertNotIn('rgbd-mediamtx.service', unit)
        self.assertNotIn('rgbd-video.service', unit)
        self.assertNotIn('/home/topeet', unit)


if __name__ == '__main__':
    unittest.main()
