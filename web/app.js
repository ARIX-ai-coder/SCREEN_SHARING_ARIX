"use strict";
/* ARIX Screen Sharing — browser receiver (protocol v2)
 * Flow: join with the 6-digit code -> server says "paired" -> we send {ready, nid} -> phone offers -> we answer
 * -> ICE both ways. Every message of one negotiation carries the same `nid`; anything else is ignored, which makes
 * refreshes / reconnects race-free. Session lifecycle & signaling are documented in backend/main.py. */

const $ = id => document.getElementById(id);
const MAX_OUTAGE_MS = 150000;      // keep trying to reach the server this long after a drop
const READY_RETRY_MS = 3000, READY_MAX_TRIES = 10;
const DISCONNECT_GRACE_MS = 15000; // "disconnected" ICE state may recover on its own

const S = {
  ws: null, code: null, welcomed: false, userClosed: false, ended: false, fatal: false,
  attempt: 0, outageSince: 0, reconnectTimer: null,
  iceServers: [], phonePresent: false,
  pc: null, nid: null, remoteSet: false, pendingIce: [], offerReceived: false,
  readyTimer: null, readyTries: 0, failTimer: null, statsTimer: null,
};

/* ---------- small helpers ---------- */
function setState(text, tone) { const el = $("receiverState"); el.textContent = text; el.dataset.tone = tone || "info"; }
function randomId() { const a = new Uint8Array(9); crypto.getRandomValues(a); return Array.from(a, b => b.toString(16).padStart(2, "0")).join(""); }
function wsUrl(code, token) {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${location.host}/ws/${code}/receiver?token=${encodeURIComponent(token || "")}`;
}
function tokenKey(code) { return "arix.rt." + code; }
function loadToken(code) { try { return sessionStorage.getItem(tokenKey(code)) || ""; } catch (e) { return ""; } }
function saveToken(code, t) { try { sessionStorage.setItem(tokenKey(code), t); } catch (e) { /* private mode: reconnect after refresh just needs a new code */ } }
function clearToken(code) { try { sessionStorage.removeItem(tokenKey(code)); } catch (e) { /* ignore */ } }
function send(obj) { if (S.ws && S.ws.readyState === WebSocket.OPEN) { S.ws.send(JSON.stringify(obj)); return true; } return false; }

const ERRORS = {
  invalid_code: "That code is invalid or has expired. Generate a new code on your phone.",
  code_in_use: "This code is already in use by another browser. Generate a new code on your phone.",
  rate_limited: "Too many attempts. Wait a minute and try again.",
  origin: "The server refused this page's origin. Open the page from the server's own address.",
  unauthorized: "Not authorised for this session.",
};
const ENDED = { sender_stopped: "The phone stopped sharing.", expired: "The session expired." };

/* ---------- UI ---------- */
function showStage(on) { $("stage").classList.toggle("hidden", !on); }
function resetUi() { $("connect").disabled = false; $("disconnectBtn").classList.add("hidden"); $("pin").disabled = false; }
function busyUi() { $("connect").disabled = true; $("pin").disabled = true; $("disconnectBtn").classList.remove("hidden"); }

/* ---------- peer connection ---------- */
function closePeer() {
  clearInterval(S.readyTimer); clearTimeout(S.failTimer); clearInterval(S.statsTimer);
  S.readyTimer = S.failTimer = S.statsTimer = null;
  if (S.pc) {
    const pc = S.pc; S.pc = null;
    pc.ontrack = pc.onicecandidate = pc.onconnectionstatechange = null;
    try { pc.close(); } catch (e) { /* ignore */ }
  }
  S.remoteSet = false; S.pendingIce = []; S.offerReceived = false; S.nid = null;
}

function startNegotiation() {
  if (!S.ws || S.ws.readyState !== WebSocket.OPEN) return;
  closePeer();
  const nid = randomId(); S.nid = nid;
  const pc = new RTCPeerConnection({ iceServers: S.iceServers });
  S.pc = pc;
  pc.ontrack = e => {
    const v = $("remoteVideo");
    v.srcObject = e.streams[0] || new MediaStream([e.track]);
    showStage(true);
    v.play().catch(() => { /* autoplay blocked: user can press play (controls are shown) */ });
  };
  pc.onicecandidate = e => { if (e.candidate && S.pc === pc) send({ type: "ice_candidate", nid, candidate: e.candidate.toJSON() }); };
  pc.onconnectionstatechange = () => { if (S.pc === pc) onPeerState(pc.connectionState); };
  setState("Paired · requesting the live screen…", "info");
  S.readyTries = 0;
  const sendReady = () => {
    if (S.pc !== pc || S.offerReceived) return;
    if (++S.readyTries > READY_MAX_TRIES) { clearInterval(S.readyTimer); setState("The phone did not answer. Check it is still sharing, then press Disconnect and reconnect.", "err"); return; }
    send({ type: "ready", nid });
  };
  sendReady();
  S.readyTimer = setInterval(sendReady, READY_RETRY_MS);
}

function onPeerState(state) {
  clearTimeout(S.failTimer); S.failTimer = null;
  if (state === "connected") {
    setState("Live screen connected", "ok");
    showStage(true);
    startStats();
  } else if (state === "disconnected") {
    setState("Stream interrupted · trying to recover…", "warn");
    S.failTimer = setTimeout(() => { if (S.pc && S.pc.connectionState !== "connected") renegotiate("Stream lost"); }, DISCONNECT_GRACE_MS);
  } else if (state === "failed") {
    renegotiate("Connection failed");
  }
}

function renegotiate(why) {
  if (S.userClosed || S.ended) return;
  if (S.phonePresent && S.ws && S.ws.readyState === WebSocket.OPEN) {
    setState(why + " · reconnecting…", "warn");
    setTimeout(() => { if (!S.userClosed && !S.ended) startNegotiation(); }, 1000);
  } else {
    setState(why + " · waiting for the phone…", "warn");   // a new "paired" will restart negotiation
  }
}

async function onOffer(d) {
  const pc = S.pc;
  if (!pc || d.nid !== S.nid || S.offerReceived || !d.sdp) return;
  S.offerReceived = true; clearInterval(S.readyTimer);
  setState("Negotiating live screen…", "info");
  try {
    await pc.setRemoteDescription({ type: "offer", sdp: d.sdp });
    if (S.pc !== pc) return;
    S.remoteSet = true;
    for (const c of S.pendingIce.splice(0)) await pc.addIceCandidate(c).catch(() => {});
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    if (S.pc !== pc) return;
    send({ type: "answer", nid: S.nid, sdp: pc.localDescription.sdp });
  } catch (err) {
    setState("WebRTC error: " + err.message, "err");
  }
}

async function onRemoteIce(d) {
  if (!S.pc || d.nid !== S.nid || !d.candidate || !d.candidate.candidate) return;
  const ice = new RTCIceCandidate(d.candidate);
  if (!S.remoteSet) { S.pendingIce.push(ice); return; }    // queue until the offer has been applied
  try { await S.pc.addIceCandidate(ice); } catch (e) { /* stale candidate: harmless */ }
}

function startStats() {
  clearInterval(S.statsTimer);
  const pc = S.pc; if (!pc) return;
  S.statsTimer = setInterval(async () => {
    if (S.pc !== pc) return;
    try {
      const report = await pc.getStats();
      report.forEach(r => {
        if (r.type === "inbound-rtp" && r.kind === "video" && r.frameWidth) {
          $("statsLine").textContent = `${r.frameWidth}×${r.frameHeight}` + (r.framesPerSecond ? ` · ${Math.round(r.framesPerSecond)} fps` : "");
        }
      });
    } catch (e) { /* ignore */ }
  }, 2000);
}

/* ---------- signaling socket ---------- */
function openSocket() {
  const ws = new WebSocket(wsUrl(S.code, loadToken(S.code)));
  S.ws = ws;
  ws.onopen = () => { S.attempt = 0; S.outageSince = 0; };
  ws.onmessage = e => { let m; try { m = JSON.parse(e.data); } catch (err) { return; } if (ws === S.ws) onServerMessage(m); };
  ws.onclose = () => { if (ws === S.ws) onSocketClosed(); };
  ws.onerror = () => { /* onclose follows */ };
}

function onServerMessage(m) {
  switch (m.type) {
    case "welcome":
      S.welcomed = true; S.iceServers = m.ice_servers || []; S.phonePresent = !!m.peer_present;
      if (m.receiver_token) saveToken(S.code, m.receiver_token);
      setState(S.phonePresent ? "Code accepted · phone is here" : "Code accepted · waiting for the phone to start sharing", "info");
      break;
    case "paired":
      S.phonePresent = true;
      if (!(S.pc && S.pc.connectionState === "connected")) startNegotiation();   // keep a healthy stream through signaling blips
      break;
    case "peer_disconnected":
      S.phonePresent = false;
      if (!(S.pc && S.pc.connectionState === "connected")) setState("Phone disconnected · waiting for it to come back…", "warn");
      break;
    case "session_ended":
      S.ended = true; endSession(ENDED[m.reason] || "Session ended.");
      break;
    case "error":
      if (ERRORS[m.code]) { S.fatal = true; clearToken(S.code); endSession(ERRORS[m.code], "err"); }
      break;
    case "signal": {
      const d = m.data || {};
      if (d.type === "offer") onOffer(d); else if (d.type === "ice_candidate") onRemoteIce(d);
      break;
    }
  }
}

function onSocketClosed() {
  if (S.userClosed || S.ended || S.fatal) return;
  if (!S.welcomed) { endSession("Could not connect. Check the code and that this computer and the phone can reach the server.", "err"); return; }
  const now = Date.now();
  if (!S.outageSince) S.outageSince = now;
  if (now - S.outageSince > MAX_OUTAGE_MS) { endSession("Lost connection to the server and could not reconnect.", "err"); return; }
  S.attempt++;
  if (!(S.pc && S.pc.connectionState === "connected")) setState(`Reconnecting to the server (attempt ${S.attempt})…`, "warn");
  clearTimeout(S.reconnectTimer);
  S.reconnectTimer = setTimeout(openSocket, Math.min(15000, 1000 * 2 ** Math.min(S.attempt - 1, 4)));
}

function endSession(message, tone) {
  clearTimeout(S.reconnectTimer);
  closePeer();
  if (S.ws) { const ws = S.ws; S.ws = null; ws.onclose = null; try { ws.close(1000); } catch (e) { /* ignore */ } }
  $("remoteVideo").srcObject = null; showStage(false); $("statsLine").textContent = "";
  setState(message, tone || "info");
  resetUi();
}

/* ---------- user actions ---------- */
function connect() {
  const code = $("pin").value;
  if (!/^\d{6}$/.test(code)) { setState("Enter the six-digit code from your phone.", "err"); return; }
  Object.assign(S, { code, welcomed: false, userClosed: false, ended: false, fatal: false, attempt: 0, outageSince: 0, phonePresent: false });
  busyUi(); setState("Connecting…", "info");
  openSocket();
}

function disconnect() {
  S.userClosed = true; clearToken(S.code);
  endSession("Disconnected. Enter a new code to connect again.");
}

function toggleFullscreen() {
  const stage = $("stage");
  if (document.fullscreenElement) { document.exitFullscreen(); return; }
  (stage.requestFullscreen ? stage.requestFullscreen() : Promise.reject()).catch(() => {
    const v = $("remoteVideo"); if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();   // iOS Safari
  });
}

$("pin").addEventListener("input", () => { $("pin").value = $("pin").value.replace(/\D/g, "").slice(0, 6); });
$("pin").addEventListener("keydown", e => { if (e.key === "Enter") connect(); });
$("connect").addEventListener("click", connect);
$("disconnectBtn").addEventListener("click", disconnect);
$("fsBtn").addEventListener("click", toggleFullscreen);
$("remoteVideo").addEventListener("dblclick", toggleFullscreen);
document.addEventListener("fullscreenchange", () => { $("fsBtn").textContent = document.fullscreenElement ? "Exit fullscreen" : "Fullscreen"; });
window.addEventListener("beforeunload", () => { S.userClosed = true; closePeer(); if (S.ws) S.ws.close(); });
