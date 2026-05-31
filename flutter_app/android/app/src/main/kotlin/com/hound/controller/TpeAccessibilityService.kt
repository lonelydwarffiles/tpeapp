package com.hound.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import java.util.regex.Pattern

private const val ACCESSIBILITY_PREFS = "tpe_accessibility_service"
private const val ACCESSIBILITY_CONNECTED_KEY = "connected"
private const val ACCESSIBILITY_LAST_PACKAGE_KEY = "last_package"
private const val ACCESSIBILITY_LAST_EVENT_KEY = "last_event_time"
private const val MIN_BUZZ_EVENT_GAP_MS = 700L
private const val COMMAND_DEDUPE_WINDOW_MS = 15_000L
private const val MAX_COMMAND_SIGNATURES = 64
private const val DEFAULT_BUZZ_DURATION_MS = 500
private const val DEFAULT_ZAP_DURATION_MS = 500

class TpeAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "TpeAccessibilityService"
        private const val TAP_DURATION_MS = 50L
        private val BUZZ_PATTERN = Pattern.compile("\\bbuzz(?:\\s+(\\d{1,2}))?(?:\\s+(loop))?\\b")
        private val ZAP_PATTERN = Pattern.compile("\\bzap(?:\\s+(\\d{1,3}))?\\b")

        @Volatile
        var instance: TpeAccessibilityService? = null
            private set

        @Volatile
        var buzzCommandListener: ((Map<String, Any>) -> Unit)? = null

        @Volatile
        private var lastBuzzEventAtMs: Long = 0L

        private val recentCommandSignatures = LinkedHashMap<String, Long>(MAX_COMMAND_SIGNATURES, 0.75f, true)

        fun injectTap(normX: Float, normY: Float): Boolean {
            val service = instance ?: return false
            val windowManager = service.getSystemService(WINDOW_SERVICE) as WindowManager
            val bounds = windowManager.currentWindowMetrics.bounds
            val px = normX.coerceIn(0f, 1f) * bounds.width()
            val py = normY.coerceIn(0f, 1f) * bounds.height()

            val path = Path().apply { moveTo(px, py) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = service.dispatchGesture(gesture, null, null)
            if (dispatched) {
                service.persistStatus(connected = true)
                Log.d(TAG, "Injected tap at ($px, $py)")
            }
            return dispatched
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        persistStatus(connected = true)
        Log.i(TAG, "Standalone accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString()?.trim().orEmpty()
        if (packageName.isBlank()) return

        val payload = when (safeEvent.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                extractCommandPayload(safeEvent, packageName, source = "notification")

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (shouldInspectOnScreenText(packageName)) {
                    extractCommandPayload(safeEvent, packageName, source = "screen")
                } else {
                    null
                }

            else -> null
        }

        if (payload != null) {
            val now = System.currentTimeMillis()
            if (now - lastBuzzEventAtMs >= MIN_BUZZ_EVENT_GAP_MS && shouldEmitCommandPayload(payload, now)) {
                lastBuzzEventAtMs = now
                buzzCommandListener?.invoke(payload)
                Log.d(TAG, "Notification/screen command detected: $payload")
            }
        }

        persistStatus(connected = true, lastPackage = packageName)
    }

    private fun shouldInspectOnScreenText(packageName: String): Boolean {
        val ownPackage = applicationContext.packageName
        if (packageName.equals(ownPackage, ignoreCase = true)) {
            return false
        }

        val p = packageName.lowercase(Locale.US)
        if (p == "com.google.android.apps.messaging" || p == "com.samsung.android.messaging") {
            return true
        }
        if (p.startsWith("com.android.mms")) {
            return true
        }

        val messagingHints = listOf(
            "whatsapp",
            "telegram",
            "messenger",
            "discord",
            "signal",
            "messages",
            "sms",
            "wechat",
            "line",
            "snapchat",
            "instagram",
            "skype",
            "viber",
        )
        return messagingHints.any { p.contains(it) }
    }

    private fun shouldEmitCommandPayload(payload: Map<String, Any>, nowMs: Long): Boolean {
        val command = payload["command"]?.toString()?.lowercase(Locale.US).orEmpty()
        if (command.isBlank()) {
            return false
        }

        val signature = buildString {
            append(payload["source"]?.toString()?.lowercase(Locale.US).orEmpty())
            append('|')
            append(payload["package"]?.toString()?.lowercase(Locale.US).orEmpty())
            append('|')
            append(command)
            append('|')
            append(payload["count"]?.toString().orEmpty())
            append('|')
            append(payload["loop"]?.toString().orEmpty())
            append('|')
            append(payload["strength"]?.toString().orEmpty())
            append('|')
            append(payload["duration_ms"]?.toString().orEmpty())
        }

        synchronized(recentCommandSignatures) {
            val entries = recentCommandSignatures.entries.iterator()
            while (entries.hasNext()) {
                val ageMs = nowMs - entries.next().value
                if (ageMs > COMMAND_DEDUPE_WINDOW_MS) {
                    entries.remove()
                }
            }

            val lastSeen = recentCommandSignatures[signature]
            if (lastSeen != null && nowMs - lastSeen < COMMAND_DEDUPE_WINDOW_MS) {
                return false
            }

            recentCommandSignatures[signature] = nowMs
            while (recentCommandSignatures.size > MAX_COMMAND_SIGNATURES) {
                val oldest = recentCommandSignatures.entries.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            return true
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Standalone accessibility service interrupted")
    }

    override fun onDestroy() {
        persistStatus(connected = false)
        instance = null
        Log.i(TAG, "Standalone accessibility service destroyed")
        super.onDestroy()
    }

    private fun persistStatus(
        connected: Boolean,
        lastPackage: String? = null,
    ) {
        getSharedPreferences(ACCESSIBILITY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ACCESSIBILITY_CONNECTED_KEY, connected)
            .putLong(ACCESSIBILITY_LAST_EVENT_KEY, System.currentTimeMillis())
            .apply {
                if (lastPackage != null) {
                    putString(ACCESSIBILITY_LAST_PACKAGE_KEY, lastPackage)
                }
            }
            .apply()
    }

    private fun extractCommandPayload(
        event: AccessibilityEvent,
        packageName: String,
        source: String,
    ): Map<String, Any>? {
        val combinedText = buildList {
            addAll(event.text?.map { it?.toString().orEmpty() }.orEmpty())
            val contentDescription = event.contentDescription?.toString()?.trim().orEmpty()
            if (contentDescription.isNotBlank()) {
                add(contentDescription)
            }
        }
            .joinToString(" ")
            .trim()
        if (combinedText.isBlank()) return null

        val parsed = parseCommandText(combinedText) ?: return null
        val confidence = confidenceFor(
            source = source,
            eventType = event.eventType,
            hadNumericArg = parsed.hadNumericArg,
            hadDurationArg = parsed.durationMs != null,
            hadLoopArg = parsed.loop,
        )

        if (parsed.command == "buzz") {
            return mapOf(
                "source" to source,
                "package" to packageName,
                "command" to "buzz",
                "count" to parsed.count,
                "loop" to parsed.loop,
                "duration_ms" to parsed.durationMs,
                "confidence" to confidence,
                "raw" to combinedText,
            )
        }

        if (parsed.command == "zap") {
            return mapOf(
                "source" to source,
                "package" to packageName,
                "command" to "zap",
                "strength" to parsed.strength,
                "duration_ms" to parsed.durationMs,
                "confidence" to confidence,
                "raw" to combinedText,
            )
        }

        return null
    }

    private fun parseCommandText(raw: String): ParsedCommand? {
        val lower = raw.lowercase(Locale.US)
        val tokenRegex = Regex("[a-z0-9%]+")
        val tokens = tokenRegex.findAll(lower).map { it.value }.toList()
        if (tokens.isEmpty()) {
            return null
        }

        val buzzIndex = tokens.indexOfFirst { it == "buzz" }
        if (buzzIndex >= 0) {
            val fallbackMatcher = BUZZ_PATTERN.matcher(lower)
            val fallbackMatched = fallbackMatcher.find()
            val fallbackCount = if (fallbackMatched) {
                fallbackMatcher.group(1)?.trim()?.toIntOrNull()?.coerceIn(1, 20)
            } else {
                null
            }
            val parsedCount = parseCountNear(tokens, buzzIndex)
            val count = parsedCount ?: fallbackCount ?: 1
            val loop = tokens.any { it == "loop" } ||
                (fallbackMatched && fallbackMatcher.group(2)?.equals("loop", ignoreCase = true) == true)
            val durationMs = parseDurationNear(tokens, buzzIndex) ?: DEFAULT_BUZZ_DURATION_MS
            return ParsedCommand(
                command = "buzz",
                count = count,
                loop = loop,
                durationMs = durationMs,
                hadNumericArg = fallbackCount != null || parsedCount != null,
            )
        }

        val zapIndex = tokens.indexOfFirst { it == "zap" }
        if (zapIndex >= 0) {
            val fallbackMatcher = ZAP_PATTERN.matcher(lower)
            val fallbackMatched = fallbackMatcher.find()
            val fallbackStrength = if (fallbackMatched) {
                fallbackMatcher.group(1)?.trim()?.toIntOrNull()?.coerceIn(1, 100)
            } else {
                null
            }
            val parsedStrength = parseStrengthNear(tokens, zapIndex)
            val strength = parsedStrength ?: fallbackStrength ?: 64
            val durationMs = parseDurationNear(tokens, zapIndex) ?: DEFAULT_ZAP_DURATION_MS
            return ParsedCommand(
                command = "zap",
                strength = strength,
                durationMs = durationMs,
                hadNumericArg = fallbackStrength != null || parsedStrength != null,
            )
        }

        return null
    }

    private fun parseCountNear(tokens: List<String>, commandIndex: Int): Int? {
        val indices = listOf(commandIndex + 1, commandIndex + 2, commandIndex - 1)
        for (i in indices) {
            if (i !in tokens.indices) continue
            val value = tokens[i]
            val count = when {
                value.matches(Regex("\\d{1,2}")) -> value.toIntOrNull()
                value.matches(Regex("x\\d{1,2}")) -> value.removePrefix("x").toIntOrNull()
                value.matches(Regex("\\d{1,2}x")) -> value.removeSuffix("x").toIntOrNull()
                else -> null
            }
            if (count != null) {
                return count.coerceIn(1, 20)
            }
        }
        return null
    }

    private fun parseStrengthNear(tokens: List<String>, commandIndex: Int): Int? {
        val indices = listOf(commandIndex + 1, commandIndex + 2, commandIndex - 1)
        for (i in indices) {
            if (i !in tokens.indices) continue
            val value = tokens[i]
            val normalized = value.removeSuffix("%")
            val strength = normalized.toIntOrNull()
            if (strength != null) {
                return strength.coerceIn(1, 100)
            }
        }
        return null
    }

    private fun parseDurationNear(tokens: List<String>, commandIndex: Int): Int? {
        val start = max(0, commandIndex - 2)
        val end = min(tokens.lastIndex, commandIndex + 4)
        for (i in start..end) {
            val token = tokens[i]

            if (token.endsWith("ms")) {
                val number = token.removeSuffix("ms").toIntOrNull()
                if (number != null) return number.coerceIn(50, 10_000)
            }
            if (token.endsWith("s")) {
                val number = token.removeSuffix("s").toIntOrNull()
                if (number != null) return (number * 1000).coerceIn(50, 10_000)
            }

            val number = token.toIntOrNull() ?: continue
            val next = tokens.getOrNull(i + 1)
            if (next in setOf("ms", "millis", "millisecond", "milliseconds")) {
                return number.coerceIn(50, 10_000)
            }
            if (next in setOf("s", "sec", "secs", "second", "seconds")) {
                return (number * 1000).coerceIn(50, 10_000)
            }
        }
        return null
    }

    private fun confidenceFor(
        source: String,
        eventType: Int,
        hadNumericArg: Boolean,
        hadDurationArg: Boolean,
        hadLoopArg: Boolean,
    ): Double {
        var score = if (source == "notification") 0.93 else 0.72
        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            score += 0.03
        }
        if (hadNumericArg) score += 0.07
        if (hadDurationArg) score += 0.03
        if (hadLoopArg) score += 0.02
        return score.coerceIn(0.0, 1.0)
    }

    private data class ParsedCommand(
        val command: String,
        val count: Int = 1,
        val loop: Boolean = false,
        val strength: Int = 64,
        val durationMs: Int? = null,
        val hadNumericArg: Boolean = false,
    )
}