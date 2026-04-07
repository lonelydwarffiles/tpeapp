package com.tpeapp.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import kotlin.math.min

/**
 * Lightweight blur and pixelation helpers for the Xposed hook layer.
 *
 * Uses the legacy [RenderScript] intrinsic blur so no extra AAR dependency is
 * required inside the target app's class-loader context.  For Android 12+
 * [android.graphics.RenderEffect] could be used directly on the View, but the
 * RenderScript path keeps compatibility across API 31–35.
 */
object BlurHelper {

    private const val TAG = "TPE_BlurHelper"

    // ------------------------------------------------------------------
    //  Gaussian blur
    // ------------------------------------------------------------------

    /**
     * Returns a new blurred copy of [src].  The original bitmap is NOT
     * recycled by this function.
     *
     * @param radius Blur radius in [1, 25] (RenderScript limit).
     */
    fun blurBitmap(context: Context, src: Bitmap, radius: Int = 15): Bitmap {
        val clampedRadius = radius.coerceIn(1, 25).toFloat()
        return runCatching {
            val rs     = RenderScript.create(context)
            val output = src.copy(Bitmap.Config.ARGB_8888, true)
            val allIn  = Allocation.createFromBitmap(rs, src)
            val allOut = Allocation.createFromBitmap(rs, output)
            val blur   = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            blur.setRadius(clampedRadius)
            blur.setInput(allIn)
            blur.forEach(allOut)
            allOut.copyTo(output)
            rs.destroy()
            output
        }.getOrElse { e ->
            Log.w(TAG, "blurBitmap failed, returning original", e)
            src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        }
    }

    // ------------------------------------------------------------------
    //  Heavy pixelation (permanent censor)
    // ------------------------------------------------------------------

    /**
     * Returns a pixelated copy of [src] by scaling it down then back up.
     * The caller retains ownership of [src].
     *
     * @param blockSize Side length of each "pixel block" in dp-independent pixels.
     */
    fun pixelateBitmap(src: Bitmap, blockSize: Int = 20): Bitmap {
        val w = src.width
        val h = src.height
        val smallW = (w / blockSize.toFloat()).toInt().coerceAtLeast(1)
        val smallH = (h / blockSize.toFloat()).toInt().coerceAtLeast(1)

        val small  = Bitmap.createScaledBitmap(src, smallW, smallH, false)
        val result = Bitmap.createScaledBitmap(small, w, h, false)
        small.recycle()
        return result
    }

    // ------------------------------------------------------------------
    //  Byte-level operations (for cache-layer hooks)
    // ------------------------------------------------------------------

    /**
     * Decode [imageBytes], pixelate, re-encode to JPEG, return new bytes.
     * Returns the original bytes unchanged if decoding fails.
     *
     * [context] is accepted for API consistency but is not required by the
     * pixelation path (which uses Canvas, not RenderScript).  Pass `null`
     * when no Context is available (e.g., inside OkHttp hooks).
     */
    fun pixelateBytes(
        @Suppress("UNUSED_PARAMETER") context: Context?,
        imageBytes: ByteArray,
        blockSize: Int = 20
    ): ByteArray {
        return runCatching {
            val src = android.graphics.BitmapFactory.decodeByteArray(
                imageBytes, 0, imageBytes.size
            ) ?: return imageBytes

            val pixelated = pixelateBitmap(src, blockSize)
            src.recycle()

            java.io.ByteArrayOutputStream().use { baos ->
                pixelated.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                pixelated.recycle()
                baos.toByteArray()
            }
        }.getOrElse {
            Log.w(TAG, "pixelateBytes failed, returning original", it)
            imageBytes
        }
    }
}
