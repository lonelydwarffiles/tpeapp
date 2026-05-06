package com.tpeapp.xposed

import android.content.Intent
import android.util.Log
import com.tpeapp.mindful.XposedToneReceiver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray

/**
 * Hooks [android.view.inputmethod.InputConnectionWrapper] to enforce the
 * restricted-vocabulary ruleset managed by the Accountability Partner.
 *
 * ### Enforcement
 * [commitText] is intercepted before text reaches the target app.  If the
 * committed text contains any restricted word (whole-word, case-insensitive),
 * the [CharSequence] argument is replaced with [SAFE_PHRASE] ("[Redacted]")
 * so the target app never sees the original word.
 *
 * ### Soft-mode bypass
 * When the partner has selected "Soft" tone mode the user may override a single
 * redaction:
 *  1. The user deletes the [SAFE_PHRASE] text — detected by hooking
 *     [deleteSurroundingText] with a before-length equal to [SAFE_PHRASE_LEN].
 *  2. Within [BYPASS_WINDOW_MS] the user re-types the exact restricted word.
 *  3. The hook allows the text through unmodified and fires an explicit broadcast
 *     Intent ([ACTION_XPOSED_TONE_INFRACTION]) to the main TPE app, which
 *     executes the punishment webhook.  The module never makes direct network
 *     calls.
 *
 * In "Strict" mode no bypass is possible — [SAFE_PHRASE] is applied every time.
 *
 * ### IPC
 * The vocabulary and tone mode are fetched from [com.tpeapp.service.FilterService]
 * via the [com.tpeapp.filter.IFilterService] AIDL interface and cached for
 * [CACHE_TTL_MS] to minimise inter-process round-trips.
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

    @Volatile private var cachedVocabJson : String       = ""
    @Volatile private var cachedVocab     : List<String> = emptyList()
    @Volatile private var lastVocabFetchMs: Long         = 0L

    // ── Tone-mode cache ───────────────────────────────────────────────────────

    @Volatile private var cachedToneMode    : String = "Soft"
    @Volatile private var lastModeFetchMs   : Long   = 0L

    // ── Soft-mode bypass state ────────────────────────────────────────────────

    /** The restricted word most recently replaced with [SAFE_PHRASE]. */
    @Volatile private var lastRedactedWord      : String? = null

    /** Epoch-ms timestamp of the last redaction. */
    @Volatile private var lastRedactTimestamp   : Long    = 0L

    /**
     * `true` once [deleteSurroundingText] removes exactly [SAFE_PHRASE_LEN]
     * characters after a fresh redaction — indicates the user is attempting
     * the bypass and the next matching [commitText] should be allowed through.
     */
    @Volatile private var bypassWindowOpen : Boolean = false

    // ── Install ───────────────────────────────────────────────────────────────

    fun install(loader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.inputmethod.InputConnectionWrapper", loader,
                "commitText", CharSequence::class.java, Int::class.java,
                commitTextHook
            )
            XposedHelpers.findAndHookMethod(
                "android.view.inputmethod.InputConnectionWrapper", loader,
                "deleteSurroundingText", Int::class.java, Int::class.java,
                deleteSurroundingTextHook
            )
            Log.i(TAG, "InputConnection hooks installed")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to install InputConnection hooks", e)
        }
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    private val commitTextHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val text    = param.args[0] as? CharSequence ?: return
            val textStr = text.toString()
            if (textStr.isBlank()) return

            val vocab = currentVocab() ?: return
            if (vocab.isEmpty()) return

            val toneMode  = currentToneMode()
            val textLower = textStr.lowercase()

            // ── Bypass check (Soft mode only) ─────────────────────────────────
            if (toneMode == "Soft" && bypassWindowOpen) {
                val redacted = lastRedactedWord
                if (redacted != null &&
                    System.currentTimeMillis() - lastRedactTimestamp <= BYPASS_WINDOW_MS &&
                    containsWholeWord(textLower, redacted)
                ) {
                    // User successfully re-typed the restricted word within the
                    // grace window — allow the commit and fire the infraction event.
                    clearBypassState()
                    Log.i(TAG, "Soft-mode override accepted for: $redacted")
                    dispatchInfractionBroadcast(redacted)
                    return   // let commitText pass unmodified
                }
                // Window expired or wrong word — close the bypass
                clearBypassState()
            }

            // ── Enforcement pass ──────────────────────────────────────────────
            for (word in vocab) {
                if (word.isBlank()) continue
                if (!containsWholeWord(textLower, word)) continue

                param.args[0]      = SAFE_PHRASE
                lastRedactedWord   = word
                lastRedactTimestamp = System.currentTimeMillis()
                bypassWindowOpen   = false
                Log.i(TAG, "Restricted word '$word' redacted via InputConnection hook")
                return
            }
        }
    }

    private val deleteSurroundingTextHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val beforeLength = param.args[0] as? Int ?: return

            // Detect when the user deletes exactly the SAFE_PHRASE that was
            // just committed, within the active bypass window.
            if (lastRedactedWord != null &&
                beforeLength == SAFE_PHRASE_LEN &&
                System.currentTimeMillis() - lastRedactTimestamp <= BYPASS_WINDOW_MS
            ) {
                bypassWindowOpen = true
                Log.d(TAG, "Soft-mode bypass window opened")
            }
        }
    }

    // ── Vocabulary / mode cache management ───────────────────────────────────

    /**
     * Returns the current restricted-vocabulary list, refreshing from the
     * service if the cache has expired.  Returns `null` when the service is
     * unavailable.
     */
    private fun currentVocab(): List<String>? {
        val now = System.currentTimeMillis()
        if (now - lastVocabFetchMs < CACHE_TTL_MS) return cachedVocab

        val service = MainHook.filterService ?: return null
        val json = runCatching { service.getRestrictedVocabulary() }.getOrNull() ?: return null
        lastVocabFetchMs = now

        if (json == cachedVocabJson) return cachedVocab
        cachedVocabJson = json
        cachedVocab     = parseVocabulary(json)
        return cachedVocab
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

    private fun containsWholeWord(text: String, word: String): Boolean =
        WORD_BOUNDARY_PATTERN.format(Regex.escape(word)).toRegex().containsMatchIn(text)

    private fun clearBypassState() {
        bypassWindowOpen  = false
        lastRedactedWord  = null
        lastRedactTimestamp = 0L
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
