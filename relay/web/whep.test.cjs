"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { FollowingCarWhepReader, iceServersFromLink, parseOffer, candidateFragment, sessionLocation, safeFailureCode } = require("./whep.js");

const url = "https://car.nujabes.top:8443/rgbd/whep";
const link = '<turn:car.nujabes.top:3478?transport=udp>; rel="ice-server"; username="test-user"; credential="test-pass"; credential-type="password"';
const sdp = "v=0\r\na=ice-ufrag:user\r\na=ice-pwd:password\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:video\r\n";
const candidate = { sdpMLineIndex: 0, candidate: "candidate:1 1 udp 1 43.162.101.137 62001 typ relay raddr 0.0.0.0 rport 0" };
const tick = () => new Promise((resolve) => setImmediate(resolve));
const response = (status, headers = {}, body = "") => ({ status, ok: status >= 200 && status < 300,
  headers: new Headers(headers), text: async () => body });

function fixture(overrides = {}) {
  const requests = [];
  const states = [];
  let peer;
  let configuration;
  const reader = new FollowingCarWhepReader({ url, onState: (state) => states.push(state),
    makePeer: (config) => {
      configuration = config;
      peer = { connectionState: "new", closed: false,
        addTransceiver: (kind, options) => { assert.equal(kind, "video"); assert.equal(options.direction, "recvonly"); },
        createOffer: async () => ({ type: "offer", sdp }),
        setLocalDescription: async () => {},
        setRemoteDescription: async () => {},
        close() { this.closed = true; },
        getStats: async () => new Map(),
      };
      return peer;
    },
    fetch: async (target, options) => {
      requests.push({ target, options });
      if (overrides.fetch) return overrides.fetch(target, options);
      if (options.method === "OPTIONS") return response(204, { Link: link });
      if (options.method === "POST") return response(201, { Location: "/rgbd/whep/test-session" }, sdp);
      return response(204);
    },
  });
  return { reader, requests, states, get peer() { return peer; }, get configuration() { return configuration; } };
}

test("ICE credentials are accepted only for authenticated same-host TURN endpoints", () => {
  assert.equal(iceServersFromLink(link, "car.nujabes.top")[0].credential, "test-pass");
  const tls = link.replace("turn:", "turns:").replace("3478?transport=udp", "5349?transport=tcp");
  assert.equal(iceServersFromLink(`${link}, ${tls}`, "car.nujabes.top").length, 2);
  for (const invalid of [null, "", link.replace("car.nujabes.top", "attacker.test"), link.replace("3478", "9999"),
    link.replace("turn:", "stun:"), link.replace("?transport=udp", "?transport=udp&extra=1")]) {
    assert.throws(() => iceServersFromLink(invalid, "car.nujabes.top"));
  }
});

test("candidate fragments preserve negotiated MID and reject line injection", () => {
  const offer = parseOffer(sdp);
  assert.match(candidateFragment(offer, [candidate]), /a=mid:video\r\n/);
  assert.match(candidateFragment(offer, [candidate]), /a=ice-ufrag:user\r\n/);
  assert.throws(() => candidateFragment(offer, [{ ...candidate, candidate: "candidate:1\r\na=invalid" }]));
});

test("failure diagnostics expose fixed codes without arbitrary browser or SDP text", () => {
  assert.equal(safeFailureCode(new Error("http_400")), "http_400");
  assert.equal(safeFailureCode(new Error("invalid_session_location")), "invalid_session_location");
  assert.equal(safeFailureCode(new Error("credential=private-value\r\na=ice-pwd:secret")), "unexpected_error");
  assert.equal(safeFailureCode(new TypeError("fetch https://private.example/path")), "network_error");
  assert.equal(safeFailureCode({ name: "AbortError", message: "private-value" }), "request_timeout");
});

test("WHEP session Location cannot escape its trusted origin or path", () => {
  assert.equal(sessionLocation("/rgbd/whep/id-123", url), `${url}/id-123`);
  for (const invalid of [null, "https://evil.test/rgbd/whep/id", "http://car.nujabes.top:8443/rgbd/whep/id",
    "/api/admin", "/rgbd/whep/id?token=x", "/rgbd/whep/id#x", "/rgbd/whep/%2e%2e"]) {
    assert.throws(() => sessionLocation(invalid, url));
  }
});

test("relay-only receive connection uses authenticated same-origin requests and cleans its session", async () => {
  const f = fixture();
  f.reader.start();
  await tick();
  assert.equal(f.configuration.iceTransportPolicy, "relay");
  f.peer.onicecandidate({ candidate });
  await tick();
  assert.equal(f.requests.filter((request) => request.options.method === "PATCH").length, 1);
  f.reader.close();
  f.reader.close();
  await tick();
  assert.equal(f.requests.filter((request) => request.options.method === "DELETE").length, 1);
  assert.equal(f.peer.closed, true);
  for (const { options } of f.requests) {
    assert.equal(options.redirect, "error");
    assert.equal(options.credentials, "same-origin");
    assert.equal(options.mode, "same-origin");
    assert.equal(options.headers?.Authorization, undefined);
  }
});

test("nonrelay candidates terminate the media connection without publishing host addresses", async () => {
  const f = fixture(); f.reader.start(); await tick();
  f.peer.onicecandidate({ candidate: { ...candidate, candidate: candidate.candidate.replace("typ relay", "typ host") } });
  await tick();
  assert.equal(f.peer.closed, true);
  assert.equal(f.requests.some((request) => request.options.method === "PATCH"), false);
  assert.equal(f.states.at(-1), "retrying");
  f.reader.close();
});

test("authentication refusal does not repeatedly allocate a peer or retry", async () => {
  const f = fixture({ fetch: async () => response(403) });
  f.reader.start(); await tick();
  assert.equal(f.states.at(-1), "authentication_failed");
  assert.equal(f.reader.retry, null);
  assert.equal(f.peer, undefined);
  f.reader.close();
});

test("closing during a delayed POST deletes a late-created session and never applies its answer", async () => {
  let resolvePost;
  const f = fixture({ fetch: async (_url, options) => {
    if (options.method === "OPTIONS") return response(204, { Link: link });
    if (options.method === "POST") return new Promise((resolve) => { resolvePost = resolve; });
    return response(204);
  } });
  f.reader.start(); await tick();
  assert.equal(typeof resolvePost, "function");
  f.reader.close();
  resolvePost(response(201, { Location: "/rgbd/whep/late-session" }, sdp));
  await tick();
  assert.equal(f.requests.filter((request) => request.options.method === "DELETE").length, 1);
  assert.equal(f.reader.session, null);
  assert.equal(f.states.at(-1), "closed");
});

test("candidate sends remain bounded while a PATCH is blocked", async () => {
  let releasePatch;
  const f = fixture({ fetch: async (_url, options) => {
    if (options.method === "OPTIONS") return response(204, { Link: link });
    if (options.method === "POST") return response(201, { Location: "/rgbd/whep/test-session" }, sdp);
    if (options.method === "PATCH") return new Promise((resolve) => { releasePatch = resolve; });
    return response(204);
  } });
  f.reader.start(); await tick();
  for (let i = 0; i < 70; i++) f.peer.onicecandidate({ candidate });
  assert.equal(f.peer.closed, true);
  assert.ok(f.reader.candidates.length <= 64);
  releasePatch(response(204)); await tick();
  f.reader.close();
});

test("a connected peer with stopped decoded frames is closed and retries after a fixed stall code", async () => {
  const f = fixture(); f.reader.start(); await tick();
  f.peer.getStats = async () => new Map([["video", { type: "inbound-rtp", kind: "video", framesDecoded: 42 }]]);
  f.reader.lastFrameCount = 42;
  f.reader.lastFrameAt = performance.now() - 6100;
  await f.reader.pollStats(f.reader.generation);
  assert.equal(f.states.at(-1), "media_stalled");
  assert.equal(f.peer.closed, true);
  assert.equal(f.reader.lastFrameCount, null);
  assert.notEqual(f.reader.retry, null);
  f.reader.close();
});

test("unavailable decoder counters never produce a false stalled-video failure", async () => {
  const f = fixture(); f.reader.start(); await tick();
  f.reader.lastFrameCount = 42;
  f.reader.lastFrameAt = performance.now() - 6100;
  await f.reader.pollStats(f.reader.generation);
  assert.equal(f.peer.closed, false);
  assert.equal(f.states.includes("media_stalled"), false);
  f.reader.close();
});
