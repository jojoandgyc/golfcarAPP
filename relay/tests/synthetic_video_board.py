#!/usr/bin/env python3
"""Bounded, isolated synthetic WebRTC source; never opens a camera or motor device.

Run on the board as root only after the operator's idle check. Initial launch also
uses the existing read-only control guard. Locks are released once children start,
so an explicitly simulated control session can measure RTT under video load.
The production MediaMTX, videoctl, network and motor configuration are untouched.
--upstream-only temporarily serves RTSP on the idle local producer's port 8554,
allowing the installed remote gateway to be tested without any camera access.
"""
import argparse
from contextlib import ExitStack
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import socket
import stat
import subprocess
import tempfile
import time

MEDIAMTX = Path('/home/topeet/Desktop/video/bin/mediamtx')
CONTROL_GUARD = Path('/home/topeet/lianzhan/scripts/app_control_guard.py')
HTTP_PORT, ICE_PORT, RTSP_PORT = 19889, 18189, 18554
PRODUCTION_RTSP_PORT = 8554


def read_turn_config(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
        raise ValueError('turn_config_requires_private_regular_file')
    if info.st_size > 4096:
        raise ValueError('turn_config_too_large')
    value = json.loads(path.read_text())
    if not isinstance(value, dict) or set(value) != {'host', 'secret'}:
        raise ValueError('invalid_turn_config')
    host, secret = value['host'], value['secret']
    if (not isinstance(host, str) or len(host) > 253 or not re.fullmatch(r'[A-Za-z0-9.-]+', host)
            or '..' in host or not isinstance(secret, str) or not re.fullmatch(r'[A-Za-z0-9_+=/-]{32,256}', secret)):
        raise ValueError('invalid_turn_config')
    return host, secret


def config(host, secret, directory, upstream_only=False):
    result = {
        'logLevel': 'warn', 'logDestinations': ['file'], 'logFile': str(directory / 'mediamtx.log'),
        'readTimeout': '10s', 'writeTimeout': '10s', 'writeQueueSize': 128,
        'authMethod': 'internal', 'authInternalUsers': [{'user': 'any', 'pass': '', 'ips': ['127.0.0.1'],
            'permissions': [{'action': 'publish', 'path': 'rgbd'}, {'action': 'read', 'path': 'rgbd'}]}],
        'api': False, 'metrics': False, 'pprof': False, 'playback': False,
        'rtsp': True, 'rtspTransports': ['tcp'],
        'rtspAddress': f'127.0.0.1:{PRODUCTION_RTSP_PORT if upstream_only else RTSP_PORT}',
        'rtmp': False, 'hls': False, 'srt': False, 'moq': False,
        'webrtc': not upstream_only,
        'paths': {'rgbd': {'source': 'publisher', 'overridePublisher': False}},
    }
    if not upstream_only:
        result.update({
        'webrtcAddress': f'127.0.0.1:{HTTP_PORT}', 'webrtcEncryption': False,
        'webrtcAllowOrigins': [f'https://{host}:8443'], 'webrtcLocalUDPAddress': f':{ICE_PORT}',
        'webrtcLocalTCPAddress': '', 'webrtcIPsFromInterfaces': True,
        'webrtcIPsFromInterfacesList': [], 'webrtcAdditionalHosts': [],
        'webrtcHandshakeTimeout': '10s',
        # Keeping this empty also exercises the pinned binary's indexed env loader.
        'webrtcICEServers2': [],
        })
    return result


def turn_environment(host, secret, upstream_only=False):
    result = dict(os.environ)
    # Do not inherit any other MediaMTX overrides from the test caller.
    for key in list(result):
        if key.startswith('MTX_'):
            del result[key]
    if upstream_only:
        return result
    for index, url in enumerate((f'turn:{host}:3478?transport=udp', f'turn:{host}:3478?transport=tcp',
                                 f'turns:{host}:5349?transport=tcp')):
        prefix = f'MTX_WEBRTCICESERVERS2_{index}_'
        result.update({prefix + 'URL': url, prefix + 'USERNAME': 'AUTH_SECRET',
                       prefix + 'PASSWORD': secret, prefix + 'CLIENTONLY': 'false'})
    return result


def check_ports(upstream_only=False):
    ports = ((PRODUCTION_RTSP_PORT, socket.SOCK_STREAM, '127.0.0.1'),) if upstream_only else (
        (HTTP_PORT, socket.SOCK_STREAM, '127.0.0.1'),
        (RTSP_PORT, socket.SOCK_STREAM, '127.0.0.1'),
        (ICE_PORT, socket.SOCK_DGRAM, '0.0.0.0'))
    with ExitStack() as stack:
        for port, kind, address in ports:
            listener = stack.enter_context(socket.socket(socket.AF_INET, kind))
            listener.bind((address, port))


def require_original_video_idle():
    for unit in ('rgbd-mediamtx.service', 'rgbd-video.service'):
        result = subprocess.run(['systemctl', 'show', '--value', '-p', 'ActiveState', unit],
                                stdin=subprocess.DEVNULL, text=True, capture_output=True, timeout=5)
        if result.returncode or result.stdout.strip() not in ('inactive', 'failed'):
            raise RuntimeError('original_video_service_not_idle')


def private_json(path, value):
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as handle:
        json.dump(value, handle, separators=(',', ':'))
        handle.write('\n')


def emit(event, **fields):
    print(json.dumps(dict(event=event, **fields), separators=(',', ':')), flush=True)


def stop_owned(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=3)


def wait_listener(process, port, stopping):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline and not stopping[0]:
        if process.poll() is not None:
            raise RuntimeError('synthetic_mediamtx_exited_before_ready')
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=.2):
                return
        except OSError:
            time.sleep(.1)
    raise RuntimeError('synthetic_listener_timeout')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--turn-config', type=Path)
    parser.add_argument('--upstream-only', action='store_true',
                        help='Serve only synthetic RTSP:8554 while both original video services are inactive')
    parser.add_argument('--duration', type=int, default=600)
    parser.add_argument('--preflight', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0 or not 10 <= args.duration <= 1800:
        raise ValueError('root_and_bounded_duration_required')
    if args.upstream_only:
        host, secret = None, None
    elif args.turn_config is not None:
        host, secret = read_turn_config(args.turn_config)
    else:
        raise ValueError('turn_config_required_for_webrtc_mode')
    version = subprocess.run([str(MEDIAMTX), '--version'], text=True, capture_output=True, timeout=5, check=True)
    if version.stdout.strip() != 'v1.21.3':
        raise RuntimeError('unexpected_mediamtx_version')
    check_ports(args.upstream_only)
    rtsp_port = PRODUCTION_RTSP_PORT if args.upstream_only else RTSP_PORT
    specification = importlib.util.spec_from_file_location('followingcar_synthetic_guard', CONTROL_GUARD)
    guard_module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(guard_module)
    if args.preflight:
        with guard_module.ControlGuard().idle_guard():
            if args.upstream_only:
                require_original_video_idle()
            emit('preflight_ok', serialOpened=False, cameraOpened=False,
                 httpPort=None if args.upstream_only else HTTP_PORT,
                 icePort=None if args.upstream_only else ICE_PORT, rtspPort=rtsp_port,
                 upstreamOnly=args.upstream_only)
        return 0

    directory = Path(tempfile.mkdtemp(prefix='followingcar-synthetic-video-'))
    os.chmod(directory, 0o700)
    configuration = directory / 'mediamtx.yml'
    private_json(configuration, config(host, secret, directory, args.upstream_only))
    stopping = [False]
    for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(signum, lambda _signum, _frame: stopping.__setitem__(0, True))
    processes = []
    started = time.monotonic()
    try:
        with ExitStack() as files, guard_module.ControlGuard().idle_guard():
            if args.upstream_only:
                require_original_video_idle()
            check_ports(args.upstream_only)
            mediamtx_error = files.enter_context(open(directory / 'mediamtx-start.log', 'wb'))
            publisher_error = files.enter_context(open(directory / 'publisher.log', 'wb'))
            media = subprocess.Popen(['/usr/bin/nice', '-n', '19', str(MEDIAMTX), str(configuration)],
                stdin=subprocess.DEVNULL, stdout=mediamtx_error, stderr=mediamtx_error, start_new_session=True,
                env=turn_environment(host, secret, args.upstream_only))
            processes.append(media)
            wait_listener(media, rtsp_port, stopping)
            if not args.upstream_only:
                wait_listener(media, HTTP_PORT, stopping)
            publisher = subprocess.Popen(['/usr/bin/nice', '-n', '19', '/usr/bin/ffmpeg', '-hide_banner',
                '-loglevel', 'warning', '-nostdin', '-re', '-f', 'lavfi', '-i', 'testsrc2=size=1280x480:rate=20',
                '-vf', 'drawbox=x=639:y=0:w=2:h=480:color=white:t=fill', '-an', '-c:v', 'libx264',
                '-threads', '2', '-preset', 'ultrafast', '-tune', 'zerolatency', '-profile:v', 'baseline',
                '-pix_fmt', 'yuv420p', '-g', '20', '-keyint_min', '20', '-bf', '0', '-b:v', '3M',
                '-maxrate', '3M', '-bufsize', '300k', '-rtsp_transport', 'tcp', '-f', 'rtsp',
                f'rtsp://127.0.0.1:{rtsp_port}/rgbd'], stdin=subprocess.DEVNULL,
                stdout=publisher_error, stderr=publisher_error, start_new_session=True)
            processes.append(publisher)
            private_json(directory / 'processes.json', {'runnerPid': os.getpid(), 'mediamtxPid': media.pid,
                'publisherPid': publisher.pid, 'durationSeconds': args.duration, 'startedEpoch': time.time(),
                'upstreamOnly': args.upstream_only, 'rtspPort': rtsp_port})
            # Child processes own duplicated descriptors; closing parent copies will not close their logs.
        # No motor locks are held during the synthetic media run. Root orchestrates dry-run controls separately.
        emit('synthetic_started', runnerPid=os.getpid(), mediamtxPid=media.pid, publisherPid=publisher.pid,
             directory=str(directory), httpPort=None if args.upstream_only else HTTP_PORT,
             rtspPort=rtsp_port, upstreamOnly=args.upstream_only, serialOpened=False, cameraOpened=False)
        while not stopping[0] and time.monotonic() - started < args.duration:
            if any(process.poll() is not None for process in processes):
                raise RuntimeError('synthetic_child_exited')
            time.sleep(.2)
        return 0
    finally:
        for process in reversed(processes):
            stop_owned(process)
        # Remove only our generated secret-bearing configuration; keep bounded test evidence.
        configuration.unlink(missing_ok=True)
        emit('synthetic_stopped', directory=str(directory), childExitCodes=[p.returncode for p in processes],
             serialOpened=False, cameraOpened=False)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        # Exceptions may reference files; never emit arbitrary config text or secret-bearing subprocess output.
        emit('synthetic_failed', errorType=type(error).__name__)
        raise SystemExit(1)
