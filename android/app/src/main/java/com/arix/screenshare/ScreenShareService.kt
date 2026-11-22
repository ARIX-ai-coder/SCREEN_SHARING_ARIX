package com.arix.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.DataChannel
import org.webrtc.SessionDescription

/**
 * Foreground service that owns one screen-sharing session.
 *
 * Threads
 *  - main:   Android callbacks, stopSharing(), notification updates.
 *  - worker: ALL session/WebRTC/signaling logic (single HandlerThread => no locking needed).
 *
 * Protocol (see backend/main.py): the browser drives negotiation. Every time it sends
 * {"type":"ready","nid":X} we build a brand-new PeerConnection for negotiation X, send an offer tagged X,
 * and ignore any answer/ICE that is not tagged X. This makes reconnects and refreshes race-free.
 */
class ScreenShareService : Service() {
    companion object {
        const val ACTION_START = "com.arix.screenshare.START"
        const val ACTION_STOP = "com.arix.screenshare.STOP"
        const val EXTRA_SERVER = "server"
        const val EXTRA_CODE = "code"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "arix_screen_share"
        private const val NOTIFICATION_ID = 7021
        private const val TAG = "ARIX"
        private const val DISCONNECT_GRACE_MS = 20_000L
        private val FATAL_SERVER_ERRORS = setOf("invalid_code", "unauthorized", "code_in_use", "rate_limited", "origin")
    }

    private val main = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    @Volatile private var worker: Handler? = null
    @Volatile private var stopping = false
    private var started = false
    private var http: OkHttpClient? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ---- worker-thread state ----
    @Volatile private var sender: ScreenSender? = null
    private var signaling: SignalingClient? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private var currentNid: String? = null
    private var remoteDescriptionSet = false
    private val pendingIce = ArrayList<IceCandidate>()
    private var disconnectTimeout: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while ARIX is sharing your screen"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // startForegroundService() obliges us to call startForeground() quickly - do it FIRST,
                // even if the request turns out to be invalid (otherwise Android kills the app).
                if (!enterForeground("Starting…")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (started) return START_NOT_STICKY       // already sharing; ignore duplicate start
                startSharing(intent)
            }
            ACTION_STOP -> {
                if (started) stopSharing("Sharing stopped.", notifyServer = true) else stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY   // never auto-restart: a restart would have no (and must not reuse) consent
    }

    // ---------------------------------------------------------------- start
    private fun startSharing(intent: Intent) {
        val server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        val quality = Quality.fromName(intent.getStringExtra(EXTRA_QUALITY))
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        if (server.isBlank() || code.length != 6 || token.isBlank() || projectionData == null ||
            resultCode != android.app.Activity.RESULT_OK
        ) {
            started = true
            stopSharing("Could not start: missing session or screen-capture permission.", notifyServer = false)
            return
        }

        started = true
        stopping = false
        ShareState.set(ShareState.Phase.CONNECTING, "Starting screen capture…")
        acquireWifiLock()
        val thread = HandlerThread("arix-session").also { it.start() }
        workerThread = thread
        val w = Handler(thread.looper)
        worker = w
        http = SignalingClient.newHttpClient()
        val (screenW, screenH) = currentScreenSize()
        val (capW, capH) = CaptureMath.scaledSize(screenW, screenH, quality.longSide)

        w.post {
            if (stopping) return@post
            try {
                val s = ScreenSender(applicationContext, projectionData, quality) {
                    // capture thread -> main thread; never touch WebRTC objects from here
                    main.post { stopSharing("Screen capture was stopped (revoked by you or by Android).", notifyServer = true) }
                }
                sender = s
                s.startCapture(capW, capH)
                if (stopping) { s.release(); sender = null; return@post }
                val client = SignalingClient(http!!, ServerAddress.senderWsUrl(server, code, token), w, signalingListener)
                signaling = client
                setPhase(ShareState.Phase.CONNECTING, "Connecting to the server…")
                client.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                main.post { stopSharing("Could not start screen capture: ${e.javaClass.simpleName}", notifyServer = false) }
            }
        }
    }

    // ---------------------------------------------------------------- signaling
    private val signalingListener = object : SignalingClient.Listener {
        override fun onConnected() {
            setPhase(ShareState.Phase.WAITING, "Connected. Enter the code on your computer.")
        }

        override fun onMessage(msg: JSONObject) {
            handleServerMessage(msg)
        }

        override fun onConnectionLost(attempt: Int) {
            setPhase(ShareState.Phase.RECONNECTING, "Lost connection to the server. Reconnecting (attempt $attempt)…")
        }

        override fun onGaveUp() {
            main.post { stopSharing("Lost connection to the server and could not reconnect.", notifyServer = false) }
        }
    }

    private fun handleServerMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "welcome" -> iceServers = parseIceServers(msg.optJSONArray("ice_servers"))
            "paired" -> {
                if (currentNid == null) setPhase(ShareState.Phase.WAITING, "Computer connected. Waiting for it to request the stream…")
            }
            "peer_disconnected" -> {
                // Media may keep flowing peer-to-peer without signaling, so keep a live peer; the ICE state decides.
                if (sender?.peer == null) setPhase(ShareState.Phase.WAITING, "Computer disconnected. Waiting for it to come back…")
            }
            "signal" -> msg.optJSONObject("data")?.let { handleSignal(it) }
            "session_ended" -> {
                val reason = msg.optString("reason")
                main.post { stopSharing("Session ended ($reason).", notifyServer = false) }
            }
            "error" -> {
                val code = msg.optString("code")
                if (code in FATAL_SERVER_ERRORS) {
                    val text = msg.optString("message", code)
                    main.post { stopSharing("Server refused the session: $text", notifyServer = false) }
                } else {
                    Log.w(TAG, "Server reported non-fatal error: $code")
                }
            }
        }
    }

    private fun handleSignal(d: JSONObject) {
        val nid = d.optString("nid")
        when (d.optString("type")) {
            "ready" -> {
                if (nid.isBlank()) return
                if (nid == currentNid && sender?.peer != null) return     // duplicate ready (browser retries)
                beginNegotiation(nid)
            }
            "answer" -> onAnswer(nid, d.optString("sdp"))
            "ice_candidate" -> onRemoteIce(nid, d.optJSONObject("candidate"))
        }
    }

    // ---------------------------------------------------------------- WebRTC negotiation
    private fun beginNegotiation(nid: String) {
        val s = sender ?: return
        clearDisconnectTimeout()
        currentNid = nid
        remoteDescriptionSet = false
        pendingIce.clear()
        setPhase(ShareState.Phase.NEGOTIATING, "Computer is connecting…")
        val pc = s.createPeer(iceServers, peerObserver(nid))
        if (pc == null) {
            Log.e(TAG, "createPeerConnection returned null")
            currentNid = null
            return
        }
        pc.createOffer(object : SdpObserverAdapter("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                worker?.post {
                    if (currentNid != nid || s.peer !== pc) return@post
                    // Offer goes out BEFORE setLocalDescription so that ICE candidates (generated after it)
                    // can never overtake it on the wire.
                    signaling?.send(JSONObject().put("type", "offer").put("sdp", desc.description).put("nid", nid))
                    pc.setLocalDescription(object : SdpObserverAdapter("setLocal") {
                        override fun onSetFailure(error: String?) {
                            super.onSetFailure(error)
                            worker?.post { if (currentNid == nid) abandonNegotiation("Could not start the connection.") }
                        }
                    }, desc)
                }
            }

            override fun onCreateFailure(error: String?) {
                super.onCreateFailure(error)
                worker?.post { if (currentNid == nid) abandonNegotiation("Could not create the connection offer.") }
            }
        }, MediaConstraints())
    }

    private fun onAnswer(nid: String, sdp: String) {
        if (nid != currentNid || sdp.isBlank()) return
        val pc = sender?.peer ?: return
        pc.setRemoteDescription(object : SdpObserverAdapter("setRemote") {
            override fun onSetSuccess() {
                worker?.post {
                    if (currentNid != nid || sender?.peer !== pc) return@post
                    remoteDescriptionSet = true
                    for (c in pendingIce) pc.addIceCandidate(c)      // flush candidates that arrived early
                    pendingIce.clear()
                }
            }

            override fun onSetFailure(error: String?) {
                super.onSetFailure(error)
                worker?.post { if (currentNid == nid) abandonNegotiation("Computer's answer was rejected.") }
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun onRemoteIce(nid: String, c: JSONObject?) {
        if (nid != currentNid || c == null) return
        val line = c.optString("candidate")
        if (line.isBlank()) return                                      // end-of-candidates marker
        val mid = if (c.isNull("sdpMid")) null else c.optString("sdpMid")
        val ice = IceCandidate(mid, c.optInt("sdpMLineIndex", 0), line)
        val pc = sender?.peer ?: return
        if (remoteDescriptionSet) pc.addIceCandidate(ice) else pendingIce.add(ice)
    }

    private fun peerObserver(nid: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            worker?.post { onIceState(nid, state) }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            worker?.post {
                if (currentNid != nid) return@post
                signaling?.send(
                    JSONObject().put("type", "ice_candidate").put("nid", nid).put(
                        "candidate",
                        JSONObject()
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                            .put("candidate", candidate.sdp)
                    )
                )
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    private fun onIceState(nid: String, state: PeerConnection.IceConnectionState?) {
        if (nid != currentNid) return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                clearDisconnectTimeout()
                setPhase(ShareState.Phase.LIVE, "Live: your screen is visible on the computer.")
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                setPhase(ShareState.Phase.RECONNECTING, "Connection to the computer interrupted. Trying to recover…")
                clearDisconnectTimeout()
                val r = Runnable { if (currentNid == nid) abandonNegotiation("Connection to the computer was lost.") }
                disconnectTimeout = r
                worker?.postDelayed(r, DISCONNECT_GRACE_MS)
            }
            PeerConnection.IceConnectionState.FAILED ->
                abandonNegotiation("Connection to the computer failed.")
            else -> {}
        }
    }

    /** Drops the current viewer connection but keeps capturing, so the browser can reconnect with a new `ready`. */
    private fun abandonNegotiation(reason: String) {
        clearDisconnectTimeout()
        currentNid = null
        remoteDescriptionSet = false
        pendingIce.clear()
        sender?.closePeer()
        setPhase(ShareState.Phase.WAITING, "$reason Waiting for the computer to reconnect…")
    }

    private fun clearDisconnectTimeout() {
        disconnectTimeout?.let { worker?.removeCallbacks(it) }
        disconnectTimeout = null
    }

    private fun parseIceServers(arr: JSONArray?): List<PeerConnection.IceServer> {
        val out = ArrayList<PeerConnection.IceServer>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urls = ArrayList<String>()
            when (val u = o.opt("urls")) {
                is String -> urls.add(u)
                is JSONArray -> for (j in 0 until u.length()) urls.add(u.optString(j))
            }
            if (urls.isEmpty()) continue
            val b = PeerConnection.IceServer.builder(urls)
            if (o.has("username")) b.setUsername(o.optString("username"))
            if (o.has("credential")) b.setPassword(o.optString("credential"))
            out.add(b.createIceServer())
        }
        return out
    }

    // ---------------------------------------------------------------- stop
    /**
     * Single exit path. Main thread. Idempotent.
     * Order matters for "stop immediately": capture + notification go first, slow native cleanup after.
     */
    private fun stopSharing(reason: String, notifyServer: Boolean) {
        if (stopping) return
        stopping = true
        ShareState.ended(reason)
        sender?.stopCaptureNow()                     // MediaProjection released right now
        stopForeground(STOP_FOREGROUND_REMOVE)       // sharing notification disappears right now
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        val w = worker
        if (w == null) {
            finishService()
            return
        }
        w.post {
            clearDisconnectTimeout()
            signaling?.close(notifyServer)
            signaling = null
            sender?.release()
            sender = null
            http?.connectionPool?.evictAll()
            main.post { finishService() }
        }
    }

    private fun finishService() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) // in case a late update raced
        releaseWifiLock()
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        started = false
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping && started) {
            // The system is destroying us: still tell the server and release capture.
            stopSharing("Sharing stopped (service destroyed).", notifyServer = true)
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val s = sender ?: return
        if (stopping) return
        val (sw, sh) = currentScreenSize()
        val (cw, ch) = CaptureMath.scaledSize(sw, sh, s.quality.longSide)
        worker?.post { s.changeCaptureSize(cw, ch) }
    }

    // ---------------------------------------------------------------- helpers
    private fun setPhase(phase: ShareState.Phase, text: String) {
        if (stopping) return
        ShareState.set(phase, text)
        updateNotification(text)
    }

    private fun enterForeground(text: String): Boolean = try {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        false
    }

    private fun updateNotification(text: String) {
        if (stopping) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), flags
        )
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("ARIX is sharing your screen")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_pause, "Stop sharing", stopPi)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun currentScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var w: Int
        var h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels; h = dm.heightPixels
        }
        val orientation = resources.configuration.orientation
        val wantPortrait = orientation == Configuration.ORIENTATION_PORTRAIT
        val wantLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        if ((wantPortrait && w > h) || (wantLandscape && h > w)) { val t = w; w = h; h = t }
        return w to h
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "ARIX:screenshare").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun releaseWifiLock() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wifiLock = null
    }
}
