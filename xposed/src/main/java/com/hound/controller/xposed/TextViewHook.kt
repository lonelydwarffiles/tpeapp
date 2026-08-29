package com.hound.controller.xposed

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.Editable
import android.util.Log
import android.widget.EditText
import android.widget.TextView
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
 *  - The dictionary is fetched from [com.hound.controller.service.FilterService] via AIDL
 *    and cached with a 30-second TTL to minimise inter-process calls.
 *  - If the service is unavailable the original text is left untouched.
 */
object TextViewHook {

    private const val TAG             = "TPE_TextViewHook"
    private const val DICT_TTL_MS     = 30_000L   // re-fetch dict at most every 30 s
    private const val MODE_STRICT     = "strict"
    private const val MODE_LOOSE      = "loose"

    // URLs, emails, and backtick code spans should be left untouched.
    private val PROTECTED_URL_REGEX = Regex(
        pattern = """(?i)\b(?:https?://|www\.)\S+|\b[a-z][a-z0-9+.-]{1,20}://\S+"""
    )
    private val PROTECTED_BARE_DOMAIN_REGEX = Regex(
        pattern = """(?i)\b(?:[a-z0-9-]+\.)+(?:com|net|org|io|dev|app|gg|xyz|tv|me|co|ai|edu|gov|mil|biz|info|us|uk|ca|au|de|fr|jp|cn|ru|br|in)(?:/\S*)?\b"""
    )
    private val PROTECTED_EMAIL_REGEX = Regex(
        pattern = """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""
    )
    private val PROTECTED_CODE_BLOCK_REGEX = Regex(
        pattern = """(?s)```.+?```|`[^`\n]+`"""
    )
    private val MARKDOWN_LINK_REGEX = Regex(
        pattern = """\[[^]]+\]\([^)]+\)"""
    )
    private val FILE_PATH_REGEX = Regex(
        pattern = """(?i)\b(?:[a-z]:\\|/)?(?:[\w.-]+[\\/])+[\w.-]+\b"""
    )

    // ── Dict cache ────────────────────────────────────────────────────────────

    @Volatile private var cachedDictJson : String              = ""
    @Volatile private var cachedDict     : Map<Regex, String>  = emptyMap()
    @Volatile private var lastFetchMs    : Long                = 0L

    @Volatile private var cachedToneMode : String = MODE_LOOSE
    @Volatile private var lastModeFetchMs: Long   = 0L
    @Volatile private var cachedPolicyJson: String = ""
    @Volatile private var cachedPolicy: ReplacementPolicy = ReplacementPolicy()
    @Volatile private var lastPolicyFetchMs: Long = 0L

    private val SENSITIVE_PACKAGE_HINTS = listOf(
        "bank", "wallet", "payment", "pay", "finance", "auth", "password", "security"
    )
    private val BLOCKED_TEXT_REPLACEMENT_PACKAGES = setOf(
        // Google Search app
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.searchlite",
        // Gboard
        "com.google.android.inputmethod.latin",
        // Messages apps (Google + common OEM/system variants)
        "com.google.android.apps.messaging",
        "com.google.android.apps.messaging.go",
        "com.android.messaging",
        "com.samsung.android.messaging",
        "com.sonyericsson.conversations",
        "com.oneplus.mms"
    )

    // ── Install ───────────────────────────────────────────────────────────────

    fun install(loader: ClassLoader) {
        try {
            // Hook the lowest-level setText(CharSequence, BufferType, boolean, int).
            // All public setText overloads ultimately delegate here, so a single
            // hook at this level intercepts every text assignment — including
            // internal calls that bypass the public API — without double-processing.
            val bufferTypeClass = XposedHelpers.findClass(
                "android.widget.TextView\$BufferType", loader
            )
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView", loader,
                "setText",
                CharSequence::class.java, bufferTypeClass,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                setTextHook
            )
            Log.i(TAG, "TextView hook installed (4-param setText)")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to install TextView hook", e)
        }
    }

    // ── Hook ──────────────────────────────────────────────────────────────────

    private val setTextHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val original = param.args[0] as? CharSequence ?: return
            val view = param.thisObject as? TextView ?: return
            if (view is EditText || view.onCheckIsTextEditor()) return
            val bufferType = param.args.getOrNull(1)?.toString()?.uppercase().orEmpty()
            if (bufferType.contains("EDITABLE")) return
            val currentText = runCatching { view.text }.getOrNull()
            if (currentText is Editable) return
            val packageName = MainHook.getProcessPackageName()
            val policy = currentPolicy()
            if (!policy.enableOnScreenReplacement) return
            if (isPackageBlockedForTextReplacement(packageName, policy)) return

            val dict = currentDict() ?: return
            if (dict.isEmpty()) return

            val toneMode = currentToneMode()
            val modified = applyReplacements(
                text = original,
                dict = dict,
                toneMode = toneMode,
                packageName = packageName,
                policy = policy,
            )
            if (modified !== original) {
                param.args[0] = modified
            }
        }
    }

    // ── Dictionary management ─────────────────────────────────────────────────

    /**
     * Returns the current dictionary, refreshing from the service if the cache
     * has expired.  Returns `null` when the service is unavailable.
     * Internal so [InputConnectionHook] can share the same cached instance.
     */
    internal fun currentDict(): Map<Regex, String>? {
        val now = System.currentTimeMillis()
        if (now - lastFetchMs < DICT_TTL_MS) return cachedDict

        val service = MainHook.filterService ?: return null

        val json = runCatching<String> { service.getTextReplacementDict() }.getOrNull() ?: return null
        lastFetchMs = now

        if (json == cachedDictJson) return cachedDict

        cachedDictJson = json
        cachedDict     = parseDict(json)
        return cachedDict
    }

    private fun parseDict(json: String): Map<Regex, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching<Map<Regex, String>> {
            val obj = JSONObject(json)
            val map = LinkedHashMap<Regex, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val pattern = keys.next()
                val replacement = obj.getString(pattern)
                try {
                    map[Regex(pattern)] = replacement
                } catch (e: java.util.regex.PatternSyntaxException) {
                    Log.w(TAG, "Skipping invalid regex pattern \"$pattern\": ${e.message}")
                }
            }
            map as Map<Regex, String>
        }.getOrElse { e ->
            Log.w(TAG, "Failed to parse text-replacement dict", e)
            emptyMap()
        }
    }

    internal fun currentPolicy(): ReplacementPolicy {
        val now = System.currentTimeMillis()
        if (now - lastPolicyFetchMs < DICT_TTL_MS) return cachedPolicy

        val service = MainHook.filterService ?: return cachedPolicy
        val json = runCatching<String> { service.getTextReplacementPolicy() }.getOrNull() ?: return cachedPolicy
        lastPolicyFetchMs = now

        if (json == cachedPolicyJson) return cachedPolicy
        cachedPolicyJson = json
        cachedPolicy = parsePolicy(json)
        return cachedPolicy
    }

    private fun parsePolicy(json: String): ReplacementPolicy {
        if (json.isBlank()) return ReplacementPolicy()
        return runCatching<ReplacementPolicy> {
            val obj = JSONObject(json)
            val defaultMode = parsePolicyMode(obj.optString("default_mode", "loose"))

            val packageModes = HashMap<String, PolicyMode>()
            obj.optJSONObject("packages")?.let { packages ->
                val keys = packages.keys()
                while (keys.hasNext()) {
                    val pkg = keys.next().trim().lowercase()
                    if (pkg.isEmpty()) continue
                    packageModes[pkg] = parsePolicyMode(packages.optString(pkg, "loose"))
                }
            }

            val prefixModes = LinkedHashMap<String, PolicyMode>()
            obj.optJSONObject("package_prefixes")?.let { prefixes ->
                val keys = prefixes.keys()
                while (keys.hasNext()) {
                    val prefix = keys.next().trim().lowercase()
                    if (prefix.isEmpty()) continue
                    prefixModes[prefix] = parsePolicyMode(prefixes.optString(prefix, "loose"))
                }
            }

            val neverReplaceWords = linkedSetOf("no", "it")
            obj.optJSONArray("never_replace_words")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val token = arr.optString(i).trim().lowercase()
                    if (token.isNotEmpty()) neverReplaceWords.add(token)
                }
            }

            val blockedPackages = linkedSetOf<String>()
            obj.optJSONArray("blocked_packages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val token = arr.optString(i).trim().lowercase()
                    if (token.isNotEmpty()) blockedPackages.add(token)
                }
            }
            obj.optJSONArray("app_blocklist")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val token = arr.optString(i).trim().lowercase()
                    if (token.isNotEmpty()) blockedPackages.add(token)
                }
            }

            val blockedPackagePrefixes = linkedSetOf<String>()
            obj.optJSONArray("blocked_package_prefixes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val token = arr.optString(i).trim().lowercase()
                    if (token.isNotEmpty()) blockedPackagePrefixes.add(token)
                }
            }
            obj.optJSONArray("app_blocklist_prefixes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val token = arr.optString(i).trim().lowercase()
                    if (token.isNotEmpty()) blockedPackagePrefixes.add(token)
                }
            }

            val enableInputRedaction = parseBooleanFlag(
                obj,
                listOf("enable_input_redaction", "input_redaction_enabled"),
                true,
            )
            val enableInlineAutocorrect = parseBooleanFlag(
                obj,
                listOf("enable_inline_autocorrect", "inline_autocorrect_enabled"),
                true,
            )
            val enableOnScreenReplacement = parseBooleanFlag(
                obj,
                listOf(
                    "enable_onscreen_replacement",
                    "onscreen_replacement_enabled",
                    "display_replacement_enabled",
                ),
                true,
            )

            ReplacementPolicy(
                defaultMode = defaultMode,
                packageModes = packageModes,
                packagePrefixModes = prefixModes,
                neverReplaceWords = neverReplaceWords,
                blockedPackages = blockedPackages,
                blockedPackagePrefixes = blockedPackagePrefixes,
                enableInputRedaction = enableInputRedaction,
                enableInlineAutocorrect = enableInlineAutocorrect,
                enableOnScreenReplacement = enableOnScreenReplacement,
            )
        }.getOrElse { e ->
            Log.w(TAG, "Failed to parse text-replacement policy JSON", e)
            ReplacementPolicy()
        }
    }

    private fun parseBooleanFlag(obj: JSONObject, keys: List<String>, defaultValue: Boolean): Boolean {
        for (key in keys) {
            if (!obj.has(key)) continue
            val raw = obj.opt(key)
            when (raw) {
                is Boolean -> return raw
                is Number -> return raw.toInt() != 0
                is String -> {
                    when (raw.trim().lowercase()) {
                        "true", "1", "yes", "on", "enabled" -> return true
                        "false", "0", "no", "off", "disabled" -> return false
                    }
                }
            }
        }
        return defaultValue
    }

    // ── Replacement with span preservation ───────────────────────────────────

    /**
     * Applies all [dict] replacements to [text], preserving all [Span] objects
     * when [text] is a [Spanned].
     *
     * Returns the original [text] reference unchanged if no pattern matches.
     * Internal so [InputConnectionHook] can apply the same engine to outgoing text.
     */
    internal fun applyReplacements(
        text: CharSequence,
        dict: Map<Regex, String>,
        toneMode: String,
        packageName: String,
        policy: ReplacementPolicy,
        applyGrammarPostProcessing: Boolean = false,
    ): CharSequence {
        val isSpanned = text is Spanned
        val context = buildContext(text.toString(), packageName, policy)

        return if (isSpanned) {
            val ssb = SpannableStringBuilder(text)  // deep-copies all spans
            var changed = false
            for ((regex, replacement) in dict) {
                val ruleClass = classifyRule(regex.pattern, replacement)
                if (!shouldApplyRule(ruleClass, context, toneMode)) continue
                val protectedRanges = protectedRanges(ssb.toString())
                if (applyRegexToSpannable(ssb, regex, replacement, protectedRanges, policy.neverReplaceWords)) {
                    changed = true
                }
            }
            if (changed) ssb else text
        } else {
            var result: String = text.toString()
            var changed = false
            for ((regex, replacement) in dict) {
                val ruleClass = classifyRule(regex.pattern, replacement)
                if (!shouldApplyRule(ruleClass, context, toneMode)) continue
                val protectedRanges = protectedRanges(result)
                val next = applyRegexToPlainText(result, regex, replacement, protectedRanges, policy.neverReplaceWords)
                if (next != result) {
                    result = next
                    changed = true
                }
            }
            if (changed && applyGrammarPostProcessing) {
                result = postProcessGrammar(result)
            }
            if (result == text.toString()) text else result
        }
    }

    internal fun isPackageExcludedFromTextReplacement(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        if (normalized.isEmpty()) return false
        return BLOCKED_TEXT_REPLACEMENT_PACKAGES.contains(normalized)
    }

    internal fun isPackageBlockedForTextReplacement(
        packageName: String,
        policy: ReplacementPolicy,
    ): Boolean {
        val normalized = packageName.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (isPackageExcludedFromTextReplacement(normalized)) return true
        return policy.isBlocked(normalized)
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
        replacement: String,
        protectedRanges: List<IntRange>,
        neverReplaceWords: Set<String>
    ): Boolean {
        val matches = regex.findAll(ssb)
            .filterNot { overlapsProtected(it.range, protectedRanges) }
            .filterNot { shouldKeepLiteralWord(it.value, neverReplaceWords) }
            .toList()
        if (matches.isEmpty()) return false

        for (match in matches.reversed()) {
            val start  = match.range.first
            val end    = match.range.last + 1
            val newStr = expandReplacement(match, replacement)

            // Snapshot only spans fully enclosed within [start, end): these are
            // the ones SpannableStringBuilder.replace() will remove.  Spans that
            // straddle the boundaries are auto-adjusted by SSB and don't need
            // special handling.
            val enclosed = ssb.getSpans(start, end, Any::class.java)
                .filter { span -> ssb.getSpanStart(span) >= start && ssb.getSpanEnd(span) <= end }
                .map { span ->
                    SpanRecord(span, ssb.getSpanStart(span), ssb.getSpanEnd(span), ssb.getSpanFlags(span))
                }

            ssb.replace(start, end, newStr)

            val newEnd = start + newStr.length

            // Re-apply the enclosed spans across the replacement range.
            for ((span, _, _, flags) in enclosed) {
                ssb.setSpan(span, start, newEnd, flags)
            }
        }
        return true
    }

    private fun applyRegexToPlainText(
        input: String,
        regex: Regex,
        replacement: String,
        protectedRanges: List<IntRange>,
        neverReplaceWords: Set<String>
    ): String {
        val matches = regex.findAll(input)
            .filterNot { overlapsProtected(it.range, protectedRanges) }
            .filterNot { shouldKeepLiteralWord(it.value, neverReplaceWords) }
            .toList()
        if (matches.isEmpty()) return input

        val out = StringBuilder(input.length + 16)
        var cursor = 0
        for (match in matches) {
            val start = match.range.first
            val end = match.range.last + 1
            if (start < cursor) continue
            out.append(input, cursor, start)
            out.append(expandReplacement(match, replacement))
            cursor = end
        }
        out.append(input, cursor, input.length)
        return out.toString()
    }

    private fun protectedRanges(text: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        fun collect(regex: Regex) {
            regex.findAll(text).forEach { ranges.add(it.range) }
        }
        collect(PROTECTED_URL_REGEX)
        collect(PROTECTED_BARE_DOMAIN_REGEX)
        collect(PROTECTED_EMAIL_REGEX)
        collect(PROTECTED_CODE_BLOCK_REGEX)
        collect(MARKDOWN_LINK_REGEX)
        collect(FILE_PATH_REGEX)
        return ranges
    }

    private fun overlapsProtected(range: IntRange, protectedRanges: List<IntRange>): Boolean {
        return protectedRanges.any { protected ->
            range.first <= protected.last && protected.first <= range.last
        }
    }

    private fun shouldKeepLiteralWord(matchedText: String, neverReplaceWords: Set<String>): Boolean {
        if (neverReplaceWords.isEmpty()) return false
        val token = matchedText.trim().lowercase()
        if (token.isEmpty()) return false
        return token in neverReplaceWords
    }

    internal fun currentToneMode(): String {
        val now = System.currentTimeMillis()
        if (now - lastModeFetchMs < DICT_TTL_MS) return cachedToneMode

        val service = MainHook.filterService ?: return cachedToneMode
        val raw = runCatching<String> { service.getToneMode() }.getOrNull() ?: return cachedToneMode
        lastModeFetchMs = now
        cachedToneMode = when (raw.trim().lowercase()) {
            "strict" -> MODE_STRICT
            "soft", "loose" -> MODE_LOOSE
            else -> MODE_LOOSE
        }
        return cachedToneMode
    }

    private fun buildContext(
        text: String,
        packageName: String,
        policy: ReplacementPolicy,
    ): ReplacementContext {
        val normalizedPackage = packageName.lowercase()
        val isSensitivePackage = SENSITIVE_PACKAGE_HINTS.any { it in normalizedPackage }
        val packageMode = policy.modeFor(normalizedPackage)
        val symbolCount = text.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        val symbolRatio = if (text.isNotEmpty()) symbolCount.toDouble() / text.length else 0.0
        val hasStructuredMarkers =
            text.contains("=") || text.contains(":") || text.contains("{") || text.contains("}") ||
                text.contains("[") || text.contains("]") || text.contains("@") || text.contains("#")
        val looksStructured = symbolRatio >= 0.18 || hasStructuredMarkers

        return ReplacementContext(
            isSensitivePackage = isSensitivePackage,
            looksStructured = looksStructured,
            packageMode = packageMode,
        )
    }

    private fun classifyRule(pattern: String, replacement: String): RuleClass {
        val p = pattern.lowercase()
        val r = replacement.lowercase()
        val identityMarkers = listOf("\\bi\\b", "\\bme\\b", "\\bmy\\b", "\\bmine\\b", "\\bmyself\\b")
        if (identityMarkers.any { it in p } ||
            listOf("this mutt", "it", "its", "itself", "mutt").any { it in r }) {
            return RuleClass.IDENTITY
        }
        if (listOf("paw", "yip", "woof", "arf", "tail", "mutt").any { it in r }) {
            return RuleClass.PLAYFUL
        }
        return RuleClass.GENERAL
    }

    private fun shouldApplyRule(
        rule: RuleClass,
        context: ReplacementContext,
        toneMode: String
    ): Boolean {
        when (context.packageMode) {
            PolicyMode.OFF -> return false
            PolicyMode.IDENTITY_ONLY -> return rule == RuleClass.IDENTITY
            PolicyMode.FULL -> return true
            PolicyMode.AUTO -> Unit
        }

        if (context.isSensitivePackage) {
            // In sensitive apps keep only identity-safe rewrites.
            return rule == RuleClass.IDENTITY
        }

        if (context.looksStructured && rule == RuleClass.PLAYFUL) {
            return false
        }

        if (toneMode == MODE_LOOSE && rule == RuleClass.PLAYFUL && context.looksStructured) {
            return false
        }

        return true
    }

    private fun parsePolicyMode(raw: String): PolicyMode {
        return when (raw.trim().lowercase()) {
            "off", "none", "disabled" -> PolicyMode.OFF
            "identity", "identity_only", "identity-only" -> PolicyMode.IDENTITY_ONLY
            "full", "all" -> PolicyMode.FULL
            "loose", "soft", "auto" -> PolicyMode.AUTO
            else -> PolicyMode.AUTO
        }
    }

    private fun postProcessGrammar(input: String): String {
        var out = input

        // Keep only tone-enforcement specific corrections.
        out = Regex("""\bthis mutt are\b""", RegexOption.IGNORE_CASE).replace(out, "this mutt is")
        out = Regex("""\bit are\b""", RegexOption.IGNORE_CASE).replace(out, "it is")
        out = Regex("""\bit\s+is\s+is\b""", RegexOption.IGNORE_CASE).replace(out, "it is")
        out = Regex("""\bits are\b""", RegexOption.IGNORE_CASE).replace(out, "it is")
        return out
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
                if (groupIndex >= match.groupValues.size) {
                    Log.w(TAG, "Replacement references group \$$groupIndex but pattern only has ${match.groupValues.size - 1} group(s)")
                }
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

    internal data class ReplacementContext(
        val isSensitivePackage: Boolean,
        val looksStructured: Boolean,
        val packageMode: PolicyMode,
    )

    internal data class ReplacementPolicy(
        val defaultMode: PolicyMode = PolicyMode.AUTO,
        val packageModes: Map<String, PolicyMode> = emptyMap(),
        val packagePrefixModes: Map<String, PolicyMode> = emptyMap(),
        val neverReplaceWords: Set<String> = linkedSetOf("no", "it"),
        val blockedPackages: Set<String> = emptySet(),
        val blockedPackagePrefixes: Set<String> = emptySet(),
        val enableInputRedaction: Boolean = true,
        val enableInlineAutocorrect: Boolean = true,
        val enableOnScreenReplacement: Boolean = true,
    ) {
        fun isBlocked(packageName: String): Boolean {
            if (blockedPackages.contains(packageName)) return true
            for (prefix in blockedPackagePrefixes) {
                if (packageName.startsWith(prefix)) return true
            }
            return false
        }

        fun modeFor(packageName: String): PolicyMode {
            if (isBlocked(packageName)) return PolicyMode.OFF
            packageModes[packageName]?.let { return it }
            for ((prefix, mode) in packagePrefixModes) {
                if (packageName.startsWith(prefix)) return mode
            }
            if (packageName.contains("inputmethod") || packageName.contains("keyboard")) {
                return PolicyMode.FULL
            }
            return defaultMode
        }
    }

    internal enum class PolicyMode {
        AUTO,
        FULL,
        IDENTITY_ONLY,
        OFF,
    }

    internal enum class RuleClass {
        IDENTITY,
        PLAYFUL,
        GENERAL,
    }
}

