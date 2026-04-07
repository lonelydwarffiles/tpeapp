package com.tpeapp.adherence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * AdherenceAlarmReceiver
 *
 * Receives the [AlarmManager] broadcast that fires at the user's scheduled
 * daily health-routine time and launches [AdherenceKioskActivity].
 *
 * On Android 10+ (API 29+) apps cannot start activities directly from a
 * background [BroadcastReceiver].  The recommended pattern for alarm-clock-
 * style prompts is to post a high-priority notification with a
 * full-screen intent that the system presents as an activity launch when the
 * device is locked, or as a heads-up notification when it is unlocked.
 *
 * Scheduling (caller responsibility)
 * ------------------------------------
 * Use [AlarmManager.setExactAndAllowWhileIdle] from a foreground context or
 * service to schedule this receiver.  Example:
 *
 * ```kotlin
 * val intent = Intent(context, AdherenceAlarmReceiver::class.java)
 *     .setAction(ACTION_ADHERENCE_ALARM)
 * val pending = PendingIntent.getBroadcast(
 *     context, 0, intent,
 *     PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
 * )
 * alarmManager.setExactAndAllowWhileIdle(
 *     AlarmManager.RTC_WAKEUP, triggerAtMillis, pending
 * )
 * ```
 *
 * Required manifest entries
 * --------------------------
 * - `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />`
 * - `<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />`
 * - `<receiver android:name=".adherence.AdherenceAlarmReceiver" ... />`
 * - `<activity android:name=".adherence.AdherenceKioskActivity"
 *       android:showWhenLocked="true"
 *       android:turnScreenOn="true" ... />`
 */
class AdherenceAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AdherenceAlarmReceiver"

        /** Intent action used when scheduling the alarm. */
        const val ACTION_ADHERENCE_ALARM = "com.tpeapp.ACTION_ADHERENCE_ALARM"

        private const val NOTIFICATION_CHANNEL_ID   = "adherence_alarm"
        private const val NOTIFICATION_CHANNEL_NAME = "Daily Health Routine"
        private const val NOTIFICATION_ID           = 0xAD2
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADHERENCE_ALARM) return

        Log.i(TAG, "Adherence alarm fired — launching kiosk")

        ensureNotificationChannel(context)

        // Full-screen intent — shown as an activity on lock screen, or as a
        // heads-up notification when the device is already unlocked.
        val kioskIntent = Intent(context, AdherenceKioskActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            kioskIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Daily Health Routine Required")
            .setContentText("Tap to record your 15-second health check-in.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, /* highPriority = */ true)
            .setContentIntent(fullScreenPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was not granted — fall back to direct activity start.
            Log.w(TAG, "Cannot post notification (missing POST_NOTIFICATIONS). " +
                    "Attempting direct activity launch.", e)
            context.startActivity(kioskIntent)
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when the daily health routine recording is due"
                enableLights(true)
                enableVibration(true)
            }
        )
    }
}
