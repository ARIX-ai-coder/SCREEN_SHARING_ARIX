# ARIX SCREEN SHARING — Test report (v0.3.0)

Legend: **PASS** = executed and succeeded · **FAIL** = executed and failed · **NOT TESTED** = never executed.
Environment: Linux sandbox, Python 3.13, Chromium (Playwright, headless), Kotlin compiler 2.4 — **no Android SDK, no Gradle
access, no phone**. Anything involving those is NOT TESTED.

## Backend (`backend/tests`, 23 tests, `python -m pytest`)
| Item | Result |
|---|---|
| Health endpoint | PASS |
| Session creation (6 digits, token, TTL, uniqueness) | PASS |
| Code expiry (unclaimed code; claimed session outlives pairing TTL; grace expiry) | PASS |
| WebSocket pairing, `welcome`/`paired` messages | PASS |
| Message relay both directions, payload preserved | PASS |
| Disconnect handling, `peer_disconnected`, reconnect with token, stale-socket replacement | PASS |
| Sender `stop` ends session; receiver cannot stop it | PASS |
| Auth: sender token, receiver token, no second receiver, unknown role/code | PASS |
| Rate limits (code creation, failed joins), origin check | PASS |
| Malformed/oversized/binary input does not kill the socket | PASS |
| TURN credential format (HMAC-SHA1 per TURN REST spec) and secret not exposed over HTTP | PASS (format only) |
| coturn actually accepting those credentials | NOT TESTED |
| Behaviour under load / behind a reverse proxy | NOT TESTED |

## Browser receiver
| Item | Result |
|---|---|
| JavaScript syntax (`node --check`) | PASS |
| WebSocket connection, welcome, token storage | PASS (Chromium e2e, 21/21 checks, repeated 4× stable) |
| SDP offer/answer exchange with negotiation ids | PASS — **simulated sender** |
| ICE exchange incl. early-candidate queue | PASS — **simulated sender** |
| Video playback (frames advancing, resolution/fps shown) | PASS — **simulated sender** (canvas track, loopback) |
| Keeps playing while phone signaling is down; no renegotiation on signaling reconnect | PASS — simulated |
| Browser refresh + rejoin renegotiates | PASS — simulated |
| Second browser cannot hijack a code | PASS |
| Fullscreen, Disconnect, error messages, phone-stop handling | PASS (Chromium headless) |
| No console errors / CSP violations | PASS |
| Layout desktop 1280 px and mobile 390 px (visually inspected screenshots) | PASS (Chromium only) |
| Firefox, Safari, Edge, iOS/Android browsers | NOT TESTED |
| Receiving a real Android stream (H.264/VP8 from Android WebRTC) | NOT TESTED |

## Android
| Item | Result |
|---|---|
| Kotlin compile against hand-written API stubs (language level only) | PASS — *not* a Gradle build |
| Pure-logic checks (address validation, capture scaling, quality, WS URL) — 28 assertions run on a JVM | PASS |
| `ServerAddress.normalize()` (needs real OkHttp) and the JUnit test classes | NOT TESTED (run in CI) |
| Gradle build | NOT TESTED — blocked: sandbox cannot reach dl.google.com / maven.google.com / repo1.maven.org / services.gradle.org (HTTP 403) and has no Android SDK |
| `google-webrtc:1.0.32006` dependency resolution / API compatibility | NOT TESTED |
| APK generation, installation | NOT TESTED |
| MediaProjection consent dialog; consent required every time | NOT TESTED |
| Foreground service start timing (Android 8–15) | NOT TESTED |
| Notification + Stop action; in-app Stop button | NOT TESTED |
| Immediate stop on permission revocation | NOT TESTED |
| Permission handling (POST_NOTIFICATIONS), rotation, lifecycle | NOT TESTED |
| Resource release (WebRTC objects, sockets, wake locks) | NOT TESTED |

## End-to-end with real devices
| Item | Result |
|---|---|
| Phone + laptop same LAN, pairing with six-digit code, live video | NOT TESTED |
| Disconnect / reconnect with the real phone | NOT TESTED |
| Phone hotspot / computer hotspot modes | NOT TESTED |
| Online mode, TURN, cross-network (incl. carrier NAT) | NOT TESTED |

## First real-device checklist (do these in order)
1. CI or Android Studio builds the APK. (Compile errors → send me the log, I'll fix them.)
2. Install, open app, generate code against `http://<laptop IP>:8000`.
3. Enter code in browser → approve capture → does video appear? Rotate phone. Press Stop (app, notification).
4. Revoke capture from Android's status bar/quick settings → sharing must end at once.
5. Refresh browser tab → re-enter code → video returns. Turn phone Wi-Fi off/on → see reconnect.
