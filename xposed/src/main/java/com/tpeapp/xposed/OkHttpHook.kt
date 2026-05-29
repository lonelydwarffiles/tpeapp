package com.tpeapp.xposed

import android.util.Log
import com.tpeapp.filter.IFilterCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
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
    private const val SCAN_TIMEOUT_MS = 800L
    private const val DECISION_CACHE_MAX = 1024

    private val requestSeq = AtomicLong(0)
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
    private val inFlight = ConcurrentHashMap<Int, Unit>()

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
        val service = MainHook.filterService ?: run {
            MainHook.getContext()?.let { MainHook.ensureServiceBound(it) }
            CoverageTelemetry.report(
                lane = CoverageTelemetry.LANE_OKHTTP,
                stage = CoverageTelemetry.STAGE_SERVICE_UNAVAILABLE,
                reason = "filter_service_not_bound"
            )
            return imageBytes
        }

        val key = fingerprint(imageBytes)
        val cachedDecision = getCachedDecision(key)
        if (cachedDecision == true) {
            return BlurHelper.pixelateBytes(context = null, imageBytes)
        }
        if (cachedDecision == false) {
            return imageBytes
        }

        // Unknown image: scan in the background and fail-open for this response.
        maybeScheduleAsyncScan(service, key, imageBytes)
        return imageBytes
    }

    private fun maybeScheduleAsyncScan(
        service: com.tpeapp.filter.IFilterService,
        key: Int,
        imageBytes: ByteArray,
    ) {
        if (inFlight.putIfAbsent(key, Unit) != null) return

        val requestId = requestSeq.incrementAndGet()
        val startedAt = System.currentTimeMillis()

        val resultDeferred = CompletableDeferred<Pair<Boolean, Float>>()

        runCatching {
            service.scanImageBytes(requestId, imageBytes, object : IFilterCallback.Stub() {
                override fun onScanResult(id: Long, isSensitive: Boolean, confidence: Float) {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(isSensitive to confidence)
                    }
                }
            })
        }.onFailure {
            inFlight.remove(key)
            CoverageTelemetry.report(
                lane = CoverageTelemetry.LANE_OKHTTP,
                stage = CoverageTelemetry.STAGE_SCAN_ERROR,
                latencyMs = System.currentTimeMillis() - startedAt,
                reason = it.javaClass.simpleName
            )
            return
        }

        bgScope.launch {
            val outcome = withTimeoutOrNull(SCAN_TIMEOUT_MS) { resultDeferred.await() }
            if (outcome == null) {
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_OKHTTP,
                    stage = CoverageTelemetry.STAGE_SCAN_TIMEOUT,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    reason = "async_timeout"
                )
            } else {
                val (isSensitive, confidence) = outcome
                putCachedDecision(key, isSensitive)
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_OKHTTP,
                    stage = CoverageTelemetry.STAGE_SCAN_RESULT,
                    sensitive = isSensitive,
                    confidence = confidence,
                    latencyMs = System.currentTimeMillis() - startedAt,
                )
                if (isSensitive) {
                    Log.d(TAG, "OkHttp: cached sensitive image [$requestId] score=$confidence")
                }
            }
            inFlight.remove(key)
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

    private fun fingerprint(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val step = maxOf(1, bytes.size / 64)
        var hash = bytes.size
        var i = 0
        while (i < bytes.size) {
            hash = 31 * hash + bytes[i].toInt()
            i += step
        }
        return hash
    }
}
