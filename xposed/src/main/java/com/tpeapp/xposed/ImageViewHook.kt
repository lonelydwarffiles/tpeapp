package com.tpeapp.xposed

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
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
 * 2. Apply a Gaussian-blur placeholder so the UI isn't empty while scanning.
 * 3. On a background coroutine, JPEG-compress the bitmap and send it to
 *    [com.tpeapp.service.FilterService] via AIDL.
 * 4. On callback, post back to the main thread:
 *    - **Safe**: restore the original image.
 *    - **Sensitive**: apply a permanent heavy-pixelation overlay.
 */
object ImageViewHook {

    private const val TAG          = "TPE_ImageViewHook"
    private const val JPEG_QUALITY = 70   // compress before sending over Binder

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgScope     = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestSeq  = AtomicLong(0)

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
            val bitmap = param.args[0] as? Bitmap ?: return
            val view   = param.thisObject as? ImageView ?: return

            // Setting a non-null result on XC_MethodHook.MethodHookParam prevents
            // the original (hooked) method from executing.  We use null here because
            // setImageBitmap returns Unit (void); any non-null boxed value would work
            // equally well, but null avoids an unnecessary allocation.
            param.result = null
            val blurred = BlurHelper.blurBitmap(view.context, bitmap, radius = 15)
            view.setImageBitmap(blurred)  // safe recursive call (already a blurred copy)

            submitForScan(view, bitmap)
        }
    }

    private val setDrawableHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val drawable = param.args[0] as? BitmapDrawable ?: return  // only handle BitmapDrawable
            val bitmap   = drawable.bitmap ?: return
            val view     = param.thisObject as? ImageView ?: return

            param.result = null
            val blurred = BlurHelper.blurBitmap(view.context, bitmap, radius = 15)
            view.setImageBitmap(blurred)

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
            mainHandler.post { view.setImageBitmap(original) }
            return
        }

        val requestId = requestSeq.incrementAndGet()

        bgScope.launch {
            val bytes = runCatching {
                ByteArrayOutputStream().use { baos ->
                    original.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                    baos.toByteArray()
                }
            }.getOrNull() ?: run {
                mainHandler.post { view.setImageBitmap(original) }
                return@launch
            }

            service.scanImageBytes(requestId, bytes, object : IFilterCallback.Stub() {
                override fun onScanResult(id: Long, isSensitive: Boolean, confidence: Float) {
                    Log.d(TAG, "Scan [$id] sensitive=$isSensitive confidence=$confidence")
                    mainHandler.post {
                        if (isSensitive) {
                            view.setImageBitmap(BlurHelper.pixelateBitmap(original, blockSize = 20))
                        } else {
                            view.setImageBitmap(original)
                        }
                    }
                }
            })
        }
    }
}
