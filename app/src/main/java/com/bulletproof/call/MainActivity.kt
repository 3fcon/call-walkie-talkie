package com.bulletproof.call

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isConnected = false

    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)

    // UI Elements
    private lateinit var ipInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var pttBtn: Button
    private lateinit var statusTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ensure these IDs match your activity_main.xml exactly
        ipInput = findViewById(R.id.ipInput)
        connectBtn = findViewById(R.id.connectBtn)
        pttBtn = findViewById(R.id.pttBtn)
        statusTv = findViewById(R.id.statusTv)

        checkPermissions()
        setupAudioTrack()

        connectBtn.setOnClickListener {
            if (!isConnected) {
                val ip = ipInput.text.toString().trim()
                if (ip.isNotEmpty()) {
                    connectWebSocket(ip)
                } else {
                    Toast.makeText(this, "Enter Server IP (e.g., 192.168.1.6:8080)", Toast.LENGTH_SHORT).show()
                }
            } else {
                disconnectWebSocket()
            }
        }

        pttBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startRecording()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopRecording()
            }
            true
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun connectWebSocket(address: String) {
        // Automatically append ws:// if the user forgets
        val url = if (address.startsWith("ws://") || address.startsWith("wss://")) address else "ws://$address"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                runOnUiThread {
                    statusTv.text = "Status: MESH ACTIVE"
                    statusTv.setTextColor(android.graphics.Color.GREEN)
                    connectBtn.text = "DISCONNECT"
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Instantly play incoming audio from the server
                val pcmData = bytes.toByteArray()
                audioTrack?.write(pcmData, 0, pcmData.size)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { updateDisconnectedUI() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Connection Failed: ${t.message}")
                runOnUiThread {
                    updateDisconnectedUI()
                    Toast.makeText(this@MainActivity, "Connection failed. Check IP/Port.", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "User disconnected")
        updateDisconnectedUI()
    }

    private fun updateDisconnectedUI() {
        isConnected = false
        statusTv.text = "Status: OFFLINE"
        statusTv.setTextColor(android.graphics.Color.RED)
        connectBtn.text = "CONNECT"
        webSocket = null
    }

    private fun setupAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigOut)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        audioTrack?.play()
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfigIn, audioFormat, bufferSize)
        audioRecord?.startRecording()
        isRecording = true

        thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0 && webSocket != null) {
                    // Corrected extension function syntax
                    webSocket?.send(buffer.toByteString(0, bytesRead))
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        disconnectWebSocket()
        audioTrack?.stop()
        audioTrack?.release()
    }
}