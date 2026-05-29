package com.tpeapp.xposed

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import com.tpeapp.filter.IFilterCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
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
    private val PLACEHOLDER_DRAWABLE = ColorDrawable(Color.argb(180, 24, 24, 24))

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgScope     = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestSeq  = AtomicLong(0)

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

            // Setting a non-null result on XC_MethodHook.MethodHookParam prevents
            // the original (hooked) method from executing.  We use null here because
            // setImageBitmap returns Unit (void); any non-null boxed value would work
            // equally well, but null avoids an unnecessary allocation.
            param.result = null
            inHook.set(true)
            try {
                view.setImageDrawable(PLACEHOLDER_DRAWABLE)
            } finally {
                inHook.set(false)
            }

            submitForScan(view, bitmap)
        }
    }

    private val setDrawableHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (inHook.get() == true) return  // our own internal call — let original proceed
            val drawable = param.args[0] as? BitmapDrawable ?: return  // only handle BitmapDrawable
            val bitmap   = drawable.bitmap ?: return
            val view     = param.thisObject as? ImageView ?: return

            param.result = null
            inHook.set(true)
            try {
                view.setImageDrawable(PLACEHOLDER_DRAWABLE)
            } finally {
                inHook.set(false)
            }

            submitForScan(view, bitmap)
        }
    }

    // ------------------------------------------------------------------
    //  Scan + update
    // ------------------------------------------------------------------

    private fun submitForScan(view: ImageView, original: Bitmap) {
        val service = MainHook.filterService
        if (service == null) {
            // Service not yet bound; bind now and show original to avoid blank screen.
            MainHook.ensureServiceBound(view.context)
            CoverageTelemetry.report(
                lane = CoverageTelemetry.LANE_IMAGEVIEW,
                stage = CoverageTelemetry.STAGE_SERVICE_UNAVAILABLE,
                reason = "filter_service_not_bound"
            )
            mainHandler.post {
                inHook.set(true)
                try { view.setImageBitmap(original) } finally { inHook.set(false) }
            }
            return
        }

        val requestId = requestSeq.incrementAndGet()
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
                mainHandler.post {
                    inHook.set(true)
                    try { view.setImageBitmap(original) } finally { inHook.set(false) }
                }
                return@launch
            }

            runCatching {
                service.scanImageBytes(requestId, bytes, object : IFilterCallback.Stub() {
                    override fun onScanResult(id: Long, isSensitive: Boolean, confidence: Float) {
                        Log.d(TAG, "Scan [$id] sensitive=$isSensitive confidence=$confidence")
                        CoverageTelemetry.report(
                            lane = CoverageTelemetry.LANE_IMAGEVIEW,
                            stage = CoverageTelemetry.STAGE_SCAN_RESULT,
                            sensitive = isSensitive,
                            confidence = confidence,
                            latencyMs = System.currentTimeMillis() - startedAt,
                        )
                        val finalBitmap = if (isSensitive) {
                            createCensoredBitmap(original)
                        } else {
                            original
                        }
                        mainHandler.post {
                            inHook.set(true)
                            try {
                                view.setImageBitmap(finalBitmap)
                            } finally {
                                inHook.set(false)
                            }
                        }

                    }
                })
            }.onFailure {
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_IMAGEVIEW,
                    stage = CoverageTelemetry.STAGE_SCAN_ERROR,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    reason = it.javaClass.simpleName
                )
                mainHandler.post {
                    inHook.set(true)
                    try { view.setImageBitmap(original) } finally { inHook.set(false) }
                }
            }
        }
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
}
