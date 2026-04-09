package com.tpeapp.affirmation

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

object AffirmationRepository {

    private const val TAG = "AffirmationRepository"
    private const val PREF_AFFIRMATIONS = "affirmations_json"
    private const val PREF_MANTRA_INTERVAL = "mantra_interval_minutes"
    private const val PREF_MANTRA_ENABLED = "mantra_enabled"
    private const val DEFAULT_INTERVAL = 60

    fun getAll(ctx: Context): List<AffirmationEntry> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_AFFIRMATIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                AffirmationEntry(id = obj.getString("id"), text = obj.getString("text"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse affirmations", e)
            emptyList()
        }
    }

    fun setAll(ctx: Context, list: List<AffirmationEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().apply { put("id", it.id); put("text", it.text) }) }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_AFFIRMATIONS, arr.toString()).apply()
    }

    fun getRandom(ctx: Context): AffirmationEntry? = getAll(ctx).takeIf { it.isNotEmpty() }?.random()

    fun isMantraEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_MANTRA_ENABLED, false)

    fun setMantraEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_MANTRA_ENABLED, enabled).apply()
    }

    fun getMantraIntervalMinutes(ctx: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(ctx).getInt(PREF_MANTRA_INTERVAL, DEFAULT_INTERVAL)

    fun setMantraIntervalMinutes(ctx: Context, minutes: Int) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putInt(PREF_MANTRA_INTERVAL, minutes).apply()
    }
}
