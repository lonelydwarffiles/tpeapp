package com.tpeapp.xposed

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
    private const val JPEG_Q     = 60
    private const val SCAN_TIMEOUT_MS = 800L
    private const val SCAN_MAX_DIM = 320
    private const val DECISION_CACHE_MAX = 1024

    private val bgScope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

            scanAndReplaceAsync(bitmap)
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
            scanAndReplaceAsync(bitmap)
        }
    }

    // ------------------------------------------------------------------
    //  Shared scan logic (non-blocking, speed-first)
    // ------------------------------------------------------------------

    private fun scanAndReplaceAsync(bitmap: Bitmap) {
        val service = MainHook.filterService ?: run {
            MainHook.getContext()?.let { MainHook.ensureServiceBound(it) }
            CoverageTelemetry.report(
                lane = CoverageTelemetry.LANE_GLIDE,
                stage = CoverageTelemetry.STAGE_SERVICE_UNAVAILABLE,
                reason = "filter_service_not_bound"
            )
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

        val requestId = requestSeq.incrementAndGet()
        val startedAt = System.currentTimeMillis()

        bgScope.launch {
            val bytes = encodeForScan(bitmap)
            if (bytes == null) {
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
                    lane = CoverageTelemetry.LANE_GLIDE,
                    stage = CoverageTelemetry.STAGE_SCAN_ERROR,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    reason = it.javaClass.simpleName,
                )
                inFlight.remove(key)
                return@launch
            }

            val outcome = withTimeoutOrNull(SCAN_TIMEOUT_MS) { deferred.await() }
            if (outcome == null) {
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_GLIDE,
                    stage = CoverageTelemetry.STAGE_SCAN_TIMEOUT,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    reason = "async_timeout",
                )
            } else {
                val (isSensitive, confidence) = outcome
                putCachedDecision(key, isSensitive)
                CoverageTelemetry.report(
                    lane = CoverageTelemetry.LANE_GLIDE,
                    stage = CoverageTelemetry.STAGE_SCAN_RESULT,
                    sensitive = isSensitive,
                    confidence = confidence,
                    latencyMs = System.currentTimeMillis() - startedAt,
                )
                if (isSensitive) {
                    Log.d(TAG, "Glide: replacing sensitive bitmap [$requestId]")
                    censorBitmapInPlace(bitmap)
                }
            }
            inFlight.remove(key)
        }
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
        val pixelated = BlurHelper.pixelateBitmap(bitmap)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawBitmap(pixelated, 0f, 0f, null)
        pixelated.recycle()
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
