@file:Suppress("DEPRECATION")

package com.bulletproof.call

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusBadge: LinearLayout
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var callsignInput: EditText
    private lateinit var connectToggleBtn: Button
    private lateinit var modeToggleBtn: Button
    private lateinit var modeHoldBtn: Button
    private lateinit var rxOnlyBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var pttBtn: Button
    private lateinit var pttText: TextView
    private lateinit var pttSub: TextView
    private lateinit var selfSquadItem: LinearLayout
    private lateinit var displayCallsign: TextView
    private lateinit var glowRing: View

    private var isConnected = false
    private var isTransmitting = false
    private var isListenOnly = false
    private var currentMode = "toggle"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusBadge = findViewById(R.id.statusBadge)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        callsignInput = findViewById(R.id.callsignInput)
        connectToggleBtn = findViewById(R.id.connectToggleBtn)
        modeToggleBtn = findViewById(R.id.modeToggleBtn)
        modeHoldBtn = findViewById(R.id.modeHoldBtn)
        rxOnlyBtn = findViewById(R.id.rxOnlyBtn)
        disconnectBtn = findViewById(R.id.disconnectBtn)
        pttBtn = findViewById(R.id.pttBtn)
        pttText = findViewById(R.id.pttText)
        pttSub = findViewById(R.id.pttSub)
        selfSquadItem = findViewById(R.id.selfSquadItem)
        displayCallsign = findViewById(R.id.displayCallsign)
        glowRing = findViewById(R.id.glowRing)

        checkPermissions()

        connectToggleBtn.setOnClickListener {
            if (isConnected) disconnectSession() else connectSession()
        }

        disconnectBtn.setOnClickListener { disconnectSession() }

        modeToggleBtn.setOnClickListener {
            currentMode = "toggle"
            updateModeUI()
            stopTxForce()
        }

        modeHoldBtn.setOnClickListener {
            currentMode = "hold"
            updateModeUI()
            stopTxForce()
        }

        rxOnlyBtn.setOnClickListener {
            isListenOnly = !isListenOnly
            updateRxUI()
            CommsService.instance?.setListenOnlyMode(isListenOnly)
            updateSubtext()
        }

        setupPttTouchBehavior()
        updateUIState()

        if (intent.getBooleanExtra("auto_connect", false)) {
            connectSession()
        }
    }

    private fun checkPermissions() {
        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun connectSession() {
        isConnected = true
        isListenOnly = false
        val nameVal = callsignInput.text.toString().ifEmpty { "Operator" }
        displayCallsign.text = nameVal
        selfSquadItem.visibility = View.VISIBLE
        updateUIState()

        val serviceIntent = Intent(this, CommsService::class.java).apply {
            putExtra("listen_only", false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun disconnectSession() {
        isConnected = false
        isTransmitting = false
        isListenOnly = false
        selfSquadItem.visibility = View.GONE
        CommsService.instance?.stopComms()
        stopService(Intent(this, CommsService::class.java))
        updateUIState()
    }

    private fun updateModeUI() {
        modeToggleBtn.setTextColor(if (currentMode == "toggle") 0xFF00F0FF.toInt() else 0xFF6B7794.toInt())
        modeHoldBtn.setTextColor(if (currentMode == "hold") 0xFF00F0FF.toInt() else 0xFF6B7794.toInt())
        updateSubtext()
    }

    private fun updateRxUI() {
        if (isListenOnly) {
            rxOnlyBtn.setTextColor(0xFFFFAA00.toInt())
            rxOnlyBtn.text = "RX: ON"
            pttBtn.isEnabled = false
            pttBtn.setBackgroundResource(R.drawable.bg_ptt_rx)
        } else {
            rxOnlyBtn.setTextColor(0xFF6B7794.toInt())
            rxOnlyBtn.text = "LISTEN-ONLY"
            pttBtn.isEnabled = isConnected
            pttBtn.setBackgroundResource(R.drawable.bg_ptt_idle)
        }
        updateSubtext()
    }

    private fun updateUIState() {
        if (isConnected) {
            statusBadge.setBackgroundResource(R.drawable.bg_status_online)
            statusIndicator.setBackgroundResource(R.drawable.dot_green)
            statusText.text = "MESH READY"
            statusText.setTextColor(0xFF00FF88.toInt())
            connectToggleBtn.text = "DISCONNECT"
            callsignInput.isEnabled = false
            pttBtn.isEnabled = true
            modeToggleBtn.isEnabled = true
            modeHoldBtn.isEnabled = true
            rxOnlyBtn.isEnabled = true
            disconnectBtn.isEnabled = true
            pttBtn.setBackgroundResource(R.drawable.bg_ptt_idle)
            pttText.text = "MIC OFF"
        } else {
            statusBadge.setBackgroundResource(R.drawable.bg_status_offline)
            statusIndicator.setBackgroundResource(R.drawable.dot_red)
            statusText.text = "OFFLINE"
            statusText.setTextColor(0xFFFF3366.toInt())
            connectToggleBtn.text = "CONNECT"
            callsignInput.isEnabled = true
            pttBtn.isEnabled = false
            modeToggleBtn.isEnabled = false
            modeHoldBtn.isEnabled = false
            rxOnlyBtn.isEnabled = false
            disconnectBtn.isEnabled = false
            pttBtn.setBackgroundResource(R.drawable.bg_ptt_idle)
            pttText.text = "OFFLINE"
        }
        updateRxUI()
        updateSubtext()
    }

    private fun updateSubtext() {
        if (!isConnected) {
            pttSub.text = "CONNECT TO ENGAGE"
            return
        }
        if (isListenOnly) {
            pttSub.text = "RX ONLY / MIC MUTED"
            return
        }
        pttSub.text = if (currentMode == "toggle") {
            if (isTransmitting) "TAP TO MUTE" else "TAP TO TRANSMIT"
        } else {
            "HOLD TO TALK"
        }
    }

    private fun startTx() {
        if (!isConnected || isListenOnly) return
        isTransmitting = true
        pttBtn.setBackgroundResource(R.drawable.bg_ptt_active)
        pttText.text = "MIC LIVE"
        CommsService.instance?.setListenOnlyMode(false)
        updateSubtext()
    }

    private fun stopTxForce() {
        isTransmitting = false
        if (!isListenOnly) {
            pttBtn.setBackgroundResource(R.drawable.bg_ptt_idle)
            if (isConnected) pttText.text = "MIC OFF"
        }
        updateSubtext()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPttTouchBehavior() {
        pttBtn.setOnTouchListener { _, event ->
            if (!isConnected || isListenOnly) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (currentMode == "hold") {
                        startTx()
                    } else {
                        if (isTransmitting) stopTxForce() else startTx()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (currentMode == "hold") {
                        stopTxForce()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
