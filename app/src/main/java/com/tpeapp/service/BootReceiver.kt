package com.hound.controller.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hound.controller.ble.LovenseScheduleManager
import com.hound.controller.gating.GeofenceManager
import com.hound.controller.oversight.ActivitySummaryWorker
import com.hound.controller.ritual.RitualRepository
import com.hound.controller.status.SubStatusManager
import com.hound.controller.vpn.TpeVpnPolicyManager
import java.util.concurrent.TimeUnit

/** Restarts [FilterService] and all scheduled features when the device boots. */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val ROOT_CMD_TIMEOUT_MS = 4_000L
    }

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

        // Keep VPN default-on across reboot/update while respecting explicit disconnect.
        TpeVpnPolicyManager.ensureDefaultEnabled(context, action)

        forceStartCacheWatchdogViaRoot(context)
    }

    private fun forceStartCacheWatchdogViaRoot(context: Context) {
        val component = "${context.packageName}/.service.CacheWatchdogService"
        val commands = listOf(
            "am startservice -n $component",
            "am start-foreground-service -n $component",
        )

        val started = commands.any { cmd ->
            runRootCommand(cmd)
        }

        if (!started) {
            Log.w(TAG, "Root force-start failed for CacheWatchdogService")
        }
    }

    private fun runRootCommand(command: String): Boolean {
        val process = runCatching {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: run {
            Log.w(TAG, "Unable to spawn root shell for command: $command")
            return false
        }

        val exited = runCatching {
            process.waitFor(ROOT_CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!exited || process.exitValue() != 0) {
            val output = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("").trim()
            Log.w(TAG, "Root command failed: $command output=$output")
            return false
        }

        return true
    }
}
