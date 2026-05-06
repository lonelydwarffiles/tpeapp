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
        val arr = loadArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("id") != id) continue
            if (isLockedAt(obj)) {
                Log.d(TAG, "revealPassword: entry $id is locked")
                return null
            }
            val password = obj.optString("password")
            val site     = obj.optString("site")
            val username = obj.optString("username")
            dispatchPasswordViewed(site, username)
            return password
        }
        Log.w(TAG, "revealPassword: entry $id not found")
        return null
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
        val id  = UUID.randomUUID().toString()
        val obj = JSONObject().apply {
            put("id",          id)
            put("site",        site)
            put("username",    username)
            put("password",    password)
            put("notes",       notes)
            put("lockedUntil", 0L)
        }
        val arr = loadArray()
        arr.put(obj)
        saveArray(arr)
        Log.i(TAG, "Vault entry added: id=$id site=$site")
        dispatchVaultEvent("vault_entry_added", mapOf("site" to site, "username" to username))
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
        val until = System.currentTimeMillis() + durationMs
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
        val until = System.currentTimeMillis() + durationMs
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

    private fun dispatchPasswordViewed(site: String, username: String) {
        val (url, token) = webhookUrlAndToken()
        if (url.isEmpty()) return
        val payload = JSONObject().apply {
            put("event",     "password_viewed")
            put("site",      site)
            put("username",  username)
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
