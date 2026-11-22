package com.arix.screenshare

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket signaling client with automatic reconnect.
 *
 * All listener callbacks are delivered on [handler]'s thread. Reconnects (exponential back-off, up to
 * [MAX_OUTAGE_MS] of continuous outage) happen only after abnormal failures; a normal close (1000) or a
 * policy close (1008, e.g. bad token / expired code) is final.
 */
class SignalingClient(
    private val http: OkHttpClient,
    private val url: String,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onMessage(msg: JSONObject)
        fun onConnectionLost(attempt: Int)
        fun onGaveUp()
    }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private var attempt = 0
    private var lostSince = 0L
    private val reconnectRunnable = Runnable { open() }

    fun connect() {
        handler.post { open() }
    }

    fun send(obj: JSONObject): Boolean = socket?.send(obj.toString()) ?: false

    /** Ends signaling for good. With [notifyStop] the server is told to end the whole session first. */
    fun close(notifyStop: Boolean) {
        closed = true
        handler.removeCallbacks(reconnectRunnable)
        val s = socket
        socket = null
        if (notifyStop) s?.send("""{"type":"stop"}""")
        s?.close(1000, "client closing")
    }

    private fun open() {
        if (closed) return
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (webSocket !== socket) return@post
                    attempt = 0
                    lostSince = 0L
                    listener.onConnected()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post {
                    if (webSocket !== socket) return@post
                    try {
                        listener.onMessage(JSONObject(text))
                    } catch (e: JSONException) {
                        Log.w(TAG, "Ignoring malformed server message")
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post {
                    if (webSocket !== socket) return@post
                    if (code == 1000 || code == 1008) {
                        // final: session ended / rejected. The server's JSON message (session_ended / error)
                        // has already been delivered and decides what the user sees.
                        socket = null
                    } else {
                        lost()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Signaling failure: ${t.javaClass.simpleName}")
                handler.post {
                    if (webSocket !== socket) return@post
                    lost()
                }
            }
        })
    }

    private fun lost() {
        if (closed) return
        socket = null
        val now = SystemClock.elapsedRealtime()
        if (lostSince == 0L) lostSince = now
        if (now - lostSince > MAX_OUTAGE_MS) {
            closed = true
            listener.onGaveUp()
            return
        }
        attempt++
        listener.onConnectionLost(attempt)
        val delay = minOf(15_000L, 1_000L shl minOf(attempt - 1, 4))
        handler.postDelayed(reconnectRunnable, delay)
    }

    companion object {
        private const val TAG = "ARIX"
        private const val MAX_OUTAGE_MS = 150_000L   // server keeps a paired session for 120 s after the last peer leaves
        fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)      // WebSocket: no read timeout
            .pingInterval(20, TimeUnit.SECONDS)         // detects dead connections
            .build()
    }
}
