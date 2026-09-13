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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Base64

class CommsService : Service() {
    private val CHANNEL_ID = "tac_mesh_channel"
    private val client = OkHttpClient()
    private val apiUrl = "https://shansoulstudio.in/call/api.php"
    private val myId = "app_${System.currentTimeMillis()}"
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var isListenOnly = false
    private var lastPollTime = 0.0

    private val sampleRate = 16000
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO

    companion object { var instance: CommsService? = null }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(1001, buildNotification("Tactical Mesh Live — HTTP API Bridged"))
        setupAudioTrack()
        startCommsLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isListenOnly = intent?.getBooleanExtra("listen_only", false) == true
        return START_STICKY
    }

    fun setListenOnlyMode(listenOnly: Boolean) {
        isListenOnly = listenOnly
        updateNotification(if (listenOnly) "Listen-Only Mode" else "Full PTT Active")
    }

    fun stopComms() {
        isRunning = false
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { stopForeground(STOP_FOREGROUND_REMOVE) } else { @Suppress("DEPRECATION") stopForeground(true) }
        stopSelf()
    }

    private fun setupAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(channelConfigOut).build())
            .setBufferSizeInBytes(minBuf).setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack?.play()
    }

    private fun startCommsLoop() {
        isRunning = true
        // Register presence once
        client.newCall(Request.Builder().url("$apiUrl?action=register&id=$myId&name=Sanee").build()).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })

        // Audio Record thread for Push
        Thread {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfigIn, audioFormat, minBuf * 2)
                try { audioRecord?.startRecording() } catch(e:Exception){}
                val buffer = ByteArray(minBuf)
                while (isRunning) {
                    if (!isListenOnly && audioRecord != null) {
                        val read = audioRecord!!.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val chunkData = buffer.copyOfRange(0, read)
                            val body = chunkData.toRequestBody("application/octet-stream".toMediaType())
                            val req = Request.Builder().url("$apiUrl?action=push&sender=$myId").post(body).build()
                            client.newCall(req).enqueue(object: Callback {
                                override fun onFailure(call: Call, e: IOException) {}
                                override fun onResponse(call: Call, response: Response) { response.close() }
                            })
                        }
                    }
                    Thread.sleep(120)
                }
            }
        }.start()

        // Poll thread for Pull
        Thread {
            lastPollTime = System.currentTimeMillis() / 1000.0 - 1.0
            while (isRunning) {
                try {
                    val req = Request.Builder().url("$apiUrl?action=pull&since=$lastPollTime&me=$myId&name=Sanee").build()
                    val res = client.newCall(req).execute()
                    val bodyStr = res.body?.string()
                    res.close()
                    if (!bodyStr.isNullOrEmpty() && !isListenOnly) {
                        val json = org.json.JSONObject(bodyStr)
                        if (json.has("chunks")) {
                            val arr = json.getJSONArray("chunks")
                            for (i in 0 until arr.length()) {
                                val ch = arr.getJSONObject(arr.length() - 1 - i) // latest first or chronological
                                val t = ch.getDouble("time")
                                if (t > lastPollTime) lastPollTime = t
                                val rawBytes = Base64.decode(ch.getString("data"), Base64.DEFAULT)
                                audioTrack?.write(rawBytes, 0, rawBytes.size)
                            }
                        }
                    }
                } catch(e:Exception){}
                Thread.sleep(300)
            }
        }.start()
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CALL Tactical PTT").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call).setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true).build()
    }
    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java)?.notify(1001, buildNotification(text)) }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Tactical Comms", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
    override fun onDestroy() { super.onDestroy(); instance = null; stopComms(); }
}
