package com.hound.controller.xposed

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Cached runtime config used by media hooks to balance speed vs strict behavior.
 */
object MediaFilterRuntimeConfig {

    private const val CONFIG_TTL_MS = 5_000L

    data class Config(
        val mode: String = "speed", // speed|strict
        val censorStyle: String = "random", // blackout|heavy_blur|pixelate|random
        val strictPackages: Set<String> = emptySet(),
        val forbiddenClassIds: Set<Int> = setOf(2, 3, 4, 6, 14),
        val maxInFlight: Int = 4,
        val failClosed: Boolean = true,
        val revealDurationMs: Int = 300,
        val nudityPermittedByHandler: Boolean = false,
        val placeholderText: String = "Loading...",
    )

    @Volatile private var lastFetchMs = 0L
    @Volatile private var cached = Config()
    private val globalInFlight = AtomicInteger(0)

    fun current(): Config {
        val now = System.currentTimeMillis()
        if (now - lastFetchMs < CONFIG_TTL_MS) return cached

        val service = MainHook.filterService
        if (service == null) {
            MainHook.getContext()?.let { MainHook.ensureServiceBound(it) }
            return cached
        }

        val json = runCatching { service.getMediaFilterConfig() }.getOrNull() ?: return cached
        lastFetchMs = now
        cached = parse(json)
        return cached
    }

    fun isStrictForCurrentPackage(): Boolean {
        val cfg = current()
        if (cfg.mode == "strict") return true
        val pkg = MainHook.getContext()?.packageName?.trim().orEmpty()
        return pkg.isNotEmpty() && cfg.strictPackages.contains(pkg)
    }

    fun tryAcquireScanBudget(): Boolean {
        val budget = current().maxInFlight.coerceIn(1, 12)
        while (true) {
            val cur = globalInFlight.get()
            if (cur >= budget) return false
            if (globalInFlight.compareAndSet(cur, cur + 1)) return true
        }
    }

    fun releaseScanBudget() {
        while (true) {
            val cur = globalInFlight.get()
            if (cur <= 0) return
            if (globalInFlight.compareAndSet(cur, cur - 1)) return
        }
    }

    fun isNudityPermittedByHandler(): Boolean = current().nudityPermittedByHandler
    fun placeholderText(): String = current().placeholderText
    fun failClosed(): Boolean = current().failClosed
    fun revealDurationMs(): Long = current().revealDurationMs.toLong()

    fun censorBitmapInPlace(bitmap: Bitmap) {
        val ctx = MainHook.getContext()
        val cfg = current()
        val style = when (cfg.censorStyle) {
            "random" -> if (Random.nextBoolean()) "pixelate" else "heavy_blur"
            else -> cfg.censorStyle
        }
        val censored = when (style) {
            "blackout" -> {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.BLACK)
                }
            }
            "heavy_blur" -> if (ctx != null) {
                BlurHelper.blurBitmap(ctx, bitmap, radius = 14)
            } else {
                BlurHelper.pixelateBitmap(bitmap)
            }
            else -> BlurHelper.pixelateBitmap(bitmap)
        }
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawBitmap(censored, 0f, 0f, null)
        censored.recycle()
    }

    private fun parse(rawJson: String): Config {
        if (rawJson.isBlank()) return cached
        return runCatching {
            val obj = JSONObject(rawJson)
            val mode = when (obj.optString("mode", "speed").trim().lowercase()) {
                "strict" -> "strict"
                else -> "speed"
            }
            val censorStyle = when (obj.optString("censor_style", "pixelate").trim().lowercase()) {
                "blackout" -> "blackout"
                "heavy_blur", "heavyblur", "blur" -> "heavy_blur"
                "random" -> "random"
                else -> "pixelate"
            }
            val strictPackages = parsePackages(obj.optJSONArray("strict_packages") ?: JSONArray())
            val forbiddenClassIds = parseForbiddenClassIds(
                obj.optJSONArray("forbidden_class_ids") ?: JSONArray()
            )
            val maxInFlight = obj.optInt("max_in_flight", 4).coerceIn(1, 12)
            val failClosed = if (obj.has("fail_closed")) {
                obj.optBoolean("fail_closed", true)
            } else {
                obj.optBoolean("nudenet_fail_closed", true)
            }
            val revealDurationMs = obj.optInt("reveal_duration_ms", 300).coerceIn(0, 3_000)
            val nudityPermittedByHandler = obj.optBoolean("nudity_permitted_by_handler", false)
            val placeholderText = obj.optString("placeholder_text", "Loading...")
                .trim()
                .take(64)
                .ifBlank { "Loading..." }
            Config(
                mode = mode,
                censorStyle = censorStyle,
                strictPackages = strictPackages,
                forbiddenClassIds = forbiddenClassIds,
                maxInFlight = maxInFlight,
                failClosed = failClosed,
                revealDurationMs = revealDurationMs,
                nudityPermittedByHandler = nudityPermittedByHandler,
                placeholderText = placeholderText,
            )
        }.getOrElse { cached }
    }

    private fun parsePackages(arr: JSONArray): Set<String> {
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val pkg = arr.optString(i).trim()
            if (pkg.isNotEmpty()) out.add(pkg)
        }
        return out
    }

    private fun parseForbiddenClassIds(arr: JSONArray): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (i in 0 until arr.length()) {
            val id = when (val value = arr.opt(i)) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            }
            if (id != null && id >= 0) out.add(id)
        }
        return if (out.isEmpty()) setOf(2, 3, 4, 6, 14) else out
    }
}

