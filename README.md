# ARIX SCREEN SHARING

Android phone → computer browser screen mirroring over WebRTC. The phone makes a six-digit code, you type it in the
browser, and the browser shows the phone's screen live. Android **always** shows its own screen-capture permission dialog.

> **Status (read this first):** backend and browser receiver are tested (see `TEST_REPORT.md`), including a full
> WebRTC session against a *simulated* sender. The **Android app has never been built into an APK or run on a phone** —
> the development sandbox had no Android SDK and no access to Google/Maven. Live phone-to-browser streaming is therefore
> **NOT TESTED**. Build the APK (section 3) and run the checklist in `TEST_REPORT.md` before trusting it.

```
android/   Kotlin app: MediaProjection + foreground service + WebRTC sender + signaling client
backend/   FastAPI signaling server (+ serves the browser page) and pytest tests
web/       Browser receiver (index.html, app.js, styles.css)
tests/e2e  Chromium end-to-end test with a simulated sender
docs/      offline-mode spec, coturn/Caddy examples, Phase 2 history
```

## 1. Run the server (the computer that shows the screen)
Needs Python 3.10+.
- **Windows:** double-click `backend\run_windows.bat`. Allow Windows Firewall on *Private* networks when asked.
- **macOS/Linux:** `./backend/run.sh`

Open `http://127.0.0.1:8000` on the computer. The page shows the address the phone must use.

## 2. Network modes
| Mode | Setup | Server address in the app |
|---|---|---|
| Same Wi-Fi router (offline OK) | Phone + computer on the same Wi-Fi. No internet needed. | `http://<computer LAN IP>:8000` (Windows: `ipconfig`, macOS/Linux: `ip a`/`ifconfig`) |
| Phone hotspot | Turn on the phone hotspot, join the computer to it. | `http://<computer IP on the hotspot>:8000` |
| Computer hotspot | Turn on the computer's hotspot, join the phone to it. | `http://<computer's hotspot IP>:8000` (Windows default is usually `192.168.137.1`; verify with `ipconfig`) |
| Online (different networks) | Deploy the server with HTTPS and a TURN server — section 5. | `https://screen.example.com` |

Notes: if the router/hotspot has "client isolation" or "AP isolation" on, phone and computer cannot see each other. In
fully offline LAN mode no STUN/TURN is configured (the default), so nothing tries to reach the internet.
Wi-Fi Direct is **not implemented**.

## 3. Build the Android app
Requirements: JDK 17, Android SDK Platform 35.
- **Android Studio:** open the `android/` folder → sync → Run on a real device (Android 8.0+). 
- **Command line:** `cd android && ./gradlew assembleDebug` → `android/app/build/outputs/apk/debug/app-debug.apk`
- **No local Android tooling:** push this folder to a GitHub repo; `.github/workflows/ci.yml` builds and uploads the
  debug APK as the artifact `arix-debug-apk` (Actions tab), and runs the unit tests.

Install: copy the APK to the phone and open it (allow "install unknown apps"), or `adb install app-debug.apk`.

**WebRTC dependency:** the app uses `org.webrtc:google-webrtc:1.0.32006` (unchanged from Phase 2; as far as I know it is
unmaintained). If Gradle cannot resolve it, replace the line in `android/app/build.gradle` with a maintained build that
keeps the same `org.webrtc` package (for example `io.github.webrtc-sdk:android` — check the current version on Maven
Central) and fix any API differences the compiler reports.

## 4. Use it
1. Computer: server running, browser open at its address.
2. Phone: open **ARIX Screen Sharing**, type the server address, tap **Generate pairing code** (valid 5 minutes).
3. Computer: enter the six digits → **Connect device**.
4. Phone: tap **Approve & start screen sharing** → accept Android's dialog (Android 13+ also asks for notification
   permission: it is required because the sharing notification with its Stop button must be visible).
5. The computer shows the live screen. Use **Fullscreen** (or double-click the video) and **Disconnect**.
6. Stop from the app's red **Stop sharing** button, the notification's **Stop sharing**, or by revoking capture in Android.
   Each stop ends the session; start again with a new code.

Refreshing the browser tab and re-entering the code in the same tab resumes the session (the tab remembers a private token).
A second browser cannot take over a code that is already in use.

## 5. Online mode (cross-network) — NOT TESTED
1. Put the backend behind HTTPS/WSS (`docs/deploy/Caddyfile.example`).
2. Run coturn with a shared secret (`docs/deploy/coturn.conf.example`).
3. Start the backend with the variables from `backend/.env.example` (`ARIX_STUN_URLS`, `ARIX_TURN_URLS`, `ARIX_TURN_SECRET`).
   The TURN secret stays on the server; peers receive short-lived HMAC credentials over the authenticated WebSocket only.
4. Use `https://…` as the server address in the app (plain `http://` is refused for non-local addresses).
Until a real cross-network test passes, do not rely on it.

## 6. Run the tests
```bash
pip install -r backend/requirements-dev.txt
cd backend && python -m pytest -q                       # signaling server (23 tests)
cd ../tests/e2e && npm install && npx playwright install chromium && ./run_e2e.sh   # browser + simulated sender
cd ../../android && ./gradlew testDebugUnitTest          # Kotlin unit tests (needs Android SDK)
```

## 7. Security model (summary)
- Android's system consent is requested for every share; the consent token is never stored or reused; the service never auto-restarts.
- Visible indicators: persistent notification + in-app status. Stop is immediate: capture is released before network cleanup.
- Pairing code: 6 digits, valid 5 min until claimed; the phone gets a secret sender token, the first browser to join gets a
  receiver token; no third party can join. Per-IP and global rate limits on code creation and failed joins; WebSocket origin check.
- Offline LAN traffic is plain `ws://`/`http://` on your local network (not encrypted). Use HTTPS/WSS for anything beyond a trusted LAN. WebRTC media itself is always encrypted (DTLS-SRTP).
