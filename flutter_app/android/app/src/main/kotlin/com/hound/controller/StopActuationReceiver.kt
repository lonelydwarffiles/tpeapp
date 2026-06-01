package com.hound.controller

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

private const val STOP_ACTUATION_ACTION = "com.hound.controller.ACTION_STOP_ACTUATION"
private const val STOP_ACTUATION_PREFS = "tpe_runtime_flags"
private const val STOP_ACTUATION_AT_KEY = "stop_actuation_requested_at"

class StopActuationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != STOP_ACTUATION_ACTION) {
            return
        }

        context.getSharedPreferences(STOP_ACTUATION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(STOP_ACTUATION_AT_KEY, System.currentTimeMillis())
            .apply()

        val notificationId = intent.getIntExtra("notification_id", 0)
        if (notificationId > 0) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.cancel(notificationId)
        }
    }
}
