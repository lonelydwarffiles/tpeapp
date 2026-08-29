package com.hound.controller.status

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.hound.controller.R
import com.hound.controller.service.FilterService
import com.hound.controller.ui.MainActivity

/**
 * SubStatusManager — manages the sub's current status and shows a persistent
 * ongoing notification in the status bar that only the Dom can change via FCM.
 *
 * Valid status values: "free_time", "task_active", "restricted", "punished"
 */
object SubStatusManager {

    private const val TAG = "SubStatusManager"
    private const val PREF_STATUS = "sub_status"
    private const val CHANNEL_ID = FilterService.CORE_CHANNEL_ID
    private const val NOTIF_ID = FilterService.CORE_NOTIFICATION_ID

    const val STATUS_FREE_TIME   = "free_time"
    const val STATUS_TASK_ACTIVE = "task_active"
    const val STATUS_RESTRICTED  = "restricted"
    const val STATUS_PUNISHED    = "punished"

    fun getStatus(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_STATUS, STATUS_FREE_TIME) ?: STATUS_FREE_TIME

    fun setStatus(ctx: Context, status: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_STATUS, status).apply()
        Log.i(TAG, "Status set to $status")
        updateStatusNotification(ctx)
    }

    fun startStatusNotification(ctx: Context) {
        ensureChannel(ctx)
        updateStatusNotification(ctx)
    }

    fun updateStatusNotification(ctx: Context) {
        ensureChannel(ctx)
        val status = getStatus(ctx)
        val (emoji, label) = when (status) {
            STATUS_FREE_TIME   -> "🟢" to "Free Time"
            STATUS_TASK_ACTIVE -> "🔵" to "Task Active"
            STATUS_RESTRICTED  -> "🔴" to "Restricted"
            STATUS_PUNISHED    -> "🟣" to "Punished"
            else               -> "⚪" to status
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Accountability core is active")
            .setContentText("Status: $emoji $label • Commands + filter online")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx,
                    0,
                    Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notification)
            ctx.startService(
                Intent(ctx, FilterService::class.java).apply {
                    action = FilterService.ACTION_REFRESH_CORE_NOTIFICATION
                },
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted", e)
        }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Accountability Core Active", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent indicator of accountability core status"
                setShowBadge(false)
            }
        )
    }
}
