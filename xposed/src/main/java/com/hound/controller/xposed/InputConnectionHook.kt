package com.hound.controller.xposed

import android.content.Intent
import android.util.Log
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
 * Both [setComposingText] and [commitText] are intercepted before text reaches
 * the target app. If text contains any restricted word (whole-word,
 * case-insensitive), the [CharSequence] argument is replaced with
 * [SAFE_PHRASE] ("[Redacted]") so the target app never sees the original word.
 * Each redaction also fires a [XposedToneReceiver.ACTION_XPOSED_TONE_BLOCK]
 * broadcast so the partner dashboard receives live blocking telemetry.
 *
 * ### Soft-mode bypass
 * When the partner has selected "Soft" tone mode the user may override a single
 * redaction:
 *  1. The user deletes the [SAFE_PHRASE] text -- detected by accumulating
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
 * In "Strict" mode no bypass is possible -- [SAFE_PHRASE] is applied every time
 * and [sessionWhitelistWords] is ignored.
 *
 * ### IPC
 * The vocabulary and tone mode are fetched from [com.hound.controller.service.FilterService]
 * via the [com.hound.controller.filter.IFilterService] AIDL interface and cached for
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
    private const val REPLACEMENT_UNDO_WINDOW_MS = 4_000L
    private const val MIN_AUTOCORRECT_TOKEN_LEN = 3
    private const val ENABLE_INLINE_DEFERRED_AUTOCORRECT = true
    private const val MODE_STRICT = "strict"
    private const val MODE_LOOSE = "loose"

    /** Whole-word boundary pattern; filled with the escaped restricted word. */
    private const val WORD_BOUNDARY_PATTERN = "(?<![\\w])%s(?![\\w])"
    private val DISCORD_TWITTER_URL_REGEX = Regex(
        """(?i)\bhttps?://(?:www\.|mobile\.)?(?:twitter\.com|x\.com)(/[^\s<>'"]*)?"""
    )
    private val AUTO_CORRECT_BOUNDARY_REGEX = Regex("""[\s,.!?;:)}\]\u201D]""")
    private val AUTO_CORRECT_TOKEN_HAS_CONTENT_REGEX = Regex("""[\p{L}\p{N}]""")
    private val URLISH_TOKEN_REGEX = Regex(
        """(?i)(?:https?://|www\.|\b(?:[a-z0-9-]+\.)+(?:com|net|org|io|dev|app|gg|xyz|tv|me|co|ai|edu|gov|mil|biz|info|us|uk|ca|au|de|fr|jp|cn|ru|br|in)(?:/\S*)?)"""
    )
    private val EMAILISH_TOKEN_REGEX = Regex(
        """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""
    )

    // -- Vocabulary cache -----------------------------------------------------

    @Volatile private var cachedVocabJson    : String                    = ""
    @Volatile private var cachedVocab        : List<String>              = emptyList()
    /** Pre-compiled regexes paired with their corresponding word strings. */
    @Volatile private var cachedVocabRegexes : List<Pair<String, Regex>> = emptyList()
    @Volatile private var lastVocabFetchMs   : Long                      = 0L

    // -- Tone-mode cache ------------------------------------------------------

    @Volatile private var cachedToneMode  : String = MODE_LOOSE
    @Volatile private var lastModeFetchMs : Long   = 0L

    // -- Session whitelist (Soft mode) ----------------------------------------

    /**
     * Words the user has successfully bypassed this process lifetime.
     * Ignored in Strict mode.
     */
    private val sessionWhitelistWords: MutableSet<String> = mutableSetOf()

    // -- Soft-mode bypass state -----------------------------------------------

    @Volatile private var lastRedactedWord    : String? = null
    @Volatile private var lastRedactTimestamp : Long    = 0L
    @Volatile private var accumulatedDeleteCount : Int  = 0
    @Volatile private var bypassWindowOpen : Boolean    = false

    // One-shot undo: lets users type a just-replaced token back once without
    // it being immediately re-replaced.
    @Volatile private var lastAutocorrectSource: String? = null
    @Volatile private var lastAutocorrectAtMs: Long = 0L

    // Deferred word-boundary replacement state.
    // Only accessed on the UI thread so @Volatile is not required.
    private var pendingCorrectionWord: String = ""
    private var pendingCorrectionBoundary: String = ""

    // Reentrancy guard: prevents the direct IC calls inside flushPendingCorrection
    // from re-entering the hook and looping.
    private var flushingInProgress: Boolean = false

    // -- Install --------------------------------------------------------------

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
                "setComposingText", CharSequence::class.java, Int::class.java,
                setComposingTextHook
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

    // -- Hooks ----------------------------------------------------------------

    private val commitTextHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // Re-entry guard: the flush below calls ic.commitText directly;
            // ignore those calls so we don't loop.
            if (flushingInProgress) return

            val text = param.args[0] as? CharSequence ?: return
            // Use isEmpty() not isBlank(): a lone space is "blank" in Kotlin but
            // it IS the word-boundary signal we need to detect.
            if (text.isEmpty()) return

            MainHook.getContext()?.let { MainHook.ensureServiceBound(it) }

            val ic = param.thisObject as? android.view.inputmethod.InputConnection
            val hasWordContent = AUTO_CORRECT_TOKEN_HAS_CONTENT_REGEX.containsMatchIn(text)

            // Pure-boundary commit (space/punctuation only): Gboard commonly
            // commits the word and its trailing space as two separate calls.
            // Assign this text as the pending word's boundary and flush it now.
            if (!hasWordContent) {
                if (pendingCorrectionWord.isNotEmpty()) {
                    pendingCorrectionBoundary = text.toString()
                    flushPendingCorrection(ic)
                }
                return
            }

            // Normal word commit: flush any previously pending word first, then
            // process the current word.
            flushPendingCorrection(ic)
            enforceOutgoingText(param, allowAutocorrect = shouldAutocorrectNow(text))
        }
    }

    /**
     * Intercepts in-progress composing text (mid-composition, before commit).
     * We skip enforcement here to prevent instant mid-word rewrites.
     * Replacements are applied on commitText instead.
     */
    private val setComposingTextHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            return
        }
    }

    /**
     * Shared enforcement pipeline applied on [commitText].
     *
     * Order of operations:
     *  1. Vocabulary blocking -- if the text contains a restricted word it is
     *     replaced with [SAFE_PHRASE] and no further processing is done.
     *  2. fxtwitter link rewrite (Discord only).
     *  3. Deferred dictionary replacement -- the word is stored as pending;
     *     the actual replacement fires on the next word-boundary commit via
     *     [flushPendingCorrection].
     */
    private fun enforceOutgoingText(
        param: XC_MethodHook.MethodHookParam,
        allowAutocorrect: Boolean,
    ) {
        val originalText = param.args[0] as? CharSequence ?: return
        var workingText = originalText
        val textStr = workingText.toString()
        val packageName = MainHook.getProcessPackageName()
        val keyboardProcess = isKeyboardProcess(packageName)
        // Never enforce from keyboard/IME processes.
        if (keyboardProcess) return
        if (TextViewHook.isPackageExcludedFromTextReplacement(packageName)) return
        val policy = TextViewHook.currentPolicy()

        val vocabRegexes = currentVocabRegexes() ?: emptyList()
        val toneMode  = currentToneMode()
        val textLower = textStr.lowercase()

        // -- Bypass check (Soft mode only) ------------------------------------
        if (toneMode == MODE_LOOSE && bypassWindowOpen) {
            val redacted = lastRedactedWord
            if (redacted != null &&
                System.currentTimeMillis() - lastRedactTimestamp <= BYPASS_WINDOW_MS &&
                containsWholeWord(textLower, redacted, vocabRegexes)
            ) {
                sessionWhitelistWords.add(redacted)
                clearBypassState()
                Log.i(TAG, "Soft-mode override accepted for: $redacted")
                dispatchInfractionBroadcast(redacted)
                return
            }
            clearBypassState()
        }

        // -- Vocabulary blocking (highest priority) ---------------------------
        if (policy.enableInputRedaction && vocabRegexes.isNotEmpty()) {
            for ((word, regex) in vocabRegexes) {
                if (word.isBlank()) continue
                if (toneMode == MODE_LOOSE && sessionWhitelistWords.contains(word)) continue
                if (!regex.containsMatchIn(textLower)) continue

                val origText       = workingText
                val sanitizedText  = SAFE_PHRASE
                param.args[0]          = sanitizedText
                adjustCursorPosition(param, origText, sanitizedText)
                lastRedactedWord       = word
                lastRedactTimestamp    = System.currentTimeMillis()
                accumulatedDeleteCount = 0
                bypassWindowOpen       = false
                Log.i(TAG, "Restricted word '$word' redacted via InputConnection hook")
                dispatchToneBlockBroadcast(word)
                return
            }
        }

        // -- fxtwitter rewrite (Discord only) ---------------------------------
        val fxtwitterRewritten = rewriteTwitterLinksForDiscord(packageName, workingText)
        if (fxtwitterRewritten.toString() != workingText.toString()) {
            param.args[0] = fxtwitterRewritten
            adjustCursorPosition(param, workingText, fxtwitterRewritten)
            workingText = fxtwitterRewritten
        }

        // -- Deferred dictionary replacement ----------------------------------
        // Store this word as pending; the actual substitution fires when the
        // NEXT word-boundary commit arrives (via flushPendingCorrection).
        if (!ENABLE_INLINE_DEFERRED_AUTOCORRECT || !policy.enableInlineAutocorrect) return

        if (shouldAllowImmediateUndo(workingText.toString().trim())) return

        if (!allowAutocorrect) {
            // Word-only commit (no trailing boundary char): Gboard two-call
            // pattern. Store with empty boundary; the space commit that follows
            // will set pendingCorrectionBoundary and trigger the flush.
            val wordOnly = workingText.toString()
            if (wordOnly.isNotEmpty()) {
                pendingCorrectionWord = wordOnly
                pendingCorrectionBoundary = ""
            }
            return
        }

        // Commit contains both word and boundary (e.g. "hello ").
        val fullText = workingText.toString()
        val boundaryChar = fullText.takeLast(1)
        val wordOnly = fullText.dropLast(1)
        if (wordOnly.isNotEmpty()) {
            pendingCorrectionWord = wordOnly
            pendingCorrectionBoundary = boundaryChar
        }
    }

    /**
     * Applies the replacement for [pendingCorrectionWord] (committed during a
     * previous [commitText] call) by calling [deleteSurroundingText] and
     * [commitText] directly on [ic].
     *
     * This does NOT touch the current `param.args[0]`, so the in-flight commit
     * for the word the user is currently typing is unaffected.  The reentrancy
     * guard [flushingInProgress] prevents the direct IC calls from re-entering
     * the hook and looping.
     *
     * [applyGrammarPostProcessing] is intentionally false: grammar post-processing
     * (punctuation/capitalisation fixes) should not run over an isolated word
     * token; it would corrupt text that doesn't form a complete sentence.
     */
    private fun flushPendingCorrection(ic: android.view.inputmethod.InputConnection?) {
        val word = pendingCorrectionWord
        val boundary = pendingCorrectionBoundary
        if (word.isEmpty()) return

        // Clear pending state immediately so any re-entrant path sees a clean slate.
        pendingCorrectionWord = ""
        pendingCorrectionBoundary = ""

        if (ic == null) return

        // Avoid rewriting common short tokens (e.g., "in", "to") that are
        // frequently edited and prone to false-positive regex replacements.
        if (word.trim().length < MIN_AUTOCORRECT_TOKEN_LEN) return

        val packageName = MainHook.getProcessPackageName()
        if (isKeyboardProcess(packageName)) return
        if (TextViewHook.isPackageExcludedFromTextReplacement(packageName)) return

        val dict = TextViewHook.currentDict() ?: return
        if (dict.isEmpty()) return

        val policy   = TextViewHook.currentPolicy()
        if (!policy.enableInlineAutocorrect) return
        val toneMode = TextViewHook.currentToneMode()

        val replaced = TextViewHook.applyReplacements(
            text = word,
            dict = dict,
            toneMode = toneMode,
            packageName = packageName,
            policy = policy,
            applyGrammarPostProcessing = false,
        )

        val replacedStr = replaced.toString()
        if (replacedStr == word) return  // no change -- nothing to do

        // Delete "word + boundary" that is already in the field, then re-commit
        // the corrected version. The guard prevents the commitText call from
        // looping back through this hook.
        val deleteLen = word.length + boundary.length
        flushingInProgress = true
        try {
            if (!ic.deleteSurroundingText(deleteLen, 0)) {
                Log.w(TAG, "flushPendingCorrection: deleteSurroundingText returned false")
                return
            }
            ic.commitText(replacedStr + boundary, 1)
        } catch (e: Exception) {
            Log.w(TAG, "flushPendingCorrection: IC call failed", e)
        } finally {
            flushingInProgress = false
        }

        lastAutocorrectSource = word
        lastAutocorrectAtMs = System.currentTimeMillis()
        Log.d(TAG, "Deferred correction: '$word' -> '$replacedStr'")
    }

    private fun shouldAutocorrectNow(text: CharSequence): Boolean {
        val s = text.toString()
        if (s.isEmpty()) return false
        if (isLikelyUrlOrAddressToken(s)) return false
        // Keep contractions (e.g. "I'") -- avoid treating apostrophes as commit boundaries.
        if (s.endsWith('\'') || s.endsWith('\u2019')) return false

        val endsWithBoundary = AUTO_CORRECT_BOUNDARY_REGEX.matches(s.takeLast(1))
        if (!endsWithBoundary && !s.endsWith('\n') && !s.endsWith('\r')) return false

        return AUTO_CORRECT_TOKEN_HAS_CONTENT_REGEX.containsMatchIn(s)
    }

    private fun isLikelyUrlOrAddressToken(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        if (URLISH_TOKEN_REGEX.containsMatchIn(trimmed)) return true
        if (EMAILISH_TOKEN_REGEX.containsMatchIn(trimmed)) return true
        if (trimmed.contains("/")) return true
        if (trimmed.contains("\\")) return true
        return false
    }

    private fun shouldAllowImmediateUndo(inputToken: String): Boolean {
        if (inputToken.isEmpty()) return false
        val source = lastAutocorrectSource ?: return false
        val now = System.currentTimeMillis()
        if (now - lastAutocorrectAtMs > REPLACEMENT_UNDO_WINDOW_MS) {
            lastAutocorrectSource = null
            return false
        }
        if (!inputToken.equals(source, ignoreCase = true)) return false
        lastAutocorrectSource = null
        lastAutocorrectAtMs = 0L
        return true
    }

    private fun isKeyboardProcess(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (normalized == "com.google.android.inputmethod.latin") return true
        return normalized.contains("inputmethod") ||
            normalized.contains("keyboard") ||
            normalized.contains("ime")
    }

    private fun rewriteTwitterLinksForDiscord(packageName: String, text: CharSequence): CharSequence {
        if (!packageName.contains("discord", ignoreCase = true)) return text
        val input = text.toString()
        val rewritten = DISCORD_TWITTER_URL_REGEX.replace(input) { match ->
            val suffix = match.groupValues.getOrNull(1).orEmpty()
            "https://fxtwitter.com$suffix"
        }
        return if (rewritten == input) text else rewritten
    }

    /**
     * Adjusts the cursor position argument when replacement changes text length,
     * so IMEs like Gboard don't jump to extreme positions after sanitization.
     */
    private fun adjustCursorPosition(
        param: XC_MethodHook.MethodHookParam,
        originalText: CharSequence,
        sanitizedText: CharSequence
    ) {
        val originalLen = originalText.length
        val sanitizedLen = sanitizedText.length
        if (originalLen == sanitizedLen) return
        val currentCursor = param.args.getOrNull(1) as? Int ?: return

        val delta = sanitizedLen - originalLen
        val adjustedCursor = when {
            currentCursor > 0 -> (currentCursor + delta).coerceIn(1, sanitizedLen + 1)
            currentCursor < 0 -> (currentCursor - delta).coerceIn(-sanitizedLen, -1)
            else -> 0
        }
        if (adjustedCursor != currentCursor) {
            param.args[1] = adjustedCursor
        }
    }

    private val deleteSurroundingTextHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // Don't interfere with deletes that originate from our own flush.
            if (flushingInProgress) return

            val beforeLength = param.args[0] as? Int ?: return
            if (beforeLength <= 0) return

            val redacted = lastRedactedWord ?: return
            if (System.currentTimeMillis() - lastRedactTimestamp > BYPASS_WINDOW_MS) {
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

    // -- Vocabulary / mode cache management -----------------------------------

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
        cachedVocabRegexes = cachedVocab.mapNotNull { word ->
            runCatching {
                word to WORD_BOUNDARY_PATTERN.format(Regex.escape(word)).toRegex()
            }.getOrElse { e ->
                Log.w(TAG, "Failed to compile regex for word '$word' -- skipping", e)
                null
            }
        }
        return cachedVocabRegexes
    }

    /**
     * Returns the current tone mode (strict or loose), refreshing from the
     * service if the cache has expired.
     */
    private fun currentToneMode(): String {
        val now = System.currentTimeMillis()
        if (now - lastModeFetchMs < CACHE_TTL_MS) return cachedToneMode

        val service = MainHook.filterService ?: return cachedToneMode
        val raw = runCatching { service.getToneMode() }.getOrNull() ?: return cachedToneMode
        lastModeFetchMs = now
        cachedToneMode = normalizeToneMode(raw)
        return cachedToneMode
    }

    private fun normalizeToneMode(raw: String): String {
        return when (raw.trim().lowercase()) {
            "strict" -> MODE_STRICT
            "loose", "soft" -> MODE_LOOSE
            else -> {
                Log.w(TAG, "Unknown tone mode '$raw' -- defaulting to loose")
                MODE_LOOSE
            }
        }
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

    // -- Helpers --------------------------------------------------------------

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

    private fun dispatchToneBlockBroadcast(word: String) {
        val context = MainHook.getContext() ?: run {
            Log.w(TAG, "No context available -- tone_block broadcast skipped for: $word")
            return
        }
        val intent = Intent(XposedConstants.ACTION_XPOSED_TONE_BLOCK).apply {
            setPackage("com.hound.controller")
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

    private fun dispatchInfractionBroadcast(word: String) {
        val context = MainHook.getContext() ?: run {
            Log.w(TAG, "No context available -- infraction broadcast skipped for: $word")
            return
        }
        val intent = Intent(XposedConstants.ACTION_XPOSED_TONE_INFRACTION).apply {
            setPackage("com.hound.controller")
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