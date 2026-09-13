package com.bulletproof.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FCMService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "ptt_call_channel"
        const val NOTIFICATION_ID = 999
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val action = remoteMessage.data["action"]
        val sender = remoteMessage.data["sender"] ?: "Tactical Node"

        if (action == "ptt_ring" || action == "wake_audio") {
            showIncomingCallNotification(sender)
            playDirectRingtone()
            
            val serviceIntent = Intent(this, CommsService::class.java).apply {
                putExtra("listen_only", false) // Default mode
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private fun showIncomingCallNotification(sender: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tactical PTT Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming walkie-talkie call alerts"
                enableVibration(true)
                setSound(ringtoneUri, audioAttributes)
            }
            manager.createNotificationChannel(channel)
        }

        val flagImmutable = 0x4000000
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("auto_connect", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PTT Incoming: $sender")
            .setContentText("Tap to latch audio stream")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSound(ringtoneUri)
            .setVibrate(longArrayOf(0, 500, 500, 500))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun playDirectRingtone() {
        try {
            val alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(applicationContext, alert)
            r.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
