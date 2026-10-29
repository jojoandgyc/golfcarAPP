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
  authentication_failed: "视频认证失败，请检查远程凭据",
  unavailable: "暂无画面，请检查视频服务",
  closed: "预览已暂停",
};
const reader = new FollowingCarWhepReader({
  url: new URL("/rgbd/whep", location.origin).href,
  // Fixed codes only; browser exception messages can contain SDP or TURN credentials.
  onError: (reason) => { document.documentElement.dataset.failure = reason; },
  onState: (state) => {
    document.title = `FollowingCarVideo:${state}`;
    document.documentElement.dataset.state = state;
    message.textContent = states[state] ?? states.unavailable;
    if (state !== "playing") quality.textContent = "";
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
    quality.textContent = stats.roundTripMs == null ? "服务器中转" : `视频 RTT ${stats.roundTripMs} ms`;
  },
});
reader.start();
window.addEventListener("pagehide", () => reader.close());
window.addEventListener("beforeunload", () => reader.close());
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") reader.close();
});
