package com.arix.screenshare

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pairing + control screen. All state about an active share lives in [ShareState] (written by the service),
 * so rotating the phone or reopening the app never loses it.
 */
class MainActivity : ComponentActivity() {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val ui = Handler(Looper.getMainLooper())
    private var createCall: Call? = null

    private lateinit var serverInput: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var generate: Button
    private lateinit var codeView: TextView
    private lateinit var expiryView: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var notifSettings: Button
    private lateinit var status: TextView

    private var localMessage: String? = null     // messages that are not part of ShareState (e.g. HTTP errors)
    private var creating = false                 // a /api/session request is in flight
    private var quality = Quality.BALANCED

    private val stateListener = Runnable { render() }
    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || notificationsEnabled()) requestScreenCapture() else showNotificationsBlocked()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val pending = ShareState.pending
            if (result.resultCode == Activity.RESULT_OK && data != null && pending != null && !pending.expired()) {
                val intent = Intent(this, ScreenShareService::class.java).apply {
                    action = ScreenShareService.ACTION_START
                    putExtra(ScreenShareService.EXTRA_SERVER, pending.server)
                    putExtra(ScreenShareService.EXTRA_CODE, pending.code)
                    putExtra(ScreenShareService.EXTRA_TOKEN, pending.senderToken)
                    putExtra(ScreenShareService.EXTRA_QUALITY, quality.name)
                    putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenShareService.EXTRA_PROJECTION_DATA, data)   // single-use consent token: never stored
                }
                ShareState.set(ShareState.Phase.CONNECTING, "Starting…")
                ContextCompat.startForegroundService(this, intent)
                localMessage = null
            } else if (result.resultCode != Activity.RESULT_OK) {
                localMessage = "Screen capture permission was not granted. Nothing is being shared."
            } else {
                localMessage = "The pairing code expired. Generate a new code."
            }
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("arix", Context.MODE_PRIVATE)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
        }

        root.addView(label("ARIX SCREEN SHARING", 24f))
        root.addView(label("Share your whole screen to a computer you control. Android will always ask for permission first.", 13f, Color.LTGRAY))
        root.addView(label("\nServer address (your laptop's LAN address for offline mode, or your https:// server)", 12f, Color.LTGRAY))
        serverInput = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "http://192.168.1.20:8000"
            setText(prefs.getString("server", "http://192.168.1.20:8000"))
        }
        root.addView(serverInput)

        root.addView(label("Quality", 12f, Color.LTGRAY))
        qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Quality.values().map { it.label })
            quality = Quality.fromName(prefs.getString("quality", null))
            setSelection(quality.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    quality = Quality.values()[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        root.addView(qualitySpinner)

        generate = Button(this).apply { text = "1. Generate pairing code" }
        root.addView(generate)
        codeView = label("------", 36f, Color.parseColor("#C5B5FF"))
        root.addView(codeView)
        expiryView = label("", 12f, Color.LTGRAY)
        root.addView(expiryView)

        start = Button(this).apply { text = "2. Approve & start screen sharing" }
        root.addView(start)
        stop = Button(this).apply { text = "Stop sharing"; setBackgroundColor(Color.parseColor("#B3261E")); setTextColor(Color.WHITE) }
        root.addView(stop)
        notifSettings = Button(this).apply { text = "Open notification settings"; visibility = View.GONE }
        root.addView(notifSettings)

        status = label("", 15f)
        status.setPadding(0, pad / 2, 0, 0)
        root.addView(status)
        root.addView(label(
            "\nAnyone who has your code AND is quick enough can view your screen. Share the code only with your own computer. " +
                "Sharing stops immediately when you tap Stop, use the notification's Stop button, or revoke capture in Android.",
            11f, Color.GRAY
        ))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080A12"))
            fitsSystemWindows = true          // Android 15 draws edge-to-edge: keep content clear of system bars
            addView(root)
        })

        generate.setOnClickListener { createSession(prefs.edit()) }
        start.setOnClickListener { beginShare(prefs.edit()) }
        stop.setOnClickListener {
            startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
        }
        notifSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onStart() {
        super.onStart()
        ShareState.addListener(stateListener)
        ui.post(tick)
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
        ShareState.removeListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) createCall?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ actions
    private fun createSession(prefs: android.content.SharedPreferences.Editor) {
        when (val addr = ServerAddress.normalize(serverInput.text.toString())) {
            is ServerAddress.Result.Bad -> { localMessage = addr.message; render() }
            is ServerAddress.Result.Ok -> {
                prefs.putString("server", addr.base).putString("quality", quality.name).apply()
                serverInput.setText(addr.base)
                localMessage = "Contacting the server…"
                creating = true
                render()
                val request = Request.Builder().url("${addr.base}/api/session").post(ByteArray(0).toRequestBody(null)).build()
                createCall = http.newCall(request)
                createCall!!.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread { localMessage = "Could not reach the server: ${e.message}"; creating = false; render() }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            val message: String? = when {
                                it.code == 429 -> "Too many codes requested. Wait a minute and try again."
                                !it.isSuccessful -> "Server refused to create a code (HTTP ${it.code})."
                                else -> try {
                                    val j = JSONObject(body)
                                    ShareState.pending = ShareState.Pending(
                                        addr.base, j.getString("code"), j.getString("sender_token"), j.optInt("expires_in", 300)
                                    )
                                    null
                                } catch (e: JSONException) {
                                    "The server's reply was not understood. Is this an ARIX server?"
                                }
                            }
                            runOnUiThread {
                                localMessage = message ?: "Code ready. Enter it on your computer, then approve screen capture."
                                creating = false
                                render()
                            }
                        }
                    }
                })
            }
        }
    }

    private fun beginShare(prefs: android.content.SharedPreferences.Editor) {
        prefs.putString("quality", quality.name).apply()
        val pending = ShareState.pending
        if (pending == null || pending.expired()) {
            localMessage = "Generate a new pairing code first."
            render()
            return
        }
        if (notificationsEnabled()) {
            requestScreenCapture()
        } else if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showNotificationsBlocked()
        }
    }

    private fun requestScreenCapture() {
        // A fresh system consent dialog is ALWAYS shown; the result is never cached or reused.
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun notificationsEnabled() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun showNotificationsBlocked() {
        localMessage = "Notifications are turned off for ARIX. The sharing notification (with its Stop button) must be visible, so sharing was not started."
        render()
    }

    // ------------------------------------------------------------ rendering
    private fun render() {
        val active = ShareState.active
        val pending = ShareState.pending
        codeView.text = pending?.code?.chunked(3)?.joinToString(" ") ?: "------"
        expiryView.text = when {
            active || pending == null -> ""
            pending.expired() -> "Code expired. Generate a new one."
            else -> "Enter this code on the computer within %d:%02d".format(pending.remainingSec() / 60, pending.remainingSec() % 60)
        }
        generate.isEnabled = !active && !creating
        start.isEnabled = !active && pending != null && !pending.expired()
        stop.isEnabled = active
        stop.alpha = if (active) 1f else 0.4f
        notifSettings.visibility = if (!active && !notificationsEnabled() && localMessage?.startsWith("Notifications") == true) View.VISIBLE else View.GONE
        status.text = when {
            active -> ShareState.detail
            localMessage != null -> localMessage
            ShareState.lastEnded != null -> "Sharing ended. ${ShareState.lastEnded}"
            else -> "Generate a code, enter it on the computer, then approve screen capture."
        }
    }
}
