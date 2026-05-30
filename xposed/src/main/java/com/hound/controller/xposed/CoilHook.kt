package com.hound.controller.xposed

import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
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
    private const val JPEG_Q          = 60
    private const val SCAN_TIMEOUT_MS = 800L
    private const val SCAN_MAX_DIM = 320
    private const val DECISION_CACHE_MAX = 1024

    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestSeq = AtomicLong(0)
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
        hookMemoryCache(loader)
        hookDiskCache(loader)
    }

    // ------------------------------------------------------------------
    //  Memory cache hook
    // ------------------------------------------------------------------

    private fun hookMemoryCache(loader: ClassLoader) {
        val memoryCacheClass = XposedHelpers.findClassIfExists("coil.memory.RealMemoryCache", loader)
        if (memoryCacheClass == null) {
            Log.i(TAG, "CoilHook (MemoryCache) skipped: class not present")
            return
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                memoryCacheClass,
                "set",
                "coil.memory.MemoryCache\$Key",
                "coil.memory.MemoryCache\$Value",
                memoryCacheSetHook
            )
            Log.i(TAG, "CoilHook (MemoryCache) installed")
        }.onFailure { Log.w(TAG, "CoilHook (MemoryCache) not installed: ${it.javaClass.simpleName}: ${it.message}") }
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

            scanAndReplaceAsync(bitmap)
        }
    }

    // ------------------------------------------------------------------
    //  Disk cache hook (coil.disk.DiskCache)
    // ------------------------------------------------------------------

    private fun hookDiskCache(loader: ClassLoader) {
        val diskCacheEditorClass = XposedHelpers.findClassIfExists("coil.disk.RealDiskCache\$Editor", loader)
        if (diskCacheEditorClass == null) {
            Log.i(TAG, "CoilHook (DiskCache) skipped: class not present")
            return
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                diskCacheEditorClass,
                "commitAndGet",
                diskCacheCommitHook
            )
            Log.i(TAG, "CoilHook (DiskCache) installed")
        }.onFailure { Log.w(TAG, "CoilHook (DiskCache) not installed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private val diskCacheCommitHook = object : XC_MethodHook() {
        // The snapshot returned contains a file; we cannot easily intercept
        // raw bytes here without reimplementing the Okio source.  Instead we
        // rely on the OkHttpHook for byte-level interception at download time.
        // This hook is a placeholder for future disk-cache scanning.
    }

    // ------------------------------------------------------------------
    //  Shared scan logic (non-blocking, speed-first)
    // ------------------------------------------------------------------

    private fun scanAndReplaceAsync(bitmap: Bitmap) {
        val service = MainHook.filterService ?: run {
            MainHook.getContext()?.let { MainHook.ensureServiceBound(it) }
            CoverageTelemetry.report(
                lane = CoverageTelemetry.LANE_COIL,
                stage = CoverageTelemetry.STAGE_SERVICE_UNAVAILABLE,
                reason = "filter_service_not_bound"
            )
            return
        }

        if (MediaFilterRuntimeConfig.isStrictForCurrentPackage()) {
            scanAndReplaceStrict(service, bitmap)
            return
        }

        val key = fingerprint(bitmap)
        val cached = getCachedDecision(key)
        if (cached == true) {
            censorBitmapInPlace(bitmap)
            return
        }
        if (cached == false) return
        if (inFlight.putIfAbsent(key, Unit) != null) return
        if (!MediaFilterRuntimeConfig.tryAcquireScanBudget()) {
            inFlight.remove(key)
            return
        }

        val requestId = requestSeq.incrementAndGet()
        val startedAt = System.currentTimeMillis()

        bgScope.launch {
            val bytes = encodeForScan(bitmap)
            if (bytes == null) {
                MediaFilterRuntimeConfig.releaseScanBudget()
                inFlight.remove(key)
                return@launch
            }

            val deferred = CompletableDeferred<Pair<Boolean, Float>>()
            runCatching {
                service.scanImageBytes(requestId, bytes, object : IFilterCallback.Stub() {
                    override fun onScanResult(id: Long, isSensitive: Boolean, confidence: Float) {
                        if (!deferred.isCompleted) deferred.complete(isSensitive to confidence)
                    }
                })
            }.onFailure {
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_COIL,
                    stage = CoverageTelemetry.STAGE_SCAN_ERROR,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    reason = it.javaClass.simpleName,
                )
                MediaFilterRuntimeConfig.releaseScanBudget()
                inFlight.remove(key)
                return@launch
            }

            val outcome = withTimeoutOrNull(SCAN_TIMEOUT_MS) { deferred.await() }
            if (outcome == null) {
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_COIL,
                    stage = CoverageTelemetry.STAGE_SCAN_TIMEOUT,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    reason = "async_timeout",
                )
            } else {
                val (isSensitive, confidence) = outcome
                putCachedDecision(key, isSensitive)
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_COIL,
                    stage = CoverageTelemetry.STAGE_SCAN_RESULT,
                    sensitive = isSensitive,
                    confidence = confidence,
                    latencyMs = System.currentTimeMillis() - startedAt,
                )
                if (isSensitive) {
                    Log.d(TAG, "Coil: replacing sensitive bitmap [$requestId]")
                    MediaFilterRuntimeConfig.censorBitmapInPlace(bitmap)
                }
            }
            MediaFilterRuntimeConfig.releaseScanBudget()
            inFlight.remove(key)
        }
    }

    private fun scanAndReplaceStrict(
        service: com.tpeapp.filter.IFilterService,
        bitmap: Bitmap,
    ) {
        if (!MediaFilterRuntimeConfig.tryAcquireScanBudget()) return
        val requestId = requestSeq.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        val bytes = encodeForScan(bitmap)
        if (bytes == null) {
            MediaFilterRuntimeConfig.releaseScanBudget()
            return
        }

        val deferred = CompletableDeferred<Pair<Boolean, Float>>()
        val submitted = runCatching {
            service.scanImageBytes(requestId, bytes, object : IFilterCallback.Stub() {
                override fun onScanResult(id: Long, isSensitive: Boolean, confidence: Float) {
                    if (!deferred.isCompleted) deferred.complete(isSensitive to confidence)
                }
            })
        }.isSuccess
        if (!submitted) {
            MediaFilterRuntimeConfig.releaseScanBudget()
            return
        }

        val outcome = runCatching {
            kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) { deferred.await() }
            }
        }.getOrNull()

        MediaFilterRuntimeConfig.releaseScanBudget()

        if (outcome == null) {
            CoverageTelemetry.report(
                lane = CoverageTelemetry.LANE_COIL,
                stage = CoverageTelemetry.STAGE_SCAN_TIMEOUT,
                latencyMs = System.currentTimeMillis() - startedAt,
                reason = "strict_timeout",
            )
            return
        }

        val (isSensitive, confidence) = outcome
        CoverageTelemetry.report(
            lane = CoverageTelemetry.LANE_COIL,
            stage = CoverageTelemetry.STAGE_SCAN_RESULT,
            sensitive = isSensitive,
            confidence = confidence,
            latencyMs = System.currentTimeMillis() - startedAt,
        )
        if (isSensitive) MediaFilterRuntimeConfig.censorBitmapInPlace(bitmap)
    }

    private fun encodeForScan(bitmap: Bitmap): ByteArray? = runCatching {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxDim > SCAN_MAX_DIM) {
            val ratio = SCAN_MAX_DIM.toFloat() / maxDim.toFloat()
            val w = maxOf(1, (bitmap.width * ratio).toInt())
            val h = maxOf(1, (bitmap.height * ratio).toInt())
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else bitmap

        val bytes = ByteArrayOutputStream().use { baos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, baos)
            baos.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        bytes
    }.getOrNull()

    private fun censorBitmapInPlace(bitmap: Bitmap) {
        MediaFilterRuntimeConfig.censorBitmapInPlace(bitmap)
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

