package com.hound.controller

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

private const val STOP_ACTUATION_ACTION = "com.hound.controller.ACTION_STOP_ACTUATION"
private const val EDGE_DOWN_ACTION = "com.hound.controller.ACTION_EDGE_DOWN"
private const val STOP_ACTUATION_PREFS = "tpe_runtime_flags"
private const val STOP_ACTUATION_AT_KEY = "stop_actuation_requested_at"
private const val EDGE_DOWN_AT_KEY = "edge_down_requested_at"

class StopActuationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != STOP_ACTUATION_ACTION && action != EDGE_DOWN_ACTION) {
            return
        }

        val prefs = context.getSharedPreferences(STOP_ACTUATION_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs
            .edit()
            .apply {
                if (action == STOP_ACTUATION_ACTION) {
                    putLong(STOP_ACTUATION_AT_KEY, now)
                }
                if (action == EDGE_DOWN_ACTION) {
                    putLong(EDGE_DOWN_AT_KEY, now)
                }
            }
            .apply()

        val notificationId = intent.getIntExtra("notification_id", 0)
        if (notificationId > 0) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.cancel(notificationId)
        }
    }
}
