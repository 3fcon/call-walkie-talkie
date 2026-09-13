package com.bulletproof.call

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var nameInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var listenOnlyBtn: Button

    private var isConnected = false
    private var isListenOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        nameInput = findViewById(R.id.nameInput)
        connectBtn = findViewById(R.id.connectBtn)
        disconnectBtn = findViewById(R.id.disconnectBtn)
        listenOnlyBtn = findViewById(R.id.listenOnlyBtn)

        checkPermissions()

        connectBtn.setOnClickListener {
            connectSession()
        }

        disconnectBtn.setOnClickListener {
            disconnectSession()
        }

        listenOnlyBtn.setOnClickListener {
            toggleListenOnly()
        }

        updateUIState()
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
        isListenOnly = false
        CommsService.instance?.stopComms()
        stopService(Intent(this, CommsService::class.java))
        updateUIState()
    }

    private fun toggleListenOnly() {
        isListenOnly = !isListenOnly
        CommsService.instance?.setListenOnlyMode(isListenOnly)
        updateUIState()
    }

    private fun updateUIState() {
        val currentName = nameInput.text.toString().ifEmpty { "Operator" }
        statusText.text = if (isConnected) "CONNECTED ($currentName)" else "OFFLINE"
        connectBtn.isEnabled = !isConnected
        disconnectBtn.isEnabled = isConnected
        listenOnlyBtn.isEnabled = isConnected
        listenOnlyBtn.text = if (isListenOnly) "RX ONLY: ON (MUTED)" else "LISTEN-ONLY"
    }
}
