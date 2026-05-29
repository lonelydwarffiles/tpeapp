package com.tpeapp.xposed

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.ImageView
import com.tpeapp.filter.IFilterCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Hooks [android.widget.ImageView.setImageBitmap] and
 * [android.widget.ImageView.setImageDrawable] in the target process.
 *
 * **Flow per image**:
 * 1. Intercept the call before it reaches the real ImageView.
 * 2. Apply a lightweight placeholder so the UI is responsive while scanning.
 * 3. On a background coroutine, JPEG-compress the bitmap and send it to
 *    [com.tpeapp.service.FilterService] via AIDL.
 * 4. On callback, post back to the main thread:
 *    - **Safe**: restore the original image.
 *    - **Sensitive**: apply configured censor styling off the main thread.
 */
object ImageViewHook {

    private const val TAG          = "TPE_ImageViewHook"
    private const val JPEG_QUALITY = 70   // compress before sending over Binder
    private const val SCAN_TIMEOUT_MS = 1_200L
    private const val DECISION_CACHE_MAX = 1024
    private val PLACEHOLDER_DRAWABLE = ColorDrawable(Color.argb(180, 24, 24, 24))

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgScope     = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestSeq  = AtomicLong(0)
    private val decisionCache = object : LinkedHashMap<Int, Boolean>(
        DECISION_CACHE_MAX,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Boolean>?): Boolean {
            return size > DECISION_CACHE_MAX
        }
    }
    private val cacheLock = Any()
    private val latestViewRequest = java.util.Collections.synchronizedMap(WeakHashMap<ImageView, Long>())

    /**
     * Re-entrancy guard for the main thread.
     *
     * LSPosed's built-in guard only suppresses re-entry while the hook's
     * [XC_MethodHook.beforeHookedMethod] call-stack is still active.  Once we
     * return from [XC_MethodHook.beforeHookedMethod] and later post a
     * `view.setImageBitmap(…)` callback onto the main thread, the hook fires
     * again, creating an infinite scan–blur loop.
     *
     * Setting this flag to `true` before any internal [ImageView.setImageBitmap]
     * or [ImageView.setImageDrawable] call we initiate prevents the hooks from
     * treating those calls as new user-initiated images to censor.
     */
    private val inHook = ThreadLocal<Boolean>()

    fun install(loader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.ImageView", loader,
                "setImageBitmap", Bitmap::class.java,
                setBitmapHook
            )
            XposedHelpers.findAndHookMethod(
                "android.widget.ImageView", loader,
                "setImageDrawable", Drawable::class.java,
                setDrawableHook
            )
            Log.i(TAG, "ImageView hooks installed")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to install ImageView hooks", e)
        }
    }

    // ------------------------------------------------------------------
    //  Hooks
    // ------------------------------------------------------------------

    private val setBitmapHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (inHook.get() == true) return  // our own internal call — let original proceed
            val bitmap = param.args[0] as? Bitmap ?: return
            val view   = param.thisObject as? ImageView ?: return

            param.result = null
            handleInterceptedBitmap(view, bitmap)
        }
    }

    private val setDrawableHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (inHook.get() == true) return  // our own internal call — let original proceed
            val drawable = param.args[0] as? BitmapDrawable ?: return  // only handle BitmapDrawable
            val bitmap   = drawable.bitmap ?: return
            val view     = param.thisObject as? ImageView ?: return

            param.result = null
            handleInterceptedBitmap(view, bitmap)
        }
    }

    private fun handleInterceptedBitmap(view: ImageView, original: Bitmap) {
        val requestId = requestSeq.incrementAndGet()
        latestViewRequest[view] = requestId

        if (MediaFilterRuntimeConfig.isNudityPermittedByHandler()) {
            runOnUiThread(view) { revealIfLatest(view, requestId, original) }
            return
        }

        val key = fingerprint(original)
        val cached = getCachedDecision(key)
        if (cached != null) {
            val finalBitmap = if (cached) createCensoredBitmap(original) else original
            runOnUiThread(view) { revealIfLatest(view, requestId, finalBitmap) }
            return
        }

        runOnUiThread(view) { setPlaceholder(view) }
        submitForScan(view, original, requestId, key)
    }

    private fun submitForScan(view: ImageView, original: Bitmap, requestId: Long, key: Int) {
        val service = MainHook.filterService
        if (service == null) {
            MainHook.ensureServiceBound(view.context)
            CoverageTelemetry.report(
                lane = CoverageTelemetry.LANE_IMAGEVIEW,
                stage = CoverageTelemetry.STAGE_SERVICE_UNAVAILABLE,
                reason = "filter_service_not_bound"
            )
            putCachedDecision(key, true)
            runOnUiThread(view) {
                revealIfLatest(view, requestId, createCensoredBitmap(original))
            }
            return
        }

        val startedAt = System.currentTimeMillis()

        bgScope.launch {
            val bytes = runCatching {
                ByteArrayOutputStream().use { baos ->
                    original.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                    baos.toByteArray()
                }
            }.getOrNull() ?: run {
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_IMAGEVIEW,
                    stage = CoverageTelemetry.STAGE_ENCODE_FAILED,
                    reason = "jpeg_compress_failed"
                )
                putCachedDecision(key, true)
                runOnUiThread(view) {
                    revealIfLatest(view, requestId, createCensoredBitmap(original))
                }
                return@launch
            }

            runCatching {
                val deferred = CompletableDeferred<Pair<Boolean, Float>>()
                service.scanImageBytes(requestId, bytes, object : IFilterCallback.Stub() {
                    override fun onScanResult(id: Long, isSensitive: Boolean, confidence: Float) {
                        if (!deferred.isCompleted) deferred.complete(isSensitive to confidence)
                    }
                })
                val outcome = withTimeoutOrNull(SCAN_TIMEOUT_MS) { deferred.await() }
                if (outcome == null) {
                    CoverageTelemetry.report(
                        lane = CoverageTelemetry.LANE_IMAGEVIEW,
                        stage = CoverageTelemetry.STAGE_SCAN_TIMEOUT,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        reason = "imageview_timeout",
                    )
                    putCachedDecision(key, true)
                    runOnUiThread(view) {
                        revealIfLatest(view, requestId, createCensoredBitmap(original))
                    }
                    return@runCatching
                }

                val (isSensitive, confidence) = outcome
                Log.d(TAG, "Scan [$requestId] sensitive=$isSensitive confidence=$confidence")
                putCachedDecision(key, isSensitive)
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_IMAGEVIEW,
                    stage = CoverageTelemetry.STAGE_SCAN_RESULT,
                    sensitive = isSensitive,
                    confidence = confidence,
                    latencyMs = System.currentTimeMillis() - startedAt,
                )
                val finalBitmap = if (isSensitive) createCensoredBitmap(original) else original
                runOnUiThread(view) {
                    revealIfLatest(view, requestId, finalBitmap)
                }
            }.onFailure {
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_IMAGEVIEW,
                    stage = CoverageTelemetry.STAGE_SCAN_ERROR,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    reason = it.javaClass.simpleName
                )
                putCachedDecision(key, true)
                runOnUiThread(view) {
                    revealIfLatest(view, requestId, createCensoredBitmap(original))
                }
            }
        }
    }

    private fun setPlaceholder(view: ImageView) {
        inHook.set(true)
        try {
            view.setImageDrawable(PLACEHOLDER_DRAWABLE)
        } finally {
            inHook.set(false)
        }
    }

    private fun revealBitmap(view: ImageView, bitmap: Bitmap) {
        inHook.set(true)
        try {
            view.setImageBitmap(bitmap)
        } finally {
            inHook.set(false)
        }
    }

    private fun revealIfLatest(view: ImageView, requestId: Long, bitmap: Bitmap) {
        val current = latestViewRequest[view]
        if (current != requestId) return
        revealBitmap(view, bitmap)
    }

    private fun runOnUiThread(view: ImageView, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }

        val activity = findActivity(view.context)
        if (activity != null && !activity.isFinishing && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed)) {
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

    private fun createCensoredBitmap(original: Bitmap): Bitmap {
        val mutable = original.copy(original.config ?: Bitmap.Config.ARGB_8888, true) ?: return original
        return runCatching {
            MediaFilterRuntimeConfig.censorBitmapInPlace(mutable)
            mutable
        }.getOrElse {
            mutable.recycle()
            original
        }
    }

    private fun getCachedDecision(key: Int): Boolean? = synchronized(cacheLock) {
        decisionCache[key]
    }

    private fun putCachedDecision(key: Int, sensitive: Boolean) {
        synchronized(cacheLock) {
            decisionCache[key] = sensitive
        }
    }

    private fun fingerprint(bitmap: Bitmap): Int {
        var hash = 31 * bitmap.width + bitmap.height
        val stepX = maxOf(1, bitmap.width / 8)
        val stepY = maxOf(1, bitmap.height / 8)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                hash = 31 * hash + runCatching { bitmap.getPixel(x, y) }.getOrDefault(0)
                x += stepX
            }
            y += stepY
        }
        return hash
    }
}
