package com.tpeapp.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.accessibility.AccessibilityServiceKeeper
import com.tpeapp.mqtt.PartnerMqttService
import com.tpeapp.pairing.PairingActivity

/**
 * CoreServiceKeeper centralizes keepalive behavior for the app's critical services.
 */
object CoreServiceKeeper {
    private const val TAG = "CoreServiceKeeper"

    fun isPaired(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PairingActivity.PREF_IS_PAIRED, false)
    }

    fun ensureCoreServicesRunning(context: Context, reason: String) {
        val appContext = context.applicationContext
        AccessibilityServiceKeeper.ensurePersistent(appContext, reason)
        if (!isPaired(appContext)) {
            Log.d(TAG, "Skipping core service start; device not paired (reason=$reason)")
            return
        }

        startForegroundServiceCompat(appContext, Intent(appContext, FilterService::class.java), "FilterService", reason)
        startServiceCompat(appContext, Intent(appContext, PartnerMqttService::class.java), "PartnerMqttService", reason)
    }

    fun scheduleWatchdog(context: Context) {
        ServiceWatchdogWorker.schedule(context.applicationContext)
    }

    private fun startForegroundServiceCompat(
        context: Context,
        intent: Intent,
        serviceName: String,
        reason: String,
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { firstErr ->
            runCatching { context.startService(intent) }
                .onFailure { secondErr ->
                    Log.w(
                        TAG,
                        "Failed to start $serviceName (reason=$reason): ${firstErr.message ?: firstErr::class.java.simpleName}; fallback=${secondErr.message ?: secondErr::class.java.simpleName}",
                    )
                }
        }
    }

    private fun startServiceCompat(
        context: Context,
        intent: Intent,
        serviceName: String,
        reason: String,
    ) {
        runCatching {
            context.startService(intent)
        }.onFailure { err ->
            Log.w(
                TAG,
                "Failed to start $serviceName (reason=$reason): ${err.message ?: err::class.java.simpleName}",
            )
        }
    }
}
