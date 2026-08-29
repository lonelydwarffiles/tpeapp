package com.hound.controller.util

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.random.Random

object ImageCensorshipEffects {
    private const val STRIP_MIN_HEIGHT = 5
    private const val STRIP_MAX_HEIGHT = 30
    private const val STRIP_SHIFT_PROBABILITY = 0.30f
    private const val STRIP_MAX_SHIFT = 50
    private const val RED_CHANNEL_SHIFT = -15
    private const val BLUE_CHANNEL_SHIFT = 15

    fun applyVhsGlitch(source: Bitmap): Bitmap {
        val base = source.copy(Bitmap.Config.ARGB_8888, true)
        val w = base.width
        val h = base.height
        if (w <= 1 || h <= 1) {
            if (!source.isRecycled) {
                runCatching { source.recycle() }
            }
            return base
        }

        val torn = IntArray(w * h)
        val sourcePixels = IntArray(w * h)
        base.getPixels(sourcePixels, 0, w, 0, 0, w, h)

        var y = 0
        while (y < h) {
            val stripHeight = min(h - y, Random.nextInt(STRIP_MIN_HEIGHT, STRIP_MAX_HEIGHT + 1))
            val shift = if (Random.nextFloat() < STRIP_SHIFT_PROBABILITY) {
                Random.nextInt(-STRIP_MAX_SHIFT, STRIP_MAX_SHIFT + 1)
            } else {
                0
            }

            for (row in y until (y + stripHeight)) {
                val rowBase = row * w
                for (x in 0 until w) {
                    val srcX = wrapX(x - shift, w)
                    torn[rowBase + x] = sourcePixels[rowBase + srcX]
                }
            }

            y += stripHeight
        }

        val glitched = IntArray(w * h)
        for (row in 0 until h) {
            val rowBase = row * w
            for (x in 0 until w) {
                val baseColor = torn[rowBase + x]
                val redColor = torn[rowBase + wrapX(x - RED_CHANNEL_SHIFT, w)]
                val blueColor = torn[rowBase + wrapX(x - BLUE_CHANNEL_SHIFT, w)]

                val a = (baseColor ushr 24) and 0xFF
                val r = (redColor ushr 16) and 0xFF
                val g = (baseColor ushr 8) and 0xFF
                val b = blueColor and 0xFF

                glitched[rowBase + x] =
                    (a shl 24) or
                        (r shl 16) or
                        (g shl 8) or
                        b
            }
        }

        base.setPixels(glitched, 0, w, 0, 0, w, h)
        if (!source.isRecycled) {
            runCatching { source.recycle() }
        }
        return base
    }

    private fun wrapX(x: Int, width: Int): Int {
        val m = x % width
        return if (m < 0) m + width else m
    }
}
