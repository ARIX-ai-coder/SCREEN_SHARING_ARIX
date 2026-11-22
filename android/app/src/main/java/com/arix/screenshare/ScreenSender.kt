package com.arix.screenshare

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Owns everything WebRTC-related that must live as long as the *share* (not as long as one viewer):
 * EGL context, factory, MediaProjection capturer and the video track. PeerConnections are cheap and are
 * created/destroyed per viewer negotiation via [createPeer]/[closePeer].
 *
 * Threading: call from a single worker thread, except [stopCaptureNow] which is safe from any thread.
 */
class ScreenSender(
    private val appContext: Context,
    private val projectionData: Intent,
    val quality: Quality,
    private val onProjectionStopped: () -> Unit,
) {
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null

    @Volatile var peer: PeerConnection? = null
        private set
    @Volatile private var captureStopped = false
    private var released = false

    /** Starts MediaProjection capture. Must be called only after startForeground(mediaProjection). */
    fun startCapture(width: Int, height: Int) {
        initializeOnce(appContext)
        val eglBase = EglBase.create()
        egl = eglBase
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        val source = f.createVideoSource(true)          // true = screencast
        videoSource = source
        val helper = SurfaceTextureHelper.create("ARIX-ScreenCapture", eglBase.eglBaseContext)
        textureHelper = helper
        val cap = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            // Fired when the user (or the system) revokes screen capture. Never block or dispose here:
            // we are on the capture thread. The service hops to the main thread.
            override fun onStop() {
                onProjectionStopped()
            }
        })
        capturer = cap
        cap.initialize(helper, appContext, source.capturerObserver)
        cap.startCapture(width, height, quality.fps)
        val track = f.createVideoTrack(TRACK_ID, source)
        track.setEnabled(true)
        videoTrack = track
    }

    /**
     * Resizes the capture after rotation. Android 14+ forbids creating a second VirtualDisplay from the same
     * MediaProjection and this WebRTC version's changeCaptureFormat() does exactly that, so we skip it there
     * (the picture is then letter-boxed after rotation instead of crashing). Returns true if applied.
     */
    fun changeCaptureSize(width: Int, height: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 34 || captureStopped) return false
        return try {
            capturer?.changeCaptureFormat(width, height, quality.fps)
            true
        } catch (e: Exception) {
            Log.w(TAG, "changeCaptureFormat failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Creates a fresh PeerConnection carrying the screen track. Any previous peer is closed first. */
    fun createPeer(iceServers: List<PeerConnection.IceServer>, observer: PeerConnection.Observer): PeerConnection? {
        closePeer()
        val f = factory ?: return null
        val track = videoTrack ?: return null
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = f.createPeerConnection(config, observer) ?: return null
        applyLimits(pc.addTrack(track, listOf(STREAM_ID)))
        peer = pc
        return pc
    }

    fun closePeer() {
        val pc = peer ?: return
        peer = null
        try { pc.close() } catch (e: Exception) { Log.w(TAG, "peer close: ${e.javaClass.simpleName}") }
        try { pc.dispose() } catch (e: Exception) { Log.w(TAG, "peer dispose: ${e.javaClass.simpleName}") }
    }

    /** Stops screen capture immediately (releases MediaProjection). Idempotent, any thread. */
    fun stopCaptureNow() {
        if (captureStopped) return
        captureStopped = true
        try { capturer?.stopCapture() } catch (e: Exception) { Log.w(TAG, "stopCapture: ${e.javaClass.simpleName}") }
    }

    /** Releases every native resource. Idempotent. Call on the worker thread. */
    fun release() {
        if (released) return
        released = true
        stopCaptureNow()
        closePeer()
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { textureHelper?.dispose() } catch (_: Exception) {}
        textureHelper = null
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { egl?.release() } catch (_: Exception) {}
        egl = null
    }

    private fun applyLimits(rtpSender: RtpSender?) {
        try {
            val s = rtpSender ?: return
            val params = s.parameters
            for (enc in params.encodings) {
                enc.maxBitrateBps = quality.maxBitrateBps
                enc.maxFramerate = quality.fps
            }
            s.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply encoder limits: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "ARIX"
        private const val TRACK_ID = "arix-screen-track"
        private const val STREAM_ID = "arix-screen-stream"
        private var initialized = false

        @Synchronized
        private fun initializeOnce(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
            initialized = true
        }
    }
}
