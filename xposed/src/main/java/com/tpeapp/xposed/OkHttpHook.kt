package com.tpeapp.xposed

import android.util.Log
import com.tpeapp.filter.IFilterCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Hooks [okhttp3.ResponseBody] to intercept image byte streams at the network
 * layer — **after** download, **before** any image-loading library caches them.
 *
 * This is the lowest-level hook: it catches images loaded by Glide, Coil,
 * Picasso, or any other OkHttp consumer in the target app.
 *
 * **Hooked method**:
 *   `okhttp3.ResponseBody.bytes()` — the fully-buffered byte array path.
 *
 * We also hook the streaming path via `okhttp3.internal.cache.CacheInterceptor`
 * to catch chunked responses before they are written to OkHttp's internal
 * DiskLruCache.
 *
 * **Performance note**: We only scan responses whose `Content-Type` starts
 * with `image/` so non-image network traffic is not affected.
 */
object OkHttpHook {

    private const val TAG             = "TPE_OkHttpHook"
    private const val SCAN_TIMEOUT_MS = 3_000L

    private val requestSeq = AtomicLong(0)

    fun install(loader: ClassLoader) {
        hookResponseBodyBytes(loader)
        hookCacheInterceptor(loader)
    }

    // ------------------------------------------------------------------
    //  ResponseBody.bytes() hook
    // ------------------------------------------------------------------

    private fun hookResponseBodyBytes(loader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "okhttp3.ResponseBody", loader,
                "bytes",
                responseBytesHook
            )
            Log.i(TAG, "OkHttpHook (ResponseBody.bytes) installed")
        }.onFailure { Log.w(TAG, "OkHttpHook (ResponseBody.bytes) not installed", it) }
    }

    private val responseBytesHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val body = param.thisObject ?: return
            if (!isImageContentType(body)) return

            val originalBytes = param.result as? ByteArray ?: return
            val censored      = scanAndCensorBytes(originalBytes)
            if (censored !== originalBytes) {
                param.result = censored
            }
        }
    }

    // ------------------------------------------------------------------
    //  CacheInterceptor hook (intercepts before disk write)
    // ------------------------------------------------------------------

    private fun hookCacheInterceptor(loader: ClassLoader) {
        runCatching {
            // OkHttp 4.x internal class name
            val clazz = "okhttp3.internal.cache.CacheInterceptor"
            XposedHelpers.findAndHookMethod(
                clazz, loader,
                "intercept",
                "okhttp3.Interceptor\$Chain",
                cacheInterceptorHook
            )
            Log.i(TAG, "OkHttpHook (CacheInterceptor) installed")
        }.onFailure { Log.w(TAG, "OkHttpHook (CacheInterceptor) not installed", it) }
    }

    private val cacheInterceptorHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // The response is the return value; replace the body if it is an image.
            val response = param.result ?: return
            runCatching {
                val bodyField = response.javaClass.getDeclaredField("body").apply { isAccessible = true }
                val body      = bodyField.get(response) ?: return
                if (!isImageContentType(body)) return

                val bytesMethod = body.javaClass.getMethod("bytes")
                val originalBytes = bytesMethod.invoke(body) as? ByteArray ?: return
                val censored = scanAndCensorBytes(originalBytes)
                if (censored !== originalBytes) {
                    // Replace with a ByteString-backed ResponseBody
                    val create = Class.forName("okhttp3.ResponseBody")
                        .getMethod("create",
                            Class.forName("okhttp3.MediaType"),
                            ByteArray::class.java
                        )
                    val mediaTypeField = body.javaClass
                        .getDeclaredField("contentType").apply { isAccessible = true }
                    val newBody = create.invoke(null, mediaTypeField.get(body), censored)
                    bodyField.set(response, newBody)
                }
            }.onFailure { Log.w(TAG, "CacheInterceptor body replacement failed", it) }
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun isImageContentType(body: Any): Boolean {
        return runCatching {
            val ctMethod = body.javaClass.getMethod("contentType")
            val ct       = ctMethod.invoke(body)?.toString() ?: return false
            ct.startsWith("image/", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun scanAndCensorBytes(imageBytes: ByteArray): ByteArray {
        val service   = MainHook.filterService ?: return imageBytes
        val requestId = requestSeq.incrementAndGet()

        val latch     = CountDownLatch(1)
        var sensitive = false
        var score     = 0f

        service.scanImageBytes(requestId, imageBytes, object : IFilterCallback.Stub() {
            override fun onScanResult(id: Long, isSensitive: Boolean, confidence: Float) {
                sensitive = isSensitive
                score     = confidence
                latch.countDown()
            }
        })

        latch.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        return if (sensitive) {
            Log.d(TAG, "OkHttp: censoring image [$requestId] score=$score")
            // Use null context — pixelateBytes works without a Context
            // (context is only needed for RenderScript blur; pixelation uses Canvas).
            BlurHelper.pixelateBytes(context = null, imageBytes)
        } else {
            imageBytes
        }
    }
}
