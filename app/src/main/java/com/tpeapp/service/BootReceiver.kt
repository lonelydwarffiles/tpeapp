package com.tpeapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts [FilterService] when the device boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            context.startForegroundService(Intent(context, FilterService::class.java))
        }
    }
}
