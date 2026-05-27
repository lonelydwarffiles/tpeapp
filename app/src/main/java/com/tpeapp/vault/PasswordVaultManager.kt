package com.tpeapp.vault

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import java.util.UUID

/**
 * PasswordVaultManager — partner-controlled credential store.
 *
 * All entries are persisted as a JSON array inside [EncryptedSharedPreferences]
 * (AES256-GCM, Android Keystore) so plaintext passwords never touch regular
 * SharedPreferences or disk unencrypted.
 *
 * Entry schema:
 * ```json
 * {
 *   "id":          "uuid-v4",
 *   "site":        "GitHub",
 *   "username":    "username@example.com",
 *   "password":    "s3cr3t",
 *   "notes":       "optional free-text notes",
 *   "lockedUntil": 0          // epoch millis; 0 = not locked
 * }
 * ```
 *
 * Callers should use [getEntries] for the sub-facing list view — passwords are
 * redacted there.  [revealPassword] returns the plaintext value and fires a
 * `password_viewed` webhook so the partner is always informed.
 */
class PasswordVaultManager(context: Context) {

    companion object {
        private const val TAG        = "PasswordVaultManager"
        private const val PREFS_FILE = "password_vault_prefs"
        private const val KEY_VAULT  = "vault_entries"

        /** SharedPreferences key that enables the password-change blocker in TpeCapabilityService. */
        const val PREF_BLOCK_PASSWORD_CHANGES = "vault_block_password_changes"

        /** SharedPreferences key for how many seconds to show a revealed password (default 10). */
        const val PREF_REVEAL_TIMEOUT_SECONDS = "vault_reveal_timeout_seconds"

        /** When true, reveal requests must include a reason with minimum length. */
        const val PREF_REQUIRE_REVEAL_REASON = "vault_require_reveal_reason"

        /** Minimum number of chars required for reveal reason (default 6). */
        const val PREF_MIN_REVEAL_REASON_LENGTH = "vault_min_reveal_reason_length"

        /** Max successful reveals per window per entry before cooldown. */
        const val PREF_REVEAL_MAX_IN_WINDOW = "vault_reveal_max_in_window"

        /** Rolling rate-limit window in ms (default 10 minutes). */
        const val PREF_REVEAL_WINDOW_MS = "vault_reveal_window_ms"

        /** Base cooldown in ms once rate limit is exceeded (default 30 seconds). */
        const val PREF_REVEAL_COOLDOWN_BASE_MS = "vault_reveal_cooldown_base_ms"

        /** Maximum cooldown in ms (default 30 minutes). */
        const val PREF_REVEAL_COOLDOWN_MAX_MS = "vault_reveal_cooldown_max_ms"

        private const val MIN_LOCK_MS = 1_000L
        private const val MAX_LOCK_MS = 30L * 24 * 60 * 60 * 1_000L
    }

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val appPrefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    data class RevealResult(
        val password: String? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val retryAfterMs: Long = 0L,
    ) {
        val isSuccess: Boolean get() = errorCode == null
    }

    /**
     * Returns all vault entries with passwords **redacted** (replaced with empty string).
     * Each element is a [JSONObject] with keys: id, site, username, password (""), notes, lockedUntil.
     */
    fun getEntries(): List<JSONObject> {
        val arr = loadArray()
        return List(arr.length()) { i ->
            val obj = arr.getJSONObject(i)
            JSONObject().apply {
                put("id",          obj.getString("id"))
                put("site",        obj.optString("site"))
                put("username",    obj.optString("username"))
                put("password",    "")   // redacted
                put("notes",       obj.optString("notes"))
                put("lockedUntil", obj.optLong("lockedUntil", 0L))
            }
        }
    }

    /**
     * Returns the plaintext password for [id] if it is not currently locked,
     * or `null` if the entry is locked / not found.
     *
     * Fires a `password_viewed` webhook on success.
     */
    fun revealPassword(context: Context, id: String): String? {
        return revealPasswordWithResult(context, id, null).password
    }

    fun revealPasswordWithResult(
        context: Context,
        id: String,
        reason: String?,
    ): RevealResult {
        return revealPassword(context, id, emitViewedEvent = true, reason = reason)
    }

    /**
     * Returns plaintext password for autofill use-cases without emitting
     * password-viewed telemetry (fill requests happen frequently and passively).
     */
    fun revealPasswordForAutofill(id: String): String? {
        val arr = loadArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") != id) continue
            if (isLockedAt(obj)) return null
            return obj.optString("password")
        }
        return null
    }

    private fun revealPassword(
        context: Context,
        id: String,
        emitViewedEvent: Boolean,
        reason: String?,
    ): RevealResult {
        val normalizedReason = (reason ?: "").trim()
        if (emitViewedEvent && isRevealReasonRequired()) {
            val minLen = getMinRevealReasonLength()
            if (normalizedReason.length < minLen) {
                return RevealResult(
                    errorCode = "REASON_REQUIRED",
                    errorMessage = "Reveal reason must be at least $minLen characters",
                )
            }
        }

        val arr = loadArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") != id) continue
            if (isLockedAt(obj)) {
                Log.d(TAG, "revealPassword: entry $id is locked")
                return RevealResult(
                    errorCode = "ENTRY_LOCKED",
                    errorMessage = "Entry is locked",
                )
            }

            if (emitViewedEvent) {
                val blocked = checkRevealRateLimit(id)
                if (blocked != null) {
                    dispatchPasswordRevealBlocked(
                        site = obj.optString("site"),
                        username = obj.optString("username"),
                        reason = normalizedReason,
                        retryAfterMs = blocked,
                    )
                    return RevealResult(
                        errorCode = "RATE_LIMITED",
                        errorMessage = "Too many reveal requests",
                        retryAfterMs = blocked,
                    )
                }
            }

            val password = obj.optString("password")
            val site     = obj.optString("site")
            val username = obj.optString("username")
            if (emitViewedEvent) {
                recordRevealSuccess(id)
                dispatchPasswordViewed(site, username, normalizedReason)
            }
            return RevealResult(password = password)
        }
        Log.w(TAG, "revealPassword: entry $id not found")
        return RevealResult(
            errorCode = "NOT_FOUND",
            errorMessage = "Entry not found",
        )
    }

    /**
     * Adds a new vault entry.  Fires a `vault_entry_added` webhook.
     * Returns the generated entry ID.
     */
    fun addEntry(
        site: String,
        username: String,
        password: String,
        notes: String,
    ): String {
        val normalizedSite = site.trim()
        val normalizedUsername = username.trim()
        val normalizedPassword = password.trim()
        val normalizedNotes = notes.trim()
        require(normalizedPassword.isNotEmpty()) { "password must not be blank" }

        val id  = UUID.randomUUID().toString()
        val obj = JSONObject().apply {
            put("id",          id)
            put("site",        normalizedSite)
            put("username",    normalizedUsername)
            put("password",    normalizedPassword)
            put("notes",       normalizedNotes)
            put("lockedUntil", 0L)
        }
        val arr = loadArray()
        arr.put(obj)
        saveArray(arr)
        Log.i(TAG, "Vault entry added: id=$id site=$normalizedSite")
        dispatchVaultEvent("vault_entry_added", mapOf("site" to normalizedSite, "username" to normalizedUsername))
        return id
    }

    /**
     * Updates fields on an existing entry.  Pass `null` for fields that should
     * not be changed.  Fires a `vault_entry_updated` webhook on success.
     *
     * @return `true` if the entry was found and updated.
     */
    fun updateEntry(
        id: String,
        site: String?,
        username: String?,
        password: String?,
        notes: String?,
    ): Boolean {
        val arr = loadArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") != id) continue
            site?.let     { obj.put("site",     it) }
            username?.let { obj.put("username", it) }
            password?.let { obj.put("password", it) }
            notes?.let    { obj.put("notes",    it) }
            saveArray(arr)
            Log.i(TAG, "Vault entry updated: id=$id")
            dispatchVaultEvent("vault_entry_updated",
                mapOf("id" to id, "site" to obj.optString("site")))
            return true
        }
        Log.w(TAG, "updateEntry: entry $id not found")
        return false
    }

    /**
     * Removes an entry permanently.  Fires a `vault_entry_deleted` webhook.
     *
     * @return `true` if an entry with [id] existed and was removed.
     */
    fun deleteEntry(id: String): Boolean {
        val arr     = loadArray()
        val updated = JSONArray()
        var found   = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") == id) {
                found = true
                dispatchVaultEvent("vault_entry_deleted",
                    mapOf("id" to id, "site" to obj.optString("site")))
            } else {
                updated.put(obj)
            }
        }
        if (found) saveArray(updated)
        Log.i(TAG, "deleteEntry: id=$id found=$found")
        return found
    }

    /**
     * Time-locks an entry so [revealPassword] returns `null` until [durationMs] has elapsed.
     * Fires a `vault_entry_locked` webhook.
     */
    fun lockEntry(id: String, durationMs: Long) {
        val safeDurationMs = durationMs.coerceIn(MIN_LOCK_MS, MAX_LOCK_MS)
        val until = System.currentTimeMillis() + safeDurationMs
        val arr   = loadArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") == id) {
                obj.put("lockedUntil", until)
                saveArray(arr)
                Log.i(TAG, "Vault entry locked: id=$id until=$until")
                dispatchVaultEvent("vault_entry_locked",
                    mapOf("id" to id, "locked_until_ms" to until.toString()))
                return
            }
        }
        Log.w(TAG, "lockEntry: entry $id not found")
    }

    /**
     * Time-locks every entry in the vault.  Fires a `vault_all_locked` webhook.
     */
    fun lockAll(durationMs: Long) {
        val safeDurationMs = durationMs.coerceIn(MIN_LOCK_MS, MAX_LOCK_MS)
        val until = System.currentTimeMillis() + safeDurationMs
        val arr   = loadArray()
        for (i in 0 until arr.length()) {
            arr.getJSONObject(i).put("lockedUntil", until)
        }
        saveArray(arr)
        Log.i(TAG, "All vault entries locked until $until")
        dispatchVaultEvent("vault_all_locked", mapOf("locked_until_ms" to until.toString()))
    }

    /**
     * Returns `true` if the entry with [id] is currently time-locked.
     * Returns `false` when the entry is not found.
     */
    fun isLocked(id: String): Boolean {
        val arr = loadArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") == id) return isLockedAt(obj)
        }
        return false
    }

    /**
     * Bulk-imports a list of entries into the vault.
     *
     * [entries] is a list of maps with keys `site`, `username`, `password`, and optionally
     * `notes`.  Duplicate pairs (same site + username) are **skipped** — the existing entry
     * is kept so that partner-set passwords cannot be silently overwritten by an import.
     * Entries with a blank `password` value are also **skipped** and not counted in the
     * return value.
     *
     * @return the number of new entries that were actually inserted.
     */
    fun importEntries(entries: List<Map<String, String>>): Int {
        val arr      = loadArray()
        var inserted = 0

        // Build a set of existing (site, username) pairs for dedup.
        val existing = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            existing.add("${obj.optString("site")}|${obj.optString("username")}")
        }

        for (entry in entries) {
            val site     = (entry["site"]     ?: "").trim()
            val username = (entry["username"] ?: "").trim()
            val password = (entry["password"] ?: "").trim()
            if (password.isEmpty()) continue            // skip empty passwords

            val key = "$site|$username"
            if (key in existing) {
                Log.d(TAG, "importEntries: skipping duplicate $key")
                continue
            }
            existing.add(key)

            val obj = JSONObject().apply {
                put("id",          UUID.randomUUID().toString())
                put("site",        site)
                put("username",    username)
                put("password",    password)
                put("notes",       entry["notes"] ?: "")
                put("lockedUntil", 0L)
            }
            arr.put(obj)
            inserted++
        }

        if (inserted > 0) {
            saveArray(arr)
            Log.i(TAG, "importEntries: inserted $inserted entries")
            dispatchVaultEvent("vault_entries_imported", mapOf("count" to inserted.toString()))
        }
        return inserted
    }

    // ------------------------------------------------------------------
    //  Internal helpers
    // ------------------------------------------------------------------

    private fun isLockedAt(obj: JSONObject): Boolean {
        val until = obj.optLong("lockedUntil", 0L)
        return until > System.currentTimeMillis()
    }

    private fun loadArray(): JSONArray {
        val json = prefs.getString(KEY_VAULT, null) ?: return JSONArray()
        return try {
            JSONArray(json)
        } catch (e: Exception) {
            Log.w(TAG, "Vault JSON corrupt — resetting", e)
            JSONArray()
        }
    }

    private fun saveArray(arr: JSONArray) {
        prefs.edit().putString(KEY_VAULT, arr.toString()).apply()
    }

    private fun webhookUrlAndToken(): Pair<String, String?> {
        val url = appPrefs.getString(FilterService.PREF_WEBHOOK_URL, null)
            ?.takeIf { it.isNotBlank() } ?: return Pair("", null)
        val token = appPrefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
        return Pair(url, token)
    }

    private fun isRevealReasonRequired(): Boolean {
        return appPrefs.getBoolean(PREF_REQUIRE_REVEAL_REASON, true)
    }

    private fun getMinRevealReasonLength(): Int {
        return appPrefs.getInt(PREF_MIN_REVEAL_REASON_LENGTH, 6).coerceIn(1, 256)
    }

    private fun getRevealWindowMs(): Long {
        return appPrefs.getLong(PREF_REVEAL_WINDOW_MS, 10 * 60 * 1_000L)
            .coerceIn(5_000L, 24L * 60 * 60 * 1_000L)
    }

    private fun getRevealMaxInWindow(): Int {
        return appPrefs.getInt(PREF_REVEAL_MAX_IN_WINDOW, 3).coerceIn(1, 50)
    }

    private fun getRevealCooldownBaseMs(): Long {
        return appPrefs.getLong(PREF_REVEAL_COOLDOWN_BASE_MS, 30_000L)
            .coerceIn(1_000L, 24L * 60 * 60 * 1_000L)
    }

    private fun getRevealCooldownMaxMs(): Long {
        return appPrefs.getLong(PREF_REVEAL_COOLDOWN_MAX_MS, 30 * 60 * 1_000L)
            .coerceIn(10_000L, 7L * 24 * 60 * 60 * 1_000L)
    }

    private fun prefKey(prefix: String, id: String): String = "vault_${prefix}_$id"

    /**
     * Returns retry-after ms if blocked by cooldown/rate-limit, else null.
     */
    private fun checkRevealRateLimit(id: String): Long? {
        val now = System.currentTimeMillis()
        val cooldownUntilKey = prefKey("reveal_cooldown_until", id)
        val cooldownUntil = appPrefs.getLong(cooldownUntilKey, 0L)
        if (cooldownUntil > now) {
            return cooldownUntil - now
        }

        val windowMs = getRevealWindowMs()
        val maxInWindow = getRevealMaxInWindow()
        val windowStartKey = prefKey("reveal_window_start", id)
        val windowCountKey = prefKey("reveal_window_count", id)
        val strikesKey = prefKey("reveal_strikes", id)

        var windowStart = appPrefs.getLong(windowStartKey, 0L)
        var windowCount = appPrefs.getInt(windowCountKey, 0)
        var strikes = appPrefs.getInt(strikesKey, 0)

        if (windowStart <= 0L || now - windowStart > windowMs) {
            windowStart = now
            windowCount = 0
        }

        if (windowCount >= maxInWindow) {
            strikes = (strikes + 1).coerceAtMost(20)
            val base = getRevealCooldownBaseMs().toDouble()
            val cooldownMs = (base * 2.0.pow((strikes - 1).toDouble())).toLong()
                .coerceAtMost(getRevealCooldownMaxMs())
            val until = now + cooldownMs

            appPrefs.edit()
                .putLong(cooldownUntilKey, until)
                .putInt(strikesKey, strikes)
                .putLong(windowStartKey, now)
                .putInt(windowCountKey, 0)
                .apply()

            return cooldownMs
        }

        return null
    }

    private fun recordRevealSuccess(id: String) {
        val now = System.currentTimeMillis()
        val windowMs = getRevealWindowMs()
        val windowStartKey = prefKey("reveal_window_start", id)
        val windowCountKey = prefKey("reveal_window_count", id)
        val strikesKey = prefKey("reveal_strikes", id)

        var windowStart = appPrefs.getLong(windowStartKey, 0L)
        var windowCount = appPrefs.getInt(windowCountKey, 0)
        var strikes = appPrefs.getInt(strikesKey, 0)

        if (windowStart <= 0L || now - windowStart > windowMs) {
            windowStart = now
            windowCount = 0
        }
        windowCount += 1
        if (strikes > 0) strikes -= 1

        appPrefs.edit()
            .putLong(windowStartKey, windowStart)
            .putInt(windowCountKey, windowCount)
            .putInt(strikesKey, strikes)
            .apply()
    }

    private fun dispatchPasswordViewed(site: String, username: String, reason: String) {
        val (url, token) = webhookUrlAndToken()
        if (url.isEmpty()) return
        val payload = JSONObject().apply {
            put("event",     "password_viewed")
            put("site",      site)
            put("username",  username)
            put("reason",    reason)
            put("timestamp", System.currentTimeMillis())
        }
        WebhookManager.dispatchEvent(url, token, payload)
    }

    private fun dispatchPasswordRevealBlocked(
        site: String,
        username: String,
        reason: String,
        retryAfterMs: Long,
    ) {
        val (url, token) = webhookUrlAndToken()
        if (url.isEmpty()) return
        val payload = JSONObject().apply {
            put("event", "password_reveal_blocked")
            put("site", site)
            put("username", username)
            put("reason", reason)
            put("retry_after_ms", retryAfterMs)
            put("timestamp", System.currentTimeMillis())
        }
        WebhookManager.dispatchEvent(url, token, payload)
    }

    private fun dispatchVaultEvent(event: String, extra: Map<String, String>) {
        val (url, token) = webhookUrlAndToken()
        if (url.isEmpty()) return
        val payload = JSONObject().apply {
            put("event",     event)
            put("timestamp", System.currentTimeMillis())
            extra.forEach { (k, v) -> put(k, v) }
        }
        WebhookManager.dispatchEvent(url, token, payload)
    }
}
