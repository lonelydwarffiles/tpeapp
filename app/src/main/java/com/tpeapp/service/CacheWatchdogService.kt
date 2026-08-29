package com.hound.controller.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.IBinder
import android.util.Log
import com.hound.controller.filter.IOnnxIpcService
import com.hound.controller.review.RootChecker
import com.hound.controller.util.BackgroundBleHapticTrigger
import com.hound.controller.util.CensorshipType
import com.hound.controller.util.ImageCensorshipEffects
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.random.Random

/**
 * Root-aware cache watchdog for target apps.
 *
 * It uses a root inotify loop for CLOSE_WRITE events, asynchronously forwards
 * candidate file paths into ONNX analysis, and censors flagged media on disk.
 */
class CacheWatchdogService : Service() {

    companion object {
        private const val TAG = "CacheWatchdogService"
        private const val DUP_EVENT_WINDOW_MS = 400L
        private const val MAX_BYTES_FOR_SCAN = 8 * 1024 * 1024
        private const val ROOT_CMD_TIMEOUT_MS = 4_000L
        private const val WATCH_RESTART_DELAY_MS = 2_000L
        private const val PRIVATE_USER_TYPE = "android.os.usertype.profile.PRIVATE"
        private const val PIXELATE_MIN_SCALE = 0.02f
        private const val PIXELATE_MAX_SCALE = 0.15f
        private const val BLUR_RADIUS = 10
        private const val JPEG_QUALITY = 90
        private const val BLE_HAPTIC_PROBABILITY = 0.08f
        private const val BLE_HAPTIC_COOLDOWN_MS = 8_000L
        private const val PREF_BLE_HAPTIC_PROBABILITY = "ble_haptic_probability"
        private const val PREF_BLE_HAPTIC_COOLDOWN_MS = "ble_haptic_cooldown_ms"
        private val ROOT_SHELLS = listOf("su:s0", "su")

        private val TARGET_CACHE_PACKAGES = listOf(
            "com.twitter.android",
            "com.reddit.frontpage",
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
    }
    private val recentEvents = ConcurrentHashMap<String, Long>()
    private val rootLineReaderTag = "watchdog-inotify"

    @Volatile
    private var rootInotifyProcess: Process? = null

    @Volatile
    private var observedCacheDirs: Set<String> = emptySet()

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

    override fun onCreate() {
        super.onCreate()
        bindOnnxServiceIfNeeded()
        startRootDirectoryObservation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { rootInotifyProcess?.destroyForcibly() }
        rootInotifyProcess = null

        runCatching { unbindService(onnxConnection) }
        onnxService = null

        serviceScope.cancel()
        super.onDestroy()
    }

    private fun bindOnnxServiceIfNeeded() {
        if (onnxService != null) return
        val intent = Intent("com.hound.controller.BIND_ONNX_IPC_SERVICE")
            .setPackage(packageName)
        runCatching {
            applicationContext.bindService(intent, onnxConnection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.w(TAG, "Failed to bind OnnxIpcService", it)
        }
    }

    private fun startRootDirectoryObservation() {
        if (!RootChecker.isRootAvailable()) {
            Log.w(TAG, "Root unavailable; cannot start root inotify loop")
            return
        }
        serviceScope.launch {
            while (isActive) {
                val watchPlan = resolveWatchPlan()
                observedCacheDirs = watchPlan.watchDirs.toSet()

                Log.i(
                    TAG,
                    "Active users=${watchPlan.activeUserIds} privateUserId=${watchPlan.privateUserId} watchDirs=${watchPlan.watchDirs}"
                )

                if (watchPlan.watchDirs.isEmpty()) {
                    delay(WATCH_RESTART_DELAY_MS)
                    continue
                }

                val loopCmd = buildInotifyLoopCommand(watchPlan.watchDirs)
                val process = runCatching {
                    startRootProcess(loopCmd)
                }.getOrNull() ?: run {
                    Log.w(TAG, "Unable to start root inotify process; retrying")
                    delay(WATCH_RESTART_DELAY_MS)
                    continue
                }

                rootInotifyProcess = process
                Log.i(TAG, "Root inotify loop started")

                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val path = line.trim()
                            if (path.startsWith("/")) {
                                onCloseWriteEvent(path)
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Root inotify output loop ended with error", it)
                }

                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                Log.w(TAG, "$rootLineReaderTag exited code=$code; restarting")

                runCatching { process.destroyForcibly() }
                rootInotifyProcess = null
                delay(WATCH_RESTART_DELAY_MS)
            }
        }
    }

    private data class WatchPlan(
        val activeUserIds: Set<Int>,
        val privateUserId: Int?,
        val watchDirs: List<String>,
    )

    private data class ParsedUsers(
        val allUserIds: Set<Int>,
        val activeUserIds: Set<Int>,
        val privateUserId: Int?,
    )

    private fun resolveWatchPlan(): WatchPlan {
        val userLines = runRootCommandForOutput(
            "pm list users -v 2>/dev/null || cmd user list -v 2>/dev/null || pm list users"
        )

        val parsed = parseUserInfo(userLines)
        var privateUserId = parsed.privateUserId

        if (privateUserId == null) {
            val dumpLines = runRootCommandForOutput("dumpsys user 2>/dev/null")
            privateUserId = parseUserInfo(dumpLines).privateUserId
        }

        val watchUserIds = linkedSetOf(0)
        privateUserId?.let { watchUserIds.add(it) }

        val dirs = watchUserIds.flatMap { userId ->
            TARGET_CACHE_PACKAGES.map { pkg ->
                "/data/user/$userId/$pkg/cache"
            }
        }

        return WatchPlan(
            activeUserIds = parsed.activeUserIds.ifEmpty { setOf(0) },
            privateUserId = privateUserId,
            watchDirs = dirs,
        )
    }

    private fun parseUserInfo(lines: List<String>): ParsedUsers {
        val all = linkedSetOf<Int>()
        val active = linkedSetOf<Int>()
        var privateId: Int? = null
        var currentId: Int? = null

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            val userInfoId = Regex("""UserInfo\{(\d+):""")
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            if (userInfoId != null) {
                currentId = userInfoId
                all.add(userInfoId)
            }

            if (line.contains(PRIVATE_USER_TYPE, ignoreCase = true)) {
                if (userInfoId != null) {
                    privateId = userInfoId
                } else if (currentId != null) {
                    privateId = currentId
                }
            }

            if (line.contains("running", ignoreCase = true) ||
                line.contains("state=RUNNING", ignoreCase = true) ||
                line.contains("isRunning=true", ignoreCase = true)
            ) {
                userInfoId?.let { active.add(it) } ?: currentId?.let { active.add(it) }
            }
        }

        return ParsedUsers(
            allUserIds = all,
            activeUserIds = active,
            privateUserId = privateId,
        )
    }

    private fun buildInotifyLoopCommand(dirs: List<String>): String {
        val joinedDirs = dirs.joinToString(" ") { shellQuote(it) }
        return buildString {
            append("while true; do ")
            append("pids=''; ")
            append("for d in ")
            append(joinedDirs)
            append("; do ")
            append("(while true; do ")
            append("if [ -d \"\$d\" ]; then ")
            append("inotifywait -m -q -r -e close_write --format '%w%f' \"\$d\" 2>/dev/null || true; ")
            append("else sleep 2; fi; ")
            append("sleep 1; ")
            append("done) & ")
            append("pids=\"\$pids \$!\"; ")
            append("done; ")
            append("wait \$pids; ")
            append("sleep 1; ")
            append("done")
        }
    }

    private fun runRootCommandForOutput(command: String): List<String> {
        if (!RootChecker.isRootAvailable()) return emptyList()
        return try {
            val process = startRootProcess(command) ?: return emptyList()

            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            val exited = process.waitFor(ROOT_CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!exited || process.exitValue() != 0) {
                emptyList()
            } else {
                lines
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root output command failed: $command", e)
            emptyList()
        }
    }

    private fun onCloseWriteEvent(fullPath: String) {
        if (!isTargetPath(fullPath)) return
        if (!isLikelyMediaFile(fullPath)) return

        val now = System.currentTimeMillis()
        val last = recentEvents[fullPath] ?: 0L
        if (now - last < DUP_EVENT_WINDOW_MS) return
        recentEvents[fullPath] = now

        serviceScope.launch {
            handleDetectedPathAsync(fullPath)
        }
    }

    private suspend fun handleDetectedPathAsync(sourcePath: String) {
        // Path handoff is asynchronous so the watcher loop never blocks.
        bindOnnxServiceIfNeeded()

        val stagedFile = stageFileForRead(sourcePath) ?: return
        try {
            val bytes = runCatching { stagedFile.readBytes() }.getOrNull() ?: return
            if (bytes.isEmpty() || bytes.size > MAX_BYTES_FOR_SCAN) return

            val service = onnxService ?: return
            val flagged = runCatching {
                service.analyzeImage(bytes)
            }.getOrDefault(false)

            if (flagged) {
                Log.i(TAG, "Flagged cached media, censoring on disk: $sourcePath")
                censorFileOnDisk(sourcePath, stagedFile)
                val bleProbability = prefs
                    .getFloat(PREF_BLE_HAPTIC_PROBABILITY, BLE_HAPTIC_PROBABILITY)
                    .coerceIn(0f, 1f)
                val bleCooldownMs = prefs
                    .getLong(PREF_BLE_HAPTIC_COOLDOWN_MS, BLE_HAPTIC_COOLDOWN_MS)
                    .coerceAtLeast(0L)
                BackgroundBleHapticTrigger.maybeTrigger(
                    context = applicationContext,
                    reason = "cache_watchdog_flagged",
                    probability = bleProbability,
                    cooldownMs = bleCooldownMs,
                )
            }
        } finally {
            runCatching { stagedFile.delete() }
        }
    }

    private fun stageFileForRead(sourcePath: String): File? {
        val staged = File(cacheDir, "watchdog_${System.nanoTime()}.bin")
        val copyCmd = "cp ${shellQuote(sourcePath)} ${shellQuote(staged.absolutePath)} && chmod 0644 ${shellQuote(staged.absolutePath)}"
        val copied = runRootCommand(copyCmd)
        if (!copied) {
            runCatching { staged.delete() }
            return null
        }
        return staged
    }

    private enum class ImageFormat {
        JPEG,
        PNG,
        WEBP,
    }

    private fun censorFileOnDisk(sourcePath: String, stagedFile: File) {
        val format = inferImageFormat(sourcePath)
        if (format == null) {
            runRootCommand("truncate -s 0 ${shellQuote(sourcePath)}")
            return
        }

        val originalBitmap = BitmapFactory.decodeFile(stagedFile.absolutePath)
        if (originalBitmap == null) {
            runRootCommand("truncate -s 0 ${shellQuote(sourcePath)}")
            return
        }

        val mutable = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (!originalBitmap.isRecycled) {
            runCatching { originalBitmap.recycle() }
        }
        if (mutable == null) {
            runRootCommand("truncate -s 0 ${shellQuote(sourcePath)}")
            return
        }

        val transformed = try {
            applyCensorshipTransform(mutable, CensorshipType.getRandomType())
        } catch (e: Exception) {
            Log.w(TAG, "Image transform failed; applying solid fill fallback", e)
            applySolidColor(mutable)
            mutable
        }

        val outFile = File(cacheDir, "watchdog_censor_${System.nanoTime()}.tmp")
        val wroteFile = runCatching {
            FileOutputStream(outFile).use { fos ->
                compressBitmap(transformed, format, fos)
            }
            outFile.length() > 0
        }.getOrDefault(false)

        if (!transformed.isRecycled) {
            runCatching { transformed.recycle() }
        }

        if (!wroteFile) {
            runCatching { outFile.delete() }
            runRootCommand("truncate -s 0 ${shellQuote(sourcePath)}")
            return
        }

        val replaceCmd = "cp ${shellQuote(outFile.absolutePath)} ${shellQuote(sourcePath)} && chmod 0644 ${shellQuote(sourcePath)}"
        val replaced = runRootCommand(replaceCmd)
        runCatching { outFile.delete() }

        if (!replaced) {
            runRootCommand("truncate -s 0 ${shellQuote(sourcePath)}")
        }
    }

    private fun inferImageFormat(path: String): ImageFormat? {
        val lower = path.lowercase(Locale.US)
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> ImageFormat.JPEG
            lower.endsWith(".png") -> ImageFormat.PNG
            lower.endsWith(".webp") -> ImageFormat.WEBP
            else -> null
        }
    }

    private fun applyCensorshipTransform(bitmap: Bitmap, type: CensorshipType): Bitmap {
        return when (type) {
            CensorshipType.SOLID_COLOR -> {
                applySolidColor(bitmap)
                bitmap
            }

            CensorshipType.PIXELATE -> {
                applyPixelate(bitmap)
            }

            CensorshipType.BLUR -> {
                applyHeavyBlur(bitmap)
            }

            CensorshipType.VHS_GLITCH -> {
                ImageCensorshipEffects.applyVhsGlitch(bitmap)
            }
        }
    }

    private fun applySolidColor(bitmap: Bitmap) {
        Canvas(bitmap).drawColor(Color.BLACK)
    }

    private fun applyPixelate(bitmap: Bitmap): Bitmap {
        val scale = Random.nextFloat() * (PIXELATE_MAX_SCALE - PIXELATE_MIN_SCALE) + PIXELATE_MIN_SCALE
        val downW = max(1, (bitmap.width * scale).toInt())
        val downH = max(1, (bitmap.height * scale).toInt())
        val tiny = Bitmap.createScaledBitmap(bitmap, downW, downH, false)
        return try {
            Bitmap.createScaledBitmap(tiny, bitmap.width, bitmap.height, false)
        } finally {
            if (!tiny.isRecycled) {
                runCatching { tiny.recycle() }
            }
            if (!bitmap.isRecycled) {
                runCatching { bitmap.recycle() }
            }
        }
    }

    private fun applyHeavyBlur(bitmap: Bitmap): Bitmap {
        val r = BLUR_RADIUS.coerceAtLeast(1)
        val w = bitmap.width
        val h = bitmap.height
        val inPixels = IntArray(w * h)
        val pass1 = IntArray(w * h)
        val outPixels = IntArray(w * h)
        bitmap.getPixels(inPixels, 0, w, 0, 0, w, h)

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
                pass1[row + x] =
                    ((a / count) shl 24) or
                        ((red / count) shl 16) or
                        ((green / count) shl 8) or
                        (blue / count)
            }
        }

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
                    val c = pass1[(yy * w) + x]
                    a += (c ushr 24) and 0xFF
                    red += (c ushr 16) and 0xFF
                    green += (c ushr 8) and 0xFF
                    blue += c and 0xFF
                    count++
                }
                outPixels[(y * w) + x] =
                    ((a / count) shl 24) or
                        ((red / count) shl 16) or
                        ((green / count) shl 8) or
                        (blue / count)
            }
        }

        if (!bitmap.isRecycled) {
            runCatching { bitmap.recycle() }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outPixels, 0, w, 0, 0, w, h)
        }
    }

    private fun compressBitmap(bitmap: Bitmap, format: ImageFormat, output: FileOutputStream): Boolean {
        return when (format) {
            ImageFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            ImageFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            ImageFormat.WEBP -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, JPEG_QUALITY, output)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, JPEG_QUALITY, output)
                }
            }
        }
    }

    private fun runRootCommand(command: String): Boolean {
        if (!RootChecker.isRootAvailable()) return false
        return try {
            val process = startRootProcess(command) ?: return false
            val exited = process.waitFor(ROOT_CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            exited && process.exitValue() == 0
        } catch (e: Exception) {
            Log.w(TAG, "Root command failed: $command", e)
            false
        }
    }

    private fun startRootProcess(command: String): Process? {
        for (shell in ROOT_SHELLS) {
            val process = runCatching {
                ProcessBuilder(shell, "-c", command)
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull()
            if (process != null) return process
        }
        return null
    }

    private fun isTargetPath(path: String): Boolean {
        return observedCacheDirs.any { dir -> path.startsWith(dir) }
    }

    private fun isLikelyMediaFile(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".mp4") ||
            lower.endsWith(".tmp") ||
            lower.contains("image") ||
            lower.contains("media")
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
