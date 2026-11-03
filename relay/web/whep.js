/*
 * Receive-only WHEP client for the pinned MediaMTX v1.21.3 endpoint.
 * SDP fragment and ICE Link handling follows the MediaMTX reader (MIT):
 * https://github.com/bluenviron/mediamtx/blob/v1.21.3/internal/servers/webrtc/reader.js
 * See THIRD_PARTY_LICENSES.txt. No audio codec probing or vehicle control is included.
 */
(function (root) {
  "use strict";
  const QUOTA_STORAGE_KEY = "followingcar.turn-quota.v1";
  const MAX_QUOTA_WAIT_MS = 300_000;

  function iceServersFromLink(link, expectedHost) {
    if (typeof link !== "string" || link.length > 16384) throw new Error("invalid_ice_config");
    const entries = link.split(/,\s*(?=<)/);
    if (entries.length > 8) throw new Error("invalid_ice_config");
    const servers = entries.map((entry) => {
      const match = entry.match(/^<(turns?:[^>]+)>;\s*rel="ice-server";\s*username="((?:\\.|[^"\\])*)";\s*credential="((?:\\.|[^"\\])*)";\s*credential-type="password"$/);
      if (!match) throw new Error("invalid_ice_config");
      const endpoint = match[1].match(/^(turn|turns):([a-zA-Z0-9.-]+):(\d+)\?transport=(udp|tcp)$/);
      if (!endpoint || endpoint[2].toLowerCase() !== expectedHost.toLowerCase() ||
          !((endpoint[1] === "turn" && endpoint[3] === "3478") ||
            (endpoint[1] === "turns" && endpoint[3] === "5349" && endpoint[4] === "tcp"))) {
        throw new Error("invalid_ice_config");
      }
      const username = JSON.parse(`"${match[2]}"`);
      const credential = JSON.parse(`"${match[3]}"`);
      if (!username || !credential || username.length > 1024 || credential.length > 1024) {
        throw new Error("invalid_ice_config");
      }
      return { urls: [match[1]], username, credential, credentialType: "password" };
    });
    if (!servers.length) throw new Error("missing_turn_server");
    return servers;
  }

  function parseOffer(sdp) {
    if (typeof sdp !== "string" || sdp.length > 128 * 1024) throw new Error("invalid_offer");
    const result = { iceUfrag: "", icePwd: "", media: [] };
    let current;
    for (const line of sdp.split("\r\n")) {
      if (line.startsWith("m=")) {
        current = { line: line.slice(2), mid: String(result.media.length) };
        result.media.push(current);
      } else if (line.startsWith("a=mid:") && current) current.mid = line.slice(6);
      else if (line.startsWith("a=ice-ufrag:") && !result.iceUfrag) result.iceUfrag = line.slice(12);
      else if (line.startsWith("a=ice-pwd:") && !result.icePwd) result.icePwd = line.slice(10);
    }
    if (!result.iceUfrag || !result.icePwd || !result.media.length) throw new Error("invalid_offer");
    return result;
  }

  function candidateFragment(offer, candidates) {
    let result = `a=ice-ufrag:${offer.iceUfrag}\r\na=ice-pwd:${offer.icePwd}\r\n`;
    offer.media.forEach((media, index) => {
      const selected = candidates.filter((candidate) => candidate.sdpMLineIndex === index);
      if (!selected.length) return;
      result += `m=${media.line}\r\na=mid:${media.mid}\r\n`;
      for (const candidate of selected) {
        if (candidate.candidate.length > 4096 || /[\r\n]/.test(candidate.candidate)) {
          throw new Error("invalid_candidate");
        }
        result += `a=${candidate.candidate}\r\n`;
      }
    });
    return result;
  }

  function sessionLocation(location, endpoint) {
    if (!location) throw new Error("invalid_session_location");
    const url = new URL(location, endpoint);
    if (url.origin !== new URL(endpoint).origin || url.username || url.password || url.search || url.hash ||
        !/^\/rgbd\/whep\/[a-zA-Z0-9_-]{1,128}$/.test(url.pathname)) {
      throw new Error("invalid_session_location");
    }
    return url.href;
  }

  function safeFailureCode(error) {
    const message = typeof error?.message === "string" ? error.message : "";
    if (/^http_[1-5][0-9]{2}$/.test(message) || new Set([
      "invalid_ice_config", "missing_turn_server", "invalid_offer", "invalid_candidate",
      "invalid_session_location", "invalid_answer", "candidate_overflow", "non_relay_candidate",
      "non_relay_transport", "media_disconnected", "media_timeout", "media_stalled",
      "turn_quota_exceeded", "first_frame_timeout",
    ]).has(message)) return message;
    if (error?.name === "AbortError") return "request_timeout";
    if (error?.name === "TypeError") return "network_error";
    if (error?.name === "OperationError") return "webrtc_operation_failed";
    return "unexpected_error";
  }

  class FollowingCarWhepReader {
    constructor(options) {
      this.options = options;
      this.endpoint = new URL(options.url);
      if (this.endpoint.protocol !== "https:" || this.endpoint.pathname !== "/rgbd/whep" ||
          this.endpoint.username || this.endpoint.password || this.endpoint.search || this.endpoint.hash) {
        throw new Error("invalid_whep_endpoint");
      }
      this.fetch = options.fetch || root.fetch.bind(root);
      this.makePeer = options.makePeer || ((config) => new root.RTCPeerConnection(config));
      this.now = options.now || (() => root.performance.now());
      this.setTimer = options.setTimeout || root.setTimeout.bind(root);
      this.clearTimer = options.clearTimeout || root.clearTimeout.bind(root);
      this.wallNow = options.wallNow || Date.now;
      try { this.quotaStorage = options.quotaStorage === undefined ? root.localStorage : options.quotaStorage; }
      catch (_) { this.quotaStorage = null; }
      this.quotaStamp = null;
      this.closed = false;
      this.paused = false;
      this.authenticationBlocked = false;
      this.opening = false;
      this.generation = 0;
      this.requests = new Set();
      this.pc = null;
      this.session = null;
      this.offer = null;
      this.candidates = [];
      this.patchRunning = false;
      this.retry = null;
      this.statsTimer = null;
      this.connectionTimer = null;
      this.firstFrameTimer = null;
      this.networkTimer = null;
      this.statsPending = false;
      this.playing = false;
      this.quotaSeen = false;
      this.quotaFailures = 0;
      this.retryFailures = 0;
      this.retryNotBefore = 0;
      this.quotaNotBefore = 0;
      this.lastFrameCount = null;
      this.lastFrameAt = null;
      this.loadQuotaCooldown();
    }

    state(value) { if (this.options.onState) this.options.onState(value); }
    current(generation) { return !this.closed && !this.paused && this.generation === generation; }

    async request(url, init, deadlineMs = 10000) {
      const abort = new AbortController();
      this.requests.add(abort);
      const timer = this.setTimer(() => abort.abort(), deadlineMs);
      try {
        return await this.fetch(url, { ...init, credentials: "same-origin", mode: "same-origin",
          redirect: "error", cache: "no-store", signal: abort.signal });
      } finally {
        this.clearTimer(timer);
        this.requests.delete(abort);
      }
    }

    start() {
      if (this.closed || this.paused || this.authenticationBlocked || this.opening || this.pc || this.retry !== null) return;
      this.loadQuotaCooldown();
      if (this.now() < this.retryNotBefore) { this.scheduleStart(); return; }
      const generation = ++this.generation;
      this.opening = true;
      this.quotaSeen = false;
      this.state("connecting");
      this.open(generation).catch((error) => this.fail(generation, error)).finally(() => {
        if (this.current(generation)) this.opening = false;
      });
    }

    scheduleStart() {
      if (this.closed || this.paused || this.authenticationBlocked || this.pc || this.opening || this.retry !== null) return;
      this.loadQuotaCooldown();
      const remaining = Math.max(0, this.retryNotBefore - this.now());
      if (remaining > 0) this.state(this.quotaNotBefore > this.now() ? "quota_wait" : "retrying");
      this.retry = this.setTimer(() => { this.retry = null; this.start(); }, remaining);
    }

    loadQuotaCooldown() {
      if (!this.quotaStorage) return;
      try {
        const raw = this.quotaStorage.getItem(QUOTA_STORAGE_KEY);
        if (raw === null) return;
        const value = JSON.parse(raw);
        if (value?.version !== 1 || !Number.isSafeInteger(value.untilEpochMs) || value.untilEpochMs < 0 ||
            !Number.isInteger(value.level) || value.level < 1 || value.level > 4) {
          this.quotaStorage.removeItem(QUOTA_STORAGE_KEY); return;
        }
        const wall = this.wallNow();
        const until = Math.min(value.untilEpochMs, wall + MAX_QUOTA_WAIT_MS);
        // Write the bounded deadline back once; otherwise a malformed far-future value
        // could create a new five-minute wait on every page reload.
        const normalized = { version: 1, untilEpochMs: until, level: value.level };
        if (until !== value.untilEpochMs) this.quotaStorage.setItem(QUOTA_STORAGE_KEY, JSON.stringify(normalized));
        this.quotaStamp = normalized;
        this.quotaFailures = Math.max(this.quotaFailures, value.level);
        this.quotaNotBefore = Math.max(this.quotaNotBefore, this.now() + Math.max(0, until - wall));
        this.retryNotBefore = Math.max(this.retryNotBefore, this.quotaNotBefore);
      } catch (_) { /* Storage being disabled must not break video or certificate checks. */ }
    }

    saveQuotaCooldown() {
      const value = { version: 1, untilEpochMs: this.wallNow() +
        Math.min(MAX_QUOTA_WAIT_MS, Math.max(0, this.quotaNotBefore - this.now())), level: this.quotaFailures };
      this.quotaStamp = value;
      try { this.quotaStorage?.setItem(QUOTA_STORAGE_KEY, JSON.stringify(value)); } catch (_) {}
    }

    clearOwnQuotaCooldown() {
      try {
        const value = JSON.parse(this.quotaStorage?.getItem(QUOTA_STORAGE_KEY) || "null");
        if (value && this.quotaStamp && value.untilEpochMs === this.quotaStamp.untilEpochMs &&
            value.level === this.quotaStamp.level) this.quotaStorage.removeItem(QUOTA_STORAGE_KEY);
      } catch (_) {}
      this.quotaStamp = null;
    }

    // A single failed candidate must not discard another candidate that can still connect.
    candidateError(generation, event) {
      if (!this.current(generation) || event.errorCode !== 486 || this.playing || this.quotaSeen) return;
      this.loadQuotaCooldown();
      this.quotaSeen = true;
      this.quotaFailures = Math.min(this.quotaFailures + 1, 4);
      this.quotaNotBefore = Math.max(this.quotaNotBefore,
        this.now() + Math.min(60_000 * (2 ** (this.quotaFailures - 1)), MAX_QUOTA_WAIT_MS));
      this.retryNotBefore = Math.max(this.retryNotBefore, this.quotaNotBefore);
      this.saveQuotaCooldown();
      if (this.options.onError) this.options.onError("turn_quota_exceeded");
    }

    // Default-network events are hints for this receiver only; no vehicle command is sent.
    networkChanged() {
      if (this.closed || this.paused || this.authenticationBlocked || this.networkTimer !== null) return;
      this.networkTimer = this.setTimer(() => {
        this.networkTimer = null;
        if (this.closed || this.paused || this.authenticationBlocked) return;
        ++this.generation;
        this.release();
        this.clearTimer(this.retry);
        this.retry = null;
        this.scheduleStart();
      }, 350);
    }

    pause() {
      if (this.closed || this.paused) return;
      this.paused = true;
      ++this.generation;
      this.clearTimer(this.retry);
      this.clearTimer(this.networkTimer);
      this.retry = this.networkTimer = null;
      this.release();
      this.state("closed");
    }

    resume() {
      if (this.closed) return;
      this.paused = false;
      if (this.authenticationBlocked) { this.state("authentication_failed"); return; }
      this.scheduleStart();
    }

    async open(generation) {
      const options = await this.request(this.endpoint.href, { method: "OPTIONS" });
      if (!this.current(generation)) return;
      if (!options.ok) throw new Error(`http_${options.status}`);
      const iceServers = iceServersFromLink(options.headers.get("Link"), this.endpoint.hostname);
      const pc = this.makePeer({ iceServers, iceTransportPolicy: "relay", bundlePolicy: "max-bundle" });
      this.pc = pc;
      pc.addTransceiver("video", { direction: "recvonly" });
      pc.ontrack = (event) => {
        if (this.current(generation) && this.options.onTrack) this.options.onTrack(event);
      };
      pc.onicecandidate = (event) => {
        if (!this.current(generation) || !event.candidate) return;
        if (this.candidates.length >= 64) { this.fail(generation, new Error("candidate_overflow")); return; }
        // The relay policy is also checked here so a platform regression cannot publish host candidates.
        if (!/\styp relay(?:\s|$)/.test(event.candidate.candidate)) {
          this.fail(generation, new Error("non_relay_candidate")); return;
        }
        this.candidates.push(event.candidate);
        this.flushCandidates(generation);
      };
      pc.onicecandidateerror = (event) => this.candidateError(generation, event);
      pc.onconnectionstatechange = () => {
        if (!this.current(generation)) return;
        if (pc.connectionState === "connected") {
          this.clearTimer(this.connectionTimer);
          this.connectionTimer = null;
          if (!this.playing && this.firstFrameTimer === null) {
            this.firstFrameTimer = this.setTimer(() => this.fail(generation, new Error("first_frame_timeout")), 12_000);
          }
          this.pollStats(generation);
        } else if (["failed", "disconnected", "closed"].includes(pc.connectionState)) {
          this.fail(generation, new Error("media_disconnected"));
        }
      };
      const offer = await pc.createOffer();
      if (!this.current(generation)) return;
      this.offer = parseOffer(offer.sdp);
      await pc.setLocalDescription(offer);
      if (!this.current(generation)) return;
      const response = await this.request(this.endpoint.href, {
        method: "POST", headers: { "Content-Type": "application/sdp" }, body: offer.sdp,
      });
      if (response.status !== 201) throw new Error(`http_${response.status}`);
      const session = sessionLocation(response.headers.get("Location"), this.endpoint.href);
      if (!this.current(generation)) { this.deleteSession(session); return; }
      this.session = session;
      const answer = await response.text();
      if (!this.current(generation)) return;
      if (answer.length > 128 * 1024 || !answer.startsWith("v=0")) throw new Error("invalid_answer");
      await pc.setRemoteDescription({ type: "answer", sdp: answer });
      if (!this.current(generation)) return;
      this.flushCandidates(generation);
      if (pc.connectionState !== "connected") {
        this.connectionTimer = this.setTimer(() => this.fail(generation, new Error("media_timeout")), 15000);
      }
    }

    async flushCandidates(generation) {
      if (!this.current(generation) || !this.session || !this.offer || this.patchRunning) return;
      this.patchRunning = true;
      try {
        while (this.current(generation) && this.session && this.candidates.length) {
          const candidates = this.candidates.splice(0, 64);
          const response = await this.request(this.session, { method: "PATCH",
            headers: { "Content-Type": "application/trickle-ice-sdpfrag", "If-Match": "*" },
            body: candidateFragment(this.offer, candidates) });
          if (!this.current(generation)) return;
          if (response.status !== 204) throw new Error(`http_${response.status}`);
        }
      } catch (error) { this.fail(generation, error); }
      finally { if (this.current(generation)) this.patchRunning = false; }
    }

    async pollStats(generation) {
      if (!this.current(generation) || !this.pc || this.statsTimer !== null || this.statsPending) return;
      this.statsPending = true;
      try {
        const report = await this.pc.getStats();
        if (!this.current(generation)) return;
        let selected;
        let framesDecoded = 0;
        let hasFrameCounter = false;
        report.forEach((entry) => {
          if (entry.type === "transport" && entry.selectedCandidatePairId) selected = report.get(entry.selectedCandidatePairId);
          if (!selected && entry.type === "candidate-pair" && entry.nominated && entry.state === "succeeded") selected = entry;
          if (entry.type === "inbound-rtp" && entry.kind === "video" && Number.isFinite(entry.framesDecoded)) {
            hasFrameCounter = true;
            framesDecoded += entry.framesDecoded;
          }
        });
        if (hasFrameCounter && this.lastFrameCount !== null && framesDecoded <= this.lastFrameCount) {
          if (this.lastFrameAt !== null && this.now() - this.lastFrameAt >= 6000) {
            this.fail(generation, new Error("media_stalled"));
            return;
          }
        } else if (hasFrameCounter && framesDecoded > 0) {
          this.lastFrameCount = framesDecoded;
          this.lastFrameAt = this.now();
        }
        const local = selected && report.get(selected.localCandidateId);
        const remote = selected && report.get(selected.remoteCandidateId);
        const rtt = selected && selected.currentRoundTripTime;
        const protocol = (value) => ["udp", "tcp", "tls"].includes(value) ? value : "unknown";
        if (local && local.candidateType !== "relay") throw new Error("non_relay_transport");
        if (hasFrameCounter && framesDecoded > 0 && !this.playing) {
          this.playing = true;
          this.clearTimer(this.firstFrameTimer);
          this.firstFrameTimer = null;
          this.quotaSeen = false;
          this.clearOwnQuotaCooldown();
          this.quotaFailures = this.retryFailures = 0;
          this.retryNotBefore = this.quotaNotBefore = 0;
          this.state("playing");
        }
        if (this.options.onStats) this.options.onStats({ candidateType: local?.candidateType,
          roundTripMs: typeof rtt === "number" && Number.isFinite(rtt) ? Math.round(rtt * 1000) : null,
          localProtocol: protocol(local?.protocol), relayProtocol: protocol(local?.relayProtocol),
          remoteProtocol: protocol(remote?.protocol),
          framesDecoded });
      } catch (error) {
        if (error.message === "non_relay_transport") { this.fail(generation, error); return; }
        // Stats are optional and must not interrupt decoding when a browser lacks a report field.
      } finally {
        if (this.current(generation)) this.statsPending = false;
      }
      if (this.current(generation)) this.statsTimer = this.setTimer(() => {
        // A queued callback from a released peer must not clear its replacement's timer.
        if (!this.current(generation)) return;
        this.statsTimer = null;
        this.pollStats(generation);
      }, this.playing ? 3000 : 1000);
    }

    deleteSession(session) {
      // Deletion is best effort and deliberately independent of aborted offer/candidate requests.
      const abort = new AbortController();
      const timer = this.setTimer(() => abort.abort(), 3000);
      Promise.resolve().then(() => this.fetch(session, { method: "DELETE", credentials: "same-origin", mode: "same-origin",
        redirect: "error", cache: "no-store", keepalive: true, signal: abort.signal }))
        .catch(() => {}).finally(() => this.clearTimer(timer));
    }

    release() {
      this.requests.forEach((request) => request.abort());
      this.requests.clear();
      this.clearTimer(this.statsTimer);
      this.clearTimer(this.connectionTimer);
      this.clearTimer(this.firstFrameTimer);
      this.statsTimer = this.connectionTimer = this.firstFrameTimer = null;
      if (this.pc) {
        this.pc.onconnectionstatechange = this.pc.onicecandidateerror = this.pc.onicecandidate = this.pc.ontrack = null;
        this.pc.close(); this.pc = null;
      }
      if (this.session) { this.deleteSession(this.session); this.session = null; }
      this.offer = null;
      this.candidates = [];
      this.patchRunning = false;
      this.opening = this.statsPending = this.playing = false;
      this.lastFrameCount = this.lastFrameAt = null;
    }

    fail(generation, error) {
      if (!this.current(generation)) return;
      const reason = safeFailureCode(error);
      const auth = /^http_(401|403)$/.test(reason);
      const quota = this.quotaSeen;
      ++this.generation;
      this.release();
      if (this.options.onError) this.options.onError(reason);
      this.authenticationBlocked = auth;
      this.state(auth ? "authentication_failed" : reason === "media_stalled" ? "media_stalled" : "retrying");
      if (!auth) {
        this.retryFailures = Math.min(this.retryFailures + 1, 5);
        if (quota) {
          this.quotaNotBefore = Math.max(this.quotaNotBefore, this.now() + 60_000);
          this.saveQuotaCooldown();
        }
        this.retryNotBefore = Math.max(this.retryNotBefore, this.now() +
          (quota ? 60_000 : Math.min(2000 * (2 ** (this.retryFailures - 1)), 30_000)));
        this.scheduleStart();
      }
    }

    close() {
      if (this.closed) return;
      this.closed = true;
      ++this.generation;
      this.clearTimer(this.retry);
      this.clearTimer(this.networkTimer);
      this.retry = this.networkTimer = null;
      this.release();
      this.state("closed");
    }
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { FollowingCarWhepReader, iceServersFromLink, parseOffer, candidateFragment, sessionLocation, safeFailureCode };
  } else root.FollowingCarWhepReader = FollowingCarWhepReader;
})(globalThis);
