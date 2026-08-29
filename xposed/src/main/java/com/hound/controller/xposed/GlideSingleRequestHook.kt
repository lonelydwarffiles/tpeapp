package com.hound.controller.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Framework ImageView hook for asynchronous IPC moderation checks.
 *
 * Targets:
 * - android.widget.ImageView#setImageBitmap(Bitmap)
 * - android.widget.ImageView#setImageDrawable(Drawable)
 */
object GlideSingleRequestHook {

    private const val TAG = "TPE_GlideSingleRequestHook"
    private const val JPEG_QUALITY = 70
    private const val DECISION_CACHE_MAX = 1024
    private const val DECISION_CACHE_TTL_MS = 2 * 60 * 1000L
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestSeq = AtomicLong(0)
    private val latestViewRequest = Collections.synchronizedMap(WeakHashMap<ImageView, Long>())
    private val suppressedViewCalls = Collections.synchronizedMap(WeakHashMap<ImageView, Int>())
    private val cacheLock = Any()
    private val decisionCache = object : LinkedHashMap<Int, CacheEntry>(
        DECISION_CACHE_MAX,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CacheEntry>?): Boolean {
            return size > DECISION_CACHE_MAX
        }
    }
    private val inFlight = HashSet<Int>()

    private data class CacheEntry(
        val shouldCensor: Boolean,
        val updatedAtMs: Long,
    )

    fun install(loader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.ImageView",
                loader,
                "setImageBitmap",
                Bitmap::class.java,
                setBitmapHook,
            )
            XposedHelpers.findAndHookMethod(
                "android.widget.ImageView",
                loader,
                "setImageDrawable",
                Drawable::class.java,
                setDrawableHook,
            )
            Log.i(TAG, "Hook installed: ImageView#setImageBitmap/setImageDrawable")
        }.onFailure {
            Log.w(TAG, "Failed to install ImageView hooks: ${it.message}")
        }
    }

    private val setBitmapHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val view = param.thisObject as? ImageView ?: return
            if (consumeSuppressedCall(view)) return
            val sourceBitmap = param.args.getOrNull(0) as? Bitmap ?: return
            analyzeAsyncForView(view, sourceBitmap)
        }
    }

    private val setDrawableHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val view = param.thisObject as? ImageView ?: return
            if (consumeSuppressedCall(view)) return
            val drawable = param.args.getOrNull(0) as? Drawable ?: return
            val sourceBitmap = drawableToBitmap(drawable) ?: return
            analyzeAsyncForView(view, sourceBitmap)
        }
    }

    private fun analyzeAsyncForView(view: ImageView, sourceBitmap: Bitmap) {
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) return

        // Instant pass-through: original setImage* already executed.
        val viewRef = WeakReference(view)
        val requestId = requestSeq.incrementAndGet()
        latestViewRequest[view] = requestId
        val fingerprint = fingerprint(sourceBitmap)

        val cachedDecision = getCachedDecision(fingerprint)
        if (cachedDecision != null) {
            if (cachedDecision) {
                postPlaceholderIfLatest(viewRef, requestId, sourceBitmap.width, sourceBitmap.height)
            }
            return
        }

        if (!markInFlight(fingerprint)) return

        ioScope.launch {
            try {
                val payload = encodeBitmap(sourceBitmap) ?: return@launch
                val shouldCensor = analyzeViaIpc(payload)
                putCachedDecision(fingerprint, shouldCensor)
                if (!shouldCensor) return@launch

                postPlaceholderIfLatest(viewRef, requestId, sourceBitmap.width, sourceBitmap.height)
            } finally {
                clearInFlight(fingerprint)
            }
        }
    }

    private fun postPlaceholderIfLatest(
        viewRef: WeakReference<ImageView>?,
        requestId: Long,
        width: Int,
        height: Int,
    ) {
        val view = viewRef?.get() ?: return
        val placeholder = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        mainHandler.post {
            val stillLatest = latestViewRequest[view] == requestId
            if (!stillLatest) {
                if (!placeholder.isRecycled) placeholder.recycle()
                return@post
            }
            runCatching {
                markSuppressedCall(view)
                view.setImageBitmap(placeholder)
            }.onFailure {
                if (!placeholder.isRecycled) placeholder.recycle()
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        val bitmapDrawable = drawable as? BitmapDrawable
        val bitmap = bitmapDrawable?.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            return bitmap
        }

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        return runCatching {
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            out
        }.getOrNull()
    }

    private fun analyzeViaIpc(imageBytes: ByteArray): Boolean {
        val service = MainHook.onnxIpcService
        if (service != null) {
            return runCatching { service.analyzeImage(imageBytes) }.getOrDefault(false)
        }

        val fallback = MainHook.filterService
        if (fallback != null) {
            return runCatching { fallback.hasForbiddenContent(imageBytes) }.getOrDefault(false)
        }

        MainHook.getContext()?.let {
            MainHook.ensureOnnxServiceBound(it)
            MainHook.ensureServiceBound(it)
        }
        return false
    }

    private fun encodeBitmap(bitmap: Bitmap): ByteArray? {
        return runCatching {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun getCachedDecision(fingerprint: Int): Boolean? {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val entry = decisionCache[fingerprint] ?: return null
            if (now - entry.updatedAtMs > DECISION_CACHE_TTL_MS) {
                decisionCache.remove(fingerprint)
                return null
            }
            return entry.shouldCensor
        }
    }

    private fun putCachedDecision(fingerprint: Int, shouldCensor: Boolean) {
        synchronized(cacheLock) {
            decisionCache[fingerprint] = CacheEntry(
                shouldCensor = shouldCensor,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun markInFlight(fingerprint: Int): Boolean {
        synchronized(cacheLock) {
            if (inFlight.contains(fingerprint)) return false
            inFlight.add(fingerprint)
            return true
        }
    }

    private fun clearInFlight(fingerprint: Int) {
        synchronized(cacheLock) {
            inFlight.remove(fingerprint)
        }
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

    private fun fingerprint(bitmap: Bitmap): Int {
        var hash = 31 * bitmap.width + bitmap.height
        val stepX = maxOf(1, bitmap.width / 8)
        val stepY = maxOf(1, bitmap.height / 8)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = runCatching { bitmap.getPixel(x, y) }.getOrDefault(0)
                hash = 31 * hash + pixel
                x += stepX
            }
            y += stepY
        }
        return hash
    }
}
