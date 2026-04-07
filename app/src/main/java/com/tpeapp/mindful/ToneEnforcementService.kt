package com.tpeapp.mindful

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import com.tpeapp.consequence.ConsequenceDispatcher
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * ToneEnforcementService — an [AccessibilityService] that monitors text-field
 * changes and instantly replaces any text that contains a word from the
 * "restricted vocabulary" managed by the Accountability Partner via FCM.
 *
 * ### Soft-mode bypass
 * If the service corrects a word and the user deletes the correction and
 * re-types the exact original word within [BYPASS_WINDOW_MS] milliseconds,
 * that word is added to [sessionWhitelist] and the correction is suppressed for
 * the rest of the session.  Each successful bypass fires a telemetry event via
 * [WebhookManager].
 *
 * ### Strict mode
 * When [ComplianceManager.isStrictToneModeEnabled] returns `true` the
 * session whitelist and bypass logic are completely ignored — corrections are
 * applied unconditionally on every TYPE_VIEW_TEXT_CHANGED event.
 *
 * ### Performance
 * [ACTION_SET_TEXT] calls are debounced through a [Handler] so rapid typing
 * does not cause keyboard lag, and a guard flag prevents infinite correction
 * loops triggered by our own text changes.
 *
 * ### Reset conditions
 * Session state (whitelist, correction tracking) is cleared whenever the user
 * switches to a different EditText node or a different app package.
 */
class ToneEnforcementService : AccessibilityService() {

    companion object {
        private const val TAG = "ToneEnforcementService"

        /** SharedPreferences key for the JSON-encoded restricted-vocabulary list. */
        const val PREF_RESTRICTED_VOCABULARY = "mindful_restricted_vocabulary"

        /** The safe replacement phrase substituted when a restricted word is detected. */
        private const val SAFE_PHRASE = "[Redacted]"

        /** Pattern template for whole-word matching; filled with the escaped word. */
        private const val WORD_BOUNDARY_REGEX = "(?<![\\w])%s(?![\\w])"

        /** Window (ms) in which the user can retype a corrected word to bypass it. */
        private const val BYPASS_WINDOW_MS = 3_000L

        /**
         * Debounce delay (ms) between detecting a violation and calling
         * ACTION_SET_TEXT, preventing keyboard lag on rapid input.
         */
        private const val DEBOUNCE_MS = 80L

        /**
         * How long (ms) to hold the correction-in-progress guard after applying
         * ACTION_SET_TEXT, giving the framework time to deliver the triggered
         * TYPE_VIEW_TEXT_CHANGED event before we process further events.
         */
        private const val CORRECTION_GUARD_MS = 250L
    }

    // ------------------------------------------------------------------
    //  Handler / debounce
    // ------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())

    /** Pending ACTION_SET_TEXT runnable; cancelled when a newer event supersedes it. */
    private var pendingCorrectionRunnable: Runnable? = null

    /**
     * `true` while we are applying (or just applied) a correction.
     * Events received during this window are ignored to prevent infinite loops.
     */
    private var isApplyingCorrection = false

    // ------------------------------------------------------------------
    //  Cached configuration (updated via SharedPreferences listener)
    // ------------------------------------------------------------------

    /**
     * In-memory cache of the strict-tone-mode flag.  Refreshed by a
     * [SharedPreferences.OnSharedPreferenceChangeListener] whenever the FCM
     * handler writes a new value, so every text-change event avoids a
     * SharedPreferences look-up.
     */
    @Volatile private var strictToneModeEnabled = false

    /** Cached webhook endpoint URL — read once at service start. */
    @Volatile private var cachedWebhookUrl: String? = null

    /** Cached webhook bearer token — read once at service start. */
    @Volatile private var cachedBearerToken: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            ComplianceManager.PREF_STRICT_TONE_MODE ->
                strictToneModeEnabled = prefs.getBoolean(key, false)
            FilterService.PREF_WEBHOOK_URL ->
                cachedWebhookUrl = prefs.getString(key, null)?.takeIf { it.isNotBlank() }
            FilterService.PREF_WEBHOOK_BEARER_TOKEN ->
                cachedBearerToken = prefs.getString(key, null)?.takeIf { it.isNotBlank() }
        }
    }

    // ------------------------------------------------------------------
    //  Session state
    // ------------------------------------------------------------------

    /** Timestamp of the most recent correction applied by this service. */
    private var lastCorrectionTimestamp: Long = 0L

    /** Lower-case restricted word most recently corrected (used for bypass detection). */
    private var lastCorrectedWord: String? = null

    /**
     * Words that the user has successfully bypassed during this session.
     * Ignored when strict mode is active.
     */
    private val sessionWhitelist = mutableSetOf<String>()

    // ------------------------------------------------------------------
    //  Focus-change tracking (used to reset session state)
    // ------------------------------------------------------------------

    private var lastFocusedNodeId: Int = -1
    private var lastPackageName: String? = null

    // ------------------------------------------------------------------
    //  AccessibilityService callbacks
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        // Populate caches from current SharedPreferences values.
        strictToneModeEnabled = prefs.getBoolean(ComplianceManager.PREF_STRICT_TONE_MODE, false)
        cachedWebhookUrl      = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() }
        cachedBearerToken     = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)?.takeIf { it.isNotBlank() }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED      -> handleFocusChange(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "ToneEnforcementService interrupted")
    }

    // ------------------------------------------------------------------
    //  Event handlers
    // ------------------------------------------------------------------

    private fun handleFocusChange(event: AccessibilityEvent) {
        val newPackage = event.packageName?.toString()
        val node       = event.source
        val newNodeId  = node?.hashCode() ?: -1
        node?.recycle()

        if (newPackage != lastPackageName || newNodeId != lastFocusedNodeId) {
            resetSessionState()
            lastFocusedNodeId = newNodeId
            lastPackageName   = newPackage
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        // Skip events triggered by our own ACTION_SET_TEXT to prevent loops.
        if (isApplyingCorrection) return

        val currentPackage = event.packageName?.toString()

        // Reset session state if the user has moved to a different app.
        if (currentPackage != lastPackageName) {
            resetSessionState()
            lastPackageName = currentPackage
        }

        val node = event.source ?: return
        try {
            val currentText = node.text?.toString() ?: return
            if (currentText.isBlank()) return

            val restricted = loadRestrictedVocabulary()
            if (restricted.isEmpty()) return

            val strictMode = strictToneModeEnabled
            val textLower  = currentText.lowercase()

            // ---- Bypass detection (soft mode only) --------------------------------
            if (!strictMode) {
                val correctedWord = lastCorrectedWord
                if (correctedWord != null &&
                    (System.currentTimeMillis() - lastCorrectionTimestamp) <= BYPASS_WINDOW_MS &&
                    containsWholeWord(textLower, correctedWord)
                ) {
                    // User deleted the correction and retyped the original word within
                    // the grace window — add to whitelist and fire telemetry.
                    sessionWhitelist.add(correctedWord)
                    lastCorrectedWord = null
                    Log.i(TAG, "Override bypass accepted for word: $correctedWord")
                    dispatchOverrideTelemetry(correctedWord)
                    ConsequenceDispatcher.punish(applicationContext, "tone_bypass: $correctedWord")
                    return
                }
            }

            // ---- Correction pass --------------------------------------------------
            for (word in restricted) {
                if (word.isBlank()) continue
                if (!containsWholeWord(textLower, word)) continue

                // In soft mode, skip words the user has explicitly whitelisted.
                if (!strictMode && sessionWhitelist.contains(word)) continue

                Log.i(TAG, "Restricted word detected ('$word') — scheduling replacement")
                lastCorrectedWord        = word
                lastCorrectionTimestamp  = System.currentTimeMillis()
                scheduleReplacement(SAFE_PHRASE)
                ConsequenceDispatcher.punish(applicationContext, "restricted_word: $word")
                return
            }
        } finally {
            node.recycle()
        }
    }

    // ------------------------------------------------------------------
    //  Debounced correction
    // ------------------------------------------------------------------

    /**
     * Cancels any pending correction and schedules a new one after [DEBOUNCE_MS].
     * Uses [rootInActiveWindow] at execution time so no [AccessibilityNodeInfo]
     * reference is held across the delay.
     */
    private fun scheduleReplacement(replacement: String) {
        pendingCorrectionRunnable?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            pendingCorrectionRunnable = null
            isApplyingCorrection = true

            val root = rootInActiveWindow
            if (root != null) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    replaceText(focused, replacement)
                    focused.recycle()
                }
                root.recycle()
            }

            // Release the guard after the framework has had time to deliver the
            // TYPE_VIEW_TEXT_CHANGED event our ACTION_SET_TEXT will trigger.
            handler.postDelayed({ isApplyingCorrection = false }, CORRECTION_GUARD_MS)
        }
        pendingCorrectionRunnable = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Returns `true` if [text] contains [word] as a whole word (regex boundary). */
    private fun containsWholeWord(text: String, word: String): Boolean =
        WORD_BOUNDARY_REGEX.format(Regex.escape(word)).toRegex().containsMatchIn(text)

    /**
     * Replaces the text inside [node] with [replacement] using
     * [AccessibilityNodeInfo.ACTION_SET_TEXT].
     */
    private fun replaceText(node: AccessibilityNodeInfo, replacement: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Fires an asynchronous telemetry event when a user successfully triggers
     * the bypass.  Uses the cached webhook URL / bearer-token populated at
     * service start.  If no URL is configured the call is silently skipped.
     */
    private fun dispatchOverrideTelemetry(originalWord: String) {
        val webhookUrl  = cachedWebhookUrl  ?: return
        val bearerToken = cachedBearerToken ?: return

        val payload = JSONObject().apply {
            put("event",         "override_used")
            put("original_text", originalWord)
        }
        WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
    }

    /**
     * Clears all per-session state.  Called when the user switches to a
     * different EditText or a different app package.
     */
    private fun resetSessionState() {
        sessionWhitelist.clear()
        lastCorrectedWord       = null
        lastCorrectionTimestamp = 0L
        pendingCorrectionRunnable?.let { handler.removeCallbacks(it) }
        pendingCorrectionRunnable = null
        isApplyingCorrection    = false
    }

    /**
     * Loads the restricted vocabulary from [SharedPreferences].  Returns a list of
     * lower-case strings, or an empty list on any parse error.
     */
    private fun loadRestrictedVocabulary(): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val json  = prefs.getString(PREF_RESTRICTED_VOCABULARY, null)
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getString(i).lowercase() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse restricted vocabulary JSON", e)
            emptyList()
        }
    }
}
