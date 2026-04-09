package com.tpeapp.ritual

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object RitualRepository {

    private const val TAG = "RitualRepository"
    private const val PREF_RITUAL_STEPS = "ritual_steps"
    private const val PREF_MORNING_TIME = "ritual_morning_time_minutes"
    private const val PREF_EVENING_TIME = "ritual_evening_time_minutes"
    private const val DEFAULT_MORNING_TIME = 480   // 8 AM
    private const val DEFAULT_EVENING_TIME = 1260  // 9 PM

    fun getSteps(ctx: Context): List<RitualStep> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_RITUAL_STEPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                RitualStep(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    description = obj.optString("description", ""),
                    requiresPhoto = obj.optBoolean("requiresPhoto", false)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ritual steps", e)
            emptyList()
        }
    }

    fun setSteps(ctx: Context, steps: List<RitualStep>) {
        val arr = JSONArray()
        steps.forEach { step ->
            arr.put(JSONObject().apply {
                put("id", step.id)
                put("title", step.title)
                put("description", step.description)
                put("requiresPhoto", step.requiresPhoto)
            })
        }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_RITUAL_STEPS, arr.toString())
            .apply()
    }

    fun getMorningTime(ctx: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getInt(PREF_MORNING_TIME, DEFAULT_MORNING_TIME)

    fun setMorningTime(ctx: Context, minutes: Int) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putInt(PREF_MORNING_TIME, minutes)
            .apply()
    }

    fun getEveningTime(ctx: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getInt(PREF_EVENING_TIME, DEFAULT_EVENING_TIME)

    fun setEveningTime(ctx: Context, minutes: Int) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putInt(PREF_EVENING_TIME, minutes)
            .apply()
    }

    fun scheduleMorningAlarm(ctx: Context) {
        scheduleAlarm(
            ctx,
            getMorningTime(ctx),
            RitualAlarmReceiver.ACTION_RITUAL_MORNING,
            REQUEST_CODE_MORNING
        )
    }

    fun scheduleEveningAlarm(ctx: Context) {
        scheduleAlarm(
            ctx,
            getEveningTime(ctx),
            RitualAlarmReceiver.ACTION_RITUAL_EVENING,
            REQUEST_CODE_EVENING
        )
    }

    private fun scheduleAlarm(ctx: Context, timeMinutes: Int, action: String, requestCode: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val intent = Intent(ctx, RitualAlarmReceiver::class.java).apply { this.action = action }
        val pending = PendingIntent.getBroadcast(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, timeMinutes / 60)
            set(Calendar.MINUTE, timeMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
        Log.i(TAG, "Ritual alarm scheduled for $action at ${cal.time}")
    }

    private const val REQUEST_CODE_MORNING = 0x7701
    private const val REQUEST_CODE_EVENING = 0x7702
}
