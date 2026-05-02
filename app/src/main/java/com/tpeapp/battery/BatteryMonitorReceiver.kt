package com.tpeapp.battery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.tpeapp.R
import com.tpeapp.consequence.ConsequenceDispatcher
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.service.FilterService
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * BatteryMonitorReceiver — a [BroadcastReceiver] that watches battery level
 * changes and enforces accountability rules when the battery falls too low:
 *
 * | Threshold | Severity   | Behaviour                                                     |
 * |-----------|------------|---------------------------------------------------------------|
 * | < 15 %    | Warning    | Local notification + backend POST (`level: "warning"`)        |
 * | < 5 %     | Critical   | `ConsequenceDispatcher.punish()` + notification + backend POST |
 *
 * Each threshold fires **once per descent** — the receiver tracks the last
 * reported severity in [SharedPreferences] so repeated broadcasts at 14 %
 * do not retrigger the punishment.  The counters reset when the battery is
 * plugged in.
 *
 * Register this receiver **dynamically** in any long-running service (e.g.
 * [com.tpeapp.service.FilterService]) with [Intent.ACTION_BATTERY_CHANGED].
 * The static registration in AndroidManifest.xml is required for the boot
 * path (`BOOT_COMPLETED`) only; `ACTION_BATTERY_CHANGED` is a sticky implicit
 * broadcast that must be registered at runtime.
 */
class BatteryMonitorReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatteryMonitorReceiver"

        private const val CHANNEL_ID   = "tpe_battery_alert"
        private const val NOTIF_WARN   = 10001
        private const val NOTIF_CRIT   = 10002

        /** SharedPreferences key tracking the last battery-consequence severity fired. */
        private const val PREF_LAST_BATTERY_SEVERITY = "battery_last_severity"

        private const val SEVERITY_NONE     = "none"
        private const val SEVERITY_WARNING  = "warning"
        private const val SEVERITY_CRITICAL = "critical"

        /** Path on the FastAPI backend that receives device status (battery, GPS, AI). */
        const val DEVICE_STATUS_PATH = "/api/handler/device-status"

        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
            // Note: delivery is best-effort. OkHttp's internal thread pool keeps
            // the callback alive after onReceive() returns — mirroring the pattern
            // used by WebhookManager throughout the app.

        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
            handleBatteryChanged(context, intent)
        }
    }

    private fun handleBatteryChanged(context: Context, intent: Intent) {
        val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)

        if (level < 0 || scale <= 0) return

        val percent = (level * 100) / scale

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Reset severity tracking when the device is charging.
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        if (isCharging) {
            if (prefs.getString(PREF_LAST_BATTERY_SEVERITY, SEVERITY_NONE) != SEVERITY_NONE) {
                prefs.edit().putString(PREF_LAST_BATTERY_SEVERITY, SEVERITY_NONE).apply()
                Log.d(TAG, "Charging — battery severity reset")
            }
            return
        }

        val lastSeverity = prefs.getString(PREF_LAST_BATTERY_SEVERITY, SEVERITY_NONE) ?: SEVERITY_NONE

        when {
            percent < 5 && lastSeverity != SEVERITY_CRITICAL -> {
                Log.w(TAG, "Battery critical: $percent%")
                prefs.edit().putString(PREF_LAST_BATTERY_SEVERITY, SEVERITY_CRITICAL).apply()

                val reason = "Battery critically low ($percent%) — device must be charged immediately."
                ConsequenceDispatcher.punish(context, reason)
                showBatteryNotification(context, NOTIF_CRIT, "Battery Critical: $percent%", reason)
                reportBatteryEvent(context, percent, SEVERITY_CRITICAL)
            }
            percent < 15 && lastSeverity == SEVERITY_NONE -> {
                Log.w(TAG, "Battery warning: $percent%")
                prefs.edit().putString(PREF_LAST_BATTERY_SEVERITY, SEVERITY_WARNING).apply()

                val reason = "Battery low ($percent%) — please charge your device."
                showBatteryNotification(context, NOTIF_WARN, "Battery Warning: $percent%", reason)
                reportBatteryEvent(context, percent, SEVERITY_WARNING)
            }
        }
    }

    // ------------------------------------------------------------------
    //  Local notification
    // ------------------------------------------------------------------

    private fun showBatteryNotification(
        context: Context,
        notifId: Int,
        title: String,
        message: String
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Battery Accountability",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts issued when battery level falls below accountability thresholds."
                    enableVibration(true)
                }
            )
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(notifId, notif)
    }

    // ------------------------------------------------------------------
    //  Backend reporting
    // ------------------------------------------------------------------

    private fun reportBatteryEvent(context: Context, percent: Int, severity: String) {
        val prefs   = PreferenceManager.getDefaultSharedPreferences(context)
        val endpoint = prefs.getString(PairingActivity.PREF_PARTNER_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
            ?.trimEnd('/')
            ?: run {
                Log.d(TAG, "No backend URL configured — skipping battery report")
                return
            }
        val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
        val deviceId = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "No device_id configured — skipping battery report")
                return
            }

        val payload = JSONObject().apply {
            put("device_id",   deviceId)
            put("battery_pct", percent)
        }

        val body    = payload.toString().toRequestBody(JSON_TYPE)
        val builder = Request.Builder()
            .url("$endpoint$DEVICE_STATUS_PATH")
            .post(body)
        if (!bearerToken.isNullOrBlank()) {
            builder.addHeader("Authorization", "Bearer $bearerToken")
        }

        httpClient.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Battery event report failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) Log.d(TAG, "Battery event reported (HTTP ${it.code})")
                    else Log.w(TAG, "Battery event report rejected (HTTP ${it.code})")
                }
            }
        })
    }
}
