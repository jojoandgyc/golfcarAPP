"use strict";

// This page receives only video. It never reads camera/microphone or sends vehicle commands.
const video = document.getElementById("video");
const message = document.getElementById("message");
const quality = document.getElementById("quality");
const states = {
  connecting: "正在连接远程画面…",
  playing: "",
  retrying: "画面连接中断，正在重连…",
  media_stalled: "画面停止更新，正在重连…",
  quota_wait: "中转资源繁忙，稍后自动重试…",
  authentication_failed: "视频认证失败，请检查远程凭据",
  unavailable: "暂无画面，请检查视频服务",
  closed: "预览已暂停",
};
let titleRevision = 0;
let errorSequence = 0;
const errorEvents = [];
const reader = new FollowingCarWhepReader({
  url: new URL("/rgbd/whep", location.origin).href,
  // Fixed codes only; browser exception messages can contain SDP or TURN credentials.
  onError: (reason) => {
    document.documentElement.dataset.failure = reason;
    errorEvents.push({ sequence: ++errorSequence, code: reason });
    if (errorEvents.length > 8) errorEvents.shift();
    // Native title callbacks may coalesce. Read this bounded fixed-code history on any
    // state/error title and deduplicate sequence within the current document.
    document.documentElement.dataset.errorEvents = JSON.stringify(errorEvents);
    ++titleRevision;
    document.title = `FollowingCarVideoError:${reason}`;
  },
  onState: (state) => {
    // A separate task lets the native receiver observe the fixed error code before state.
    const revision = ++titleRevision;
    setTimeout(() => { if (revision === titleRevision) document.title = `FollowingCarVideo:${state}`; }, 0);
    document.documentElement.dataset.state = state;
    message.textContent = states[state] ?? states.unavailable;
    if (state === "playing") delete document.documentElement.dataset.failure;
    else {
      quality.textContent = "";
      video.srcObject = null;
      document.documentElement.dataset.framesDecoded = "0";
      document.documentElement.dataset.roundTripMs = "";
    }
  },
  onTrack: (event) => {
    video.srcObject = event.streams[0] || new MediaStream([event.track]);
    video.play().catch(() => {});
  },
  onStats: (stats) => {
    // Only aggregate measurements are exposed; no addresses, SDP, tokens or ICE credentials.
    document.documentElement.dataset.candidateType = stats.candidateType || "unknown";
    document.documentElement.dataset.framesDecoded = String(stats.framesDecoded || 0);
    document.documentElement.dataset.roundTripMs = stats.roundTripMs == null ? "" : String(stats.roundTripMs);
    document.documentElement.dataset.localProtocol = stats.localProtocol;
    document.documentElement.dataset.relayProtocol = stats.relayProtocol;
    document.documentElement.dataset.remoteProtocol = stats.remoteProtocol;
    document.documentElement.dataset.statsSequence = String(Number(document.documentElement.dataset.statsSequence || 0) + 1);
    if (document.documentElement.dataset.state === "playing")
      quality.textContent = stats.roundTripMs == null ? "服务器中转" : `视频 RTT ${stats.roundTripMs} ms`;
  },
});
// Fixed no-argument API: Android reports a default-network change without passing addresses.
// The reader debounces notifications and retains quota cooldowns across these restarts.
window.followingCarNetworkChanged = () => reader.networkChanged();
window.addEventListener("online", window.followingCarNetworkChanged);
window.addEventListener("pagehide", () => reader.pause());
window.addEventListener("pageshow", () => { if (document.visibilityState !== "hidden") reader.resume(); });
window.addEventListener("beforeunload", () => reader.close());
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") reader.pause();
  else reader.resume();
});
if (document.visibilityState === "hidden") reader.pause();
else reader.start();
