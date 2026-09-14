@file:Suppress("DEPRECATION", "SetJavaScriptEnabled")

package com.bulletproof.call

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import org.json.JSONObject

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
    private lateinit var nodesContainer: LinearLayout
    private lateinit var activeNodesHeader: TextView

    private var isConnected = false
    private var isTransmitting = false
    private var isListenOnly = false
    private var currentMode = "toggle"

    private var ringtone: Ringtone? = null
    private var ringDialog: AlertDialog? = null

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
        pttTouchArea = findViewById(R.id.pttTouchArea)
        pttVisualCircle = findViewById(R.id.pttVisualCircle)
        pttText = findViewById(R.id.pttText)
        pttSub = findViewById(R.id.pttSub)
        selfSquadItem = findViewById(R.id.selfSquadItem)
        displayCallsign = findViewById(R.id.displayCallsign)
        nodesContainer = findViewById(R.id.nodesContainer)
        activeNodesHeader = findViewById(R.id.activeNodesHeader)

        checkPermissions()
        setupDynamicWebEngine()

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
    private fun setupDynamicWebEngine() {
        webEngine = WebView(this)
        findViewById<ViewGroup>(android.R.id.content).addView(webEngine, ViewGroup.LayoutParams(1, 1))

        webEngine.settings.javaScriptEnabled = true
        webEngine.settings.domStorageEnabled = true
        webEngine.settings.mediaPlaybackRequiresUserGesture = false
        webEngine.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        class NativeBridge {
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
            fun onIncomingCall(caller: String) {
                runOnUiThread {
                    triggerIncomingAlert(caller)
                }
            }

            @JavascriptInterface
            fun updateNodes(jsonStr: String) {
                runOnUiThread {
                    renderNodesList(jsonStr)
                }
            }

            @JavascriptInterface
            fun onDisconnected() {
                runOnUiThread { disconnectSession() }
            }
        }

        webEngine.addJavascriptInterface(NativeBridge(), "AndroidHost")

        val engineHtml = """
            <!DOCTYPE html>
            <html>
            <head><script src="https://unpkg.com/peerjs@1.5.2/dist/peerjs.min.js"></script></head>
            <body>
            <script>
                var localStream = null;
                var peer = null;
                var myId = null;
                var myName = "Sanee";
                var pollTimer = null;
                var presenceTimer = null;
                var activeCalls = {};
                var isListenOnlyMode = false;
                var lastRingTime = 0;

                function startEngine(name) {
                    myName = name || "Operator";
                    myId = "app_" + myName.toLowerCase().replace(/[^a-z0-9]/g, '_') + "_" + Math.floor(100+Math.random()*900);
                    navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false })
                    .then(function(s) {
                        localStream = s;
                        if(localStream.getAudioTracks()[0]) localStream.getAudioTracks()[0].enabled = false;
                        initPeer();
                    }).catch(function() {
                        initPeer();
                    });
                }

                function initPeer() {
                    peer = new Peer(myId, { debug: 0, config: { iceServers: [{ urls: "stun:stun.l.google.com:19302" }] } });
                    peer.on('open', function() {
                        AndroidHost.onConnected();
                        register();
                        presenceTimer = setInterval(register, 5000);
                        pollTimer = setInterval(poll, 2500);
                    });
                    peer.on('call', function(call) {
                        call.answer(localStream);
                        handleCall(call);
                        activeCalls[call.peer] = call;
                    });
                }

                function handleCall(call) {
                    call.on('stream', function(stream) {
                        var audio = document.getElementById('aud_' + call.peer);
                        if (!audio) {
                            audio = document.createElement('audio');
                            audio.id = 'aud_' + call.peer;
                            audio.autoplay = true;
                            document.body.appendChild(audio);
                        }
                        audio.srcObject = stream;
                        audio.play().catch(function(){});
                    });
                    call.on('close', function() {
                        var a = document.getElementById('aud_' + call.peer);
                        if (a) a.remove();
                        delete activeCalls[call.peer];
                    });
                }

                function register() {
                    if (!myId) return;
                    fetch('https://shansoulstudio.in/call/api.php?action=register&id=' + encodeURIComponent(myId) + '&name=' + encodeURIComponent(myName)).catch(function(){});
                }

                function triggerRing() {
                    fetch('https://shansoulstudio.in/call/api.php?action=ring&caller=' + encodeURIComponent(myName) + '&id=' + encodeURIComponent(myId)).catch(function(){});
                }

                function poll() {
                    fetch('https://shansoulstudio.in/call/api.php')
                    .then(function(r){ return r.json(); })
                    .then(function(data) {
                        if (data.ring && data.ring.id !== myId && (Date.now() - data.ring.ts) < 8000) {
                            if (data.ring.ts > lastRingTime) {
                                lastRingTime = data.ring.ts;
                                AndroidHost.onIncomingCall(data.ring.caller);
                            }
                        }
                        if (data.peers) {
                            AndroidHost.updateNodes(JSON.stringify(data.peers));
                            for (var peerId in data.peers) {
                                if (peerId !== myId && !activeCalls[peerId]) {
                                    var call = peer.call(peerId, localStream);
                                    if (call) {
                                        handleCall(call);
                                        activeCalls[peerId] = call;
                                    }
                                }
                            }
                        }
                    }).catch(function(){});
                }

                function setMic(active) {
                    if (!localStream || isListenOnlyMode) return;
                    if(localStream.getAudioTracks()[0]) localStream.getAudioTracks()[0].enabled = active;
                }

                function setListenOnly(val) {
                    isListenOnlyMode = val;
                    if (val && localStream && localStream.getAudioTracks()[0]) localStream.getAudioTracks()[0].enabled = false;
                }

                function stopEngine() {
                    if (pollTimer) clearInterval(pollTimer);
                    if (presenceTimer) clearInterval(presenceTimer);
                    if (myId) fetch('https://shansoulstudio.in/call/api.php?action=unregister&id=' + encodeURIComponent(myId)).catch(function(){});
                    for (var k in activeCalls) { activeCalls[k].close(); }
                    activeCalls = {};
                    if (localStream) {
                        localStream.getTracks().forEach(function(t){ t.stop(); });
                        localStream = null;
                    }
                    if (peer) { peer.destroy(); peer = null; }
                }
            </script>
            </body>
            </html>
        """.trimIndent()

        webEngine.loadDataWithBaseURL("https://shansoulstudio.in/call/", engineHtml, "text/html", "UTF-8", null)
    }

    private fun renderNodesList(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val currentSelfId = "app_" + callsignInput.text.toString().trim().lowercase()

            for (i in nodesContainer.childCount - 1 downTo 1) {
                nodesContainer.removeViewAt(i)
            }

            var count = 1
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                if (id.startsWith(currentSelfId)) continue

                count++
                val nodeObj = json.getJSONObject(id)
                val nodeName = nodeObj.optString("name", "Node")
                val isWeb = id.startsWith("web")

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(24, 20, 24, 20)
                    setBackgroundColor(0xFF161C28.toInt())
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0, 8, 0, 0)
                    layoutParams = lp
                }

                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(16, 16).apply {
                        marginEnd = 16
                    }
                    setBackgroundResource(R.drawable.dot_green)
                }

                val nameTv = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = "$nodeName (${if (isWeb) "WEB" else "APP"})"
                    setTextColor(0xFFF0F4FC.toInt())
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val badge = TextView(this).apply {
                    text = "LINKED"
                    setTextColor(0xFF00FF88.toInt())
                    textSize = 9f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(16, 4, 16, 4)
                    setBackgroundColor(0xFF1F2B45.toInt())
                }

                row.addView(dot)
                row.addView(nameTv)
                row.addView(badge)
                nodesContainer.addView(row)
            }
            activeNodesHeader.text = "ACTIVE NODES ($count)"
        } catch (_: Exception) {}
    }

    private fun triggerIncomingAlert(caller: String) {
        if (ringDialog?.isShowing == true) return

        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, alertUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()

            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(longArrayOf(0, 800, 400, 800, 400), 0)
        } catch (_: Exception) {}

        ringDialog = AlertDialog.Builder(this)
            .setTitle("🚨 INCOMING CALL")
            .setMessage("$caller is calling on Tactical Mesh!")
            .setCancelable(false)
            .setPositiveButton("ACCEPT CALL") { d, _ ->
                stopRinging()
                d.dismiss()
            }
            .setNegativeButton("DISMISS") { d, _ ->
                stopRinging()
                d.dismiss()
            }
            .create()

        ringDialog?.show()
    }

    private fun stopRinging() {
        try {
            ringtone?.stop()
            ringtone = null
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.cancel()
        } catch (_: Exception) {}
    }

    private fun connectSession() {
        val name = callsignInput.text.toString().ifEmpty { "Sanee" }
        displayCallsign.text = "$name (APP)"
        selfSquadItem.visibility = View.VISIBLE

        val serviceIntent = Intent(this, CommsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        webEngine.evaluateJavascript("startEngine('$name');", null)
    }

    private fun disconnectSession() {
        stopRinging()
        isConnected = false
        isTransmitting = false
        isListenOnly = false
        selfSquadItem.visibility = View.GONE

        stopService(Intent(this, CommsService::class.java))
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
        updateSubtext()
    }

    private fun startTx() {
        if (!isConnected || isListenOnly) return
        isTransmitting = true
        pttVisualCircle.setBackgroundResource(R.drawable.bg_ptt_active)
        pttText.text = "MIC LIVE"
        webEngine.evaluateJavascript("setMic(true); triggerRing();", null)
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
