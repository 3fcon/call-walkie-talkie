@file:Suppress("DEPRECATION", "SetJavaScriptEnabled")

package com.bulletproof.call

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webEngine: WebView
    private lateinit var statusBadge: LinearLayout
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var callsignInput: EditText
    private lateinit var connectToggleBtn: Button
    private lateinit var modeToggleBtn: Button
    private lateinit var modeHoldBtn: Button
    private lateinit var rxOnlyBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var pttTouchArea: FrameLayout
    private lateinit var pttVisualCircle: View
    private lateinit var pttText: TextView
    private lateinit var pttSub: TextView
    private lateinit var selfSquadItem: LinearLayout
    private lateinit var displayCallsign: TextView
    private lateinit var activeNodesLabel: TextView

    private var isConnected = false
    private var isTransmitting = false
    private var isListenOnly = false
    private var currentMode = "toggle"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webEngine = findViewById(R.id.webEngine)
        statusBadge = findViewById(R.id.statusBadge)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        callsignInput = findViewById(R.id.callsignInput)
        connectToggleBtn = findViewById(R.id.connectToggleBtn)
        modeToggleBtn = findViewById(R.id.modeToggleBtn)
        modeHoldBtn = findViewById(R.id.modeHoldBtn)
        rxOnlyBtn = findViewById(R.id.rxOnlyBtn)
        disconnectBtn = findViewById(R.id.disconnectBtn)
        pttTouchArea = findViewById(R.id.pttTouchArea)
        pttVisualCircle = findViewById(R.id.pttVisualCircle)
        pttText = findViewById(R.id.pttText)
        pttSub = findViewById(R.id.pttSub)
        selfSquadItem = findViewById(R.id.selfSquadItem)
        displayCallsign = findViewById(R.id.displayCallsign)
        activeNodesLabel = findViewById(R.id.activeNodesLabel)

        checkPermissions()
        setupWebEngine()

        connectToggleBtn.setOnClickListener {
            if (isConnected) disconnectSession() else connectSession()
        }
        disconnectBtn.setOnClickListener { disconnectSession() }

        modeToggleBtn.setOnClickListener {
            currentMode = "toggle"
            modeToggleBtn.setTextColor(0xFF00F0FF.toInt())
            modeHoldBtn.setTextColor(0xFF6B7794.toInt())
            stopTx()
        }

        modeHoldBtn.setOnClickListener {
            currentMode = "hold"
            modeHoldBtn.setTextColor(0xFF00F0FF.toInt())
            modeToggleBtn.setTextColor(0xFF6B7794.toInt())
            stopTx()
        }

        rxOnlyBtn.setOnClickListener {
            if (!isConnected) return@setOnClickListener
            isListenOnly = !isListenOnly
            if (isListenOnly) {
                rxOnlyBtn.setTextColor(0xFFFFAA00.toInt())
                rxOnlyBtn.text = "RX: ON"
                stopTx()
                pttTouchArea.isEnabled = false
                pttVisualCircle.setBackgroundResource(R.drawable.bg_ptt_rx)
                webEngine.evaluateJavascript("setListenOnly(true);", null)
            } else {
                rxOnlyBtn.setTextColor(0xFF6B7794.toInt())
                rxOnlyBtn.text = "LISTEN-ONLY"
                pttTouchArea.isEnabled = true
                pttVisualCircle.setBackgroundResource(R.drawable.bg_ptt_idle)
                webEngine.evaluateJavascript("setListenOnly(false);", null)
            }
            updateSubtext()
        }

        setupPttTouch()
    }

    private fun checkPermissions() {
        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebEngine() {
        webEngine.settings.javaScriptEnabled = true
        webEngine.settings.domStorageEnabled = true
        webEngine.settings.mediaPlaybackRequiresUserGesture = false
        webEngine.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }
        webEngine.addJavascriptInterface(object {
            @JavascriptInterface
            fun onConnected() {
                runOnUiThread {
                    isConnected = true
                    statusBadge.setBackgroundResource(R.drawable.bg_status_online)
                    statusIndicator.setBackgroundResource(R.drawable.dot_green)
                    statusText.text = "MESH READY"
                    statusText.setTextColor(0xFF00FF88.toInt())
                    connectToggleBtn.text = "DISCONNECT"
                    callsignInput.isEnabled = false
                    pttTouchArea.isEnabled = true
                    pttVisualCircle.setBackgroundResource(R.drawable.bg_ptt_idle)
                    pttText.text = "MIC OFF"
                    updateSubtext()
                }
            }

            @JavascriptInterface
            fun onNodeCount(count: Int) {
                runOnUiThread {
                    activeNodesLabel.text = "ACTIVE NODES ($count)"
                }
            }

            @JavascriptInterface
            fun onDisconnected() {
                runOnUiThread {
                    disconnectSession()
                }
            }
        }, "AndroidHost")

        // Invisible Engine HTML running PeerJS inside OS WebRTC
        val engineHtml = """
            <!DOCTYPE html>
            <html>
            <head><script src="https://unpkg.com/peerjs@1.5.2/dist/peerjs.min.js"></script></head>
            <body>
            <script>
                let localStream = null, peer = null, myId = null, myName = "Sanee";
                let pollTimer = null, presenceTimer = null;
                const activeCalls = new Map();
                let isListenOnlyMode = false;

                async function startEngine(name) {
                    myName = name || "Operator";
                    myId = "app_" + myName.toLowerCase().replace(/[^a-z0-9]/g, '_') + "_" + Math.floor(100+Math.random()*900);
                    try {
                        localStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false });
                        localStream.getAudioTracks()[0].enabled = false;
                    } catch(e){}

                    peer = new Peer(myId, { debug: 0, config: { iceServers: [{ urls: "stun:stun.l.google.com:19302" }] } });
                    peer.on('open', () => {
                        AndroidHost.onConnected();
                        register();
                        presenceTimer = setInterval(register, 5000);
                        pollTimer = setInterval(poll, 3000);
                    });

                    peer.on('call', (call) => {
                        call.answer(localStream);
                        handleCall(call);
                        activeCalls.set(call.peer, call);
                    });
                }

                function handleCall(call) {
                    call.on('stream', (stream) => {
                        let audio = document.getElementById('aud_' + call.peer);
                        if (!audio) {
                            audio = document.createElement('audio');
                            audio.id = 'aud_' + call.peer;
                            audio.autoplay = true;
                            document.body.appendChild(audio);
                        }
                        audio.srcObject = stream;
                        audio.play().catch(()=>{});
                    });
                    call.on('close', () => {
                        const a = document.getElementById('aud_' + call.peer);
                        if (a) a.remove();
                        activeCalls.delete(call.peer);
                    });
                }

                async function register() {
                    if (!myId) return;
                    fetch('https://shansoulstudio.in/call/api.php?action=register&id=' + encodeURIComponent(myId) + '&name=' + encodeURIComponent(myName)).catch(()=>{});
                }

                async function poll() {
                    try {
                        const res = await fetch('https://shansoulstudio.in/call/api.php');
                        const nodes = await res.json();
                        AndroidHost.onNodeCount(Object.keys(nodes).length);
                        for (const peerId of Object.keys(nodes)) {
                            if (peerId !== myId && !activeCalls.has(peerId)) {
                                const call = peer.call(peerId, localStream);
                                if (call) {
                                    handleCall(call);
                                    activeCalls.set(peerId, call);
                                }
                            }
                        }
                    } catch(e){}
                }

                function setMic(active) {
                    if (!localStream || isListenOnlyMode) return;
                    localStream.getAudioTracks()[0].enabled = active;
                }

                function setListenOnly(val) {
                    isListenOnlyMode = val;
                    if (val && localStream) localStream.getAudioTracks()[0].enabled = false;
                }

                function stopEngine() {
                    if (pollTimer) clearInterval(pollTimer);
                    if (presenceTimer) clearInterval(presenceTimer);
                    if (myId) fetch('https://shansoulstudio.in/call/api.php?action=unregister&id=' + encodeURIComponent(myId)).catch(()=>{});
                    activeCalls.forEach(c => c.close());
                    activeCalls.clear();
                    if (localStream) { localStream.getTracks().forEach(t=>t.stop()); localStream = null; }
                    if (peer) { peer.destroy(); peer = null; }
                }
            </script>
            </body>
            </html>
        """.trimIndent()

        webEngine.loadDataWithBaseURL("https://shansoulstudio.in/call/", engineHtml, "text/html", "UTF-8", null)
    }

    private fun connectSession() {
        val name = callsignInput.text.toString().ifEmpty { "Sanee" }
        displayCallsign.text = "$name (APP)"
        selfSquadItem.visibility = View.VISIBLE
        webEngine.evaluateJavascript("startEngine('$name');", null)
    }

    private fun disconnectSession() {
        isConnected = false
        isTransmitting = false
        isListenOnly = false
        selfSquadItem.visibility = View.GONE
        webEngine.evaluateJavascript("stopEngine();", null)

        statusBadge.setBackgroundResource(R.drawable.bg_status_offline)
        statusIndicator.setBackgroundResource(R.drawable.dot_red)
        statusText.text = "OFFLINE"
        statusText.setTextColor(0xFFFF3366.toInt())
        connectToggleBtn.text = "CONNECT"
        callsignInput.isEnabled = true
        pttTouchArea.isEnabled = false
        pttVisualCircle.setBackgroundResource(R.drawable.bg_ptt_idle)
        pttText.text = "OFFLINE"
        activeNodesLabel.text = "ACTIVE NODES (0)"
        updateSubtext()
    }

    private fun startTx() {
        if (!isConnected || isListenOnly) return
        isTransmitting = true
        pttVisualCircle.setBackgroundResource(R.drawable.bg_ptt_active)
        pttText.text = "MIC LIVE"
        webEngine.evaluateJavascript("setMic(true);", null)
        updateSubtext()
    }

    private fun stopTx() {
        isTransmitting = false
        if (!isListenOnly) {
            pttVisualCircle.setBackgroundResource(R.drawable.bg_ptt_idle)
            if (isConnected) pttText.text = "MIC OFF"
        }
        webEngine.evaluateJavascript("setMic(false);", null)
        updateSubtext()
    }

    private fun updateSubtext() {
        if (!isConnected) { pttSub.text = "CONNECT TO ENGAGE"; return }
        if (isListenOnly) { pttSub.text = "RX ONLY / MIC MUTED"; return }
        pttSub.text = if (currentMode == "toggle") {
            if (isTransmitting) "TAP TO MUTE" else "TAP TO TRANSMIT"
        } else {
            "HOLD TO TALK"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPttTouch() {
        pttTouchArea.setOnTouchListener { _, event ->
            if (!isConnected || isListenOnly) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (currentMode == "hold") startTx() else { if (isTransmitting) stopTx() else startTx() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (currentMode == "hold") stopTx()
                    true
                }
                else -> false
            }
        }
    }
}
