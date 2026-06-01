package com.hound.controller.xposed

/**
 * Super-light local grammar enhancer used after regex replacements.
 *
 * This stays intentionally conservative: no network, no heavy model, and no
 * deep rewrites. It only applies low-risk fixes that improve readability.
 */
object LightweightTextNlp {

    private data class Rule(val pattern: Regex, val replacement: String)
    private data class ProtectedSegment(val token: String, val original: String)

    // Protect content that should never be grammar-mutated.
    private val protectedRegexes = listOf(
        // URLs and URI schemes.
        Regex("""(?i)\b(?:https?://|www\.)\S+|\b[a-z][a-z0-9+.-]{1,20}://\S+"""),
        // Emails.
        Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""),
        // Markdown code spans and fenced blocks.
        Regex("""(?s)```.+?```|`[^`\n]+`"""),
        // Markdown links [text](url).
        Regex("""\[[^]]+\]\([^)]+\)"""),
        // Discord mentions/channels/roles and emoji IDs.
        Regex("""<@!?\d+>|<#\d+>|<@&\d+>|<a?:\w+:\d+>"""),
        // File-ish paths.
        Regex("""(?i)\b(?:[a-z]:\\|/)?(?:[\w.-]+[\\/])+[\w.-]+\b"""),
    )

    private val simpleRules = listOf(
        Rule(Regex("""\b[Ii]\s+are\b"""), "I am"),
        Rule(Regex("""\b[Yy]ou\s+is\b"""), "you are"),
        Rule(Regex("""\b[Ww]e\s+is\b"""), "we are"),
        Rule(Regex("""\b[Tt]hey\s+is\b"""), "they are"),
        Rule(Regex("""\b([Hh]e|[Ss]he|[Ii]t)\s+are\b"""), "$1 is"),
        Rule(Regex("""\b([Hh]e|[Ss]he|[Ii]t)\s+were\b"""), "$1 was"),
        Rule(Regex("""\b([Hh]e|[Ss]he|[Ii]t)\s+have\b"""), "$1 has"),
        Rule(Regex("""\b([Tt]his|[Tt]hat)\s+are\b"""), "$1 is"),
        Rule(Regex("""\b([Tt]his|[Tt]hat)\s+were\b"""), "$1 was"),
        Rule(Regex("""\b([Tt]his|[Tt]hat)\s+have\b"""), "$1 has"),
        Rule(Regex("""\b([Tt]hese|[Tt]hose)\s+is\b"""), "$1 are"),
        Rule(Regex("""\b([Tt]hese|[Tt]hose)\s+was\b"""), "$1 were"),
        Rule(Regex("""\b([Tt]hese|[Tt]hose)\s+has\b"""), "$1 have"),

        Rule(Regex("""\b[Yy]ou\s+was\b"""), "you were"),
        Rule(Regex("""\b[Yy]ou\s+has\b"""), "you have"),
        Rule(Regex("""\b[Ww]e\s+was\b"""), "we were"),
        Rule(Regex("""\b[Ww]e\s+has\b"""), "we have"),
        Rule(Regex("""\b[Tt]hey\s+was\b"""), "they were"),
        Rule(Regex("""\b[Tt]hey\s+has\b"""), "they have"),

        // Common contractions / typos.
        Rule(Regex("""\b[Dd]ont\b"""), "don't"),
        Rule(Regex("""\b[Cc]ant\b"""), "can't"),
        Rule(Regex("""\b[Ww]ont\b"""), "won't"),
        Rule(Regex("""\b[Ii]m\b"""), "I'm"),
        Rule(Regex("""\b[Ii]ve\b"""), "I've"),
        Rule(Regex("""\b[Yy]oure\b"""), "you're"),
        Rule(Regex("""\b[Ww]e\s+re\b"""), "we're"),

        // Remove adjacent duplicate words: "the the" -> "the"
        Rule(Regex("""\b(\w+)\s+\1\b""", RegexOption.IGNORE_CASE), "$1"),
    )

    private val irregularPluralNouns = setOf(
        "children", "people", "men", "women", "mice", "geese", "feet", "teeth", "criteria", "phenomena"
    )

    private val uncountableSingularNouns = setOf(
        "information", "advice", "furniture", "equipment", "evidence", "traffic", "baggage",
        "luggage", "music", "homework", "progress", "research", "news", "content"
    )

    // Lightweight built-in regression fixtures for quick smoke validation
    // after rule changes. This intentionally covers persona rewrites and
    // grammar oddballs that previously regressed.
    private val regressionCases: List<Pair<String, String>> = listOf(
        "this mutt are ready" to "this mutt is ready",
        "puppy have tasks" to "puppy has tasks",
        "it don't know" to "it doesn't know",
        "its are messy" to "it is messy",
        "two tasks is done" to "two tasks are done",
        "there is many reasons" to "there are many reasons",
        "there are information" to "there is information",
        "children is loud" to "children are loud",
        "news are bad" to "news is bad",
        "a apple" to "an apple",
        "an user" to "a user",
        "a hour" to "an hour",
        "dont do this" to "don't do this",
        "Ive done it" to "I've done it",
        "Check https://x.com/test and dont touch it" to "Check https://x.com/test and don't touch it",
        "Message <@123456789> dont break" to "Message <@123456789> don't break",
        "Path C:/Users/test/file.txt dont mutate path" to "Path C:/Users/test/file.txt don't mutate path",
        "Code `dont touch this` but dont touch that" to "Code `dont touch this` but don't touch that",
    )

    fun enhance(input: String): String {
        if (input.length < 4) return input

        val (maskedInput, segments) = protectSensitiveSegments(input)
        var out = maskedInput

        // 1) Apply conservative lexical and agreement fixes.
        for (rule in simpleRules) {
            out = rule.pattern.replace(out, rule.replacement)
        }

        // 1a) Keep third-person agreement correct for persona self-reference
        // terms introduced by replacement maps (it/pup/puppy/mutt/bitch).
        out = fixPersonaThirdPersonAgreement(out)
        out = fixPersonaPossessiveFallout(out)

        // 1b) Conservative noun-number agreement for plural-looking subjects.
        out = fixPluralLookingSubjectAgreement(out)
        out = fixQuantifiedPluralAgreement(out)
        out = fixIrregularPluralAgreement(out)
        out = fixUncountableAgreement(out)
        out = fixThereAgreement(out)

        // 2) Fix article agreement for obvious vowel starts: "a apple" -> "an apple".
        out = Regex("""\b([Aa])\s+([aeiouAEIOU]\w*)\b""").replace(out) { m ->
            val article = m.groupValues[1]
            val word = m.groupValues[2]
            if (article == "A") "An $word" else "an $word"
        }
        out = fixArticleOddballs(out)

        // 3) Keep basic punctuation spacing clean.
        out = Regex("""\s+([,.;:!?])""").replace(out, "$1")
        out = Regex("""([,.;:!?])(?!\s|$)""").replace(out, "$1 ")
        out = Regex("""[ ]{2,}""").replace(out, " ")

        out = restoreSensitiveSegments(out, segments)
        return out.trim()
    }

    /**
     * Returns static before/after fixture pairs used for grammar regression checks.
     */
    internal fun regressionFixtures(): List<Pair<String, String>> = regressionCases

    /**
     * Runs built-in fixture checks and returns a list of mismatch descriptions.
     * Empty list means all fixtures passed for the current rule set.
     */
    internal fun runRegressionSelfCheck(): List<String> {
        val failures = mutableListOf<String>()
        for ((idx, entry) in regressionCases.withIndex()) {
            val input = entry.first
            val expected = entry.second
            val actual = enhance(input)
            if (actual != expected) {
                failures.add(
                    "case[$idx] expected='$expected' actual='$actual' input='$input'"
                )
            }
        }
        return failures
    }

    private fun protectSensitiveSegments(input: String): Pair<String, List<ProtectedSegment>> {
        val segments = mutableListOf<ProtectedSegment>()
        var out = input
        var idx = 0

        for (regex in protectedRegexes) {
            out = regex.replace(out) { m ->
                val token = "__TPE_SEG_${idx++}__"
                segments.add(ProtectedSegment(token, m.value))
                token
            }
        }

        return out to segments
    }

    private fun restoreSensitiveSegments(input: String, segments: List<ProtectedSegment>): String {
        var out = input
        for (segment in segments) {
            out = out.replace(segment.token, segment.original)
        }
        return out
    }

    private fun fixPersonaThirdPersonAgreement(input: String): String {
        var out = input
        val personaSubject =
            "(?:this\\s+mutt|this\\s+puppy|this\\s+pup|this\\s+bitch|" +
                "the\\s+mutt|the\\s+puppy|the\\s+pup|the\\s+bitch|" +
                "puppy|pup|mutt|bitch|it)"

        out = Regex("""\b($personaSubject)\s+are\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} is"
        }
        out = Regex("""\b($personaSubject)\s+were\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} was"
        }
        out = Regex("""\b($personaSubject)\s+have\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} has"
        }
        out = Regex("""\b($personaSubject)\s+do\s+not\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} does not"
        }
        out = Regex("""\b($personaSubject)\s+don't\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} doesn't"
        }
        out = Regex("""\b($personaSubject)\s+do\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} does"
        }

        return out
    }

    private fun fixPersonaPossessiveFallout(input: String): String {
        var out = input

        // Possessive pronouns are determiners, not standalone subjects in this
        // context; normalize obvious replacement fallout.
        out = Regex("""\bits\s+are\b""", RegexOption.IGNORE_CASE).replace(out, "it is")
        out = Regex("""\bits\s+were\b""", RegexOption.IGNORE_CASE).replace(out, "it was")
        out = Regex("""\bits\s+have\b""", RegexOption.IGNORE_CASE).replace(out, "it has")
        out = Regex("""\bits\s+do\b""", RegexOption.IGNORE_CASE).replace(out, "it does")
        out = Regex("""\bits\s+don't\b""", RegexOption.IGNORE_CASE).replace(out, "it doesn't")

        // Common collapsed possessive contractions after aggressive rewrites.
        out = Regex("""\bit's\s+are\b""", RegexOption.IGNORE_CASE).replace(out, "it is")
        out = Regex("""\bit's\s+were\b""", RegexOption.IGNORE_CASE).replace(out, "it was")
        out = Regex("""\bit's\s+have\b""", RegexOption.IGNORE_CASE).replace(out, "it has")

        return out
    }

    private fun fixPluralLookingSubjectAgreement(input: String): String {
        var out = input

        // Avoid false positives where words ending in 's' are singular nouns.
        val knownSingularEndingS = setOf(
            "news", "series", "species", "physics", "mathematics", "economics", "chess"
        )

        fun isPluralLooking(subject: String): Boolean {
            val s = subject.trim().lowercase()
            if (s.length < 3) return false
            if (!s.endsWith("s")) return false
            if (s.endsWith("'s")) return false
            if (s in knownSingularEndingS) return false
            return true
        }

        out = Regex("""\b([A-Za-z][A-Za-z'-]{1,})\s+is\b""").replace(out) { m ->
            val subject = m.groupValues[1]
            if (isPluralLooking(subject)) "$subject are" else m.value
        }
        out = Regex("""\b([A-Za-z][A-Za-z'-]{1,})\s+was\b""").replace(out) { m ->
            val subject = m.groupValues[1]
            if (isPluralLooking(subject)) "$subject were" else m.value
        }
        out = Regex("""\b([A-Za-z][A-Za-z'-]{1,})\s+has\b""").replace(out) { m ->
            val subject = m.groupValues[1]
            if (isPluralLooking(subject)) "$subject have" else m.value
        }

        return out
    }

    private fun fixQuantifiedPluralAgreement(input: String): String {
        var out = input
        val quantifiedPlural = Regex(
            """\b(\d+|[Tt]wo|[Tt]hree|[Ff]our|[Ff]ive|[Ss]ix|[Ss]even|[Ee]ight|[Nn]ine|[Tt]en|[Mm]any|[Ss]everal|[Ff]ew|[Bb]oth)\s+([A-Za-z][A-Za-z'-]{1,})\s+(is|was|has)\b"""
        )
        out = quantifiedPlural.replace(out) { m ->
            val quant = m.groupValues[1]
            val noun = m.groupValues[2]
            val verb = m.groupValues[3]
            val fixedVerb = when (verb.lowercase()) {
                "is" -> "are"
                "was" -> "were"
                "has" -> "have"
                else -> verb
            }
            "$quant $noun $fixedVerb"
        }
        return out
    }

    private fun fixIrregularPluralAgreement(input: String): String {
        var out = input
        val nounPattern = irregularPluralNouns.joinToString("|") { Regex.escape(it) }
        out = Regex("""\b($nounPattern)\s+is\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} are"
        }
        out = Regex("""\b($nounPattern)\s+was\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} were"
        }
        out = Regex("""\b($nounPattern)\s+has\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} have"
        }
        return out
    }

    private fun fixUncountableAgreement(input: String): String {
        var out = input
        val nounPattern = uncountableSingularNouns.joinToString("|") { Regex.escape(it) }
        out = Regex("""\b($nounPattern)\s+are\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} is"
        }
        out = Regex("""\b($nounPattern)\s+were\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} was"
        }
        out = Regex("""\b($nounPattern)\s+have\b""", RegexOption.IGNORE_CASE).replace(out) { m ->
            "${m.groupValues[1]} has"
        }
        return out
    }

    private fun fixThereAgreement(input: String): String {
        var out = input
        out = Regex("""\b[Tt]here\s+is\s+(\d+|many|several|few|two|three|four|five|six|seven|eight|nine|ten)\b""").replace(out) { m ->
            val lead = if (m.value.first().isUpperCase()) "There" else "there"
            "$lead are ${m.groupValues[1]}"
        }
        out = Regex("""\b[Tt]here\s+are\s+(a|an|one)\b""").replace(out) { m ->
            val lead = if (m.value.first().isUpperCase()) "There" else "there"
            "$lead is ${m.groupValues[1]}"
        }
        out = Regex("""\b[Tt]here\s+are\s+(information|advice|furniture|equipment|evidence|traffic|baggage|luggage|music|homework|progress|research|news|content)\b""").replace(out) { m ->
            val lead = if (m.value.first().isUpperCase()) "There" else "there"
            "$lead is ${m.groupValues[1]}"
        }
        out = Regex("""\b[Tt]here\s+is\s+(children|people|men|women|mice|geese|feet|teeth|criteria|phenomena)\b""").replace(out) { m ->
            val lead = if (m.value.first().isUpperCase()) "There" else "there"
            "$lead are ${m.groupValues[1]}"
        }
        return out
    }

    private fun fixArticleOddballs(input: String): String {
        var out = input

        // Consonant-sound vowels: "an user" -> "a user".
        out = Regex("""\b([Aa])n\s+(user|university|unique|unit|one|euro\w*)\b""", RegexOption.IGNORE_CASE)
            .replace(out) { m ->
                val first = m.groupValues[1]
                val noun = m.groupValues[2]
                if (first == "A") "A $noun" else "a $noun"
            }

        // Silent-h starters: "a hour" -> "an hour".
        out = Regex("""\b([Aa])\s+(hour|honor|honest|heir)\b""", RegexOption.IGNORE_CASE)
            .replace(out) { m ->
                val first = m.groupValues[1]
                val noun = m.groupValues[2]
                if (first == "A") "An $noun" else "an $noun"
            }

        return out
    }
}
