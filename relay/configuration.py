"""Pure, validated configuration rendering for the independent car relay.

No credentials live in this module. The production services only expose
authenticated endpoints; the two frp backend listeners remain loopback-only.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import ipaddress
import re


FRP_VERSION = "0.71.1"
FRP_SHA256 = {
    "amd64": "1d9875210972a5d1a62b2dc4c48df45ab0af05d3ca1b5e494a014428259df803",
    "arm64": "8edf590b0c396fa42f9c147121a76af83cfc2002b08f5aef0ea1008c43482019",
}
SERVICE_USER = "followingcar-relay"
CONFIG_ROOT = "/etc/followingcar-relay"
INSTALL_ROOT = "/opt/followingcar-relay"


@dataclass(frozen=True)
class RelaySettings:
    host: str = "car.nujabes.top"
    public_ip: str = "43.162.101.137"
    local_ip: str = "10.4.0.16"
    control_port: int = 2443
    video_port: int = 8443
    transport_port: int = 7443
    turn_port: int = 3478
    turns_port: int = 5349
    relay_min: int = 62000
    relay_max: int = 62099

    def validate(self) -> None:
        if not re.fullmatch(r"(?=.{1,253}\Z)[a-z0-9]+(?:[.-][a-z0-9]+)*", self.host):
            raise ValueError("Invalid relay DNS name")
        for value in (self.public_ip, self.local_ip):
            ipaddress.IPv4Address(value)
        ports = [self.control_port, self.video_port, self.transport_port,
                 self.turn_port, self.turns_port, 22022, 18889]
        if any(type(p) is not int or not 1024 <= p <= 65535 for p in ports):
            raise ValueError("Invalid relay port")
        if len(set(ports)) != len(ports):
            raise ValueError("Relay listener ports overlap")
        if not 1024 <= self.relay_min <= self.relay_max <= 65535:
            raise ValueError("Invalid TURN relay range")
        if any(self.relay_min <= p <= self.relay_max for p in ports):
            raise ValueError("TURN relay range overlaps a listener")


def validate_secret(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Expected a dedicated 256-bit hexadecimal secret")
    return value


def frps_config(settings: RelaySettings) -> str:
    settings.validate()
    return f'''# Dedicated instance; no VPN, route, firewall, or existing service changes.
bindAddr = "0.0.0.0"
bindPort = {settings.transport_port}
quicBindPort = {settings.transport_port}
proxyBindAddr = "127.0.0.1"
allowPorts = [{{ single = 22022 }}, {{ single = 18889 }}]
maxPortsPerClient = 2
detailedErrorsToClient = false
auth.method = "token"
auth.additionalScopes = ["HeartBeats", "NewWorkConns"]
auth.tokenSource.type = "file"
auth.tokenSource.file.path = "{CONFIG_ROOT}/frp-token"
transport.tls.force = true
transport.tls.certFile = "{CONFIG_ROOT}/tls/server.crt"
transport.tls.keyFile = "{CONFIG_ROOT}/tls/server.key"
transport.maxPoolCount = 2
transport.quic.keepalivePeriod = 10
transport.quic.maxIdleTimeout = 30
log.to = "console"
log.level = "warn"
log.disablePrintColor = true
'''


def admin_password(token: str) -> str:
    validate_secret(token)
    return hmac.new(bytes.fromhex(token), b"followingcar/frpc-local-admin/v1", hashlib.sha256).hexdigest()


def frpc_config(settings: RelaySettings, transport: str = "quic", local_admin_password: str | None = None,
                video_local_port: int = 19889) -> str:
    settings.validate()
    if transport not in ("quic", "tcp"):
        raise ValueError("Unsupported transport")
    if video_local_port not in (8889, 19889):
        raise ValueError("Only the independent gateway or legacy video ports are allowed")
    admin = ""
    if local_admin_password is not None:
        validate_secret(local_admin_password)
        admin = f'''webServer.addr = "127.0.0.1"
webServer.port = 17400
webServer.user = "followingcar"
webServer.password = "{local_admin_password}"
webServer.pprofEnable = false
'''
    return f'''# No network interfaces, default routes, or motor processes are changed.
serverAddr = "{settings.host}"
serverPort = {settings.transport_port}
user = "followingcar"
clientID = "board-001"
loginFailExit = false
auth.method = "token"
auth.additionalScopes = ["HeartBeats", "NewWorkConns"]
auth.tokenSource.type = "file"
auth.tokenSource.file.path = "{CONFIG_ROOT}/frp-token"
transport.protocol = "{transport}"
transport.tls.enable = true
transport.tls.trustedCaFile = "/etc/ssl/certs/ca-certificates.crt"
transport.tls.serverName = "{settings.host}"
transport.poolCount = 2
transport.quic.keepalivePeriod = 10
transport.quic.maxIdleTimeout = 30
log.to = "console"
log.level = "warn"
log.disablePrintColor = true
{admin}

[[proxies]]
name = "board-ssh"
type = "tcp"
localIP = "127.0.0.1"
localPort = 22
remotePort = 22022
transport.useCompression = false

[[proxies]]
name = "board-whep"
type = "tcp"
localIP = "127.0.0.1"
localPort = {video_local_port}
remotePort = 18889
transport.useCompression = false
'''


def nginx_config(settings: RelaySettings) -> str:
    settings.validate()
    return f'''load_module /usr/lib/nginx/modules/ngx_stream_module.so;
worker_processes 1;
pid /run/followingcar-nginx/nginx.pid;
error_log stderr crit;
events {{ worker_connections 256; }}
stream {{
    server {{
        listen {settings.control_port} ssl;
        ssl_certificate {CONFIG_ROOT}/tls/server.crt;
        ssl_certificate_key {CONFIG_ROOT}/tls/server.key;
        ssl_client_certificate {CONFIG_ROOT}/tls/client-ca.crt;
        ssl_verify_client on;
        ssl_verify_depth 2;
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_session_tickets off;
        ssl_handshake_timeout 10s;
        proxy_connect_timeout 5s;
        proxy_timeout 1h;
        tcp_nodelay on;
        proxy_pass 127.0.0.1:22022;
    }}
}}
http {{
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    access_log off;
    server_tokens off;
    sendfile on;
    tcp_nodelay on;
    client_body_temp_path /run/followingcar-nginx/client_temp;
    proxy_temp_path /run/followingcar-nginx/proxy_temp;
    fastcgi_temp_path /run/followingcar-nginx/fastcgi_temp;
    uwsgi_temp_path /run/followingcar-nginx/uwsgi_temp;
    scgi_temp_path /run/followingcar-nginx/scgi_temp;
    server {{
        listen {settings.video_port} ssl;
        server_name {settings.host};
        ssl_certificate {CONFIG_ROOT}/tls/server.crt;
        ssl_certificate_key {CONFIG_ROOT}/tls/server.key;
        ssl_client_certificate {CONFIG_ROOT}/tls/client-ca.crt;
        ssl_verify_client on;
        ssl_verify_depth 2;
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_session_tickets off;
        client_max_body_size 128k;
        add_header Cache-Control "no-store" always;
        add_header X-Content-Type-Options nosniff always;
        location = /healthz {{ return 204; }}
        location = /player {{ return 308 /player/; }}
        location /player/ {{
            alias {INSTALL_ROOT}/web/;
            index index.html;
            limit_except GET {{ deny all; }}
        }}
        location ~ ^/rgbd/whep(?:/[A-Za-z0-9_-]+)?$ {{
            limit_except OPTIONS POST PATCH DELETE {{ deny all; }}
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_set_header Connection "";
            proxy_set_header X-Forwarded-Proto https;
            proxy_set_header X-Forwarded-For $remote_addr;
            proxy_pass http://127.0.0.1:18889;
            proxy_buffering off;
            proxy_request_buffering off;
            proxy_connect_timeout 5s;
            proxy_read_timeout 20s;
            proxy_send_timeout 20s;
            proxy_redirect off;
        }}
        location / {{ return 404; }}
    }}
}}
'''


def coturn_config(settings: RelaySettings, secret: str) -> str:
    settings.validate()
    validate_secret(secret)
    return f'''listening-port={settings.turn_port}
tls-listening-port={settings.turns_port}
listening-ip={settings.local_ip}
relay-ip={settings.local_ip}
external-ip={settings.public_ip}/{settings.local_ip}
min-port={settings.relay_min}
max-port={settings.relay_max}
realm={settings.host}
server-name={settings.host}
fingerprint
use-auth-secret
static-auth-secret={secret}
cert={CONFIG_ROOT}/tls/server.crt
pkey={CONFIG_ROOT}/tls/server.key
no-tlsv1
no-tlsv1_1
no-dtls
no-tcp-relay
no-cli
no-loopback-peers
no-multicast-peers
# Both remote peers must allocate on this server; do not relay to arbitrary hosts.
denied-peer-ip=0.0.0.0-255.255.255.255
allowed-peer-ip={settings.public_ip}
user-quota=4
total-quota=32
max-bps=1250000
bps-capacity=10000000
relay-threads=1
stale-nonce=600
log-file=stdout
simple-log
pidfile=/run/followingcar-turn/turnserver.pid
userdb=/run/followingcar-turn/turndb
'''


def mediamtx_turn_overlay(settings: RelaySettings, secret: str) -> str:
    settings.validate()
    validate_secret(secret)
    return f'''# Merge ONLY when the existing video service is idle. Never replace its full config.
# MediaMTX v1.21.3 AUTH_SECRET produces 24-hour temporary credentials.
webrtcICEServers2:
  - url: turn:{settings.host}:{settings.turn_port}?transport=udp
    username: AUTH_SECRET
    password: "{secret}"
    clientOnly: false
  - url: turns:{settings.host}:{settings.turns_port}?transport=tcp
    username: AUTH_SECRET
    password: "{secret}"
    clientOnly: false
'''


def systemd_unit(kind: str) -> str:
    commands = {
        "frps": f"{INSTALL_ROOT}/frp/{FRP_VERSION}/frps -c {CONFIG_ROOT}/frps.toml",
        "frpc": f"/usr/bin/python3 {INSTALL_ROOT}/tools/transport_supervisor.py",
        "nginx": f"/usr/sbin/nginx -c {CONFIG_ROOT}/nginx.conf -g 'daemon off;'",
        "turn": f"/usr/bin/turnserver -c {CONFIG_ROOT}/coturn.conf",
    }
    if kind not in commands:
        raise ValueError("Unsupported service")
    runtime = f"followingcar-{kind}"
    persistent = "StateDirectory=followingcar-relay\nStateDirectoryMode=0750\n" if kind == "frpc" else ""
    return f'''[Unit]
Description=FollowingCar independent {kind} relay
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=60
StartLimitBurst=10

[Service]
Type=simple
User={SERVICE_USER}
Group={SERVICE_USER}
ExecStart={commands[kind]}
Restart=on-failure
RestartSec=2s
TimeoutStopSec=10s
RuntimeDirectory={runtime}
RuntimeDirectoryMode=0750
{persistent}UMask=0077
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
CapabilityBoundingSet=
LockPersonality=true
MemoryMax=256M
StandardOutput=journal
StandardError=journal
SyslogIdentifier=followingcar-{kind}

[Install]
WantedBy=multi-user.target
'''


def maintenance_unit() -> str:
    return f'''[Unit]
Description=Report FollowingCar TLS maintenance pending manual coordination
After=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 {INSTALL_ROOT}/tools/certificate_maintenance.py
TimeoutStartSec=60s
UMask=0077
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths={CONFIG_ROOT}
PrivateTmp=true
StandardOutput=journal
StandardError=journal
'''


def maintenance_timer() -> str:
    return '''[Unit]
Description=Report pending FollowingCar certificate maintenance without service restarts

[Timer]
OnBootSec=5min
OnUnitActiveSec=5min
RandomizedDelaySec=30s
Unit=followingcar-certificate-maintenance.service

[Install]
WantedBy=timers.target
'''
