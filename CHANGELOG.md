# Changelog

## 0.3.0 — Phase A/B/C continuation (Android source repair, protocol v2, browser receiver)

### Backend — `backend/main.py` (rewritten, same endpoints and relay envelope)
Fixed: sessions deleted after 5 min even while streaming · roles not exclusive · no auth/rate limit · relay crash on bad JSON or dead peer · stale `/api/health`.
Added: sender/receiver tokens, `welcome` message with ICE servers (+ short-lived TURN credentials, secret stays server-side), grace period for reconnects, `stop`/`session_ended`, rate limits, origin check, CSP/security headers, `/api/config`.
New: `backend/tests/test_signaling.py` (23 tests), `requirements-dev.txt`, `run.sh`, `.env.example`; `run_windows.bat` fixed to work from any directory.

### Android — `android/`
Fixed (compile): missing `MediaProjection` import; missing `androidx.activity` dependency; no Gradle wrapper (added `gradlew`, Gradle 8.9).
Fixed (runtime): `stopSelf()` before `startForeground()` crash path · reconnect/second `ready` never rebuilt the peer · ICE candidates not queued · stop callback on capture thread + no re-entrancy guard · hard-coded 1280×720 landscape capture · notification permission never requested · silent WebSocket failures, no reconnect · no service-state UI · lost state on rotation · cleartext allowed to any host.
Changed files: `MainActivity.kt`, `ScreenShareService.kt` (rewritten), `build.gradle`, `AndroidManifest.xml`, `gradle.properties`.
New files: `ScreenSender.kt`, `SignalingClient.kt`, `ShareState.kt`, `ServerAddress.kt`, `Quality.kt`, `SdpObserverAdapter.kt`, resources (icon, notification icon, colors), unit tests, Gradle wrapper.
Removed: `android/NEXT_STEP.md` (obsolete placeholder).

### Browser — `web/`
`app.js` rewritten (readable, protocol v2: negotiation ids, ICE queue, reconnect, fullscreen, disconnect, error messages, stats). `index.html`: left card is now phone instructions (the old "generate code on the website" sender socket conflicted with the Android flow and was removed); video stage section; honest notice. `styles.css`: added missing `.hidden`, removed Google Fonts `@import` (breaks offline mode), stage/fullscreen styles. New `addr.js` (shows the server address; separate file because CSP forbids inline scripts).

### Other
New: `tests/e2e/` (Chromium + simulated sender), `.github/workflows/ci.yml`, `docs/deploy/*`, README, TEST_REPORT, KNOWN_LIMITATIONS. Moved `PHASE2_STATUS.md` → `docs/history/`, `OFFLINE_MODE_SPEC.md` → `docs/`.
