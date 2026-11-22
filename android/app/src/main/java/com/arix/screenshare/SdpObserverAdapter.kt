package com.arix.screenshare

import android.util.Log
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** No-op SdpObserver that logs failures; override only what you need. */
open class SdpObserverAdapter(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) { Log.w(LOG, "$tag: create failed: $error") }
    override fun onSetFailure(error: String?) { Log.w(LOG, "$tag: set failed: $error") }
    companion object { const val LOG = "ARIX" }
}
