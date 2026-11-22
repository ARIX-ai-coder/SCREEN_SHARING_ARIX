/* Browser-side SIMULATED Android sender. Speaks the exact same protocol as ScreenShareService.kt
 * (welcome -> ready{nid} -> offer{nid} -> answer{nid} -> ICE{nid}) but sends a canvas video track.
 * It is NOT the Android app and proves nothing about MediaProjection/Android WebRTC. */
window.SimSender = class {
  constructor() { this.readies = []; this.iceState = ""; this.pending = []; this.remoteSet = false; this.paired = 0; }
  async create() {
    const r = await fetch("/api/session", { method: "POST" });
    this.s = await r.json();
    const c = document.createElement("canvas"); c.width = 640; c.height = 360;
    const g = c.getContext("2d"); let t = 0;
    setInterval(() => { t++; g.fillStyle = "#123"; g.fillRect(0, 0, 640, 360); g.fillStyle = "#fc0"; g.fillRect((t * 7) % 600, 150, 40, 40); }, 50);
    this.track = c.captureStream(15).getVideoTracks()[0];
    return this.s.code;
  }
  connect() {
    const ws = new WebSocket(`ws://${location.host}/ws/${this.s.code}/sender?token=${this.s.sender_token}`);
    this.ws = ws;
    ws.onmessage = e => this.onMsg(JSON.parse(e.data));
    return new Promise(res => { ws.onopen = () => res(true); });
  }
  send(o) { this.ws.send(JSON.stringify(o)); }
  onMsg(m) {
    if (m.type === "welcome") this.ice = m.ice_servers;
    else if (m.type === "paired") this.paired++;
    else if (m.type === "session_ended") this.ended = m.reason;
    else if (m.type === "signal") {
      const d = m.data;
      if (d.type === "ready") { this.readies.push(d.nid); if (d.nid !== this.nid || !this.pc) this.begin(d.nid); }
      else if (d.type === "answer" && d.nid === this.nid) this.onAnswer(d);
      else if (d.type === "ice_candidate" && d.nid === this.nid) this.onIce(d.candidate);
    }
  }
  async begin(nid) {
    if (this.pc) this.pc.close();
    this.nid = nid; this.pending = []; this.remoteSet = false;
    const pc = new RTCPeerConnection({ iceServers: this.ice || [] }); this.pc = pc;
    pc.addTrack(this.track, new MediaStream([this.track]));
    pc.onicecandidate = e => { if (e.candidate && this.nid === nid) this.send({ type: "ice_candidate", nid, candidate: e.candidate.toJSON() }); };
    pc.oniceconnectionstatechange = () => { this.iceState = pc.iceConnectionState; };
    const offer = await pc.createOffer();
    this.send({ type: "offer", sdp: offer.sdp, nid });      // offer first, then setLocalDescription (same order as Android)
    await pc.setLocalDescription(offer);
  }
  async onAnswer(d) {
    await this.pc.setRemoteDescription({ type: "answer", sdp: d.sdp });
    this.remoteSet = true;
    for (const c of this.pending.splice(0)) await this.pc.addIceCandidate(c);
  }
  async onIce(c) {
    if (!c || !c.candidate) return;
    if (this.remoteSet) await this.pc.addIceCandidate(c); else this.pending.push(c);
  }
  dropSocket() { this.ws.close(); }
  stop() { this.send({ type: "stop" }); }
};
