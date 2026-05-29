package com.tpeapp.xposed

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cached runtime config used by media hooks to balance speed vs strict behavior.
 */
object MediaFilterRuntimeConfig {

    private const val CONFIG_TTL_MS = 5_000L

    data class Config(
        val mode: String = "speed", // speed|strict
        val censorStyle: String = "pixelate", // blackout|heavy_blur|pixelate
        val strictPackages: Set<String> = emptySet(),
        val maxInFlight: Int = 4,
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

    fun censorBitmapInPlace(bitmap: Bitmap) {
        val ctx = MainHook.getContext()
        val style = current().censorStyle
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
                else -> "pixelate"
            }
            val strictPackages = parsePackages(obj.optJSONArray("strict_packages") ?: JSONArray())
            val maxInFlight = obj.optInt("max_in_flight", 4).coerceIn(1, 12)
            Config(mode, censorStyle, strictPackages, maxInFlight)
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
}
