package com.arix.screenshare

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide, observable sharing state. The service writes it, the activity renders it.
 * (Survives activity recreation/rotation; a process death resets it, which also ends sharing.)
 */
object ShareState {
    enum class Phase { IDLE, CONNECTING, WAITING, NEGOTIATING, LIVE, RECONNECTING }

    /** A pairing code that was created but not yet used. Never persisted to disk. */
    class Pending(val server: String, val code: String, val senderToken: String, expiresInSec: Int) {
        private val expiresAt = SystemClock.elapsedRealtime() + expiresInSec * 1000L
        fun remainingSec(): Int = maxOf(0L, (expiresAt - SystemClock.elapsedRealtime()) / 1000).toInt()
        fun expired(): Boolean = remainingSec() <= 0
    }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    @Volatile
    var detail: String = ""
        private set

    @Volatile
    var lastEnded: String? = null
        private set

    @Volatile var pending: Pending? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Runnable>()

    val active: Boolean get() = phase != Phase.IDLE

    fun set(p: Phase, text: String) {
        phase = p; detail = text; lastEnded = null; fire()
    }

    fun ended(reason: String) {
        phase = Phase.IDLE; detail = ""; lastEnded = reason; pending = null; fire()
    }

    fun addListener(r: Runnable) { listeners.add(r) }
    fun removeListener(r: Runnable) { listeners.remove(r) }
    private fun fire() { main.post { listeners.forEach { it.run() } } }
}
