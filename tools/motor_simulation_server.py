#!/usr/bin/env python3
"""Loopback-only emulator test gateway. Always invokes the bridge with --simulate.

Run from the repository, then `adb -s emulator-5554 reverse tcp:28765 tcp:28765`.
No SSH, sudo, serial ports, real control locks, or real motor configuration is used.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import json
from pathlib import Path
import subprocess
import sys

BRIDGE = Path(__file__).resolve().parents[1] / "board" / "app_motor_config_bridge.py"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # Do not dump request data or protocol frames to the console.

    def send_json(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path != "/health":
            self.send_json(404, {"error": "not_found"})
            return
        self.send_json(200, {"kind": "followingcar-motor-simulator", "simulation": True})

    def do_POST(self):
        if self.path != "/request":
            self.send_json(404, {"error": "not_found"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 8192:
                self.send_json(400, {"error": "invalid_length"})
                return
            data = self.rfile.read(size)
            # The strict production request parser and Modbus simulator do all validation.
            result = subprocess.run([sys.executable, "-B", str(BRIDGE), "--request", "--simulate"],
                                    input=data, capture_output=True, timeout=75)
            body = json.loads(result.stdout)
            if body.get("simulation") is not True:
                raise ValueError("unexpected non-simulation reply")
            self.send_json(200, {"stdout": result.stdout.decode("utf-8"), "exitCode": result.returncode})
        except (ValueError, OSError, subprocess.TimeoutExpired):
            self.send_json(500, {"error": "simulation_failed"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=28765)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Use a port in 1024..65535")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Motor simulator listening on 127.0.0.1:{args.port}; hardware access disabled", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
