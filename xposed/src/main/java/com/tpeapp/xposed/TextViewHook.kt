package com.tpeapp.xposed

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject

/**
 * Hooks [android.widget.TextView.setText] to perform system-wide, stealth
 * text replacement driven by a Regex dictionary stored in the TPE app.
 *
 * **Design goals**
 *  - All original [android.text.Span] objects (colors, styles, click listeners, …)
 *    are preserved and remapped to the new string indices so the replacement is
 *    visually indistinguishable from native UI.
 *  - The dictionary is fetched from [com.tpeapp.service.FilterService] via AIDL
 *    and cached with a 30-second TTL to minimise inter-process calls.
 *  - If the service is unavailable the original text is left untouched.
 */
object TextViewHook {

    private const val TAG             = "TPE_TextViewHook"
    private const val DICT_TTL_MS     = 30_000L   // re-fetch dict at most every 30 s

    // ── Dict cache ────────────────────────────────────────────────────────────

    @Volatile private var cachedDictJson : String              = ""
    @Volatile private var cachedDict     : Map<Regex, String>  = emptyMap()
    @Volatile private var lastFetchMs    : Long                = 0L

    // ── Install ───────────────────────────────────────────────────────────────

    fun install(loader: ClassLoader) {
        try {
            // Hook setText(CharSequence, BufferType).
            // setText(CharSequence) delegates to this overload internally, so
            // hooking only here avoids double-processing the same text.
            val bufferTypeClass = XposedHelpers.findClass(
                "android.widget.TextView\$BufferType", loader
            )
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView", loader,
                "setText", CharSequence::class.java, bufferTypeClass,
                setTextHook
            )
            Log.i(TAG, "TextView hook installed")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to install TextView hook", e)
        }
    }

    // ── Hook ──────────────────────────────────────────────────────────────────

    private val setTextHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val original = param.args[0] as? CharSequence ?: return

            val dict = currentDict() ?: return
            if (dict.isEmpty()) return

            val modified = applyReplacements(original, dict)
            if (modified !== original) {
                param.args[0] = modified
            }
        }
    }

    // ── Dictionary management ─────────────────────────────────────────────────

    /**
     * Returns the current dictionary, refreshing from the service if the cache
     * has expired.  Returns `null` when the service is unavailable.
     */
    private fun currentDict(): Map<Regex, String>? {
        val now = System.currentTimeMillis()
        if (now - lastFetchMs < DICT_TTL_MS) return cachedDict

        val service = MainHook.filterService ?: return null

        val json = runCatching { service.getTextReplacementDict() }.getOrNull() ?: return null
        lastFetchMs = now

        if (json == cachedDictJson) return cachedDict

        cachedDictJson = json
        cachedDict     = parseDict(json)
        return cachedDict
    }

    private fun parseDict(json: String): Map<Regex, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val map = LinkedHashMap<Regex, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val pattern = keys.next()
                val replacement = obj.getString(pattern)
                map[Regex(pattern)] = replacement
            }
            map as Map<Regex, String>
        }.getOrElse { e ->
            Log.w(TAG, "Failed to parse text-replacement dict", e)
            emptyMap()
        }
    }

    // ── Replacement with span preservation ───────────────────────────────────

    /**
     * Applies all [dict] replacements to [text], preserving all [Span] objects
     * when [text] is a [Spanned].
     *
     * Returns the original [text] reference unchanged if no pattern matches.
     */
    private fun applyReplacements(
        text: CharSequence,
        dict: Map<Regex, String>
    ): CharSequence {
        val isSpanned = text is Spanned

        return if (isSpanned) {
            val ssb = SpannableStringBuilder(text)  // deep-copies all spans
            var changed = false
            for ((regex, replacement) in dict) {
                if (applyRegexToSpannable(ssb, regex, replacement)) changed = true
            }
            if (changed) ssb else text
        } else {
            var result: String = text.toString()
            for ((regex, replacement) in dict) {
                val next = regex.replace(result) { expandReplacement(it, replacement) }
                if (next !== result) result = next
            }
            if (result == text.toString()) text else result
        }
    }

    /**
     * Applies a single [regex] replacement to [ssb] in-place.
     *
     * Spans that are entirely inside a matched region are collected before the
     * replacement and re-applied to cover the full replacement range afterwards,
     * so no formatting is ever lost.  [SpannableStringBuilder.replace] already
     * handles spans that straddle the replaced boundaries automatically.
     *
     * Matches are processed from the end of the string to the beginning so
     * earlier indices remain valid after each in-place edit.
     *
     * @return `true` if at least one replacement was made.
     */
    private fun applyRegexToSpannable(
        ssb: SpannableStringBuilder,
        regex: Regex,
        replacement: String
    ): Boolean {
        val matches = regex.findAll(ssb).toList()
        if (matches.isEmpty()) return false

        for (match in matches.reversed()) {
            val start  = match.range.first
            val end    = match.range.last + 1
            val newStr = expandReplacement(match, replacement)

            // Snapshot spans that overlap [start, end) before the replacement.
            val overlapping = ssb.getSpans(start, end, Any::class.java).map { span ->
                SpanRecord(span, ssb.getSpanStart(span), ssb.getSpanEnd(span), ssb.getSpanFlags(span))
            }

            ssb.replace(start, end, newStr)

            val newEnd = start + newStr.length

            // Re-apply spans that SpannableStringBuilder removed (those that
            // were fully enclosed within the replaced region).
            for ((span, _, _, flags) in overlapping) {
                if (ssb.getSpanStart(span) == -1) {
                    ssb.setSpan(span, start, newEnd, flags)
                }
            }
        }
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Expands `$1`, `$2`, … references in [template] using the capture groups
     * of [match].  Multi-digit group indices (e.g. `$10`) are handled correctly
     * by consuming all consecutive digit characters after the `$`.
     */
    private fun expandReplacement(match: MatchResult, template: String): String {
        if ('$' !in template) return template
        val sb = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '$' && i + 1 < template.length && template[i + 1].isDigit()) {
                var j = i + 1
                while (j < template.length && template[j].isDigit()) j++
                val groupIndex = template.substring(i + 1, j).toInt()
                sb.append(match.groupValues.getOrElse(groupIndex) { "" })
                i = j
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private data class SpanRecord(
        val span  : Any,
        val start : Int,
        val end   : Int,
        val flags : Int
    )
}
