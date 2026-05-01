package com.tpeapp.capability

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.preference.PreferenceManager
import com.tpeapp.consequence.ConsequenceDispatcher
import com.tpeapp.gating.AppGatingManager
import com.tpeapp.mindful.ToneEnforcementService
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * TpeCapabilityService — a comprehensive [AccessibilityService] designed as the
 * fallback for the LSPosed module on non-rooted and lower-end devices.
 *
 * Capabilities provided:
 *
 * 1. **Screen-text reading (Tone Enforcement fallback)** — listens for
 *    [AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED] events, loads the restricted
 *    vocabulary from SharedPreferences (same key as [ToneEnforcementService]),
 *    and fires a webhook + consequence when a violation is detected.  The
 *    actual text-replacement ACTION_SET_TEXT is left to [ToneEnforcementService]
 *    if it is also active; this service only provides the detection and telemetry
 *    layer so the two services do not issue conflicting ACTION_SET_TEXT calls.
 *
 * 2. **Unauthorized app-launch blocking** — listens for
 *    [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] and presses BACK when the
 *    foregrounded package is not on the partner-approved list managed by
 *    [AppGatingManager].  A webhook is fired and an access request is raised so
 *    the partner can approve the app remotely.
 *
 * 3. **Uninstall prevention** — listens for
 *    [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] and presses BACK immediately
 *    when the system's package-installer UI comes to the foreground, preventing
 *    the device owner from uninstalling any app without partner approval.
 */
class TpeCapabilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TpeCapabilityService"

        /** Our own package — never blocked so the user can always open the TPE app. */
        private const val OWN_PACKAGE = "com.tpeapp"

        /**
         * Known package names for the Android package-installer UI across
         * AOSP, Google-signed, and common OEM variants.
         */
        private val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.oneplus.packageinstaller",
        )

        /**
         * System packages and launchers that should never be intercepted so
         * the device remains usable regardless of gating settings.
         */
        private val SYSTEM_ALLOW_LIST = setOf(
            OWN_PACKAGE,
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
        )

        /** Maximum number of distinct words tracked in the telemetry timestamp map. */
        private const val MAX_WORD_TIMESTAMP_ENTRIES = 200
    }

    // ------------------------------------------------------------------
    //  Cached configuration (refreshed via SharedPreferences listener)
    // ------------------------------------------------------------------

    @Volatile private var cachedWebhookUrl: String? = null
    @Volatile private var cachedBearerToken: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            FilterService.PREF_WEBHOOK_URL ->
                cachedWebhookUrl = prefs.getString(key, null)?.takeIf { it.isNotBlank() }
            FilterService.PREF_WEBHOOK_BEARER_TOKEN ->
                cachedBearerToken = prefs.getString(key, null)?.takeIf { it.isNotBlank() }
        }
    }

    // ------------------------------------------------------------------
    //  Duplicate-detection state for screen-text telemetry
    // ------------------------------------------------------------------

    /**
     * Throttle per-word telemetry to at most once per 60 s to avoid webhook spam.
     * Capped at [MAX_WORD_TIMESTAMP_ENTRIES] entries to prevent unbounded growth
     * if the restricted vocabulary is frequently rotated.
     */
    private val lastWordFireTimestamp = LinkedHashMap<String, Long>(16, 0.75f, true)
    private val WORD_TELEMETRY_COOLDOWN_MS = 60_000L

    /**
     * Cache of compiled whole-word [Regex] patterns keyed by the restricted word.
     * Avoids allocating a new Regex object on every text-change event.
     */
    private val wordRegexCache = LinkedHashMap<String, Regex>(16, 0.75f, true)

    // ------------------------------------------------------------------
    //  Main-thread handler for BACK key injection
    // ------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        cachedWebhookUrl  = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() }
        cachedBearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)?.takeIf { it.isNotBlank() }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Log.i(TAG, "TpeCapabilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onInterrupt() {
        Log.d(TAG, "TpeCapabilityService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED  -> handleTextChanged(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
        }
    }

    // ------------------------------------------------------------------
    //  Capability 1: Screen-text reading (Tone Enforcement fallback)
    // ------------------------------------------------------------------

    /**
     * Checks changed text against the partner-configured restricted vocabulary.
     * When a violation is detected a webhook telemetry event is fired and a
     * consequence is dispatched.  The physical text replacement (ACTION_SET_TEXT)
     * is intentionally omitted here — [ToneEnforcementService] handles that if
     * it is active on the device; this service provides the detection fallback.
     */
    private fun handleTextChanged(event: AccessibilityEvent) {
        val node = event.source ?: return
        try {
            val text = node.text?.toString() ?: return
            if (text.isBlank()) return

            val restricted = loadRestrictedVocabulary()
            val textLower  = text.lowercase()

            for (word in restricted) {
                if (word.isBlank()) continue
                if (!containsWholeWord(textLower, word)) continue

                val now = System.currentTimeMillis()
                val lastFired = lastWordFireTimestamp[word] ?: 0L
                if (now - lastFired < WORD_TELEMETRY_COOLDOWN_MS) continue

                // Trim map before inserting to bound memory usage.
                if (lastWordFireTimestamp.size >= MAX_WORD_TIMESTAMP_ENTRIES) {
                    lastWordFireTimestamp.entries.firstOrNull()?.let {
                        lastWordFireTimestamp.remove(it.key)
                    }
                }
                lastWordFireTimestamp[word] = now
                Log.i(TAG, "Capability: restricted word detected via accessibility — '$word'")
                dispatchToneBlockTelemetry(word)
                ConsequenceDispatcher.punish(applicationContext, "capability_restricted_word=$word")
                break // one consequence per event
            }
        } finally {
            node.recycle()
        }
    }

    // ------------------------------------------------------------------
    //  Capability 2 & 3: App-launch blocking + Uninstall prevention
    // ------------------------------------------------------------------

    /**
     * Evaluates the newly-foregrounded package when a window-state change fires.
     *
     * Priority order:
     *  1. Uninstall prevention — immediately press BACK when the package installer
     *     UI is detected.
     *  2. App-launch gating — when [AppGatingManager] is enabled and the package
     *     is not on the approved list, press BACK and raise an access request.
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // --- Uninstall prevention -------------------------------------------
        if (pkg in INSTALLER_PACKAGES) {
            Log.i(TAG, "Capability: package installer detected ($pkg) — pressing BACK")
            handler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
            dispatchUninstallAttemptTelemetry(pkg)
            return
        }

        // --- App-launch gating ----------------------------------------------
        if (pkg in SYSTEM_ALLOW_LIST) return
        if (!AppGatingManager.isEnabled(applicationContext)) return
        if (AppGatingManager.isApproved(applicationContext, pkg)) return

        Log.i(TAG, "Capability: unauthorized app launch ($pkg) — pressing BACK")
        handler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
        AppGatingManager.requestAccess(applicationContext, pkg)
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Returns `true` if [text] contains [word] as a whole word (regex boundary). */
    private fun containsWholeWord(text: String, word: String): Boolean {
        val regex = wordRegexCache.getOrPut(word) {
            "(?<![\\w])${Regex.escape(word)}(?![\\w])".toRegex()
        }
        // Trim cache to avoid unbounded growth if vocabulary changes frequently.
        if (wordRegexCache.size > MAX_WORD_TIMESTAMP_ENTRIES) {
            wordRegexCache.entries.firstOrNull()?.let { wordRegexCache.remove(it.key) }
        }
        return regex.containsMatchIn(text)
    }

    /**
     * Loads the restricted vocabulary from SharedPreferences using the same key
     * as [ToneEnforcementService] so both services share a single partner-set list.
     */
    private fun loadRestrictedVocabulary(): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val json  = prefs.getString(ToneEnforcementService.PREF_RESTRICTED_VOCABULARY, null)
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getString(i).lowercase() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse restricted vocabulary JSON", e)
            emptyList()
        }
    }

    private fun dispatchToneBlockTelemetry(word: String) {
        val url   = cachedWebhookUrl  ?: return
        val token = cachedBearerToken
        val payload = JSONObject().apply {
            put("event",     "capability_tone_block")
            put("word",      word)
            put("timestamp", System.currentTimeMillis())
        }
        WebhookManager.dispatchEvent(url, token, payload)
    }

    private fun dispatchUninstallAttemptTelemetry(installerPkg: String) {
        val url   = cachedWebhookUrl  ?: return
        val token = cachedBearerToken
        val payload = JSONObject().apply {
            put("event",         "uninstall_attempt_blocked")
            put("installer_pkg", installerPkg)
            put("timestamp",     System.currentTimeMillis())
        }
        WebhookManager.dispatchEvent(url, token, payload)
    }
}
