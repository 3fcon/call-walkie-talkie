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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    private var isConnected by mutableStateOf(false)
    private var isToggleMode by mutableStateOf(false) // False = Hold-to-Talk, True = Tap ON/OFF
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    private val port = 50000
    private val multicastGroup = "224.0.0.1" 
    
    private var socket: MulticastSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isRunning = true

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
            
            val statusText = if (isTransmitting) "TRANSMITTING LIVE..."
            else if (isReceiving) "RECEIVING AUDIO..."
            else if (isConnected) "SYSTEM ONLINE • READY"
            else "DISCONNECTED"

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF090D13)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(20.dp)
                ) {
                    // Top Bar: Connection Pill & Connect/Disconnect Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (isConnected) Color(0xFF065F46) else Color(0xFF7F1D1D),
                            shape = CircleShape
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isConnected) Color(0xFF34D399) else Color(0xFFF87171), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isConnected) "MESH ACTIVE" else "OFFLINE",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (isConnected) disconnectMesh() else initNetworkingAndAudio()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isConnected) Color(0xFF475569) else Color(0xFF0284C7)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isConnected) "DISCONNECT" else "CONNECT",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Middle Section: Status & Giant Talk Button
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = statusText,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )

                        // Mode Selector (Hold vs Toggle ON/OFF)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            Text(
                                text = "Mode: ${if (isToggleMode) "TAP ON/OFF" else "HOLD-TO-TALK"}",
                                color = Color(0xFFA1A1AA),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = isToggleMode,
                                onCheckedChange = { 
                                    isToggleMode = it
                                    if (isTransmitting) stopTransmitting()
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF38BDF8))
                            )
                        }
                        
                        // Giant Tactical Button using stable Compose detectTapGestures
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(220.dp)
                                .background(bgColor, CircleShape)
                                .pointerInput(isToggleMode, isConnected) {
                                    if (!isConnected) return@pointerInput
                                    
                                    if (isToggleMode) {
                                        detectTapGestures(
                                            onTap = {
                                                if (isTransmitting) stopTransmitting() else startTransmitting()
                                            }
                                        )
                                    } else {
                                        detectTapGestures(
                                            onPress = {
                                                startTransmitting()
                                                tryAwaitRelease()
                                                stopTransmitting()
                                            }
                                        )
                                    }
                                }
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isTransmitting) "LIVE" else if (isReceiving) "RX" else "TALK",
                                    color = Color.White,
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isTransmitting) "TAP/RELEASE TO STOP" else if (isToggleMode) "TAP TO TOGGLE" else "HOLD TO TALK",
                                    color = Color(0xAAFFFFFF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Bottom Instructions Card
                    Surface(
                        color = Color(0xFF131A24),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "QUICK INSTRUCTIONS",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• Connect both phones to the same Wi-Fi or Hotspot.\n• Hold or Tap the button to broadcast your voice instantly.\n• Use the toggle to switch between Hold-to-Talk and Tap ON/OFF.",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }

    private fun initNetworkingAndAudio() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("CallWalkieTalkieLock")
            multicastLock?.acquire()

            socket = MulticastSocket(port)
            socket?.joinGroup(InetAddress.getByName(multicastGroup))
            isConnected = true
            
            val outBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(outBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                
            audioTrack?.play()
            startListeningThread()
            Toast.makeText(this, "Connected to Local Mesh", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            isConnected = false
            Toast.makeText(this, "Connection failed. Check Wi-Fi.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disconnectMesh() {
        stopTransmitting()
        isRunning = false
        multicastLock?.release()
        socket?.close()
        audioTrack?.stop()
        audioTrack?.release()
        socket = null
        isConnected = false
        Toast.makeText(this, "Disconnected from mesh", Toast.LENGTH_SHORT).show()
    }

    private fun startListeningThread() {
        isRunning = true
        thread {
            val receiveData = ByteArray(bufferSize)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(receiveData, receiveData.size)
                    socket?.receive(packet)
                    
                    if (!isTransmitting) {
                        isReceiving = true
                        audioTrack?.write(packet.data, 0, packet.length)
                        window.decorView.postDelayed({ isReceiving = false }, 300)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun startTransmitting() {
        if (isTransmitting) return
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
        disconnectMesh()
    }
}
