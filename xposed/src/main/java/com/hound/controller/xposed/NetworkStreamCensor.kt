package com.hound.controller.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.max
import kotlin.random.Random

/**
 * Centralized network-stream censorship processor used by OkHttp and
 * HttpURLConnection hooks.
 */
object NetworkStreamCensor {

    private const val TAG = "TPE_NetworkStreamCensor"
    private const val IPC_TIMEOUT_MS = 300L
    private const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
    private const val FALLBACK_MODULE_PACKAGE = "com.hound.controller.xposed"
    private const val FALLBACK_LOCK_ASSET_B64 = "network_lock_block.b64"

    private val worker = Executors.newCachedThreadPool()

    private val stampPool = arrayOf(
        "ACCESS DENIED, MUTT",
        "BAD DOG",
        "NOT FOR PUPPIES",
        "BLOCKED BY HANDLER",
    )

    fun maybeCensorNetworkImage(contentType: String?, bodyBytes: ByteArray): ByteArray {
        if (bodyBytes.isEmpty() || bodyBytes.size > MAX_IMAGE_BYTES) return bodyBytes
        if (!isImageContentType(contentType)) return bodyBytes

        val cfg = MediaFilterRuntimeConfig.current()
        val scanOutcome = evaluateForbidden(bodyBytes)

        if (scanOutcome == null) {
            CoverageTelemetry.report(
                lane = CoverageTelemetry.LANE_OKHTTP,
                stage = CoverageTelemetry.STAGE_SCAN_TIMEOUT,
                reason = "network_ipc_timeout_or_error",
            )
            return if (cfg.failClosed) fallbackLockBytes() else bodyBytes
        }

        if (!scanOutcome) return bodyBytes

        val classLabels = cfg.forbiddenClassIds.joinToString(separator = ",") { "class_$it" }
        CoverageTelemetry.report(
            lane = CoverageTelemetry.LANE_OKHTTP,
            stage = CoverageTelemetry.STAGE_SCAN_RESULT,
            sensitive = true,
            confidence = 0.55f,
            reason = "labels=$classLabels",
        )

        return runCatching {
            val src = BitmapFactory.decodeByteArray(bodyBytes, 0, bodyBytes.size) ?: return bodyBytes
            val out = ensureMutable(src)
            if (out == null) {
                if (!src.isRecycled) src.recycle()
                return bodyBytes
            }

            val doStamp = Random.nextBoolean()
            if (doStamp) {
                drawBlackStampPenalty(out, stampPool[Random.nextInt(stampPool.size)])
            } else {
                val pixelated = BlurHelper.pixelateBitmap(out, blockSize = 28)
                val c = Canvas(out)
                c.drawBitmap(pixelated, 0f, 0f, null)
                pixelated.recycle()
            }

            val encoded = encodeLikeSource(out, contentType)
            if (!out.isRecycled) out.recycle()
            if (out !== src && !src.isRecycled) src.recycle()
            encoded ?: bodyBytes
        }.getOrElse {
            Log.w(TAG, "network image rewrite failed", it)
            bodyBytes
        }
    }

    fun maybeCensorNetworkImage(contentType: String?, stream: InputStream): ByteArray {
        return stream.use {
            val bytes = runCatching { it.readBytes() }.getOrDefault(ByteArray(0))
            maybeCensorNetworkImage(contentType, bytes)
        }
    }

    private fun evaluateForbidden(imageBytes: ByteArray): Boolean? {
        val context = MainHook.getContext() ?: return null
        MainHook.ensureServiceBound(context)
        val service = MainHook.filterService ?: return null

        val future = worker.submit(Callable {
            service.hasForbiddenContent(imageBytes)
        })

        return try {
            future.get(IPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "hasForbiddenContent failed", t)
            null
        }
    }

    private fun ensureMutable(src: Bitmap): Bitmap? {
        if (src.isMutable && src.config != Bitmap.Config.HARDWARE) return src
        val safeConfig = src.config?.takeIf { it != Bitmap.Config.HARDWARE } ?: Bitmap.Config.ARGB_8888
        return runCatching { src.copy(safeConfig, true) }.getOrNull()
    }

    private fun drawBlackStampPenalty(bitmap: Bitmap, stampText: String) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = max(32f, bitmap.width / 12f)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 120, 0, 0)
            style = Paint.Style.FILL
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val centerY = bitmap.height * 0.52f
        val pad = 24f
        val bounds = Rect()
        textPaint.getTextBounds(stampText, 0, stampText.length, bounds)
        val boxWidth = bounds.width() + (pad * 2)
        val boxHeight = bounds.height() + (pad * 1.5f)
        val left = (bitmap.width - boxWidth) / 2f
        val top = centerY - (boxHeight / 2f)

        canvas.drawRect(left, top, left + boxWidth, top + boxHeight, bgPaint)
        canvas.drawRect(left, top, left + boxWidth, top + boxHeight, strokePaint)
        canvas.drawText(stampText, bitmap.width / 2f, centerY + (bounds.height() / 2f), textPaint)
    }

    private fun encodeLikeSource(bitmap: Bitmap, contentType: String?): ByteArray? {
        val format = if (contentType?.contains("png", ignoreCase = true) == true) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }

        return runCatching {
            ByteArrayOutputStream().use { os ->
                bitmap.compress(format, 90, os)
                os.toByteArray()
            }
        }.getOrNull()
    }

    private fun fallbackLockBytes(): ByteArray {
        val context = MainHook.getContext()
        if (context != null) {
            val fromAsset = loadFallbackFromAsset(context)
            if (fromAsset.isNotEmpty()) return fromAsset
        }

        val bmp = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        drawBlackStampPenalty(bmp, "LOCKED")
        val encoded = encodeLikeSource(bmp, "image/png") ?: ByteArray(0)
        bmp.recycle()
        return encoded
    }

    private fun loadFallbackFromAsset(context: Context): ByteArray {
        return runCatching {
            val moduleContext = context.createPackageContext(
                FALLBACK_MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            moduleContext.assets.open(FALLBACK_LOCK_ASSET_B64).use { input ->
                android.util.Base64.decode(input.readBytes(), android.util.Base64.DEFAULT)
            }
        }.getOrElse {
            Log.w(TAG, "Failed to load fallback asset", it)
            ByteArray(0)
        }
    }

    private fun isImageContentType(contentType: String?): Boolean {
        val value = contentType?.trim().orEmpty()
        return value.startsWith("image/", ignoreCase = true)
    }
}
