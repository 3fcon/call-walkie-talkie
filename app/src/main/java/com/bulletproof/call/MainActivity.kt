package com.bulletproof.call

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
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
import okhttp3.*
import okio.ByteString
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var isTransmitting by mutableStateOf(false)
    private var isReceiving by mutableStateOf(false)
    private var isConnected by mutableStateOf(false)
    private var serverIp by mutableStateOf("192.168.1.6") // Change this in UI to match your Termux IP
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (!isGranted) Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            val bgColor = if (isTransmitting) Color(0xFFDC2626) // TX Red
            else if (isReceiving) Color(0xFF3B82F6) // RX Blue
            else Color(0xFF1E293B) // Standby Slate
            
            val statusText = if (!isConnected) "DISCONNECTED"
            else if (isTransmitting) "TRANSMITTING LIVE..."
            else if (isReceiving) "RECEIVING AUDIO..."
            else "SYSTEM ONLINE • READY"

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().background(Color(0xFF090D13))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Server Connection Row
                    OutlinedTextField(
                        value = serverIp,
                        onValueChange = { serverIp = it },
                        label = { Text("Node.js Server IP", color = Color.Gray) },
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color.DarkGray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth(0.8f).padding(bottom = 16.dp)
                    )
                    
                    Button(
                        onClick = { toggleConnection() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) Color(0xFF064E3B) else Color(0xFF0284C7)
                        ),
                        modifier = Modifier.padding(bottom = 60.dp)
                    ) {
                        Text(if (isConnected) "DISCONNECT MESH" else "CONNECT TO SERVER", fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = statusText,
                        color = Color.LightGray,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(bottom = 40.dp)
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
                            Text("TALK", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black)
                            Text("HOLD TO BROADCAST", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.padding(top=8.dp))
                        }
                    }
                }
            }
        }
    }

    private fun toggleConnection() {
        if (isConnected) {
            webSocket?.close(1000, "User disconnected")
            isConnected = false
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            return
        }

        val request = Request.Builder().url("ws://$serverIp:3000").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { isConnected = true }
                setupAudioPlayback()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isTransmitting) {
                    runOnUiThread { isReceiving = true }
                    audioTrack?.write(bytes.toByteArray(), 0, bytes.size)
                    
                    // Reset RX UI after a short delay
                    window.decorView.postDelayed({
                        runOnUiThread { isReceiving = false }
                    }, 300)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { isConnected = false }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { 
                    isConnected = false
                    Toast.makeText(this@MainActivity, "Failed to connect to $serverIp", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun setupAudioPlayback() {
        val outBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(outBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    private fun startTransmitting() {
        if (!isConnected) return
        isTransmitting = true
        
        thread {
            try {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@thread
                
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
                audioRecord?.startRecording()
                
                val buffer = ByteArray(bufferSize)
                
                while (isTransmitting) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0 && webSocket != null) {
                        // Send raw PCM bytes to Node.js server
                        webSocket?.send(ByteString.of(buffer, 0, read))
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
        webSocket?.close(1000, "App closed")
        audioTrack?.release()
    }
}