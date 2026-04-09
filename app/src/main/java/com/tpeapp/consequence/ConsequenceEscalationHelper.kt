package com.tpeapp.consequence

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.ble.LovenseManager
import com.tpeapp.ble.PavlokManager
import com.tpeapp.mdm.AppDeviceAdminReceiver
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * CornerTimeActivity — full-screen countdown with camera preview.
 * Launched via FCM START_CORNER_TIME → full-screen intent notification.
 */
// (This file extends ConsequenceDispatcher with escalation support.)

object ConsequenceEscalationHelper {

    private const val TAG = "ConsequenceEscalation"
    const val ACTION_ESCALATE = "com.tpeapp.ACTION_CONSEQUENCE_ESCALATE"
    const val EXTRA_REASON = "escalation_reason"
    const val EXTRA_LEVEL = "escalation_level"
    private const val REQUEST_CODE = 0x6601

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Delivers a levelled punishment:
     *  0 = mild (Lovense 10, no Pavlok)
     *  1 = standard (full ConsequenceDispatcher.punish)
     *  2 = severe (punish + lock screen)
     */
    fun punishAtLevel(context: Context, reason: String, level: Int) {
        Log.i(TAG, "punishAtLevel level=$level reason=$reason")
        val appCtx = context.applicationContext
        when (level) {
            0 -> scope.launch {
                LovenseManager.init(appCtx)
                LovenseManager.vibrate(10)
                delay(2_000)
                LovenseManager.stopAll()
                dispatchWebhook(appCtx, "punishment_mild", reason)
            }
            1 -> ConsequenceDispatcher.punish(appCtx, reason)
            else -> {
                ConsequenceDispatcher.punish(appCtx, reason)
                scope.launch {
                    delay(500)
                    try {
                        val dpm = appCtx.getSystemService(DevicePolicyManager::class.java)
                        val admin = ComponentName(appCtx, AppDeviceAdminReceiver::class.java)
                        if (dpm.isAdminActive(admin)) dpm.lockNow()
                    } catch (e: Exception) {
                        Log.w(TAG, "lockNow failed", e)
                    }
                    dispatchWebhook(appCtx, "punishment_severe_lockout", reason)
                }
            }
        }
    }

    /**
     * Schedules an escalation alarm to fire after [timeoutMinutes].
     * If the sub acknowledges the consequence in time, call [cancelEscalation].
     */
    fun scheduleEscalation(context: Context, reason: String, currentLevel: Int, timeoutMinutes: Int) {
        val nextLevel = minOf(currentLevel + 1, 2)
        val triggerMs = System.currentTimeMillis() + timeoutMinutes * 60_000L
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ConsequenceEscalationReceiver::class.java).apply {
            action = ACTION_ESCALATE
            putExtra(EXTRA_REASON, reason)
            putExtra(EXTRA_LEVEL, nextLevel)
        }
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pending)
        Log.i(TAG, "Escalation scheduled in ${timeoutMinutes}m → level $nextLevel")
    }

    /** Cancels a pending escalation alarm (call when consequence is acknowledged). */
    fun cancelEscalation(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ConsequenceEscalationReceiver::class.java).apply {
            action = ACTION_ESCALATE
        }
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let { am.cancel(it); it.cancel() }
        Log.i(TAG, "Escalation alarm cancelled")
    }

    private fun dispatchWebhook(ctx: Context, event: String, reason: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() } ?: return
        val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
        WebhookManager.dispatchEvent(url, token, JSONObject().apply {
            put("event", event); put("reason", reason); put("timestamp", System.currentTimeMillis())
        })
    }
}
