package com.tpeapp.status

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager

/**
 * SubStatusManager — manages the sub's current status and shows a persistent
 * ongoing notification in the status bar that only the Dom can change via FCM.
 *
 * Valid status values: "free_time", "task_active", "restricted", "punished"
 */
object SubStatusManager {

    private const val TAG = "SubStatusManager"
    private const val PREF_STATUS = "sub_status"
    private const val CHANNEL_ID = "sub_status"
    private const val NOTIF_ID = 9001

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
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("$emoji $label")
            .setContentText("Current status: $label")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted", e)
        }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sub Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent indicator of the sub's current status (set by Dom via FCM)"
                setShowBadge(false)
            }
        )
    }
}
