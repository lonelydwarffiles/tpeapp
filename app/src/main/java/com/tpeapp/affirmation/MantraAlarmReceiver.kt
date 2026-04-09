package com.tpeapp.affirmation

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * MantraAlarmReceiver — fires at configurable intervals to surface an
 * [AffirmationActivity] the sub must complete.
 *
 * Self-reschedules after each fire using [AffirmationRepository.getMantraIntervalMinutes].
 * Initial scheduling is done via [scheduleNext].
 */
class MantraAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MantraAlarmReceiver"
        const val ACTION_MANTRA_ALARM = "com.tpeapp.ACTION_MANTRA_ALARM"
        private const val CHANNEL_ID = "mantra_reminders"
        private const val NOTIF_ID = 0x8801
        private const val REQUEST_CODE = 0x8800

        fun scheduleNext(ctx: Context) {
            if (!AffirmationRepository.isMantraEnabled(ctx)) return
            val intervalMs = AffirmationRepository.getMantraIntervalMinutes(ctx) * 60_000L
            val triggerMs = System.currentTimeMillis() + intervalMs
            val am = ctx.getSystemService(AlarmManager::class.java)
            val intent = Intent(ctx, MantraAlarmReceiver::class.java).apply {
                action = ACTION_MANTRA_ALARM
            }
            val pending = PendingIntent.getBroadcast(
                ctx, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pending)
            Log.i(TAG, "Mantra alarm scheduled in ${intervalMs / 60_000}m")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MANTRA_ALARM) return
        if (!AffirmationRepository.isMantraEnabled(context)) return

        val entry = AffirmationRepository.getRandom(context) ?: run {
            Log.d(TAG, "No affirmations configured — skipping")
            scheduleNext(context)
            return
        }

        Log.i(TAG, "Mantra alarm fired")
        ensureChannel(context)

        val activityIntent = Intent(context, AffirmationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AffirmationActivity.EXTRA_TEXT, entry.text)
            putExtra(AffirmationActivity.EXTRA_REQUIRE_TYPING, true)
        }
        val tapPending = PendingIntent.getActivity(
            context, NOTIF_ID, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("💭 Time for your affirmation")
            .setContentText(entry.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .setFullScreenIntent(tapPending, true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — launching directly", e)
            context.startActivity(activityIntent)
        }

        scheduleNext(context)
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mantra Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Periodic affirmation reminders from your partner"
                enableVibration(true)
            }
        )
    }
}
