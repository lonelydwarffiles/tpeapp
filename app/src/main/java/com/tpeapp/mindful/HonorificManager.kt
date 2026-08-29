package com.hound.controller.mindful

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import kotlin.random.Random

object HonorificManager {

    private const val PREF_ENABLED = "honorific_mode_enabled"
    private const val PREF_TEXT = "honorific_text"
    private const val DEFAULT_TEXT = "Sir, "
    private const val PREF_DISCORD_QL_ENABLED = "honorific_discord_ql_enabled"
    private const val PREF_DISCORD_QL_TEXT = "honorific_discord_ql_text"
    private const val DEFAULT_DISCORD_QL_TEXT = "Sir QL, "
    private const val PREF_DISCORD_HONORIFIC_USERS_JSON = "honorific_discord_users_json"
    private val DEFAULT_DISCORD_HONORIFIC_USERS = setOf("ql", "handler", "owner")
    private val DISCORD_QL_AGENT_REPLACEMENTS = listOf("handler", "owner")
    private val DISCORD_QL_REQUEST_WORDS = setOf(
        "ask", "tell", "ping", "message", "dm", "contact", "consult", "notify", "check"
    )
    private val DISCORD_QL_POSSESSIVES = setOf(
        "my", "the", "a", "an", "your", "our", "their", "his", "her"
    )
    private val TRAILING_WORD_REGEX = Regex("""([\p{L}]+)\s*$""")
    private val DISCORD_QL_BARE_REGEX = Regex("""(?i)(?<![\p{L}\p{N}_@])ql(?![\p{L}\p{N}_])""")

    fun isDiscordQlEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_DISCORD_QL_ENABLED, true)

    fun setDiscordQlEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_DISCORD_QL_ENABLED, enabled).apply()
    }

    fun getDiscordQlHonorific(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_DISCORD_QL_TEXT, DEFAULT_DISCORD_QL_TEXT) ?: DEFAULT_DISCORD_QL_TEXT

    fun setDiscordQlHonorific(ctx: Context, text: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_DISCORD_QL_TEXT, text).apply()
    }

    fun getDiscordHonorificUsers(ctx: Context): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val raw = prefs.getString(PREF_DISCORD_HONORIFIC_USERS_JSON, null)
        if (raw.isNullOrBlank()) {
            return DEFAULT_DISCORD_HONORIFIC_USERS
        }
        return try {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim().lowercase()
                    if (item.isNotBlank()) add(item)
                }
            }.ifEmpty { DEFAULT_DISCORD_HONORIFIC_USERS }
        } catch (_: Exception) {
            DEFAULT_DISCORD_HONORIFIC_USERS
        }
    }

    fun setDiscordHonorificUsers(ctx: Context, users: Collection<String>) {
        val normalized = users
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        val payload = JSONArray().apply {
            for (user in if (normalized.isEmpty()) DEFAULT_DISCORD_HONORIFIC_USERS else normalized) {
                put(user)
            }
        }.toString()

        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_DISCORD_HONORIFIC_USERS_JSON, payload)
            .apply()
    }

    fun addDiscordHonorificUser(ctx: Context, user: String) {
        val candidate = user.trim().lowercase()
        if (candidate.isBlank()) return
        val updated = getDiscordHonorificUsers(ctx).toMutableSet().apply { add(candidate) }
        setDiscordHonorificUsers(ctx, updated)
    }

    fun removeDiscordHonorificUser(ctx: Context, user: String) {
        val candidate = user.trim().lowercase()
        if (candidate.isBlank()) return
        val updated = getDiscordHonorificUsers(ctx).toMutableSet().apply { remove(candidate) }
        setDiscordHonorificUsers(ctx, updated)
    }

    fun isEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_ENABLED, enabled).apply()
    }

    fun getHonorific(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_TEXT, DEFAULT_TEXT) ?: DEFAULT_TEXT

    fun setHonorific(ctx: Context, text: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_TEXT, text).apply()
    }

    /**
     * Returns a rewritten text with contextual honorific rules applied,
     * or null when no rewrite is needed.
     */
    fun rewriteForContext(ctx: Context, packageName: String?, text: String): String? {
        val raw = text
        if (raw.isBlank()) return null

        val pkg = packageName?.lowercase().orEmpty()
        if (pkg.contains("discord") &&
            isDiscordQlEnabled(ctx)) {
            val rewrittenDiscord = rewriteDiscordQlReferences(raw)
            if (rewrittenDiscord != null) {
                return rewrittenDiscord
            }
        }

        if (isEnabled(ctx)) {
            val honorific = getHonorific(ctx)
            if (honorific.isNotBlank() && !raw.startsWith(honorific, ignoreCase = true)) {
                return honorific + raw
            }
        }

        return null
    }

    private fun rewriteDiscordQlReferences(text: String): String? {
        if (!DISCORD_QL_BARE_REGEX.containsMatchIn(text)) return null
        return DISCORD_QL_BARE_REGEX.replace(text) { match ->
            selectDiscordQlReplacementForContext(text, match.range.first)
        }
    }

    private fun selectDiscordQlReplacementForContext(text: String, matchStart: Int): String {
        val prefix = text.substring(0, matchStart)
        val priorWord = TRAILING_WORD_REGEX.find(prefix)?.groupValues?.get(1)?.lowercase()

        return when {
            priorWord != null && priorWord in DISCORD_QL_REQUEST_WORDS -> {
                "its ${randomAgentReplacement()}"
            }
            priorWord != null && priorWord in DISCORD_QL_POSSESSIVES -> {
                randomAgentReplacement()
            }
            else -> "sir"
        }
    }

    private fun randomAgentReplacement(): String =
        DISCORD_QL_AGENT_REPLACEMENTS[Random.nextInt(DISCORD_QL_AGENT_REPLACEMENTS.size)]
}
