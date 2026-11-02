"""Render the domestic relay from the existing, tested protocol configuration."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from configuration import RelaySettings, coturn_config, frps_config, nginx_config  # noqa: E402


HOST = "world-agents.com"
PUBLIC_IP = "117.50.34.249"
TURN_IP = "172.28.240.10"
FRPS_IP = "172.28.240.11"
NGINX_IP = "172.28.240.12"
SETTINGS = RelaySettings(host=HOST, public_ip=PUBLIC_IP, local_ip=TURN_IP,
                         control_port=2443, video_port=9443, transport_port=7443,
                         turn_port=3478, turns_port=5349,
                         relay_min=62000, relay_max=62099)


def replace_once(source: str, old: str, new: str) -> str:
    if source.count(old) != 1:
        raise ValueError(f"Unexpected upstream relay template: {old!r}")
    return source.replace(old, new, 1)


def render_frps() -> str:
    result = frps_config(SETTINGS)
    # Accessible only on this Compose network; neither backend is host-published.
    return replace_once(result, 'proxyBindAddr = "127.0.0.1"',
                        f'proxyBindAddr = "{FRPS_IP}"')


def render_nginx() -> str:
    result = nginx_config(SETTINGS)
    # The selected container image has stream built in.
    result = replace_once(result,
                          "load_module /usr/lib/nginx/modules/ngx_stream_module.so;\n", "")
    result = replace_once(result, "proxy_pass 127.0.0.1:22022;",
                          f"proxy_pass {FRPS_IP}:22022;")
    result = replace_once(result, "proxy_pass http://127.0.0.1:18889;",
                          f"proxy_pass http://{FRPS_IP}:18889;")
    return result


def render_coturn(secret: str) -> str:
    return coturn_config(SETTINGS, secret)
