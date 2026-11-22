/* End-to-end test: real uvicorn server + real browser receiver page (Chromium) + SIMULATED sender.
 * Usage: node e2e.js http://127.0.0.1:8765      (server must be running; see run_e2e.sh)
 * Not an Android test. */
const { chromium } = require("playwright");
const path = require("path");
const BASE = process.argv[2] || "http://127.0.0.1:8765";
const results = [];
const rec = (name, ok, note = "") => { results.push({ name, ok }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${note ? "  — " + note : ""}`); };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const frames = p => p.evaluate(() => { const v = document.getElementById("remoteVideo"); return v.videoWidth > 0 ? v.getVideoPlaybackQuality().totalVideoFrames : 0; });
async function framesAdvance(p, ms = 2000) { const a = await frames(p); await sleep(ms); const b = await frames(p); return { ok: b > a && b >= 5, a, b }; }
const stateText = p => p.locator("#receiverState").innerText();

(async () => {
  const browser = await chromium.launch({ args: ["--disable-features=WebRtcHideLocalIpsWithMdns", "--autoplay-policy=no-user-gesture-required"] });
  const ctx = await browser.newContext();
  const consoleProblems = [];
  const watch = p => { p.on("console", m => { if (["error", "warning"].includes(m.type()) && !/favicon/.test(m.text())) consoleProblems.push(m.text()); }); p.on("pageerror", e => consoleProblems.push("pageerror: " + e.message)); };

  async function newSim() {
    const page = await ctx.newPage(); await page.goto(BASE + "/api/health");
    await page.addScriptTag({ path: path.join(__dirname, "sim_sender.js") });
    const code = await page.evaluate(async () => { window.sim = new SimSender(); return await sim.create(); });
    await page.evaluate(() => sim.connect());
    return { page, code };
  }
  async function newReceiver() { const p = await ctx.newPage(); watch(p); await p.goto(BASE + "/"); return p; }
  async function join(p, code) { await p.fill("#pin", code); await p.click("#connect"); }

  try {
    // ---- 1. page basics
    const rcv = await newReceiver();
    rec("receiver page shows server address for the app", (await rcv.locator("#serverAddr").innerText()) === BASE);
    rec("video stage hidden before connecting (.hidden bug fixed)", !(await rcv.locator("#stage").isVisible()));
    await join(rcv, "12345");
    rec("short code rejected client-side", /six-digit/i.test(await stateText(rcv)));

    // ---- 2. wrong code
    await join(rcv, "000000");
    await rcv.waitForFunction(() => /invalid or has expired/i.test(document.getElementById("receiverState").textContent), null, { timeout: 5000 })
      .then(() => rec("wrong code shows clear error", true), async () => rec("wrong code shows clear error", false, await stateText(rcv)));

    // ---- 3. pairing + live video (sender joins first, receiver second)
    const { page: sp, code } = await newSim();
    await rcv.fill("#pin", code); await rcv.click("#connect");
    await rcv.waitForFunction(() => /Live screen connected/.test(document.getElementById("receiverState").textContent), null, { timeout: 20000 })
      .then(() => rec("pairing + WebRTC connect (simulated sender)", true), async () => rec("pairing + WebRTC connect (simulated sender)", false, await stateText(rcv)));
    const f1 = await framesAdvance(rcv);
    rec("remote video frames are actually advancing", f1.ok, `frames ${f1.a} -> ${f1.b}`);
    rec("video stage visible when live", await rcv.locator("#stage").isVisible());
    await rcv.waitForFunction(() => /\d+×\d+/.test(document.getElementById("statsLine").textContent), null, { timeout: 8000 }).then(() => rec("resolution/fps stats shown", true), () => rec("resolution/fps stats shown", false));

    // ---- 4. fullscreen
    await rcv.click("#fsBtn"); await sleep(400);
    const fs = await rcv.evaluate(() => document.fullscreenElement && document.fullscreenElement.id);
    rec("fullscreen button enters fullscreen", fs === "stage", String(fs));
    if (fs) { await rcv.click("#fsBtn"); await sleep(300); }

    // ---- 5. signaling drop: media must continue, reconnect must NOT renegotiate
    const nidBefore = await sp.evaluate(() => sim.nid), readiesBefore = await sp.evaluate(() => sim.readies.length);
    await sp.evaluate(() => sim.dropSocket()); await sleep(1500);
    const f2 = await framesAdvance(rcv, 1500);
    rec("video keeps flowing while phone signaling is down", f2.ok, `frames ${f2.a} -> ${f2.b}`);
    await sp.evaluate(() => sim.connect()); await sleep(1500);
    const nidAfter = await sp.evaluate(() => sim.nid), readiesAfter = await sp.evaluate(() => sim.readies.length);
    rec("sender reconnect to signaling keeps healthy stream (no renegotiation)", nidBefore === nidAfter && readiesBefore === readiesAfter);
    const f3 = await framesAdvance(rcv, 1500); rec("video still flowing after signaling reconnect", f3.ok);

    // ---- 6. browser refresh -> rejoin with stored token -> fresh negotiation
    await rcv.reload();
    await join(rcv, code);
    await rcv.waitForFunction(() => /Live screen connected/.test(document.getElementById("receiverState").textContent), null, { timeout: 20000 })
      .then(() => rec("browser refresh + rejoin (token) renegotiates and goes live", true), async () => rec("browser refresh + rejoin (token) renegotiates and goes live", false, await stateText(rcv)));
    const nidNew = await sp.evaluate(() => sim.nid);
    rec("new negotiation id used after refresh", nidNew !== nidAfter);
    const f4 = await framesAdvance(rcv, 1500); rec("video flowing after refresh", f4.ok, `frames ${f4.a} -> ${f4.b}`);

    // ---- 7. second browser cannot hijack a claimed code
    const intruder = await newReceiver(); await join(intruder, code);
    await intruder.waitForFunction(() => /already in use/i.test(document.getElementById("receiverState").textContent), null, { timeout: 5000 })
      .then(() => rec("second browser cannot take over a claimed code", true), () => rec("second browser cannot take over a claimed code", false));
    await intruder.close();

    // ---- 8. sender stop ends session for receiver
    await sp.evaluate(() => sim.stop()); 
    await rcv.waitForFunction(() => /phone stopped sharing/i.test(document.getElementById("receiverState").textContent), null, { timeout: 5000 })
      .then(() => rec("phone Stop ends session on the receiver", true), async () => rec("phone Stop ends session on the receiver", false, await stateText(rcv)));
    rec("video stage hidden after stop", !(await rcv.locator("#stage").isVisible()));
    rec("connect button re-enabled after stop", await rcv.locator("#connect").isEnabled());

    // ---- 9. Disconnect button
    const { page: sp2, code: code2 } = await newSim();
    await join(rcv, code2);
    await rcv.waitForFunction(() => /Live screen connected/.test(document.getElementById("receiverState").textContent), null, { timeout: 20000 });
    await rcv.click("#disconnectBtn"); await sleep(500);
    rec("Disconnect button resets UI", /Disconnected/.test(await stateText(rcv)) && !(await rcv.locator("#stage").isVisible()) && (await rcv.locator("#connect").isEnabled()));
    await sp2.close(); await sp.close();

    // ---- 10. no console errors / CSP violations on the receiver page
    rec("no console errors or CSP violations on receiver page", consoleProblems.length === 0, consoleProblems.join(" | "));
  } catch (e) {
    rec("test harness crashed", false, e.stack);
  } finally {
    await browser.close();
  }
  const failed = results.filter(r => !r.ok).length;
  console.log(`\n${results.length - failed}/${results.length} passed`);
  process.exit(failed ? 1 : 0);
})();
