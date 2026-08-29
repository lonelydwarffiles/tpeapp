package com.hound.controller.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.Display
import android.widget.ImageView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hound.controller.filter.IOnnxIpcService
import com.hound.controller.util.BackgroundBleHapticTrigger
import com.hound.controller.util.CensorshipType
import com.hound.controller.util.ImageCensorshipEffects
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Accessibility-based visual scanner that captures live screen content from selected apps
 * and forwards frames/crops to OnnxIpcService for async NSFW analysis.
 */
class ScreenScannerService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenScannerService"
        private const val CAPTURE_DEBOUNCE_MS = 200L
        private const val JPEG_QUALITY = 80
        private const val PIXELATE_MIN_SCALE = 0.02f
        private const val PIXELATE_MAX_SCALE = 0.15f
        private const val GHOST_SWIPE_MIN_READING_MS = 45_000L
        private const val GHOST_SWIPE_ACTIVE_SCROLL_WINDOW_MS = 12_000L
        private const val GHOST_SWIPE_COOLDOWN_MS = 60_000L
        private const val GHOST_SWIPE_PROBABILITY = 0.004f
        private const val GHOST_SWIPE_DISTANCE_PX = 150f
        private const val GHOST_SWIPE_DURATION_MS = 300L
        private const val BLE_HAPTIC_PROBABILITY = 0.08f
        private const val BLE_HAPTIC_COOLDOWN_MS = 8_000L

        private const val PREF_GHOST_SWIPE_MIN_READING_MS = "ghost_swipe_min_reading_ms"
        private const val PREF_GHOST_SWIPE_ACTIVE_SCROLL_WINDOW_MS = "ghost_swipe_active_scroll_window_ms"
        private const val PREF_GHOST_SWIPE_COOLDOWN_MS = "ghost_swipe_cooldown_ms"
        private const val PREF_GHOST_SWIPE_PROBABILITY = "ghost_swipe_probability"
        private const val PREF_GHOST_SWIPE_DISTANCE_PX = "ghost_swipe_distance_px"
        private const val PREF_GHOST_SWIPE_DURATION_MS = "ghost_swipe_duration_ms"
        private const val PREF_BLE_HAPTIC_PROBABILITY = "ble_haptic_probability"
        private const val PREF_BLE_HAPTIC_COOLDOWN_MS = "ble_haptic_cooldown_ms"
        private val TARGET_PACKAGES = setOf(
            "com.twitter.android",
            "com.reddit.frontpage",
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastCaptureAtMs = AtomicLong(0L)
    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screen-scanner-shot").apply { isDaemon = true }
    }
    private val windowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }
    private val prefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
    }
    private val overlayViews = LinkedHashMap<String, OverlayEntry>()
    private val screenshotLock = Any()

    private data class OverlayEntry(
        val view: ImageView,
        val params: WindowManager.LayoutParams,
        val censorshipType: CensorshipType,
    )

    @Volatile
    private var latestScreenshotBitmap: Bitmap? = null

    @Volatile
    private var foregroundPackage: String = ""

    @Volatile
    private var readingSessionStartAtMs: Long = 0L

    @Volatile
    private var lastScrollAtMs: Long = 0L

    @Volatile
    private var lastGhostSwipeAtMs: Long = 0L

    @Volatile
    private var onnxService: IOnnxIpcService? = null

    private val onnxConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            onnxService = IOnnxIpcService.Stub.asInterface(service)
            Log.i(TAG, "OnnxIpcService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onnxService = null
            Log.w(TAG, "OnnxIpcService disconnected")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bindOnnxServiceIfNeeded()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()?.trim().orEmpty()
                foregroundPackage = pkg
                readingSessionStartAtMs = if (pkg in TARGET_PACKAGES) System.currentTimeMillis() else 0L
                lastScrollAtMs = 0L
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (foregroundPackage in TARGET_PACKAGES) {
                    val now = System.currentTimeMillis()
                    if (readingSessionStartAtMs == 0L) {
                        readingSessionStartAtMs = now
                    }
                    lastScrollAtMs = now
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                maybeInjectGhostSwipe()
                if (!shouldCaptureNow()) return
                captureAndScanForegroundWindow()
            }
        }
    }

    override fun onInterrupt() {
        // No-op: scanner has no long-lived spoken feedback.
    }

    override fun onDestroy() {
        runCatching { unbindService(onnxConnection) }
        clearAllCensorshipOverlays()
        synchronized(screenshotLock) {
            latestScreenshotBitmap?.let {
                if (!it.isRecycled) {
                    runCatching { it.recycle() }
                }
            }
            latestScreenshotBitmap = null
        }
        runCatching { screenshotExecutor.shutdownNow() }
        super.onDestroy()
    }

    fun drawCensorshipOverlay(rect: Rect) {
        if (rect.isEmpty) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; cannot draw censorship overlay")
            return
        }

        val clampedRect = Rect(rect)
        val key = overlayKey(clampedRect)
        synchronized(overlayViews) {
            val existing = overlayViews[key]
            if (existing != null) {
                existing.params.x = clampedRect.left
                existing.params.y = clampedRect.top
                existing.params.width = clampedRect.width().coerceAtLeast(1)
                existing.params.height = clampedRect.height().coerceAtLeast(1)
                applyOverlayStyle(existing.view, clampedRect, existing.censorshipType)
                runCatching { windowManager.updateViewLayout(existing.view, existing.params) }
                return
            }

            val randomType = CensorshipType.getRandomType()
            val overlayView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 1.0f
            }
            applyOverlayStyle(overlayView, clampedRect, randomType)
            val params = WindowManager.LayoutParams(
                clampedRect.width().coerceAtLeast(1),
                clampedRect.height().coerceAtLeast(1),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = clampedRect.left
                y = clampedRect.top
            }

            runCatching {
                windowManager.addView(overlayView, params)
                overlayViews[key] = OverlayEntry(overlayView, params, randomType)
            }.onFailure {
                Log.w(TAG, "Failed to add censorship overlay", it)
            }
        }
    }

    fun clearCensorshipOverlay(rect: Rect) {
        val key = overlayKey(rect)
        synchronized(overlayViews) {
            val entry = overlayViews.remove(key) ?: return
            runCatching { windowManager.removeView(entry.view) }
        }
    }

    fun moveCensorshipOverlay(fromRect: Rect, toRect: Rect) {
        val fromKey = overlayKey(fromRect)
        synchronized(overlayViews) {
            val entry = overlayViews.remove(fromKey) ?: return
            val newKey = overlayKey(toRect)
            entry.params.x = toRect.left
            entry.params.y = toRect.top
            entry.params.width = toRect.width().coerceAtLeast(1)
            entry.params.height = toRect.height().coerceAtLeast(1)
            runCatching { windowManager.updateViewLayout(entry.view, entry.params) }
                .onFailure {
                    Log.w(TAG, "Failed to move censorship overlay", it)
                }
            overlayViews[newKey] = entry
        }
    }

    fun syncCensorshipOverlays(visibleRects: Collection<Rect>) {
        val incoming = visibleRects
            .filter { !it.isEmpty }
            .map { Rect(it) }
            .associateBy { overlayKey(it) }

        synchronized(overlayViews) {
            val toRemove = overlayViews.keys.filter { key -> !incoming.containsKey(key) }
            for (key in toRemove) {
                val entry = overlayViews.remove(key) ?: continue
                runCatching { windowManager.removeView(entry.view) }
            }
        }

        incoming.values.forEach { rect ->
            drawCensorshipOverlay(rect)
        }
    }

    private fun clearAllCensorshipOverlays() {
        synchronized(overlayViews) {
            for ((_, entry) in overlayViews) {
                releaseImageViewBitmap(entry.view)
                runCatching { windowManager.removeView(entry.view) }
            }
            overlayViews.clear()
        }
    }

    private fun applyOverlayStyle(
        overlayView: ImageView,
        rect: Rect,
        censorshipType: CensorshipType,
    ) {
        when (censorshipType) {
            CensorshipType.SOLID_COLOR -> applySolidColorStyle(overlayView)
            CensorshipType.PIXELATE -> applyPixelateStyle(overlayView, rect)
            CensorshipType.BLUR -> applyBlurStyle(overlayView, rect)
            CensorshipType.VHS_GLITCH -> applyVhsGlitchStyle(overlayView, rect)
        }
    }

    private fun applySolidColorStyle(overlayView: ImageView) {
        releaseImageViewBitmap(overlayView)
        overlayView.setRenderEffect(null)
        overlayView.setImageDrawable(null)
        overlayView.setBackgroundColor(Color.BLACK)
    }

    private fun applyPixelateStyle(overlayView: ImageView, rect: Rect) {
        val region = extractSnapshotRegion(rect)
        if (region == null) {
            applySolidColorStyle(overlayView)
            return
        }

        val out = runCatching {
            val scale = Random.nextFloat() * (PIXELATE_MAX_SCALE - PIXELATE_MIN_SCALE) + PIXELATE_MIN_SCALE
            val downW = (region.width * scale).toInt().coerceAtLeast(1)
            val downH = (region.height * scale).toInt().coerceAtLeast(1)

            val tiny = Bitmap.createScaledBitmap(region, downW, downH, false)
            try {
                Bitmap.createScaledBitmap(
                    tiny,
                    region.width.coerceAtLeast(1),
                    region.height.coerceAtLeast(1),
                    false,
                )
            } finally {
                if (!tiny.isRecycled) {
                    tiny.recycle()
                }
            }
        }.getOrNull()

        if (!region.isRecycled) {
            region.recycle()
        }

        if (out == null) {
            applySolidColorStyle(overlayView)
            return
        }

        releaseImageViewBitmap(overlayView)
        overlayView.setRenderEffect(null)
        overlayView.setBackgroundColor(Color.TRANSPARENT)
        overlayView.setImageBitmap(out)
    }

    private fun applyBlurStyle(overlayView: ImageView, rect: Rect) {
        val region = extractSnapshotRegion(rect)
        if (region == null) {
            applySolidColorStyle(overlayView)
            return
        }

        releaseImageViewBitmap(overlayView)
        overlayView.setBackgroundColor(Color.TRANSPARENT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            overlayView.setImageBitmap(region)
            overlayView.setRenderEffect(RenderEffect.createBlurEffect(14f, 14f, Shader.TileMode.CLAMP))
        } else {
            val blurred = boxBlur(region, 6)
            if (!region.isRecycled) {
                region.recycle()
            }
            overlayView.setRenderEffect(null)
            overlayView.setImageBitmap(blurred)
        }
    }

    private fun applyVhsGlitchStyle(overlayView: ImageView, rect: Rect) {
        val region = extractSnapshotRegion(rect)
        if (region == null) {
            applySolidColorStyle(overlayView)
            return
        }

        val glitched = runCatching {
            ImageCensorshipEffects.applyVhsGlitch(region)
        }.getOrNull()

        if (glitched == null) {
            if (!region.isRecycled) {
                runCatching { region.recycle() }
            }
            applySolidColorStyle(overlayView)
            return
        }

        releaseImageViewBitmap(overlayView)
        overlayView.setRenderEffect(null)
        overlayView.setBackgroundColor(Color.TRANSPARENT)
        overlayView.setImageBitmap(glitched)
    }

    private fun extractSnapshotRegion(rect: Rect): Bitmap? {
        val source = synchronized(screenshotLock) { latestScreenshotBitmap } ?: return null
        if (source.isRecycled) return null

        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        val width = right - left
        val height = bottom - top
        if (width <= 1 || height <= 1) return null

        return runCatching {
            Bitmap.createBitmap(source, left, top, width, height)
        }.getOrNull()
    }

    private fun releaseImageViewBitmap(overlayView: ImageView) {
        val oldBitmap = (overlayView.drawable as? BitmapDrawable)?.bitmap
        overlayView.setImageDrawable(null)
        if (oldBitmap != null && !oldBitmap.isRecycled) {
            runCatching { oldBitmap.recycle() }
        }
    }

    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceAtLeast(1)
        val w = src.width
        val h = src.height
        val inPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        src.getPixels(inPixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                val xStart = (x - r).coerceAtLeast(0)
                val xEnd = (x + r).coerceAtMost(w - 1)
                for (xx in xStart..xEnd) {
                    val c = inPixels[row + xx]
                    a += (c ushr 24) and 0xFF
                    red += (c ushr 16) and 0xFF
                    green += (c ushr 8) and 0xFF
                    blue += c and 0xFF
                    count++
                }
                outPixels[row + x] =
                    ((a / count) shl 24) or
                        ((red / count) shl 16) or
                        ((green / count) shl 8) or
                        (blue / count)
            }
        }

        val finalPixels = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                val yStart = (y - r).coerceAtLeast(0)
                val yEnd = (y + r).coerceAtMost(h - 1)
                for (yy in yStart..yEnd) {
                    val c = outPixels[(yy * w) + x]
                    a += (c ushr 24) and 0xFF
                    red += (c ushr 16) and 0xFF
                    green += (c ushr 8) and 0xFF
                    blue += c and 0xFF
                    count++
                }
                finalPixels[(y * w) + x] =
                    ((a / count) shl 24) or
                        ((red / count) shl 16) or
                        ((green / count) shl 8) or
                        (blue / count)
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(finalPixels, 0, w, 0, 0, w, h)
        }
    }

    private fun overlayKey(rect: Rect): String {
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    private fun shouldCaptureNow(): Boolean {
        if (foregroundPackage !in TARGET_PACKAGES) return false
        val now = System.currentTimeMillis()
        if (readingSessionStartAtMs == 0L) {
            readingSessionStartAtMs = now
        }
        val last = lastCaptureAtMs.get()
        if (now - last < CAPTURE_DEBOUNCE_MS) return false
        return lastCaptureAtMs.compareAndSet(last, now)
    }

    private fun maybeInjectGhostSwipe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (foregroundPackage !in TARGET_PACKAGES) return

        val now = System.currentTimeMillis()
        val minReadingMs = prefs.getLong(PREF_GHOST_SWIPE_MIN_READING_MS, GHOST_SWIPE_MIN_READING_MS)
        val activeScrollWindowMs = prefs.getLong(PREF_GHOST_SWIPE_ACTIVE_SCROLL_WINDOW_MS, GHOST_SWIPE_ACTIVE_SCROLL_WINDOW_MS)
        val cooldownMs = prefs.getLong(PREF_GHOST_SWIPE_COOLDOWN_MS, GHOST_SWIPE_COOLDOWN_MS)
        val probability = prefs.getFloat(PREF_GHOST_SWIPE_PROBABILITY, GHOST_SWIPE_PROBABILITY).coerceIn(0f, 1f)

        if (readingSessionStartAtMs == 0L || now - readingSessionStartAtMs < minReadingMs.coerceAtLeast(0L)) return
        if (now - lastScrollAtMs > activeScrollWindowMs.coerceAtLeast(0L)) return
        if (now - lastGhostSwipeAtMs < cooldownMs.coerceAtLeast(0L)) return
        if (Random.nextFloat() > probability) return

        injectGhostSwipe()
        lastGhostSwipeAtMs = now
    }

    private fun injectGhostSwipe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val dm = resources.displayMetrics
        val startX = (dm.widthPixels * 0.5f) + Random.nextInt(-20, 21)
        val startY = (dm.heightPixels * 0.6f) + Random.nextInt(-20, 21)
        val distancePx = prefs.getFloat(PREF_GHOST_SWIPE_DISTANCE_PX, GHOST_SWIPE_DISTANCE_PX)
        val durationMs = prefs.getLong(PREF_GHOST_SWIPE_DURATION_MS, GHOST_SWIPE_DURATION_MS)
        val endY = (startY - distancePx.coerceAtLeast(20f)).coerceAtLeast(40f)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(100L, 1_500L),
                )
            )
            .build()

        runCatching {
            dispatchGesture(gesture, null, null)
        }.onFailure {
            Log.w(TAG, "Ghost swipe dispatch failed", it)
        }
    }

    private fun captureAndScanForegroundWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        bindOnnxServiceIfNeeded()

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    handleScreenshotResult(screenshot)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed code=$errorCode")
                }
            },
        )
    }

    private fun handleScreenshotResult(result: ScreenshotResult) {
        val hardwareBuffer: HardwareBuffer = result.hardwareBuffer ?: return
        val colorSpace: ColorSpace = result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)

        val wrapped = try {
            Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
        } catch (t: Throwable) {
            Log.w(TAG, "wrapHardwareBuffer failed", t)
            null
        } finally {
            runCatching { hardwareBuffer.close() }
        } ?: return

        // Hardware-backed bitmap cannot be encoded directly in all paths, so copy to ARGB_8888.
        val screenshotBitmap = runCatching {
            wrapped.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
        if (!wrapped.isRecycled) {
            runCatching { wrapped.recycle() }
        }
        if (screenshotBitmap == null) return

        synchronized(screenshotLock) {
            latestScreenshotBitmap?.let {
                if (!it.isRecycled) {
                    runCatching { it.recycle() }
                }
            }
            latestScreenshotBitmap = screenshotBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        scope.launch {
            try {
                val crops = cropVisibleMediaRegionsOrFull(screenshotBitmap)
                if (crops.isEmpty()) {
                    analyzeBitmapAsync(screenshotBitmap)
                } else {
                    for (bmp in crops) {
                        analyzeBitmapAsync(bmp)
                        if (bmp !== screenshotBitmap && !bmp.isRecycled) {
                            bmp.recycle()
                        }
                    }
                }
            } finally {
                if (!screenshotBitmap.isRecycled) {
                    screenshotBitmap.recycle()
                }
            }
        }
    }

    private fun cropVisibleMediaRegionsOrFull(full: Bitmap): List<Bitmap> {
        val root = rootInActiveWindow ?: return listOf(full)
        val mediaBounds = mutableListOf<Rect>()
        collectMediaNodeBounds(root, mediaBounds)
        runCatching { root.recycle() }

        if (mediaBounds.isEmpty()) return listOf(full)

        val out = mutableListOf<Bitmap>()
        for (rect in mediaBounds) {
            val left = rect.left.coerceIn(0, full.width)
            val top = rect.top.coerceIn(0, full.height)
            val right = rect.right.coerceIn(left + 1, full.width)
            val bottom = rect.bottom.coerceIn(top + 1, full.height)
            val width = right - left
            val height = bottom - top
            if (width <= 1 || height <= 1) continue
            val crop = runCatching {
                Bitmap.createBitmap(full, left, top, width, height)
            }.getOrNull() ?: continue
            out.add(crop)
        }

        return if (out.isEmpty()) listOf(full) else out
    }

    private fun collectMediaNodeBounds(node: AccessibilityNodeInfo, out: MutableList<Rect>) {
        val className = node.className?.toString().orEmpty()
        val isVisible = node.isVisibleToUser
        if (isVisible && isMediaLikeClass(className)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) out.add(rect)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectMediaNodeBounds(child, out)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    private fun isMediaLikeClass(className: String): Boolean {
        if (className.isBlank()) return false
        return className.endsWith("ImageView") ||
            className.endsWith("TextureView") ||
            className.endsWith("SurfaceView") ||
            className.contains("Photo", ignoreCase = true) ||
            className.contains("Video", ignoreCase = true)
    }

    private fun analyzeBitmapAsync(bitmap: Bitmap) {
        val payload = encodeJpeg(bitmap) ?: return
        val service = onnxService ?: return

        runCatching {
            service.analyzeImage(payload)
        }.onSuccess { flagged ->
            if (flagged) {
                Log.d(TAG, "Flagged media content in package=$foregroundPackage")
                val bleProbability = prefs
                    .getFloat(PREF_BLE_HAPTIC_PROBABILITY, BLE_HAPTIC_PROBABILITY)
                    .coerceIn(0f, 1f)
                val bleCooldownMs = prefs
                    .getLong(PREF_BLE_HAPTIC_COOLDOWN_MS, BLE_HAPTIC_COOLDOWN_MS)
                    .coerceAtLeast(0L)
                BackgroundBleHapticTrigger.maybeTrigger(
                    context = applicationContext,
                    reason = "screen_scanner_flagged",
                    probability = bleProbability,
                    cooldownMs = bleCooldownMs,
                )
            }
        }.onFailure {
            Log.w(TAG, "analyzeImage failed", it)
        }
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray? {
        return runCatching {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun bindOnnxServiceIfNeeded() {
        if (onnxService != null) return
        val intent = Intent("com.hound.controller.BIND_ONNX_IPC_SERVICE").setPackage("com.hound.controller")
        runCatching {
            applicationContext.bindService(intent, onnxConnection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.w(TAG, "bindService OnnxIpcService failed", it)
        }
    }
}
