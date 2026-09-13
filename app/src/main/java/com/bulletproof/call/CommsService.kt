package com.bulletproof.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

class CommsService : Service() {
    private val CHANNEL_ID = "tac_mesh_channel"
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isListenOnly = false

    private val sampleRate = 16000
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO

    companion object {
        var instance: CommsService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(1001, buildNotification("Tactical Mesh Live — Audio Bridged"))
        setupAudioTrack()
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isListenOnly = intent?.getBooleanExtra("listen_only", false) == true
        if (!isListenOnly) {
            setupAudioRecordAndStream()
        } else {
            stopAudioRecording()
        }
        return START_STICKY
    }

    fun setListenOnlyMode(listenOnly: Boolean) {
        isListenOnly = listenOnly
        if (listenOnly) {
            stopAudioRecording()
            updateNotification("Listen-Only Mode (Mic Muted)")
        } else {
            setupAudioRecordAndStream()
            updateNotification("Full Duplex PTT Active")
        }
    }

    fun stopComms() {
        isRecording = false
        stopAudioRecording()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) { e.printStackTrace() }
        audioTrack = null

        webSocket?.close(1000, "Manual disconnect")
        webSocket = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun setupAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    private fun setupAudioRecordAndStream() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfigIn,
            audioFormat,
            minBuf * 2
        )
        isRecording = true
        Thread {
            val buffer = ByteArray(minBuf)
            try {
                audioRecord?.startRecording()
                while (isRecording && !isListenOnly) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        webSocket?.send(buffer.toByteString(0, read))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun stopAudioRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url("wss://shansoulstudio.in/call/ws-relay").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"register","id":"app_node_${System.currentTimeMillis()}"}""")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                audioTrack?.write(data, 0, data.size)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {}
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
        })
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CALL Tactical PTT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1001, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Tactical Comms", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopAudioRecording()
        audioTrack?.stop()
        audioTrack?.release()
        webSocket?.close(1000, "Service destroyed")
    }
}
