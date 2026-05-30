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
        val action = intent.action ?: return
        val shouldHandle = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!shouldHandle) return

        CoreServiceKeeper.ensureCoreServicesRunning(context, action)
        CoreServiceKeeper.scheduleWatchdog(context)

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
