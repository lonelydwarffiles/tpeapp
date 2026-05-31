package com.tpeapp.mindful

import android.accessibilityservice.AccessibilityService
import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.tpeapp.consequence.ConsequenceDispatcher
import com.tpeapp.mindful.HonorificManager
import com.tpeapp.mindful.PermissionToSpeakManager
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

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

    private enum class GrammarProfile {
        STRICT,
        LIGHT,
    }

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

        /** How long (ms) to ignore the first echoed text event for our own rewrite. */
        private const val REPLACEMENT_ECHO_WINDOW_MS = 1_500L

        /** Minimum ms between accepting dictionary replacements (per keystroke). */
        private const val MIN_REPLACEMENT_INTERVAL_MS = 500L

        /** Max age (ms) for tracking recent replacements to prevent re-application. */
        private const val RECENT_REPLACEMENT_WINDOW_MS = 5_000L

        /** Contact snapshot refresh interval used by contact-based exemptions. */
        private const val CONTACT_CACHE_TTL_MS = 60_000L

        /** Chance range for optional negation->affirmation rewrites. */
        private const val AFFIRMATION_REWRITE_MIN_CHANCE = 0.10
        private const val AFFIRMATION_REWRITE_MAX_CHANCE = 0.20

        /**
         * Grammar correction rules as (pattern → replacement) pairs.
         * Applied AFTER text dictionary replacements to fix agreement errors.
         */
        private val GRAMMAR_RULES = listOf(
            // Subject-verb agreement
            Regex("""\bthis mutt are\b""", RegexOption.IGNORE_CASE) to "this mutt is",
            Regex("""\bthis mutt were\b""", RegexOption.IGNORE_CASE) to "this mutt was",
            Regex("""\bthis mutt have\b""", RegexOption.IGNORE_CASE) to "this mutt has",
            Regex("""\bthis mutt do\b""", RegexOption.IGNORE_CASE) to "this mutt does",
            Regex("""\bthis mutt don't\b|\bthis mutt do not\b""", RegexOption.IGNORE_CASE) to "this mutt does not",
            Regex("""\bthis mutt can't\b|\bthis mutt cannot\b""", RegexOption.IGNORE_CASE) to "this mutt is unable to",
            Regex("""\bthis mutt won't\b|\bthis mutt will not\b""", RegexOption.IGNORE_CASE) to "this mutt will not",
            Regex("""\bit are\b""", RegexOption.IGNORE_CASE) to "it is",
            Regex("""\bit were\b""", RegexOption.IGNORE_CASE) to "it was",
            Regex("""\bit have\b""", RegexOption.IGNORE_CASE) to "it has",
            Regex("""\bit do\b""", RegexOption.IGNORE_CASE) to "it does",
            Regex("""\bit don't\b|\bit do not\b""", RegexOption.IGNORE_CASE) to "it does not",
            Regex("""\bit can't\b|\bit cannot\b""", RegexOption.IGNORE_CASE) to "it is unable to",
            Regex("""\bpuppy are\b""", RegexOption.IGNORE_CASE) to "puppy is",
            Regex("""\bpuppy were\b""", RegexOption.IGNORE_CASE) to "puppy was",
            Regex("""\bpuppy have\b""", RegexOption.IGNORE_CASE) to "puppy has",
            Regex("""\bpuppy do\b""", RegexOption.IGNORE_CASE) to "puppy does",
            Regex("""\bpuppy don't\b|\bpuppy do not\b""", RegexOption.IGNORE_CASE) to "puppy does not",
            Regex("""\bpuppy can't\b|\bpuppy cannot\b""", RegexOption.IGNORE_CASE) to "puppy is unable to",
            Regex("""\bpup are\b""", RegexOption.IGNORE_CASE) to "pup is",
            Regex("""\bpup were\b""", RegexOption.IGNORE_CASE) to "pup was",
            Regex("""\bpup have\b""", RegexOption.IGNORE_CASE) to "pup has",
            Regex("""\bpup do\b""", RegexOption.IGNORE_CASE) to "pup does",
            Regex("""\bpup don't\b|\bpup do not\b""", RegexOption.IGNORE_CASE) to "pup does not",
            Regex("""\bpup can't\b|\bpup cannot\b""", RegexOption.IGNORE_CASE) to "pup is unable to",
            Regex("""\bthis bitch are\b""", RegexOption.IGNORE_CASE) to "this bitch is",
            Regex("""\bthis bitch were\b""", RegexOption.IGNORE_CASE) to "this bitch was",
            Regex("""\bthis bitch have\b""", RegexOption.IGNORE_CASE) to "this bitch has",
            Regex("""\bthis bitch do\b""", RegexOption.IGNORE_CASE) to "this bitch does",
            Regex("""\bthis bitch don't\b|\bthis bitch do not\b""", RegexOption.IGNORE_CASE) to "this bitch does not",
            Regex("""\bthis bitch can't\b|\bthis bitch cannot\b""", RegexOption.IGNORE_CASE) to "this bitch is unable to",
            // Singular-subject contraction/auxiliary edge cases
            Regex("""\b(it|puppy|pup|this mutt|this bitch) aren't\b""", RegexOption.IGNORE_CASE) to "$1 is not",
            Regex("""\b(it|puppy|pup|this mutt|this bitch) ain't\b""", RegexOption.IGNORE_CASE) to "$1 is not",
            Regex("""\b(it|puppy|pup|this mutt|this bitch) haven't\b""", RegexOption.IGNORE_CASE) to "$1 has not",
            Regex("""\b(it|puppy|pup|this mutt|this bitch) were not\b""", RegexOption.IGNORE_CASE) to "$1 was not",
            Regex("""\b(it|puppy|pup|this mutt|this bitch)\s+(?:don't|do not)\s+have\b""", RegexOption.IGNORE_CASE) to "$1 does not have",
            // Common malformed contractions
            Regex("""\bits dont\b""", RegexOption.IGNORE_CASE) to "it does not",
            Regex("""\bits doesnt\b""", RegexOption.IGNORE_CASE) to "it does not",
            Regex("""\bits cant\b""", RegexOption.IGNORE_CASE) to "it is unable to",
            Regex("""\bits wont\b""", RegexOption.IGNORE_CASE) to "it will not",
            // Double verbs
            Regex("""\b(is|are|was|were)\s+\1\b""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""\b(has|have|had)\s+\1\b""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""\b(do|does|did)\s+\1\b""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""\b(not)\s+\1\b""", RegexOption.IGNORE_CASE) to "$1",
            // Double common words
            Regex("""\b(the|a|an|and|or|but)\s+\1\b""", RegexOption.IGNORE_CASE) to "$1",
            Regex("""\b(please)\s+\1\b""", RegexOption.IGNORE_CASE) to "$1",
            // Common cleanup from aggressive replacements
            Regex("""\bits's\b""", RegexOption.IGNORE_CASE) to "its",
            Regex("""\b(its)\s+is\b""", RegexOption.IGNORE_CASE) to "it is",
            Regex("""\b(it|puppy|pup|this mutt|this bitch)\s+is\s+not\s+not\b""", RegexOption.IGNORE_CASE) to "$1 is not",
            Regex("""\b(it|puppy|pup|this mutt|this bitch)\s+is\s+unable to\s+unable to\b""", RegexOption.IGNORE_CASE) to "$1 is unable to"
        )
        private val DISCORD_TWITTER_URL_REGEX = Regex(
            """(?i)\bhttps?://(?:www\.|mobile\.)?(?:twitter\.com|x\.com)(/[^\s<>'\"]*)?"""
        )

        private val NEGATION_AFFIRMATION_RULES = listOf(
            Regex("""(?i)\bno\b""") to listOf("yes", "yes please", "okay", "okay yes"),
            Regex("""(?i)\bnope\b""") to listOf("yes", "yes please", "okay"),
            Regex("""(?i)\bnah\b""") to listOf("yes", "yes please", "okay"),
            Regex("""(?i)\bno thanks\b""") to listOf("yes please", "yes please thank you", "okay yes please"),
            Regex("""(?i)\bnot now\b""") to listOf("yes now", "yes please now", "okay now"),
            Regex("""(?i)\bdon't\b|\bdo not\b""") to listOf("do", "will", "yes"),
            Regex("""(?i)\bcan't\b|\bcannot\b""") to listOf("can", "can now", "can do"),
            Regex("""(?i)\bwon't\b|\bwill not\b""") to listOf("will", "will now", "yes"),
        )

            /**
             * Replacement personas coordinate all self-reference pronouns (I, me, my, etc.)
             * so they form a consistent voice within a session.
             */
            private data class ReplacementPersona(
                val name: String,
                val replacements: Map<String, String>
            )

            private val SELF_REFERENCE_PERSONAS = listOf(
                ReplacementPersona(
                    "puppy",
                    mapOf(
                        """(?i)\bI\b""" to "puppy",
                        """(?i)\bme\b""" to "it",
                        """(?i)\bmyself\b""" to "itself",
                        """(?i)\bmy\b""" to "its",
                        """(?i)\bmine\b""" to "its"
                    )
                ),
                ReplacementPersona(
                    "mutt",
                    mapOf(
                        """(?i)\bI\b""" to "this mutt",
                        """(?i)\bme\b""" to "it",
                        """(?i)\bmyself\b""" to "itself",
                        """(?i)\bmy\b""" to "its",
                        """(?i)\bmine\b""" to "its"
                    )
                ),
                ReplacementPersona(
                    "it",
                    mapOf(
                        """(?i)\bI\b""" to "it",
                        """(?i)\bme\b""" to "it",
                        """(?i)\bmyself\b""" to "itself",
                        """(?i)\bmy\b""" to "its",
                        """(?i)\bmine\b""" to "its"
                    )
                )
            )

            private val SELF_REFERENCE_PATTERNS = setOf(
                """(?i)\bI\b""",
                """(?i)\bme\b""",
                """(?i)\bmyself\b""",
                """(?i)\bmy\b""",
                """(?i)\bmine\b"""
            )

        /** Package-name substrings that identify messaging/SMS apps for PTS gating. */
        private val MESSAGING_PACKAGE_KEYWORDS = listOf(
            "sms", "message", "whatsapp", "telegram", "signal", "messenger",
            "snapchat", "viber", "wechat", "kik"
        )
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

    /** Last full replacement text injected by this service (used to stop rewrite loops). */
    private var lastAppliedReplacementText: String? = null

    /** Timestamp for [lastAppliedReplacementText]. */
    private var lastAppliedReplacementAt: Long = 0L

    /** Timestamp of the last accepted replacement (for rate-limiting per keystroke). */
    private var lastReplacementAcceptedAt: Long = 0L

    /** Original text before the last dictionary replacement (for bypass detection). */
    private var lastDictReplacementOriginal: String? = null

    /** The regex pattern applied in last dictionary replacement (for bypass detection). */
    private var lastAppliedDictPattern: String? = null

    /** Timestamp of the last dictionary replacement (for bypass detection). */
    private var lastDictReplacementTimestamp: Long = 0L

    /**
     * Tracks recently-applied dictionary replacements (original → result pairs).
     * Used to prevent re-applying the same pattern when the user extends the
     * already-replaced text (e.g., typing a space after a replacement).
     */
    private data class RecentReplacement(
        val original: String,
        val result: String,
        val pattern: String,
        val appliedAt: Long
    )
    private val recentDictReplacements = mutableListOf<RecentReplacement>()


    /**
     * Regex patterns that the user has explicitly bypassed this session
     * by deleting and retyping the original text.
     */
    private val dictionaryBypassPatterns = mutableSetOf<String>()
    /**
     * Text corrections that the user has explicitly bypassed this session.
     * Used to prevent re-applying grammar corrections the user wants to keep wrong.
     */
    private val grammarBypassRules = mutableSetOf<String>()
    /**
     * Words that the user has successfully bypassed during this session.
     * Ignored when strict mode is active.
     */
    private val sessionWhitelist = mutableSetOf<String>()

    /**
     * Tracks the chosen replacement option for each pattern this session.
     * When a pattern has multiple replacement options, the first randomly-selected
     * option is reused for all matches within that session, ensuring consistency
     * (e.g., "I" stays as "puppy" throughout a message, not varying per keystroke).
     */
    private val sessionReplacementChoices = mutableMapOf<String, String>()

    /** Per-session decision cache for whether a negation pattern should flip. */
    private val sessionNegationFlipDecisions = mutableMapOf<String, Boolean>()

    /** Stable per-session probability for negation->affirmation rewrites. */
    private var sessionAffirmationChance =
        Random.nextDouble(AFFIRMATION_REWRITE_MIN_CHANCE, AFFIRMATION_REWRITE_MAX_CHANCE)

        /**
         * Tracks the chosen persona for self-reference pronouns this session.
         * Once a self-reference pattern is matched, a persona is selected and
         * applied to ALL self-reference patterns for consistency.
         */
        private var sessionPersona: ReplacementPersona? = null

    // ------------------------------------------------------------------
    //  Focus-change tracking (used to reset session state)
    // ------------------------------------------------------------------

    private var lastFocusedNodeId: Int = -1
    private var lastPackageName: String? = null
    private var lastWindowContextPackage: String? = null
    private var lastWindowContextText: String? = null

    private data class ContactSnapshot(
        val loadedAtMs: Long,
        val names: Set<String>,
        val phoneKeys: Set<String>,
    )

    @Volatile
    private var cachedContactSnapshot: ContactSnapshot? = null

    private val phoneTokenRegex = Regex("""\+?[\d()\-\s]{7,}\d""")

    /** Tracks the last package for which a PTS request was already fired this session. */
    private var lastPtsRequestedPackage: String? = null


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
            AccessibilityEvent.TYPE_VIEW_FOCUSED        -> handleFocusChange(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED   -> handleTextChanged(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
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

    /**
     * Handles app-switch events for Permission-To-Speak gating.
     * Fires a webhook and shows a Toast when the sub opens a messaging app
     * not on the approved contacts list.
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        lastWindowContextPackage = pkg
        lastWindowContextText = buildWindowContextText(event)
        if (!PermissionToSpeakManager.isEnabled(applicationContext)) return

        val isMessagingApp = MESSAGING_PACKAGE_KEYWORDS.any { pkg.contains(it, ignoreCase = true) }
        if (!isMessagingApp) return
        if (PermissionToSpeakManager.isApprovedPackage(applicationContext, pkg)) return
        if (pkg == lastPtsRequestedPackage) return  // already requested this session

        lastPtsRequestedPackage = pkg
        PermissionToSpeakManager.requestPermission(applicationContext, pkg)
        handler.post {
            android.widget.Toast.makeText(
                applicationContext,
                "⏸ Permission to speak required — request sent to your partner.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        Log.i(TAG, "PTS request fired for $pkg")
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

            val now = System.currentTimeMillis()
            if (currentText == lastAppliedReplacementText &&
                (now - lastAppliedReplacementAt) <= REPLACEMENT_ECHO_WINDOW_MS
            ) {
                Log.d(TAG, "Ignoring replacement echo event")
                return
            }

            // Rate-limit dictionary replacements to avoid firing on every keystroke.
            val timeSinceLastReplacement = now - lastReplacementAcceptedAt
            if (timeSinceLastReplacement < MIN_REPLACEMENT_INTERVAL_MS) {
                Log.d(TAG, "Replacement rate-limited: ${timeSinceLastReplacement}ms since last")
                return
            }

            if (isTextCorrectionExemptForCurrentContact(event, currentPackage, currentText)) {
                Log.d(TAG, "Text correction skipped for exempt contact context")
                return
            }

            // ---- Contextual honorific rewrite --------------------------------------
            HonorificManager
                .rewriteForContext(applicationContext, currentPackage, currentText)
                ?.takeIf { it != currentText }
                ?.let { rewritten ->
                    scheduleReplacement(rewritten)
                    return
                }

            // ---- Discord Twitter/X link rewrite pass -------------------------------
            val fxtwitterRewrite = applyDiscordTwitterLinkRewrite(currentPackage, currentText)
            if (fxtwitterRewrite != currentText) {
                Log.i(TAG, "Discord link rewrite applied (x/twitter -> fxtwitter)")
                lastReplacementAcceptedAt = System.currentTimeMillis()
                scheduleReplacement(fxtwitterRewrite)
                return
            }

            // ---- Correction pass --------------------------------------------------
            val rewritten = applyTextReplacementDictionary(currentText)
            if (rewritten != currentText) {
                Log.i(TAG, "Text-replacement dictionary matched — applying rewrite")
                lastReplacementAcceptedAt = System.currentTimeMillis()
                applyReplacement(node, ensureTrailingSpace(rewritten))
                return
            }

            // ---- Grammar correction pass --------------------------------------------------
            val grammarCorrected = postProcessGrammar(currentPackage, currentText)
            if (grammarCorrected != currentText) {
                Log.i(TAG, "Grammar error detected and corrected")
                applyReplacement(node, grammarCorrected)
                return
            }

            // ---- Optional negation -> affirmation pass ---------------------------
            val affirmativeRewrite = applyOptionalAffirmativeRewrite(currentText)
            if (affirmativeRewrite != currentText) {
                Log.i(TAG, "Optional affirmation rewrite applied")
                applyReplacement(node, affirmativeRewrite)
                return
            }

            // ---- Dictionary bypass detection (soft mode only) ----
            if (!strictToneModeEnabled) {
                val pattern = lastAppliedDictPattern
                val original = lastDictReplacementOriginal
                if (pattern != null && original != null &&
                    currentText == original &&
                    (System.currentTimeMillis() - lastDictReplacementTimestamp) <= BYPASS_WINDOW_MS
                ) {
                    // User deleted the replacement and retyped original within grace window.
                    dictionaryBypassPatterns.add(pattern)
                    lastAppliedDictPattern = null
                    lastDictReplacementOriginal = null
                    Log.i(TAG, "Dictionary pattern bypass accepted: $pattern")
                    ConsequenceDispatcher.punish(applicationContext, "dict_bypass=$pattern")
                    return
                }
            }

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
                    ConsequenceDispatcher.punish(applicationContext, "tone_bypass=$correctedWord")
                    return
                }
            }

            for (word in restricted) {
                if (word.isBlank()) continue
                if (!containsWholeWord(textLower, word)) continue

                // In soft mode, skip words the user has explicitly whitelisted.
                if (!strictMode && sessionWhitelist.contains(word)) continue

                Log.i(TAG, "Restricted word detected ('$word') — scheduling replacement")
                lastCorrectedWord        = word
                lastCorrectionTimestamp  = System.currentTimeMillis()
                applyReplacement(node, SAFE_PHRASE)
                dispatchToneBlockTelemetry(word)
                ConsequenceDispatcher.punish(applicationContext, "restricted_word=$word")
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
     * Attempts to replace text on the same node that emitted the event, then
     * falls back to debounced focused-node replacement when direct action fails.
     */
    private fun applyReplacement(targetNode: AccessibilityNodeInfo, replacement: String) {
        lastAppliedReplacementText = replacement
        lastAppliedReplacementAt = System.currentTimeMillis()
        isApplyingCorrection = true
        val succeeded = runCatching {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    replacement
                )
            }
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrElse { err ->
            Log.w(TAG, "Direct node replacement failed; falling back", err)
            false
        }

        if (succeeded) {
            handler.postDelayed({ isApplyingCorrection = false }, CORRECTION_GUARD_MS)
        } else {
            isApplyingCorrection = false
            scheduleReplacement(replacement)
        }
    }

    /**
     * Cancels any pending correction and schedules a new one after [DEBOUNCE_MS].
     * Uses [rootInActiveWindow] at execution time so no [AccessibilityNodeInfo]
     * reference is held across the delay.
     */
    private fun scheduleReplacement(replacement: String) {
        pendingCorrectionRunnable?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            pendingCorrectionRunnable = null
            lastAppliedReplacementText = replacement
            lastAppliedReplacementAt = System.currentTimeMillis()
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
     * Applies partner-configured regex dictionary rules from SharedPreferences.
     *
     * This provides a local fallback when LSPosed or external hook layers are
     * unavailable, ensuring replacements still occur through Accessibility.
     * 
     * Skips any patterns that the user has explicitly bypassed during this session,
     * and also skips patterns whose replacement results are already visible in the
     * current text (to avoid re-applying them as the user continues typing).
     */
    private fun applyTextReplacementDictionary(text: String): String {
        // Clean up expired recent replacements.
        val now = System.currentTimeMillis()
        recentDictReplacements.removeAll { (now - it.appliedAt) > RECENT_REPLACEMENT_WINDOW_MS }

        // If any recent replacement's result is already in the current text,
        // skip dictionary application entirely to avoid re-triggering.
        for (recent in recentDictReplacements) {
            if (text.contains(recent.result)) {
                Log.d(TAG, "Current text already contains recent replacement result, skipping dict")
                return text
            }
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val dictJson = prefs.getString(FilterService.PREF_TEXT_REPLACEMENT_DICT, null)
            ?.takeIf { it.isNotBlank() }
            ?: return text

        return try {
            val obj = JSONObject(dictJson)
            var rewritten = text
            var appliedPattern: String? = null
            var appliedReplacement: String? = null
            val keys = obj.keys()
            while (keys.hasNext()) {
                val pattern = keys.next()
                if (pattern.isBlank()) continue
                // Skip patterns the user has already bypassed this session.
                if (dictionaryBypassPatterns.contains(pattern)) {
                    Log.d(TAG, "Skipping bypassed dict pattern: $pattern")
                    continue
                }
                // Support both single string and array of strings for multiple options.
                val replacementValue = obj.opt(pattern)
                val replacements = when (replacementValue) {
                    is String -> if (replacementValue.isEmpty()) continue else listOf(replacementValue)
                    is org.json.JSONArray -> {
                        val list = mutableListOf<String>()
                        for (i in 0 until replacementValue.length()) {
                            val item = replacementValue.optString(i, "").takeIf { it.isNotEmpty() }
                            if (item != null) list.add(item)
                        }
                        if (list.isEmpty()) continue else list
                    }
                    else -> continue
                }
                    // Check if this is a self-reference pattern (I, me, my, myself, mine).
                    val isSelfReference = SELF_REFERENCE_PATTERNS.contains(pattern)

                    // If it's a self-reference and we haven't picked a persona yet, pick one now.
                    // The persona defines consistent replacements for ALL self-reference pronouns.
                    if (isSelfReference && sessionPersona == null) {
                        sessionPersona = SELF_REFERENCE_PERSONAS.random()
                        Log.i(TAG, "Selected persona for session: ${sessionPersona?.name}")
                    }

                    // Get replacement from persona if self-reference, otherwise use session cache.
                    val replacement = if (isSelfReference && sessionPersona != null) {
                        sessionPersona!!.replacements[pattern] ?: continue
                    } else {
                        // For non-self-reference patterns, use random selection cache.
                        sessionReplacementChoices.getOrPut(pattern) {
                            replacements.random()
                        }
                    }
                runCatching {
                    val beforeRewrite = rewritten
                    rewritten = rewritten.replace(pattern.toRegex(), replacement)
                    // Track the first pattern that actually matched.
                    if (appliedPattern == null && rewritten != beforeRewrite) {
                        appliedPattern = pattern
                        appliedReplacement = replacement
                        // Store the original text before any replacements for bypass detection.
                        lastDictReplacementOriginal = text
                        lastAppliedDictPattern = pattern
                        lastDictReplacementTimestamp = System.currentTimeMillis()
                        // Record this replacement to prevent re-application during incremental typing.
                        recentDictReplacements.add(
                            RecentReplacement(text, rewritten, pattern, now)
                        )
                        Log.i(TAG, "Dictionary pattern applied: $pattern → $replacement")
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Invalid replacement regex skipped: $pattern", err)
                }
            }
            rewritten
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse text-replacement dictionary", e)
            text
        }
    }

    /**
     * Applies grammar correction rules to fix common errors introduced by
     * text replacements (e.g., subject-verb agreement, double verbs).
     * 
     * Skips any rules the user has explicitly bypassed during this session.
     */
    private fun postProcessGrammar(packageName: String?, text: String): String {
        val profile = grammarProfileForPackage(packageName)
        var corrected = text
        for ((pattern, replacement) in GRAMMAR_RULES) {
                if (profile == GrammarProfile.LIGHT && !isLightGrammarRule(pattern.pattern)) {
                    continue
                }
                val ruleId = pattern.pattern  // Use the regex pattern string as ID
                if (grammarBypassRules.contains(ruleId)) {
                    Log.d(TAG, "Skipping bypassed grammar rule: $ruleId")
                continue
            }
            runCatching {
                val before = corrected
                corrected = pattern.replace(corrected, replacement)
                if (corrected != before) {
                        Log.i(TAG, "Grammar corrected: $ruleId")
                }
            }.onFailure { err ->
                    Log.w(TAG, "Grammar rule failed: $ruleId", err)
            }
        }
        corrected = normalizeGrammarSurfaceText(corrected)
        return corrected
    }

    private fun grammarProfileForPackage(packageName: String?): GrammarProfile {
        val pkg = packageName?.lowercase().orEmpty()
        if (pkg.isBlank()) return GrammarProfile.STRICT

        val strictKeywords = listOf(
            "sms", "message", "messaging", "whatsapp", "telegram", "signal", "messenger",
            "discord", "slack", "teams",
        )
        if (strictKeywords.any { pkg.contains(it) }) {
            return GrammarProfile.STRICT
        }

        val lightKeywords = listOf(
            "notes", "keep", "docs", "office", "word", "onenote",
            "chrome", "firefox", "edge", "brave",
            "search", "launcher",
        )
        if (lightKeywords.any { pkg.contains(it) }) {
            return GrammarProfile.LIGHT
        }

        return GrammarProfile.STRICT
    }

    private fun isLightGrammarRule(rulePattern: String): Boolean {
        val lightRuleMarkers = listOf(
            "\\b(is|are|was|were)\\s+\\1\\b",
            "\\b(has|have|had)\\s+\\1\\b",
            "\\b(do|does|did)\\s+\\1\\b",
            "\\b(not)\\s+\\1\\b",
            "\\b(the|a|an|and|or|but)\\s+\\1\\b",
            "\\b(please)\\s+\\1\\b",
            "its's",
            "unable to\\s+unable to",
        )
        return lightRuleMarkers.any { marker -> rulePattern.contains(marker) }
    }

    private fun normalizeGrammarSurfaceText(text: String): String {
        var normalized = text
        // Collapse runaway spacing while preserving intentional trailing space behavior elsewhere.
        normalized = normalized.replace(Regex("""[ \t]{2,}"""), " ")
        // Remove spaces before punctuation: "word ," -> "word,"
        normalized = normalized.replace(Regex("""\s+([,.;!?:])"""), "$1")
        // Avoid accidental duplicate punctuation bursts from chained rewrites.
        normalized = normalized.replace(Regex("""([,.;!?:])\1+"""), "$1")
        return normalized
    }

    private fun isTextCorrectionExemptForCurrentContact(
        event: AccessibilityEvent,
        packageName: String?,
        currentText: String,
    ): Boolean {
        val snapshot = loadPhoneContactSnapshot()
        if (snapshot.names.isEmpty() && snapshot.phoneKeys.isEmpty()) return false

        val contextParts = mutableListOf<String>()
        if (lastWindowContextPackage == packageName) {
            lastWindowContextText?.let { if (it.isNotBlank()) contextParts.add(it) }
        }
        event.contentDescription?.toString()?.let { if (it.isNotBlank()) contextParts.add(it) }
        event.text
            ?.mapNotNull { it?.toString() }
            ?.forEach { text ->
                if (text.isBlank()) return@forEach
                if (text == currentText) return@forEach
                contextParts.add(text)
            }

        if (contextParts.isEmpty()) return false
        val haystack = contextParts.joinToString(" ").lowercase().trim()

        val hasNameMatch = snapshot.names.any { name ->
            name.length >= 3 && containsNameInContext(haystack, name)
        }
        if (hasNameMatch) return true

        val numberTokens = phoneTokenRegex.findAll(haystack)
            .mapNotNull { match ->
                normalizePhoneDigits(match.value)
                    ?.takeIf { it.length >= 7 }
            }
            .toList()
        return numberTokens.any { digits ->
            val keys = mutableSetOf(digits)
            if (digits.length >= 7) keys.add(digits.takeLast(7))
            if (digits.length >= 10) keys.add(digits.takeLast(10))
            keys.any { snapshot.phoneKeys.contains(it) }
        }
    }

    private fun loadPhoneContactSnapshot(): ContactSnapshot {
        val now = System.currentTimeMillis()
        val cached = cachedContactSnapshot
        if (cached != null && (now - cached.loadedAtMs) < CONTACT_CACHE_TTL_MS) {
            return cached
        }

        if (!hasReadContactsPermission()) {
            return ContactSnapshot(now, emptySet(), emptySet())
        }

        val names = mutableSetOf<String>()
        val phoneKeys = mutableSetOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
        )

        runCatching {
            applicationContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )
        }.getOrNull()?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val normalizedIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)

            while (cursor.moveToNext()) {
                val rawName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                normalizeContactName(rawName)?.let { names.add(it) }

                val rawNormalized = if (normalizedIndex >= 0) cursor.getString(normalizedIndex) else null
                val rawNumber = if (numberIndex >= 0) cursor.getString(numberIndex) else null
                val digits = normalizePhoneDigits(rawNormalized) ?: normalizePhoneDigits(rawNumber)
                if (digits != null) {
                    phoneKeys.add(digits)
                    if (digits.length >= 7) phoneKeys.add(digits.takeLast(7))
                    if (digits.length >= 10) phoneKeys.add(digits.takeLast(10))
                }
            }
        }

        return ContactSnapshot(now, names, phoneKeys).also {
            cachedContactSnapshot = it
        }
    }

    private fun hasReadContactsPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.d(TAG, "READ_CONTACTS not granted; contact-based exemption disabled")
        }
        return granted
    }

    private fun normalizeContactName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.lowercase().trim().replace(Regex("""\s+"""), " ")
            .takeIf { it.length >= 3 }
    }

    private fun normalizePhoneDigits(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() }
        return digits.takeIf { it.length >= 7 }
    }

    private fun containsNameInContext(context: String, name: String): Boolean {
        val escaped = Regex.escape(name)
        val pattern = Regex("(?<![\\w])$escaped(?![\\w])")
        return pattern.containsMatchIn(context)
    }

    private fun buildWindowContextText(event: AccessibilityEvent): String {
        val parts = mutableListOf<String>()
        event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        event.text
            ?.mapNotNull { it?.toString() }
            ?.filter { it.isNotBlank() }
            ?.forEach(parts::add)
        return parts.joinToString(" ").trim()
    }

    private fun applyDiscordTwitterLinkRewrite(packageName: String?, text: String): String {
        if (packageName?.contains("discord", ignoreCase = true) != true) {
            return text
        }
        return DISCORD_TWITTER_URL_REGEX.replace(text) { match ->
            val suffix = match.groupValues.getOrNull(1).orEmpty()
            "https://fxtwitter.com$suffix"
        }
    }

    private fun applyOptionalAffirmativeRewrite(text: String): String {
        var rewritten = text
        for ((pattern, options) in NEGATION_AFFIRMATION_RULES) {
            val key = "negation:${pattern.pattern}"
            val shouldFlip = sessionNegationFlipDecisions.getOrPut(key) {
                Random.nextDouble() <= sessionAffirmationChance
            }
            if (!shouldFlip) continue
            if (!pattern.containsMatchIn(rewritten)) continue

            val replacement = sessionReplacementChoices.getOrPut(key) {
                options.random()
            }
            rewritten = pattern.replace(rewritten, replacement)
        }
        return rewritten
    }

    /**
     * Replaces the text inside [node] with [replacement] using
     * [AccessibilityNodeInfo.ACTION_SET_TEXT].
     */
    private fun replaceText(node: AccessibilityNodeInfo, replacement: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun ensureTrailingSpace(text: String): String {
        if (text.isBlank()) return text
        if (text.last().isWhitespace()) return text
        return "$text "
    }

    /**
     * Fires an asynchronous telemetry event each time the service enforces a
     * restriction by replacing a blocked word.  Lets the FastAPI Handler Panel
     * track tone blocks independently from generic punishment events.
     *
     * Uses the cached webhook URL / bearer-token populated at service start.
     * If no URL is configured the call is silently skipped.
     */
    private fun dispatchToneBlockTelemetry(word: String) {
        val webhookUrl  = cachedWebhookUrl  ?: return
        val bearerToken = cachedBearerToken

        val payload = JSONObject().apply {
            put("event",     "tone_block")
            put("word",      word)
            put("timestamp", System.currentTimeMillis())
        }
        WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
    }

    /**
     * Fires an asynchronous telemetry event when a user successfully triggers
     * the bypass.  Uses the cached webhook URL / bearer-token populated at
     * service start.  If no URL is configured the call is silently skipped.
     */
    private fun dispatchOverrideTelemetry(originalWord: String) {
        val webhookUrl  = cachedWebhookUrl  ?: return
        // Bearer token is optional — server accepts no-auth requests when no
        // webhook secret is configured.
        val bearerToken = cachedBearerToken

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
        dictionaryBypassPatterns.clear()
        grammarBypassRules.clear()
        recentDictReplacements.clear()
        sessionReplacementChoices.clear()
        sessionNegationFlipDecisions.clear()
        sessionAffirmationChance =
            Random.nextDouble(AFFIRMATION_REWRITE_MIN_CHANCE, AFFIRMATION_REWRITE_MAX_CHANCE)
            sessionPersona = null
        lastCorrectedWord       = null
        lastCorrectionTimestamp = 0L
        lastReplacementAcceptedAt = 0L
        lastDictReplacementOriginal = null
        lastAppliedDictPattern = null
        lastDictReplacementTimestamp = 0L
        lastAppliedReplacementText = null
        lastAppliedReplacementAt = 0L
        lastPtsRequestedPackage = null
        lastWindowContextPackage = null
        lastWindowContextText = null
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
