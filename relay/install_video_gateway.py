#!/usr/bin/env python3
"""Install an isolated TURN-enabled video gateway without changing local video.

The new unprivileged MediaMTX receives the already encoded local RTSP stream on
demand. It owns neither camera nor motor devices and never starts videoctl.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import socket
import stat

from configuration import CONFIG_ROOT, INSTALL_ROOT, SERVICE_USER, RelaySettings, validate_secret

UNIT_NAME = 'followingcar-video-gateway.service'
SOURCE_BINARY = Path('/home/topeet/Desktop/video/bin/mediamtx')
SOURCE_BINARY_SHA256 = 'a1621ba39c0aa58430809df28263865e37a75bb4d2f1c87e642ddf5f3f67b19d'
BINARY = Path(INSTALL_ROOT) / 'mediamtx/1.21.3/mediamtx'
GATEWAY_CONFIG = Path(CONFIG_ROOT) / 'video-gateway.yml'
ORIGINAL_FILES = (Path('/home/topeet/Desktop/video/config/mediamtx.yml'),
                  Path('/etc/systemd/system/rgbd-mediamtx.service'))


def gateway_config(settings, secret):
    settings.validate()
    validate_secret(secret)
    return {
        'logLevel': 'warn', 'logDestinations': ['stdout'],
        'readTimeout': '10s', 'writeTimeout': '10s', 'writeQueueSize': 128,
        'authMethod': 'internal', 'authInternalUsers': [{'user': 'any', 'pass': '', 'ips': ['127.0.0.1'],
            'permissions': [{'action': 'read', 'path': 'rgbd'}]}],
        'api': False, 'metrics': False, 'pprof': False, 'playback': False,
        'rtsp': False, 'rtmp': False, 'hls': False, 'srt': False, 'moq': False,
        'webrtc': True, 'webrtcAddress': '127.0.0.1:19889', 'webrtcEncryption': False,
        'webrtcAllowOrigins': [f'https://{settings.host}:{settings.video_port}'],
        'webrtcLocalUDPAddress': ':18189', 'webrtcLocalTCPAddress': '',
        'webrtcIPsFromInterfaces': True, 'webrtcIPsFromInterfacesList': [], 'webrtcAdditionalHosts': [],
        'webrtcHandshakeTimeout': '10s',
        'webrtcICEServers2': [{'url': url, 'username': 'AUTH_SECRET', 'password': secret, 'clientOnly': False}
            for url in (f'turn:{settings.host}:{settings.turn_port}?transport=udp',
                        f'turn:{settings.host}:{settings.turn_port}?transport=tcp',
                        f'turns:{settings.host}:{settings.turns_port}?transport=tcp')],
        'paths': {'rgbd': {'source': 'rtsp://127.0.0.1:8554/rgbd', 'rtspTransport': 'tcp',
            'sourceOnDemand': True, 'sourceOnDemandStartTimeout': '10s',
            'sourceOnDemandCloseAfter': '2s', 'maxReaders': 2}},
    }


def gateway_unit():
    return f'''[Unit]
Description=FollowingCar independent remote video gateway
Wants=network-online.target
After=network-online.target
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User={SERVICE_USER}
Group={SERVICE_USER}
ExecStart={BINARY.as_posix()} {GATEWAY_CONFIG.as_posix()}
Restart=on-failure
RestartSec=3s
TimeoutStopSec=10s
UMask=0077
Nice=5
CPUWeight=20
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
# Pion/Go reads interface addresses through a read-only netlink socket during ICE setup.
# No capabilities are granted, so this does not permit changing routes or interfaces.
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
CapabilityBoundingSet=
LockPersonality=true
MemoryMax=256M
StandardOutput=journal
StandardError=journal
SyslogIdentifier=followingcar-video-gateway

[Install]
WantedBy=multi-user.target
'''


def check_ports():
    for address, port, kind in (('127.0.0.1', 19889, socket.SOCK_STREAM), ('0.0.0.0', 18189, socket.SOCK_DGRAM)):
        with socket.socket(socket.AF_INET, kind) as probe:
            probe.bind((address, port))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--activate', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0 or platform.machine() not in ('aarch64', 'arm64'):
        raise RuntimeError('root_on_board_required')
    # Import the POSIX installer only in the deployment path; renderers remain testable on Windows.
    from provision import atomic_write, ensure_account, run
    descriptor = os.open(args.bundle, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_size > 16384:
            raise RuntimeError('private_root_board_bundle_required')
        payload = json.load(stream)
    if (set(payload) != {'schemaVersion', 'settings', 'frpToken', 'turnAuthSecret'}
            or type(payload['schemaVersion']) is not int or payload['schemaVersion'] != 1):
        raise RuntimeError('invalid_board_bundle')
    settings = RelaySettings(**payload['settings'])
    rendered = gateway_config(settings, payload['turnAuthSecret'])
    if run(str(SOURCE_BINARY), '--version').stdout.strip() != 'v1.21.3':
        raise RuntimeError('unexpected_mediamtx_version')
    state = run('systemctl', 'show', '--value', '-p', 'ActiveState', UNIT_NAME).stdout.strip()
    if state not in ('inactive', 'failed'):
        raise RuntimeError('remote_video_gateway_must_be_stopped_before_update')
    before = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in ORIGINAL_FILES}
    spec = importlib.util.spec_from_file_location('followingcar_gateway_guard',
        '/home/topeet/lianzhan/scripts/app_control_guard.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with module.ControlGuard().idle_guard():
        check_ports()  # Also rejects an isolated synthetic source which has not been cleaned up.
        ensure_account()
        # A separate root-owned copy allows ProtectHome and avoids trusting a mutable home-directory executable.
        binary = SOURCE_BINARY.read_bytes()
        if hashlib.sha256(binary).hexdigest() != SOURCE_BINARY_SHA256:
            raise RuntimeError('pinned_board_mediamtx_digest_changed')
        atomic_write(BINARY, binary, mode=0o755, service_group=False)
        atomic_write(GATEWAY_CONFIG, json.dumps(rendered, separators=(',', ':')) + '\n')
        atomic_write(Path('/etc/systemd/system') / UNIT_NAME, gateway_unit(), mode=0o644, service_group=False)
        run('systemd-analyze', 'verify', str(Path('/etc/systemd/system') / UNIT_NAME))
        run('systemctl', 'daemon-reload')
        after = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in ORIGINAL_FILES}
        if before != after:
            raise RuntimeError('original_video_files_changed_during_install')
        if args.activate:
            run('systemctl', 'enable', '--now', UNIT_NAME)
            run('systemctl', 'is-active', '--quiet', UNIT_NAME)
        receipt = {'installed': True, 'active': args.activate, 'originalFilesSha256': after,
            'gatewayBinarySha256': hashlib.sha256(binary).hexdigest(), 'cameraAccess': False,
            'motorAccess': False, 'localVideoModified': False, 'sourceOnDemand': True}
        atomic_write(Path(CONFIG_ROOT) / 'video-gateway-deployment.json', json.dumps(receipt) + '\n',
                     mode=0o600, service_group=False)
    print(json.dumps(receipt, separators=(',', ':')))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Do not emit JSON config, TURN secret, arbitrary subprocess stderr or supplied bundle text.
        print(json.dumps({'errorType': type(error).__name__, 'cameraAccess': False, 'motorAccess': False}))
        raise SystemExit(1)
