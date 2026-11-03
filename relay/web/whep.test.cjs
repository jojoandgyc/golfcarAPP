"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
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
  const errors = [];
  const peers = [];
  let peer;
  let configuration;
  const reader = new FollowingCarWhepReader({ url, onState: (state) => states.push(state),
    onError: (error) => errors.push(error),
    now: overrides.clock?.now, setTimeout: overrides.clock?.setTimeout, clearTimeout: overrides.clock?.clearTimeout,
    wallNow: overrides.clock ? () => 1_000_000 + overrides.clock.now() : undefined,
    quotaStorage: overrides.quotaStorage,
    makePeer: (config) => {
      configuration = config;
      peer = { connectionState: "new", closed: false,
        addTransceiver: (kind, options) => { assert.equal(kind, "video"); assert.equal(options.direction, "recvonly"); },
        createOffer: async () => ({ type: "offer", sdp }),
        setLocalDescription: async () => {},
        answers: [],
        setRemoteDescription: async function (answer) { this.answers.push(answer); },
        close() { this.closed = true; },
        getStats: async () => new Map(),
      };
      peers.push(peer);
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
  return { reader, requests, states, errors, peers, get peer() { return peer; }, get configuration() { return configuration; } };
}

function fakeClock() {
  let now = 0;
  let serial = 0;
  const timers = new Map();
  return {
    now: () => now,
    setTimeout: (callback, delay) => { const id = ++serial; timers.set(id, { at: now + delay, callback }); return id; },
    clearTimeout: (id) => timers.delete(id),
    callback: (id) => timers.get(id)?.callback,
    async advance(ms) {
      const end = now + ms;
      for (let count = 0; ; count++) {
        assert.ok(count < 10_000, "A retry must not spin without advancing time");
        const next = [...timers.entries()].filter(([, timer]) => timer.at <= end)
          .sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
        if (!next) break;
        timers.delete(next[0]); now = next[1].at; next[1].callback(); await tick();
      }
      now = end; await tick();
    },
  };
}

const frames = (count) => new Map([["video", { type: "inbound-rtp", kind: "video", framesDecoded: count }]]);
const quotaKey = "followingcar.turn-quota.v1";
function memoryStorage(initial = null) {
  const values = new Map(initial === null ? [] : [[quotaKey, initial]]);
  return { getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key) };
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
  assert.equal(safeFailureCode(new Error("turn_quota_exceeded")), "turn_quota_exceeded");
  assert.equal(safeFailureCode(new Error("first_frame_timeout")), "first_frame_timeout");
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
  const queuedCandidate = f.peer.onicecandidate;
  for (let i = 0; i < 70; i++) queuedCandidate({ candidate });
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
  assert.ok(f.states.includes("media_stalled"));
  assert.equal(f.peer.closed, true);
  assert.equal(f.reader.lastFrameCount, null);
  assert.notEqual(f.reader.retry, null);
  f.reader.close();
});

test("one candidate's TURN 486 does not kill a working alternative or expose server details", async () => {
  const clock = fakeClock(); const f = fixture({ clock });
  f.reader.start(); await tick();
  f.peer.onicecandidateerror({ errorCode: 486, errorText: "secret TURN credential", url: "turn:private.test" });
  assert.deepEqual(f.errors, ["turn_quota_exceeded"]);
  assert.equal(f.peer.closed, false);
  assert.equal(f.reader.retryNotBefore, 60_000);
  f.peer.connectionState = "connected";
  f.peer.getStats = async () => frames(0);
  f.peer.onconnectionstatechange(); await tick();
  assert.equal(f.states.includes("playing"), false);
  assert.equal(f.states.filter((state) => state === "connecting").length, 1,
    "Connected-before-first-frame must not emit another connecting state that detaches the track");
  assert.equal(f.reader.quotaFailures, 1, "ICE connectivity alone must not clear the penalty");
  f.peer.getStats = async () => frames(1);
  await clock.advance(1000);
  assert.equal(f.states.at(-1), "playing");
  assert.equal(f.reader.quotaFailures, 0);
  assert.equal(f.reader.retryNotBefore, 0);
  assert.equal(f.reader.firstFrameTimer, null);
  assert.equal(f.peers.length, 1);
  f.reader.close();
});

test("failed quota attempts wait 60/120/240/300 seconds and network or visibility cannot bypass them", async () => {
  const clock = fakeClock(); const f = fixture({ clock });
  f.reader.start(); await tick();
  for (const delay of [60_000, 120_000, 240_000, 300_000, 300_000]) {
    const before = f.peers.length;
    f.peer.onicecandidateerror({ errorCode: 486 });
    f.peer.onicecandidateerror({ errorCode: 486 }); // Multiple transports are one failed attempt.
    f.peer.connectionState = "failed";
    f.peer.onconnectionstatechange();
    assert.equal(f.states.at(-1), "quota_wait");
    assert.equal(f.reader.retryNotBefore - clock.now(), delay);
    f.reader.pause(); f.reader.resume();
    for (let i = 0; i < 20; i++) f.reader.networkChanged();
    await clock.advance(delay - 1);
    assert.equal(f.peers.length, before, "No new Allocate/PC during the cooldown");
    await clock.advance(1);
    assert.equal(f.peers.length, before + 1);
  }
  f.reader.close();
});

test("quota cooldown survives page replacement and only real playback clears its stored penalty", async () => {
  const clock = fakeClock(); const quotaStorage = memoryStorage();
  const old = fixture({ clock, quotaStorage }); old.reader.start(); await tick();
  old.peer.onicecandidateerror({ errorCode: 486 });
  old.peer.connectionState = "failed"; old.peer.onconnectionstatechange();
  old.reader.close(); await clock.advance(10_000);
  const fresh = fixture({ clock, quotaStorage });
  fresh.reader.start(); fresh.reader.networkChanged(); fresh.reader.pause(); fresh.reader.resume();
  await clock.advance(49_999);
  assert.equal(fresh.peers.length, 0);
  await clock.advance(1);
  assert.equal(fresh.peers.length, 1);
  assert.equal(fresh.reader.quotaFailures, 1);
  fresh.peer.getStats = async () => frames(0);
  fresh.peer.connectionState = "connected"; fresh.peer.onconnectionstatechange(); await tick();
  assert.notEqual(quotaStorage.getItem(quotaKey), null);
  fresh.peer.getStats = async () => frames(1); await clock.advance(1000);
  assert.equal(quotaStorage.getItem(quotaKey), null);
  assert.equal(fresh.reader.quotaFailures, 0);
  fresh.reader.close();
});

test("malformed stored cooldown cannot create an endless wait across reloads", async () => {
  const clock = fakeClock();
  const quotaStorage = memoryStorage(JSON.stringify({ version: 1, level: 4, untilEpochMs: Number.MAX_SAFE_INTEGER }));
  const first = fixture({ clock, quotaStorage }); first.reader.start();
  assert.equal(JSON.parse(quotaStorage.getItem(quotaKey)).untilEpochMs, 1_300_000);
  first.reader.close(); await clock.advance(60_000);
  const next = fixture({ clock, quotaStorage }); next.reader.start();
  await clock.advance(239_999); assert.equal(next.peers.length, 0);
  await clock.advance(1); assert.equal(next.peers.length, 1);
  next.reader.close();
  for (const invalid of ["not JSON", "null", JSON.stringify({ version: 1, level: 999, untilEpochMs: 1_900_000 })]) {
    const broken = fixture({ clock, quotaStorage: memoryStorage(invalid) });
    broken.reader.start(); await tick();
    assert.equal(broken.peers.length, 1);
    broken.reader.close();
  }
});

test("an older player's success cannot erase another player's newer quota cooldown", async () => {
  const clock = fakeClock(); const quotaStorage = memoryStorage();
  const a = fixture({ clock, quotaStorage }); const b = fixture({ clock, quotaStorage });
  a.reader.start(); b.reader.start(); await tick();
  a.peer.onicecandidateerror({ errorCode: 486 });
  b.peer.onicecandidateerror({ errorCode: 486 });
  const newer = quotaStorage.getItem(quotaKey);
  assert.equal(JSON.parse(newer).level, 2);
  a.peer.getStats = async () => frames(1);
  a.peer.connectionState = "connected"; a.peer.onconnectionstatechange(); await tick();
  assert.equal(quotaStorage.getItem(quotaKey), newer);
  a.reader.close(); b.reader.close();
});

test("a connected peer with zero decoded frames times out instead of claiming playback", async () => {
  const clock = fakeClock(); const f = fixture({ clock });
  f.reader.start(); await tick();
  f.peer.getStats = async () => frames(0);
  f.peer.connectionState = "connected"; f.peer.onconnectionstatechange(); await tick();
  await clock.advance(11_999);
  assert.equal(f.states.includes("playing"), false);
  assert.equal(f.peer.closed, false);
  await clock.advance(1);
  assert.equal(f.errors.at(-1), "first_frame_timeout");
  assert.equal(f.peer.closed, true);
  assert.notEqual(f.reader.retry, null);
  f.reader.close();
});

test("network notifications coalesce, invalidate old callbacks and delete late WHEP sessions", async () => {
  const clock = fakeClock(); let releaseOldPost; let postCount = 0;
  const f = fixture({ clock, fetch: async (_target, options) => {
    if (options.method === "OPTIONS") return response(204, { Link: link });
    if (options.method === "POST" && ++postCount === 1)
      return new Promise((resolve) => { releaseOldPost = resolve; });
    if (options.method === "POST") return response(201, { Location: "/rgbd/whep/new-session" }, sdp);
    return response(204);
  } });
  f.reader.start(); await tick();
  const oldPeer = f.peer;
  const oldCandidate = oldPeer.onicecandidate;
  const oldError = oldPeer.onicecandidateerror;
  const oldConnection = oldPeer.onconnectionstatechange;
  for (let i = 0; i < 20; i++) f.reader.networkChanged();
  await clock.advance(349); assert.equal(f.peers.length, 1);
  await clock.advance(1); assert.equal(f.peers.length, 2);
  assert.equal(oldPeer.closed, true);
  oldError({ errorCode: 486 });
  oldCandidate({ candidate: { ...candidate, candidate: candidate.candidate.replace("typ relay", "typ host") } });
  oldPeer.connectionState = "failed"; oldConnection();
  releaseOldPost(response(201, { Location: "/rgbd/whep/old-session" }, sdp));
  await tick();
  assert.equal(oldPeer.answers.length, 0);
  assert.deepEqual(f.errors, []);
  assert.equal(f.reader.session, `${url}/new-session`);
  assert.equal(f.requests.filter((r) => r.options.method === "DELETE" && r.target === `${url}/old-session`).length, 1);
  f.peer.getStats = async () => frames(1);
  f.peer.connectionState = "connected"; f.peer.onconnectionstatechange(); await tick();
  assert.equal(f.states.at(-1), "playing");
  f.reader.close();
});

test("pause releases media, resume creates one new peer, and final close cannot restart", async () => {
  const clock = fakeClock(); const f = fixture({ clock });
  f.reader.start(); f.reader.start(); await tick();
  assert.equal(f.peers.length, 1, "Repeated start during OPTIONS cannot create parallel offers");
  f.reader.pause(); f.reader.pause();
  assert.equal(f.peer.closed, true);
  await clock.advance(60_000);
  assert.equal(f.peers.length, 1);
  f.reader.resume(); f.reader.resume(); await clock.advance(0);
  assert.equal(f.peers.length, 2);
  f.reader.close(); f.reader.resume(); f.reader.networkChanged(); f.reader.start();
  await clock.advance(60_000);
  assert.equal(f.peers.length, 2);
});

test("a queued stats timer from an old network cannot clear or duplicate the replacement poll", async () => {
  const clock = fakeClock(); const f = fixture({ clock });
  f.reader.start(); await tick();
  f.peer.getStats = async () => frames(1);
  f.peer.connectionState = "connected"; f.peer.onconnectionstatechange(); await tick();
  const lateOldTimer = clock.callback(f.reader.statsTimer);
  assert.equal(typeof lateOldTimer, "function");
  f.reader.networkChanged(); await clock.advance(350);
  let polls = 0;
  f.peer.getStats = async () => { polls++; return frames(polls); };
  f.peer.connectionState = "connected";
  f.peer.onconnectionstatechange(); f.peer.onconnectionstatechange(); await tick();
  assert.equal(polls, 1, "Repeated connection events must not overlap getStats");
  const replacementTimer = f.reader.statsTimer;
  lateOldTimer(); await tick();
  assert.equal(f.reader.statsTimer, replacementTimer);
  assert.equal(polls, 1);
  await clock.advance(3000);
  assert.equal(polls, 2, "Only one polling chain survives the network change");
  f.reader.close();
});

test("authentication refusal cannot be retried through visibility or network notifications", async () => {
  const clock = fakeClock(); const f = fixture({ clock, fetch: async () => response(403) });
  f.reader.start(); await tick();
  f.reader.networkChanged(); f.reader.pause(); f.reader.resume(); f.reader.start();
  await clock.advance(300_000);
  assert.equal(f.requests.length, 1);
  assert.equal(f.states.at(-1), "authentication_failed");
  f.reader.close();
});

test("player visibility restores reception and its native API exposes only receiver restart", async () => {
  const clock = fakeClock(); const windowEvents = new Map(); const documentEvents = new Map();
  const calls = []; let reader;
  const elements = { video: { srcObject: null, play: async () => {} }, message: {}, quality: {} };
  const document = { visibilityState: "visible", title: "", documentElement: { dataset: {} },
    getElementById: (id) => elements[id], addEventListener: (name, callback) => documentEvents.set(name, callback) };
  const window = { addEventListener: (name, callback) => windowEvents.set(name, callback) };
  class FakeReader {
    constructor(options) { this.options = options; reader = this; }
    start() { calls.push("start"); } pause() { calls.push("pause"); }
    resume() { calls.push("resume"); } close() { calls.push("close"); }
    networkChanged() { calls.push("networkChanged"); }
  }
  vm.runInNewContext(fs.readFileSync(require.resolve("./player.js"), "utf8"), {
    document, window, location: { origin: "https://car.nujabes.top:8443" }, URL,
    FollowingCarWhepReader: FakeReader, setTimeout: clock.setTimeout,
  });
  document.visibilityState = "hidden"; documentEvents.get("visibilitychange")();
  document.visibilityState = "visible"; documentEvents.get("visibilitychange")();
  window.followingCarNetworkChanged(); windowEvents.get("online")();
  windowEvents.get("pagehide")(); windowEvents.get("pageshow")(); windowEvents.get("beforeunload")();
  assert.deepEqual(calls, ["start", "pause", "resume", "networkChanged", "networkChanged", "pause", "resume", "close"]);
  reader.options.onError("turn_quota_exceeded");
  assert.equal(document.title, "FollowingCarVideoError:turn_quota_exceeded");
  reader.options.onState("quota_wait");
  assert.equal(document.documentElement.dataset.failure, "turn_quota_exceeded");
  await clock.advance(0);
  assert.equal(document.title, "FollowingCarVideo:quota_wait");
  const receivedStream = {};
  reader.options.onState("connecting");
  reader.options.onTrack({ streams: [receivedStream] });
  assert.equal(elements.video.srcObject, receivedStream);
  reader.options.onState("playing"); await clock.advance(0);
  assert.equal(elements.video.srcObject, receivedStream, "The first decoded frame keeps the received track attached");
  assert.equal(document.documentElement.dataset.failure, undefined);
  assert.equal(document.title, "FollowingCarVideo:playing");
  assert.deepEqual(JSON.parse(document.documentElement.dataset.errorEvents), [
    { sequence: 1, code: "turn_quota_exceeded" },
  ], "Playback may clear the live warning, but cannot erase the diagnostic before native reads it");
  for (let i = 0; i < 10; i++) reader.options.onError("media_timeout");
  reader.options.onState("quota_wait"); await clock.advance(0);
  const history = JSON.parse(document.documentElement.dataset.errorEvents);
  assert.equal(history.length, 8);
  assert.deepEqual(history.map((row) => row.sequence), [4, 5, 6, 7, 8, 9, 10, 11]);
  assert.ok(history.every((row) => row.code === "media_timeout" && Object.keys(row).length === 2));
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
