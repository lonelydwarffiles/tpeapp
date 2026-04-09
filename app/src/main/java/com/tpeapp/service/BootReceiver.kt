package com.tpeapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tpeapp.ble.LovenseScheduleManager
import com.tpeapp.gating.GeofenceManager
import com.tpeapp.oversight.ActivitySummaryWorker
import com.tpeapp.ritual.RitualRepository
import com.tpeapp.status.SubStatusManager

/** Restarts [FilterService] and all scheduled features when the device boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) return

        context.startForegroundService(Intent(context, FilterService::class.java))

        // Persistent status notification
        SubStatusManager.startStatusNotification(context)

        // Re-schedule daily ritual alarms
        RitualRepository.scheduleMorningAlarm(context)
        RitualRepository.scheduleEveningAlarm(context)

        // Re-schedule Lovense patterns
        LovenseScheduleManager.scheduleAll(context)

        // Resume geofence monitoring if enabled
        if (GeofenceManager.isEnabled(context)) {
            GeofenceManager.startMonitoring(context, store = true)
        }

        // Schedule daily activity summary
        ActivitySummaryWorker.schedule(context)
    }
}
