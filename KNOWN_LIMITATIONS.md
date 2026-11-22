# Known limitations (honest list)

**Verification gaps**
- The Android app has never been built by Gradle, installed, or run. Only a Kotlin compile against hand-written API stubs was done (language-level; it cannot prove the stubs match the real Android/WebRTC/OkHttp signatures).
- Real phone → browser streaming, Android 8–15 behaviour, MediaProjection/foreground-service behaviour, hotspot modes, TURN and cross-network use are all **untested**.
- The e2e test uses a *simulated* sender in Chromium on loopback/host candidates, not the Android WebRTC stack.

**Functional**
- Android 14+: after rotating the phone the picture is letter-boxed instead of resized (this WebRTC version's `changeCaptureFormat` would create a second VirtualDisplay, which Android 14 forbids). Fix = custom capturer using `VirtualDisplay.resize()`.
- No audio. No remote control/input. Android 15 lets the user share a single app instead of the whole screen; the app does not prevent that choice.
- Video only; encoder limits come from the Quality setting (Low/Balanced/High), no adaptive tuning for screen-content (`degradationPreference` not set).
- One viewer per session. Stopping always ends the session (new code needed). If the phone is offline when Stop is pressed, the server session lingers ≤ 2 minutes.
- Refreshing the browser keeps the session only in the same tab (token in `sessionStorage`); a new tab/browser needs a new code.
- Debug build only: release signing, R8/ProGuard keep rules and Play-Store packaging are not done. `google-webrtc:1.0.32006` native libraries are not 16 KB-page-aligned (matters for Play Store targeting Android 15+ and 16 KB-page devices).
- Chrome hides its LAN IP behind mDNS names; the connection is expected to work because the phone's real address is reachable (browser-initiated ICE checks), but this is untested with a real phone.
- `usesCleartextTraffic` is on (needed for LAN `http://`); the app itself refuses `http://` to non-local hosts.
- Offline LAN signaling is unencrypted on the local network; use HTTPS/WSS beyond a trusted LAN.
- Wi-Fi Direct is not implemented (future investigation).
