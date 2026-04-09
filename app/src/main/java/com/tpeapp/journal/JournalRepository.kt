package com.tpeapp.journal

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONArray
import org.json.JSONObject

object JournalRepository {

    private const val TAG = "JournalRepository"
    private const val PREF_JOURNAL_ENTRIES = "journal_entries_json"
    private const val PREF_INFRACTION_ENTRIES = "infraction_entries_json"
    private const val PREF_JOURNAL_REMINDER_TIME = "journal_reminder_time_minutes"
    private const val PREF_JOURNAL_DUE_TODAY = "journal_due_today"
    private const val DEFAULT_REMINDER_TIME = 1320  // 10 PM

    // ------------------------------------------------------------------
    //  Journal entries
    // ------------------------------------------------------------------

    fun addJournalEntry(ctx: Context, entry: JournalEntry) {
        val list = getJournalEntries(ctx).toMutableList()
        list.add(entry)
        val arr = JSONArray()
        list.forEach { arr.put(entryToJson(it)) }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_JOURNAL_ENTRIES, arr.toString()).apply()
        dispatchWebhook(ctx, "journal_entry", entryToJson(entry))
    }

    fun getJournalEntries(ctx: Context): List<JournalEntry> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_JOURNAL_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                JournalEntry(
                    id = o.getString("id"),
                    timestamp = o.getLong("timestamp"),
                    mood = o.getInt("mood"),
                    violations = o.optString("violations", ""),
                    gratitude = o.optString("gratitude", ""),
                    notes = o.optString("notes", "")
                )
            }
        } catch (e: Exception) { Log.w(TAG, "Failed to parse journal entries", e); emptyList() }
    }

    // ------------------------------------------------------------------
    //  Infractions
    // ------------------------------------------------------------------

    fun addInfraction(ctx: Context, entry: InfractionEntry) {
        val list = getInfractions(ctx).toMutableList()
        list.add(entry)
        val arr = JSONArray()
        list.forEach { arr.put(infractionToJson(it)) }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_INFRACTION_ENTRIES, arr.toString()).apply()
        dispatchWebhook(ctx, "infraction_reported", infractionToJson(entry))
    }

    fun getInfractions(ctx: Context): List<InfractionEntry> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_INFRACTION_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                InfractionEntry(
                    id = o.getString("id"),
                    timestamp = o.getLong("timestamp"),
                    description = o.optString("description", ""),
                    category = o.optString("category", "other")
                )
            }
        } catch (e: Exception) { Log.w(TAG, "Failed to parse infractions", e); emptyList() }
    }

    // ------------------------------------------------------------------
    //  Scheduling helpers
    // ------------------------------------------------------------------

    fun getReminderTimeMinutes(ctx: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(ctx).getInt(PREF_JOURNAL_REMINDER_TIME, DEFAULT_REMINDER_TIME)

    fun setReminderTimeMinutes(ctx: Context, minutes: Int) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putInt(PREF_JOURNAL_REMINDER_TIME, minutes).apply()
    }

    fun isJournalDueToday(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_JOURNAL_DUE_TODAY, false)

    fun markJournalDue(ctx: Context) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_JOURNAL_DUE_TODAY, true).apply()
    }

    fun markJournalComplete(ctx: Context) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_JOURNAL_DUE_TODAY, false).apply()
    }

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    private fun entryToJson(e: JournalEntry) = JSONObject().apply {
        put("id", e.id); put("timestamp", e.timestamp); put("mood", e.mood)
        put("violations", e.violations); put("gratitude", e.gratitude); put("notes", e.notes)
    }

    private fun infractionToJson(e: InfractionEntry) = JSONObject().apply {
        put("id", e.id); put("timestamp", e.timestamp)
        put("description", e.description); put("category", e.category)
    }

    private fun dispatchWebhook(ctx: Context, event: String, payload: JSONObject) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() } ?: return
        val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
        payload.put("event", event)
        payload.put("timestamp", System.currentTimeMillis())
        WebhookManager.dispatchEvent(url, token, payload)
    }
}
