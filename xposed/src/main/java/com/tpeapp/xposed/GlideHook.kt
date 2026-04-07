package com.tpeapp.xposed

import android.graphics.Bitmap
import android.util.Log
import com.tpeapp.filter.IFilterCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Hooks Glide's disk-cache write path so images are scanned **before** they
 * are written to the DiskLruCache.
 *
 * Target method:
 *   `com.bumptech.glide.load.engine.cache.DiskLruCacheWrapper`
 *   `.put(Key key, Writer writer)`
 *
 * Because the actual file write is delegated to a [Writer] callback, we
 * intercept at the [com.bumptech.glide.load.engine.EngineJob] bitmap-ready
 * callback instead — specifically `onResourceReady` — where we have access
 * to the decoded [Bitmap] before it is placed into the memory/disk cache.
 *
 * If sensitive content is found the Bitmap pixels are replaced in-place
 * with the pixelated version so every subsequent consumer (cache, ImageView)
 * receives the censored copy.
 */
object GlideHook {

    private const val TAG        = "TPE_GlideHook"
    private const val JPEG_Q     = 70
    private const val SCAN_TIMEOUT_MS = 3_000L

    private val bgScope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestSeq = AtomicLong(0)

    fun install(loader: ClassLoader) {
        hookEngineJob(loader)
        hookBitmapPool(loader)
    }

    // ------------------------------------------------------------------
    //  Hook EngineJob#onResourceReady (fires just before cache put)
    // ------------------------------------------------------------------

    private fun hookEngineJob(loader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.bumptech.glide.load.engine.EngineJob", loader,
                "onResourceReady",
                "com.bumptech.glide.load.engine.Resource",
                "com.bumptech.glide.load.DataSource",
                Boolean::class.javaPrimitiveType,
                engineJobHook
            )
            Log.i(TAG, "GlideHook (EngineJob) installed")
        }.onFailure { Log.w(TAG, "GlideHook (EngineJob) not installed", it) }
    }

    private val engineJobHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val resource = param.args[0] ?: return
            val bitmap = runCatching {
                val getMethod = resource.javaClass.getMethod("get")
                getMethod.invoke(resource) as? Bitmap
            }.getOrNull() ?: return

            scanAndReplaceSync(bitmap)
        }
    }

    // Glide pool recycles bitmaps — hook acquire to catch recycled cases too.
    private fun hookBitmapPool(loader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.bumptech.glide.load.resource.bitmap.BitmapResource", loader,
                "get",
                bitmapResourceGetHook
            )
            Log.i(TAG, "GlideHook (BitmapResource) installed")
        }.onFailure { Log.w(TAG, "GlideHook (BitmapResource) not installed", it) }
    }

    private val bitmapResourceGetHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val bitmap = param.result as? Bitmap ?: return
            scanAndReplaceSync(bitmap)
        }
    }

    // ------------------------------------------------------------------
    //  Shared scan logic (synchronous within timeout so cache write waits)
    // ------------------------------------------------------------------

    private fun scanAndReplaceSync(bitmap: Bitmap) {
        val service = MainHook.filterService ?: return
        val requestId = requestSeq.incrementAndGet()

        val bytes = runCatching {
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, baos)
                baos.toByteArray()
            }
        }.getOrNull() ?: return

        val latch  = CountDownLatch(1)
        var sensitive = false

        service.scanImageBytes(requestId, bytes, object : IFilterCallback.Stub() {
            override fun onScanResult(id: Long, isSensitive: Boolean, confidence: Float) {
                sensitive = isSensitive
                latch.countDown()
            }
        })

        // Block up to SCAN_TIMEOUT_MS — acceptable since we are on Glide's
        // background decode thread (never the main thread).
        latch.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        if (sensitive) {
            Log.d(TAG, "Glide: replacing sensitive bitmap [$requestId]")
            val pixelated = BlurHelper.pixelateBitmap(bitmap)
            // Replace pixels in-place so the same Bitmap object is censored
            // before Glide writes it into cache.
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawBitmap(pixelated, 0f, 0f, null)
            pixelated.recycle()
        }
    }
}
