package com.tpeapp.ritual

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class RitualAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RitualAlarmReceiver"
        const val ACTION_RITUAL_MORNING = "com.tpeapp.ACTION_RITUAL_MORNING"
        const val ACTION_RITUAL_EVENING = "com.tpeapp.ACTION_RITUAL_EVENING"
        private const val CHANNEL_ID = "ritual_alarm"
        private const val NOTIF_ID_MORNING = 0x7710
        private const val NOTIF_ID_EVENING = 0x7711
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isMorning = intent.action == ACTION_RITUAL_MORNING
        if (intent.action != ACTION_RITUAL_MORNING && intent.action != ACTION_RITUAL_EVENING) return

        Log.i(TAG, "Ritual alarm fired: ${intent.action}")
        ensureChannel(context)

        val activityIntent = Intent(context, RitualChecklistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val notifId = if (isMorning) NOTIF_ID_MORNING else NOTIF_ID_EVENING
        val fullScreenPending = PendingIntent.getActivity(
            context, notifId, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isMorning) "🌅 Morning Ritual Required" else "🌙 Evening Ritual Required"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("Tap to begin your ritual checklist.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post notification — launching directly", e)
            context.startActivity(activityIntent)
        }

        // Re-schedule for next day
        if (isMorning) RitualRepository.scheduleMorningAlarm(context)
        else RitualRepository.scheduleEveningAlarm(context)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Daily Rituals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts for morning and evening ritual checklists"
                enableLights(true)
                enableVibration(true)
            }
        )
    }
}
