package com.bulletproof.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var isTransmitting by mutableStateOf(false)
    private var isReceiving by mutableStateOf(false)
    
    // Audio configuration for raw PCM voice transport
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // Network configuration for "At all costs" UDP Multicast Broadcast
    private val port = 50000
    private val multicastGroup = "224.0.0.1" 
    
    private var socket: MulticastSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isRunning = true

    // Permission launcher for Android 6.0+ dynamic permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) initNetworkingAndAudio()
        else Toast.makeText(this, "Microphone permission is required to talk", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initNetworkingAndAudio()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            val bgColor = if (isTransmitting) Color(0xFFDC2626) // TX Red
            else if (isReceiving) Color(0xFF3B82F6) // RX Blue
            else Color(0xFF1E293B) // Standby Slate
            
            val statusText = if (isTransmitting) "TRANSMITTING TO ALL"
            else if (isReceiving) "RECEIVING TRANSMISSION..."
            else "SYSTEM READY • STANDBY"

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().background(Color(0xFF090D13))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = statusText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(bottom = 60.dp)
                    )
                    
                    Button(
                        onClick = {},
                        modifier = Modifier
                            .size(240.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        startTransmitting()
                                        tryAwaitRelease()
                                        stopTransmitting()
                                    }
                                )
                            },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = bgColor)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("PTT", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                            Text("HOLD TO TALK", color = Color(0xAAFFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top=8.dp))
                        }
                    }
                }
            }
        }
    }

    private fun initNetworkingAndAudio() {
        try {
            // Multicast Lock is strictly required to receive UDP broadcasts on modern Android when screen is off
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("CallWalkieTalkieLock")
            multicastLock?.acquire()

            socket = MulticastSocket(port)
            socket?.joinGroup(InetAddress.getByName(multicastGroup))
            
            // Setup AudioTrack for playback
            val outBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(outBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                
            audioTrack?.play()
            
            startListeningThread()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to bind to network port", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startListeningThread() {
        thread {
            val receiveData = ByteArray(bufferSize)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(receiveData, receiveData.size)
                    socket?.receive(packet)
                    
                    // Do not play back our own echo if we are transmitting
                    if (!isTransmitting) {
                        isReceiving = true
                        audioTrack?.write(packet.data, 0, packet.length)
                        // Auto-reset receiving UI state
                        window.decorView.postDelayed({ isReceiving = false }, 500)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun startTransmitting() {
        isTransmitting = true
        thread {
            try {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@thread
                
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
                audioRecord?.startRecording()
                
                val group = InetAddress.getByName(multicastGroup)
                val buffer = ByteArray(bufferSize)
                
                while (isTransmitting) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val packet = DatagramPacket(buffer, read, group, port)
                        socket?.send(packet)
                    }
                }
                
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopTransmitting() {
        isTransmitting = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        multicastLock?.release()
        socket?.close()
        audioTrack?.stop()
        audioTrack?.release()
    }
}