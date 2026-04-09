package com.tpeapp.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * LovenseScheduleManager — stores and schedules timed vibration patterns.
 *
 * Each schedule fires a daily repeating alarm via [AlarmManager.setRepeating].
 * The alarm is handled by [LovenseScheduleReceiver].
 */
object LovenseScheduleManager {

    private const val TAG = "LovenseScheduleMgr"
    private const val PREF_SCHEDULES = "lovense_schedules_json"
    private const val BASE_REQUEST_CODE = 0x5500

    data class LovenseSchedule(
        val id: String,
        val timeOfDayMinutes: Int,
        val vibrationLevel: Int,
        val durationMs: Int,
        val label: String
    )

    fun getSchedules(ctx: Context): List<LovenseSchedule> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_SCHEDULES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                LovenseSchedule(
                    id = o.getString("id"),
                    timeOfDayMinutes = o.getInt("time_of_day_minutes"),
                    vibrationLevel = o.getInt("vibration_level"),
                    durationMs = o.getInt("duration_ms"),
                    label = o.optString("label", "")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse schedules", e)
            emptyList()
        }
    }

    fun setSchedules(ctx: Context, list: List<LovenseSchedule>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("time_of_day_minutes", s.timeOfDayMinutes)
                put("vibration_level", s.vibrationLevel); put("duration_ms", s.durationMs)
                put("label", s.label)
            })
        }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_SCHEDULES, arr.toString()).apply()
    }

    fun scheduleAll(ctx: Context) {
        getSchedules(ctx).forEach { scheduleSingle(ctx, it) }
    }

    fun scheduleSingle(ctx: Context, schedule: LovenseSchedule) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val intent = Intent(ctx, LovenseScheduleReceiver::class.java).apply {
            action = LovenseScheduleReceiver.ACTION_LOVENSE_SCHEDULED
            putExtra(LovenseScheduleReceiver.EXTRA_VIBRATION_LEVEL, schedule.vibrationLevel)
            putExtra(LovenseScheduleReceiver.EXTRA_DURATION_MS, schedule.durationMs)
            putExtra(LovenseScheduleReceiver.EXTRA_SCHEDULE_ID, schedule.id)
        }
        val requestCode = BASE_REQUEST_CODE + (schedule.id.hashCode() and 0x0FFF)
        val pending = PendingIntent.getBroadcast(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, schedule.timeOfDayMinutes / 60)
            set(java.util.Calendar.MINUTE, schedule.timeOfDayMinutes % 60)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }

        am.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pending)
        Log.i(TAG, "Lovense schedule '${schedule.label}' set for ${schedule.timeOfDayMinutes / 60}:${schedule.timeOfDayMinutes % 60}")
    }
}
