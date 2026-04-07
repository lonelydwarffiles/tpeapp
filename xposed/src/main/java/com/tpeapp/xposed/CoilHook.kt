package com.tpeapp.xposed

import android.graphics.Bitmap
import android.util.Log
import com.tpeapp.filter.IFilterCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Hooks Coil's decode pipeline to intercept images after network download
 * but before they reach the memory / disk cache.
 *
 * Coil 2.x decodes via [coil.decode.BitmapFactoryDecoder] which calls
 * `BitmapFactory.decodeStream` internally and wraps the result in a
 * [coil.size.PixelSize]-aware [coil.decode.DecodeResult].
 *
 * We hook [coil.decode.DecodeResult] access via the `ImageLoader`'s
 * `BitmapMemoryCache` put path.  Alternatively we hook
 * `coil.intercept.RealInterceptorChain.proceed` at the
 * [coil.fetch.Fetcher] boundary.
 *
 * Strategy chosen: hook `coil.memory.MemoryCache.set(key, value)` which
 * fires just before the bitmap enters the memory cache, giving us the fully
 * decoded Bitmap before any further consumer uses it.
 */
object CoilHook {

    private const val TAG             = "TPE_CoilHook"
    private const val JPEG_Q          = 70
    private const val SCAN_TIMEOUT_MS = 3_000L

    private val requestSeq = AtomicLong(0)

    fun install(loader: ClassLoader) {
        hookMemoryCache(loader)
        hookDiskCache(loader)
    }

    // ------------------------------------------------------------------
    //  Memory cache hook
    // ------------------------------------------------------------------

    private fun hookMemoryCache(loader: ClassLoader) {
        runCatching {
            // Coil 2.x class: coil.memory.RealMemoryCache
            XposedHelpers.findAndHookMethod(
                "coil.memory.RealMemoryCache", loader,
                "set",
                "coil.memory.MemoryCache\$Key",
                "coil.memory.MemoryCache\$Value",
                memoryCacheSetHook
            )
            Log.i(TAG, "CoilHook (MemoryCache) installed")
        }.onFailure { Log.w(TAG, "CoilHook (MemoryCache) not installed", it) }
    }

    private val memoryCacheSetHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val value  = param.args[1] ?: return
            val bitmap = runCatching {
                val imageField = value.javaClass.getDeclaredField("image").apply { isAccessible = true }
                val image = imageField.get(value)
                // coil.size.Dimension / coil.request.ImageResult differ; try to
                // extract the underlying Bitmap.
                val bitmapField = image?.javaClass?.getDeclaredField("bitmap")?.apply { isAccessible = true }
                bitmapField?.get(image) as? Bitmap
            }.getOrNull() ?: return

            scanAndReplaceSync(bitmap)
        }
    }

    // ------------------------------------------------------------------
    //  Disk cache hook (coil.disk.DiskCache)
    // ------------------------------------------------------------------

    private fun hookDiskCache(loader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "coil.disk.RealDiskCache\$Editor", loader,
                "commitAndGet",
                diskCacheCommitHook
            )
            Log.i(TAG, "CoilHook (DiskCache) installed")
        }.onFailure { Log.w(TAG, "CoilHook (DiskCache) not installed", it) }
    }

    private val diskCacheCommitHook = object : XC_MethodHook() {
        // The snapshot returned contains a file; we cannot easily intercept
        // raw bytes here without reimplementing the Okio source.  Instead we
        // rely on the OkHttpHook for byte-level interception at download time.
        // This hook is a placeholder for future disk-cache scanning.
    }

    // ------------------------------------------------------------------
    //  Shared scan logic
    // ------------------------------------------------------------------

    private fun scanAndReplaceSync(bitmap: Bitmap) {
        val service   = MainHook.filterService ?: return
        val requestId = requestSeq.incrementAndGet()

        val bytes = runCatching {
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, baos)
                baos.toByteArray()
            }
        }.getOrNull() ?: return

        val latch     = CountDownLatch(1)
        var sensitive = false

        service.scanImageBytes(requestId, bytes, object : IFilterCallback.Stub() {
            override fun onScanResult(id: Long, isSensitive: Boolean, confidence: Float) {
                sensitive = isSensitive
                latch.countDown()
            }
        })

        latch.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        if (sensitive) {
            Log.d(TAG, "Coil: replacing sensitive bitmap [$requestId]")
            val pixelated = BlurHelper.pixelateBitmap(bitmap)
            val canvas    = android.graphics.Canvas(bitmap)
            canvas.drawBitmap(pixelated, 0f, 0f, null)
            pixelated.recycle()
        }
    }
}
