package com.tpeapp.xposed

import android.content.Intent
import android.util.Log
import com.tpeapp.mindful.XposedToneReceiver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray

/**
 * Hooks [android.view.inputmethod.InputConnectionWrapper] (and
 * [android.view.inputmethod.BaseInputConnection] as a fallback for Compose /
 * custom-IME apps) to enforce the restricted-vocabulary ruleset managed by
 * the Accountability Partner.
 *
 * ### Enforcement
 * [commitText] is intercepted before text reaches the target app.  If the
 * committed text contains any restricted word (whole-word, case-insensitive),
 * the [CharSequence] argument is replaced with [SAFE_PHRASE] ("[Redacted]")
 * so the target app never sees the original word.  Each redaction also fires
 * a [XposedToneReceiver.ACTION_XPOSED_TONE_BLOCK] broadcast so the partner
 * dashboard receives live blocking telemetry.
 *
 * ### Soft-mode bypass
 * When the partner has selected "Soft" tone mode the user may override a single
 * redaction:
 *  1. The user deletes the [SAFE_PHRASE] text — detected by accumulating
 *     [deleteSurroundingText] `beforeLength` values within [BYPASS_WINDOW_MS].
 *  2. Within [BYPASS_WINDOW_MS] the user re-types the exact restricted word.
 *  3. The hook allows the text through unmodified and fires an explicit broadcast
 *     Intent ([XposedToneReceiver.ACTION_XPOSED_TONE_INFRACTION]) to the main
 *     TPE app, which executes the punishment webhook.  The module never makes
 *     direct network calls.
 *  4. Once bypassed, the word is added to [sessionWhitelistWords] so it is not
 *     re-blocked for the remainder of the process lifetime (mirrors the old
 *     AccessibilityService session-whitelist behaviour).
 *
 * In "Strict" mode no bypass is possible — [SAFE_PHRASE] is applied every time
 * and [sessionWhitelistWords] is ignored.
 *
 * ### IPC
 * The vocabulary and tone mode are fetched from [com.tpeapp.service.FilterService]
 * via the [com.tpeapp.filter.IFilterService] AIDL interface and cached for
 * [CACHE_TTL_MS] to minimise inter-process round-trips.  Regex objects for
 * whole-word matching are pre-compiled when the vocabulary cache is refreshed,
 * so they are not re-created on every keystroke.  [MainHook.ensureServiceBound]
 * is called on the first [commitText] invocation so the service binding is
 * established even in apps that have no images (chat apps, notes apps).
 */
object InputConnectionHook {

    private const val TAG = "TPE_InputConnectionHook"

    /** Replacement text inserted when a restricted word is detected. */
    private const val SAFE_PHRASE     = "[Redacted]"
    private const val SAFE_PHRASE_LEN = SAFE_PHRASE.length

    /** Bypass window: user must retype the word within this duration (ms). */
    private const val BYPASS_WINDOW_MS = 3_000L

    /** How long the vocabulary / tone-mode caches remain valid (ms). */
    private const val CACHE_TTL_MS = 30_000L

    /** Whole-word boundary pattern; filled with the escaped restricted word. */
    private const val WORD_BOUNDARY_PATTERN = "(?<![\\w])%s(?![\\w])"

    // ── Vocabulary cache ──────────────────────────────────────────────────────

    @Volatile private var cachedVocabJson    : String                   = ""
    @Volatile private var cachedVocab        : List<String>             = emptyList()
    /** Pre-compiled regexes paired with their corresponding word strings. */
    @Volatile private var cachedVocabRegexes : List<Pair<String, Regex>> = emptyList()
    @Volatile private var lastVocabFetchMs   : Long                     = 0L

    // ── Tone-mode cache ───────────────────────────────────────────────────────

    @Volatile private var cachedToneMode  : String = "Soft"
    @Volatile private var lastModeFetchMs : Long   = 0L

    // ── Session whitelist (Soft mode) ─────────────────────────────────────────

    /**
     * Words the user has successfully bypassed this process lifetime.
     * Ignored in Strict mode.  Mirrors the `sessionWhitelist` of the old
     * [com.tpeapp.mindful.ToneEnforcementService].
     */
    private val sessionWhitelistWords: MutableSet<String> = mutableSetOf()

    // ── Soft-mode bypass state ────────────────────────────────────────────────

    /** The restricted word most recently replaced with [SAFE_PHRASE]. */
    @Volatile private var lastRedactedWord    : String? = null

    /** Epoch-ms timestamp of the last redaction. */
    @Volatile private var lastRedactTimestamp : Long    = 0L

    /**
     * Running total of characters deleted since the last redaction.
     * IMEs that delete one character at a time (Gboard, SwiftKey) accumulate
     * individual [deleteSurroundingText] calls here; the bypass window is
     * opened once the total reaches [SAFE_PHRASE_LEN].
     */
    @Volatile private var accumulatedDeleteCount : Int     = 0

    /**
     * `true` once [accumulatedDeleteCount] reaches [SAFE_PHRASE_LEN] after a
     * fresh redaction — indicates the user is attempting the bypass and the
     * next matching [commitText] should be allowed through.
     */
    @Volatile private var bypassWindowOpen : Boolean = false

    // ── Install ───────────────────────────────────────────────────────────────

    fun install(loader: ClassLoader) {
        // Hook InputConnectionWrapper (standard IME path).
        tryHook("android.view.inputmethod.InputConnectionWrapper", loader)
        // Hook BaseInputConnection as a fallback for Jetpack Compose and apps
        // with custom IME integrations that bypass InputConnectionWrapper.
        tryHook("android.view.inputmethod.BaseInputConnection", loader)
    }

    private fun tryHook(className: String, loader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                className, loader,
                "commitText", CharSequence::class.java, Int::class.java,
                commitTextHook
            )
            XposedHelpers.findAndHookMethod(
                className, loader,
                "deleteSurroundingText", Int::class.java, Int::class.java,
                deleteSurroundingTextHook
            )
            Log.i(TAG, "InputConnection hooks installed on $className")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to install InputConnection hooks on $className", e)
        }
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    private val commitTextHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val text    = param.args[0] as? CharSequence ?: return
            val textStr = text.toString()
            if (textStr.isBlank()) return

            // Ensure the AIDL service binding is established the first time any
            // text field is committed, even in apps with no images.
            MainHook.getContext()?.let { MainHook.ensureServiceBound(it) }

            val vocabRegexes = currentVocabRegexes() ?: return
            if (vocabRegexes.isEmpty()) return

            val toneMode  = currentToneMode()
            val textLower = textStr.lowercase()

            // ── Bypass check (Soft mode only) ─────────────────────────────────
            if (toneMode == "Soft" && bypassWindowOpen) {
                val redacted = lastRedactedWord
                if (redacted != null &&
                    System.currentTimeMillis() - lastRedactTimestamp <= BYPASS_WINDOW_MS &&
                    containsWholeWord(textLower, redacted, vocabRegexes)
                ) {
                    // User successfully re-typed the restricted word within the
                    // grace window — add to session whitelist, allow the commit,
                    // and fire the infraction event.
                    sessionWhitelistWords.add(redacted)
                    clearBypassState()
                    Log.i(TAG, "Soft-mode override accepted for: $redacted")
                    dispatchInfractionBroadcast(redacted)
                    return   // let commitText pass unmodified
                }
                // Window expired or wrong word — close the bypass
                clearBypassState()
            }

            // ── Enforcement pass ──────────────────────────────────────────────
            for ((word, regex) in vocabRegexes) {
                if (word.isBlank()) continue
                // In Soft mode, skip words the user has already whitelisted.
                if (toneMode == "Soft" && sessionWhitelistWords.contains(word)) continue
                if (!regex.containsMatchIn(textLower)) continue

                param.args[0]        = SAFE_PHRASE
                lastRedactedWord     = word
                lastRedactTimestamp  = System.currentTimeMillis()
                accumulatedDeleteCount = 0
                bypassWindowOpen     = false
                Log.i(TAG, "Restricted word '$word' redacted via InputConnection hook")
                dispatchToneBlockBroadcast(word)
                return
            }
        }
    }

    private val deleteSurroundingTextHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val beforeLength = param.args[0] as? Int ?: return
            if (beforeLength <= 0) return

            val redacted = lastRedactedWord ?: return
            if (System.currentTimeMillis() - lastRedactTimestamp > BYPASS_WINDOW_MS) {
                // Window already expired — reset accumulator.
                accumulatedDeleteCount = 0
                return
            }

            // Accumulate individual deletions so IMEs that delete one char at
            // a time (Gboard, SwiftKey) still trigger the bypass window.
            accumulatedDeleteCount += beforeLength
            if (accumulatedDeleteCount >= SAFE_PHRASE_LEN) {
                bypassWindowOpen = true
                Log.d(TAG, "Soft-mode bypass window opened for '$redacted' (accumulated=${accumulatedDeleteCount})")
            }
        }
    }

    // ── Vocabulary / mode cache management ───────────────────────────────────

    /**
     * Returns pre-compiled word/regex pairs for the current restricted
     * vocabulary, refreshing from the service when the cache has expired.
     * Returns `null` when the service is unavailable.
     */
    private fun currentVocabRegexes(): List<Pair<String, Regex>>? {
        val now = System.currentTimeMillis()
        if (now - lastVocabFetchMs < CACHE_TTL_MS) return cachedVocabRegexes

        val service = MainHook.filterService ?: return null
        val json = runCatching { service.getRestrictedVocabulary() }.getOrNull() ?: return null
        lastVocabFetchMs = now

        if (json == cachedVocabJson) return cachedVocabRegexes
        cachedVocabJson    = json
        cachedVocab        = parseVocabulary(json)
        // Pre-compile a Regex for each word once, reused on every keystroke.
        cachedVocabRegexes = cachedVocab.map { word ->
            word to WORD_BOUNDARY_PATTERN.format(Regex.escape(word)).toRegex()
        }
        return cachedVocabRegexes
    }

    /**
     * Returns the current tone mode ("Strict" or "Soft"), refreshing from the
     * service if the cache has expired.  Defaults to "Soft" on service failure.
     */
    private fun currentToneMode(): String {
        val now = System.currentTimeMillis()
        if (now - lastModeFetchMs < CACHE_TTL_MS) return cachedToneMode

        val service = MainHook.filterService ?: return cachedToneMode
        val mode = runCatching { service.getToneMode() }.getOrNull() ?: return cachedToneMode
        lastModeFetchMs = now
        cachedToneMode  = mode
        return cachedToneMode
    }

    private fun parseVocabulary(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getString(i).lowercase() }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to parse restricted vocabulary JSON", e)
            emptyList()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns `true` if [text] contains [word] as a whole word, using the
     * pre-compiled [Regex] from [vocabRegexes] when available, or compiling
     * one on the fly for bypass checks where the word is known but not in the
     * current cache.
     */
    private fun containsWholeWord(
        text: String,
        word: String,
        vocabRegexes: List<Pair<String, Regex>>
    ): Boolean {
        val cached = vocabRegexes.firstOrNull { it.first == word }?.second
        val regex  = cached ?: WORD_BOUNDARY_PATTERN.format(Regex.escape(word)).toRegex()
        return regex.containsMatchIn(text)
    }

    private fun clearBypassState() {
        bypassWindowOpen       = false
        lastRedactedWord       = null
        lastRedactTimestamp    = 0L
        accumulatedDeleteCount = 0
    }

    /**
     * Sends an explicit broadcast to [com.tpeapp.mindful.XposedToneReceiver]
     * in the main TPE app carrying a `tone_block` telemetry event.  The module
     * never makes direct network calls — all webhook logic lives in the receiver.
     */
    private fun dispatchToneBlockBroadcast(word: String) {
        val context = MainHook.getContext() ?: run {
            Log.w(TAG, "No context available — tone_block broadcast skipped for: $word")
            return
        }
        val intent = Intent(XposedToneReceiver.ACTION_XPOSED_TONE_BLOCK).apply {
            setPackage("com.tpeapp")
            putExtra("word",           word)
            putExtra("timestamp",      System.currentTimeMillis())
            putExtra("source_package", context.packageName)
        }
        try {
            context.sendBroadcast(intent)
            Log.d(TAG, "Tone-block broadcast sent for word: $word")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to send tone_block broadcast", e)
        }
    }

    /**
     * Sends an explicit broadcast to [com.tpeapp.mindful.XposedToneReceiver]
     * in the main TPE app.  The module never makes direct network calls —
     * all webhook logic lives in the main app's receiver.
     */
    private fun dispatchInfractionBroadcast(word: String) {
        val context = MainHook.getContext() ?: run {
            Log.w(TAG, "No context available — infraction broadcast skipped for: $word")
            return
        }
        val intent = Intent(XposedToneReceiver.ACTION_XPOSED_TONE_INFRACTION).apply {
            setPackage("com.tpeapp")
            putExtra("word",           word)
            putExtra("timestamp",      System.currentTimeMillis())
            putExtra("source_package", context.packageName)
        }
        try {
            context.sendBroadcast(intent)
            Log.i(TAG, "Infraction broadcast sent for word: $word")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to send infraction broadcast", e)
        }
    }
}
