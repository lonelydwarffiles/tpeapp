package com.hound.controller.xposed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Fail-closed ImageView hook:
 * - instantly obscures target container in beforeHookedMethod
 * - sends source bytes to local Binder service
 * - keeps locked overlay on timeout/service failure
 * - fades approved processed image in over configured duration (default 300ms)
 */
object ImageViewHook {

    private const val TAG = "TPE_ImageViewHook"
    private const val IPC_TIMEOUT_MS = 200L
    private const val DEFAULT_REVEAL_MS = 300L
    private const val JPEG_QUALITY = 90

    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestSeq = AtomicLong(0)
    private val latestViewRequest = java.util.Collections.synchronizedMap(WeakHashMap<ImageView, Long>())
    private val suppressedViewCalls = java.util.Collections.synchronizedMap(WeakHashMap<ImageView, Int>())
    private val inHook = ThreadLocal<Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(loader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.ImageView", loader,
                "setImageBitmap", Bitmap::class.java,
                setBitmapHook,
            )
            XposedHelpers.findAndHookMethod(
                "android.widget.ImageView", loader,
                "setImageDrawable", Drawable::class.java,
                setDrawableHook,
            )
            Log.i(TAG, "Fail-closed ImageView hooks installed")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to install ImageView hooks", t)
        }
    }

    private val setBitmapHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val view = param.thisObject as? ImageView ?: return
            if (consumeSuppressedCall(view) || inHook.get() == true) return
            val bitmap = param.args[0] as? Bitmap ?: return

            param.result = null
            handleInterceptedBitmap(view, bitmap)
        }
    }

    private val setDrawableHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val view = param.thisObject as? ImageView ?: return
            if (consumeSuppressedCall(view) || inHook.get() == true) return
            val drawable = param.args[0] as? BitmapDrawable ?: return
            val bitmap = drawable.bitmap ?: return

            param.result = null
            handleInterceptedBitmap(view, bitmap)
        }
    }

    private fun handleInterceptedBitmap(view: ImageView, original: Bitmap) {
        val requestId = requestSeq.incrementAndGet()
        latestViewRequest[view] = requestId
        val cfg = MediaFilterRuntimeConfig.current()

        if (cfg.failClosed) {
            runOnUiThread(view) { setLockedOverlay(view) }
        }

        bgScope.launch {
            val encoded = encode(original) ?: return@launch
            val processedBytes = withTimeoutOrNull(IPC_TIMEOUT_MS) {
                MainHook.getContext()?.let { MainHook.ensureServiceBound(it) }
                val service = MainHook.filterService ?: return@withTimeoutOrNull null
                service.processImageBytesForDisplay(encoded)
            }

            if (processedBytes == null || processedBytes.isEmpty()) {
                if (!cfg.failClosed) {
                    runOnUiThread(view) {
                        revealIfLatest(
                            view,
                            requestId,
                            original,
                            cfg.revealDurationMs.toLong(),
                            recycleIfStale = false,
                        )
                    }
                } else {
                    Log.w(TAG, "Image request=$requestId timed out/unavailable; keeping LOCKED overlay")
                }
                return@launch
            }

            val processed = BitmapFactory.decodeByteArray(processedBytes, 0, processedBytes.size)
            if (processed == null) {
                if (!cfg.failClosed) {
                    runOnUiThread(view) {
                        revealIfLatest(
                            view,
                            requestId,
                            original,
                            cfg.revealDurationMs.toLong(),
                            recycleIfStale = false,
                        )
                    }
                } else {
                    Log.w(TAG, "Image request=$requestId decode failed; keeping LOCKED overlay")
                }
                return@launch
            }

            runOnUiThread(view) {
                revealIfLatest(view, requestId, processed, cfg.revealDurationMs.toLong())
            }
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        return runCatching {
            ByteArrayOutputStream().use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)
                os.toByteArray()
            }
        }.getOrNull()
    }

    private fun setLockedOverlay(view: ImageView) {
        markSuppressedCall(view)
        inHook.set(true)
        try {
            view.alpha = 1f
            view.setImageDrawable(LockedDrawable("LOCKED / UNVERIFIED"))
        } finally {
            inHook.set(false)
        }
    }

    private fun revealIfLatest(
        view: ImageView,
        requestId: Long,
        bitmap: Bitmap,
        revealMs: Long,
        recycleIfStale: Boolean = true,
    ) {
        val current = latestViewRequest[view]
        if (current != requestId) {
            if (recycleIfStale && !bitmap.isRecycled) bitmap.recycle()
            return
        }

        markSuppressedCall(view)
        inHook.set(true)
        try {
            view.alpha = 0f
            view.setImageBitmap(bitmap)
            val duration = revealMs.coerceIn(0L, 3_000L)
            view.animate().alpha(1f).setDuration(if (duration > 0) duration else DEFAULT_REVEAL_MS).start()
        } finally {
            inHook.set(false)
        }
    }

    private fun runOnUiThread(view: ImageView, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val activity = findActivity(view.context)
        if (activity != null && !activity.isFinishing) {
            activity.runOnUiThread(action)
            return
        }
        mainHandler.post(action)
    }

    private fun findActivity(context: android.content.Context?): android.app.Activity? {
        var current = context
        while (current is ContextThemeWrapper) {
            if (current is android.app.Activity) return current
            current = current.baseContext
        }
        return if (current is android.app.Activity) current else null
    }

    private fun markSuppressedCall(view: ImageView) {
        synchronized(suppressedViewCalls) {
            val current = suppressedViewCalls[view] ?: 0
            suppressedViewCalls[view] = current + 1
        }
    }

    private fun consumeSuppressedCall(view: ImageView): Boolean {
        synchronized(suppressedViewCalls) {
            val remaining = suppressedViewCalls[view] ?: return false
            if (remaining <= 1) suppressedViewCalls.remove(view) else suppressedViewCalls[view] = remaining - 1
            return true
        }
    }

    private class LockedDrawable(private val text: String) : Drawable() {
        private val bgPaint = Paint().apply { color = Color.BLACK }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 40f
            isFakeBoldText = true
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b, bgPaint)
            canvas.drawRect(b, strokePaint)
            if (b.isEmpty) return
            val fm = textPaint.fontMetrics
            val baseline = b.exactCenterY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, b.exactCenterX(), baseline, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            strokePaint.alpha = alpha
            textPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
