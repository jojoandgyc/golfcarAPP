"""Render a second, independent board relay for the domestic server.

The generated services only make outbound network connections and read the
existing local SSH and encoded RTSP endpoints. They never invoke car control.
"""

from __future__ import annotations

import ipaddress
import hashlib
import hmac
import json
import re


HOST = "world-agents.com"
PUBLIC_IP = "117.50.34.249"
CONFIG = "/etc/followingcar-cn"
BIN = "/opt/followingcar-cn/bin"
ACCOUNT = "followingcar-cn"
FRPC_UNIT = "followingcar-cn-frpc.service"
GATEWAY_UNIT = "followingcar-cn-video-gateway.service"


def validate_bundle(payload: object) -> dict:
    if not isinstance(payload, dict) or set(payload) != {
            "schemaVersion", "settings", "frpToken", "turnAuthSecret"}:
        raise ValueError("invalid_board_bundle")
    if type(payload["schemaVersion"]) is not int or payload["schemaVersion"] != 1:
        raise ValueError("invalid_board_bundle_version")
    settings = payload["settings"]
    if not isinstance(settings, dict):
        raise ValueError("invalid_board_settings")
    expected = {"host": HOST, "public_ip": PUBLIC_IP,
                "control_port": 2443, "video_port": 9443,
                "transport_port": 7443, "turn_port": 3478,
                "turns_port": 5349, "relay_min": 62000,
                "relay_max": 62099}
    if any(settings.get(k) != v for k, v in expected.items()):
        raise ValueError("unexpected_domestic_endpoint")
    try:
        ipaddress.IPv4Address(settings["local_ip"])
    except (KeyError, ValueError, TypeError) as error:
        raise ValueError("invalid_local_ip") from error
    for key in ("frpToken", "turnAuthSecret"):
        if not isinstance(payload[key], str) or not re.fullmatch(r"[0-9a-f]{64}", payload[key]):
            raise ValueError("invalid_board_secret")
    return payload


def frpc_config(payload: dict, transport: str = "quic") -> str:
    validate_bundle(payload)
    if transport not in ("quic", "tcp"):
        raise ValueError("invalid_transport")
    return f'''serverAddr = "{HOST}"
serverPort = 7443
user = "followingcar-cn"
clientID = "board-cn-001"
loginFailExit = false

auth.method = "token"
auth.additionalScopes = ["HeartBeats", "NewWorkConns"]
auth.tokenSource.type = "file"
auth.tokenSource.file.path = "{CONFIG}/frp-token"

transport.protocol = "{transport}"
transport.tls.enable = true
transport.tls.trustedCaFile = "/etc/ssl/certs/ca-certificates.crt"
transport.tls.serverName = "{HOST}"
transport.poolCount = 2
transport.quic.keepalivePeriod = 10
transport.quic.maxIdleTimeout = 30

log.to = "console"
log.level = "warn"

webServer.addr = "127.0.0.1"
webServer.port = 17401
webServer.user = "followingcar-cn"
webServer.password = "{admin_password(payload['frpToken'])}"
webServer.pprofEnable = false

[[proxies]]
name = "board-cn-ssh"
type = "tcp"
localIP = "127.0.0.1"
localPort = 22
remotePort = 22022
transport.useCompression = false

[[proxies]]
name = "board-cn-whep"
type = "tcp"
localIP = "127.0.0.1"
localPort = 19890
remotePort = 18889
transport.useCompression = false
'''


def admin_password(token: str) -> str:
    if not isinstance(token, str) or not re.fullmatch(r"[0-9a-f]{64}", token):
        raise ValueError("invalid_frp_token")
    return hmac.new(bytes.fromhex(token),
                    b"followingcar/frpc-local-admin/v1", hashlib.sha256).hexdigest()


def legacy_direct_frpc_config(payload: dict, transport: str) -> str:
    """Exact pre-supervisor config, for a guarded one-time upgrade check."""
    text = frpc_config(payload, transport)
    admin = f'''webServer.addr = "127.0.0.1"
webServer.port = 17401
webServer.user = "followingcar-cn"
webServer.password = "{admin_password(payload['frpToken'])}"
webServer.pprofEnable = false

'''
    if text.count(admin) != 1:
        raise ValueError("unexpected_admin_config")
    return text.replace(admin, "")


def gateway_config(payload: dict) -> str:
    validate_bundle(payload)
    secret = payload["turnAuthSecret"]
    config = {
        "logLevel": "warn", "logDestinations": ["stdout"],
        "readTimeout": "10s", "writeTimeout": "10s", "writeQueueSize": 128,
        "authMethod": "internal",
        "authInternalUsers": [{"user": "any", "pass": "", "ips": ["127.0.0.1"],
            "permissions": [{"action": "read", "path": "rgbd"}]}],
        "api": False, "metrics": False, "pprof": False, "playback": False,
        "rtsp": False, "rtmp": False, "hls": False, "srt": False, "moq": False,
        "webrtc": True, "webrtcAddress": "127.0.0.1:19890",
        "webrtcEncryption": False,
        "webrtcAllowOrigins": [f"https://{HOST}:9443"],
        "webrtcLocalUDPAddress": ":18190", "webrtcLocalTCPAddress": "",
        "webrtcIPsFromInterfaces": True, "webrtcIPsFromInterfacesList": [],
        "webrtcAdditionalHosts": [], "webrtcHandshakeTimeout": "10s",
        "webrtcICEServers2": [
            {"url": url, "username": "AUTH_SECRET", "password": secret, "clientOnly": False}
            for url in (f"turn:{HOST}:3478?transport=udp",
                        f"turn:{HOST}:3478?transport=tcp",
                        f"turns:{HOST}:5349?transport=tcp")],
        "paths": {"rgbd": {
            "source": "rtsp://127.0.0.1:8554/rgbd", "rtspTransport": "tcp",
            "sourceOnDemand": True, "sourceOnDemandStartTimeout": "10s",
            "sourceOnDemandCloseAfter": "2s", "maxReaders": 2}},
    }
    return json.dumps(config, separators=(",", ":")) + "\n"


def systemd_unit(kind: str) -> str:
    if kind not in ("frpc", "video-gateway"):
        raise ValueError("invalid_unit")
    command = (f"/usr/bin/python3 -B /opt/followingcar-cn/tools/transport_supervisor.py" if kind == "frpc"
               else f"{BIN}/mediamtx {CONFIG}/video-gateway.json")
    families = "AF_INET AF_INET6 AF_UNIX" + (" AF_NETLINK" if kind == "video-gateway" else "")
    return f'''[Unit]
Description=FollowingCar domestic independent {kind}
Wants=network-online.target
After=network-online.target
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User={ACCOUNT}
Group={ACCOUNT}
ExecStart={command}
Restart=on-failure
RestartSec=3s
TimeoutStopSec=10s
UMask=0077
Nice=5
CPUWeight=20
MemoryMax=256M
{'StateDirectory=followingcar-cn' if kind == 'frpc' else ''}
{'StateDirectoryMode=0750' if kind == 'frpc' else ''}
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies={families}
CapabilityBoundingSet=
LockPersonality=true
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
'''


def legacy_direct_frpc_unit() -> str:
    """Exact direct frpc unit from the first domestic deployment."""
    text = systemd_unit("frpc")
    text = text.replace("ExecStart=/usr/bin/python3 -B /opt/followingcar-cn/tools/transport_supervisor.py",
                        f"ExecStart={BIN}/frpc -c {CONFIG}/frpc-quic.toml")
    return text.replace("StateDirectory=followingcar-cn\nStateDirectoryMode=0750\n", "")
