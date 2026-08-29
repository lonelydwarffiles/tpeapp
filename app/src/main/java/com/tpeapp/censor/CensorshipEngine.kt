package com.hound.controller.censor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.random.Random

/**
 * Local YOLOv8 selective-censorship processor.
 *
 * Model contract:
 * - input tensor:  [1, 3, 320, 320] in RGB order, float32 [0..1]
 * - output tensor: [1, 22, 2100]
 *   row 0: center-x
 *   row 1: center-y
 *   row 2: width
 *   row 3: height
 *   row 4..21: class confidence scores for 18 classes
 */
class CensorshipEngine(
    private val context: Context,
    private val modelAssetName: String = "320.ort",
    private val defaultForbiddenClassIds: Set<Int> = setOf(11, 13, 14, 17),
    private val scoreThreshold: Float = 0.55f,
) : AutoCloseable {

    companion object {
        private const val TAG = "CensorshipEngine"
        private const val MODEL_SIZE = 320
        private const val CHANNELS = 3
        private const val ATTR_ROWS = 22
        private const val BOX_COUNT = 2100
    }

    data class CensorResult(
        val hasForbidden: Boolean,
        val boxes: List<Rect>,
        val outputBitmap: Bitmap,
    )

    private val closed = AtomicBoolean(false)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    private fun createSession(): OrtSession {
        val modelBytes = runCatching {
            context.assets.open(modelAssetName).use { it.readBytes() }
        }.recoverCatching {
            // When the model is shipped from Flutter assets, it lands under flutter_assets/.
            context.assets.open("flutter_assets/assets/$modelAssetName").use { it.readBytes() }
        }.getOrElse { t ->
            throw IllegalStateException("Missing or unreadable ONNX model asset: $modelAssetName", t)
        }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        return env.createSession(modelBytes, options)
    }

    /**
     * Converts source bitmap into normalized tensor data [1][3][320][320].
     */
    fun preprocess(src: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(src, MODEL_SIZE, MODEL_SIZE, true)
        try {
            val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
            resized.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

            val out = FloatArray(1 * CHANNELS * MODEL_SIZE * MODEL_SIZE)
            val hw = MODEL_SIZE * MODEL_SIZE

            for (i in pixels.indices) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF) / 255.0f
                val g = ((p shr 8) and 0xFF) / 255.0f
                val b = (p and 0xFF) / 255.0f

                out[i] = r
                out[hw + i] = g
                out[(2 * hw) + i] = b
            }
            return out
        } finally {
            if (!resized.isRecycled) resized.recycle()
        }
    }

    /**
     * Parses [1][22][2100] YOLO output into full-resolution bounding boxes.
     */
    fun processOutput(
        output: Array<Array<FloatArray>>,
        original: Bitmap,
        forbiddenClassIds: Set<Int> = defaultForbiddenClassIds,
    ): List<Rect> {
        if (output.isEmpty()) return emptyList()
        val matrix = output[0]
        if (matrix.size < ATTR_ROWS) return emptyList()
        if (matrix[0].isEmpty() || matrix[1].isEmpty() || matrix[2].isEmpty() || matrix[3].isEmpty()) {
            return emptyList()
        }

        val maxBoxesFromRows = (0 until ATTR_ROWS).minOfOrNull { row -> matrix[row].size } ?: 0
        val boxCount = minOf(BOX_COUNT, maxBoxesFromRows)
        if (boxCount <= 0) return emptyList()

        val sx = original.width / MODEL_SIZE.toFloat()
        val sy = original.height / MODEL_SIZE.toFloat()

        val result = ArrayList<Rect>()
        for (i in 0 until boxCount) {
            val best = bestClass(matrix, i)
            if (best.score < scoreThreshold) continue
            if (best.classId !in forbiddenClassIds) continue

            val cx = matrix[0][i]
            val cy = matrix[1][i]
            val w = matrix[2][i]
            val h = matrix[3][i]

            val left = ((cx - w / 2f) * sx).toInt().coerceIn(0, original.width)
            val top = ((cy - h / 2f) * sy).toInt().coerceIn(0, original.height)
            val right = ((cx + w / 2f) * sx).toInt().coerceIn(0, original.width)
            val bottom = ((cy + h / 2f) * sy).toInt().coerceIn(0, original.height)

            if (right - left < 4 || bottom - top < 4) continue
            result.add(Rect(left, top, right, bottom))
        }
        return mergeOverlaps(result)
    }

    private data class ClassScore(val classId: Int, val score: Float)

    private fun bestClass(matrix: Array<FloatArray>, boxIdx: Int): ClassScore {
        var bestClass = -1
        var bestScore = 0f
        for (row in 4 until ATTR_ROWS) {
            val score = matrix[row][boxIdx]
            if (score > bestScore) {
                bestScore = score
                bestClass = row - 4
            }
        }
        return ClassScore(bestClass, bestScore)
    }

    /**
     * Pixelates only forbidden regions and returns a mutable censored copy.
     */
    fun applySelectiveBlur(original: Bitmap, boxes: List<Rect>): Bitmap {
        if (boxes.isEmpty()) return original.copy(original.config ?: Bitmap.Config.ARGB_8888, true)

        val mutable = original.copy(original.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        for (box in boxes) {
            val clamped = Rect(
                box.left.coerceIn(0, mutable.width),
                box.top.coerceIn(0, mutable.height),
                box.right.coerceIn(0, mutable.width),
                box.bottom.coerceIn(0, mutable.height),
            )
            if (clamped.width() <= 2 || clamped.height() <= 2) continue

            val region = Bitmap.createBitmap(
                mutable,
                clamped.left,
                clamped.top,
                clamped.width(),
                clamped.height(),
            )
            try {
                val pxW = max(1, clamped.width() / 14)
                val pxH = max(1, clamped.height() / 14)
                val tiny = Bitmap.createScaledBitmap(region, pxW, pxH, false)
                try {
                    val blocky = Bitmap.createScaledBitmap(tiny, clamped.width(), clamped.height(), false)
                    try {
                        canvas.drawBitmap(blocky, clamped.left.toFloat(), clamped.top.toFloat(), paint)
                    } finally {
                        if (!blocky.isRecycled) blocky.recycle()
                    }
                } finally {
                    if (!tiny.isRecycled) tiny.recycle()
                }
            } finally {
                if (!region.isRecycled) region.recycle()
            }
        }

        return mutable
    }

    private fun applySelectiveCensorWithStyle(
        original: Bitmap,
        boxes: List<Rect>,
        requestedStyle: String,
    ): Bitmap {
        if (boxes.isEmpty()) return original.copy(original.config ?: Bitmap.Config.ARGB_8888, true)

        val normalizedStyle = requestedStyle.trim().lowercase()
        val mutable = original.copy(original.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        for (box in boxes) {
            val clamped = Rect(
                box.left.coerceIn(0, mutable.width),
                box.top.coerceIn(0, mutable.height),
                box.right.coerceIn(0, mutable.width),
                box.bottom.coerceIn(0, mutable.height),
            )
            if (clamped.width() <= 2 || clamped.height() <= 2) continue

            val region = Bitmap.createBitmap(
                mutable,
                clamped.left,
                clamped.top,
                clamped.width(),
                clamped.height(),
            )
            try {
                val styleForRegion = when (normalizedStyle) {
                    "random" -> if (Random.nextBoolean()) "pixelate" else "heavy_blur"
                    "blur", "heavy_blur", "heavyblur" -> "heavy_blur"
                    "pixelate" -> "pixelate"
                    else -> "pixelate"
                }

                val transformed = if (styleForRegion == "heavy_blur") {
                    val smallW = max(1, clamped.width() / 18)
                    val smallH = max(1, clamped.height() / 18)
                    val tiny = Bitmap.createScaledBitmap(region, smallW, smallH, true)
                    try {
                        Bitmap.createScaledBitmap(tiny, clamped.width(), clamped.height(), true)
                    } finally {
                        if (!tiny.isRecycled) tiny.recycle()
                    }
                } else {
                    val pxW = max(1, clamped.width() / 14)
                    val pxH = max(1, clamped.height() / 14)
                    val tiny = Bitmap.createScaledBitmap(region, pxW, pxH, false)
                    try {
                        Bitmap.createScaledBitmap(tiny, clamped.width(), clamped.height(), false)
                    } finally {
                        if (!tiny.isRecycled) tiny.recycle()
                    }
                }

                try {
                    canvas.drawBitmap(transformed, clamped.left.toFloat(), clamped.top.toFloat(), paint)
                } finally {
                    if (!transformed.isRecycled) transformed.recycle()
                }
            } finally {
                if (!region.isRecycled) region.recycle()
            }
        }

        return mutable
    }

    fun censorBitmap(
        src: Bitmap,
        censorStyle: String = "pixelate",
        forbiddenClassIds: Set<Int> = defaultForbiddenClassIds,
    ): CensorResult {
        ensureOpen()

        val task = inferenceExecutor.submit<CensorResult> {
            censorBitmapInternal(src, censorStyle, forbiddenClassIds)
        }
        return try {
            task.get(1500, TimeUnit.MILLISECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            Log.w(TAG, "censorBitmap timed out after 1500ms; passthrough fallback")
            CensorResult(false, emptyList(), safeCopy(src))
        } catch (e: Exception) {
            Log.e(TAG, "censorBitmap wrapper failed; passthrough", e)
            CensorResult(false, emptyList(), safeCopy(src))
        }
    }

    private fun censorBitmapInternal(
        src: Bitmap,
        censorStyle: String,
        forbiddenClassIds: Set<Int>,
    ): CensorResult {
        ensureOpen()

        val inputData = try {
            preprocess(src)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM during preprocess; returning passthrough", oom)
            return CensorResult(false, emptyList(), safeCopy(src))
        }

        val inputName = session.inputNames.first()
        val tensor = try {
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(inputData),
                longArrayOf(1, CHANNELS.toLong(), MODEL_SIZE.toLong(), MODEL_SIZE.toLong()),
            )
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM creating ONNX tensor; returning passthrough", oom)
            return CensorResult(false, emptyList(), safeCopy(src))
        }

        val output = try {
            session.run(mapOf(inputName to tensor))
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference failed; returning passthrough", e)
            return CensorResult(false, emptyList(), safeCopy(src))
        } finally {
            // tensor must be closed regardless of whether run() succeeds or throws.
            tensor.close()
        }

        try {
            val matrix = try {
                @Suppress("UNCHECKED_CAST")
                output[0].value as Array<Array<FloatArray>>
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected YOLO output shape", t)
                return CensorResult(false, emptyList(), safeCopy(src))
            }

            val boxes = processOutput(matrix, src, forbiddenClassIds)

            val outBitmap = try {
                applySelectiveCensorWithStyle(src, boxes, censorStyle)
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM during censor apply; returning passthrough", oom)
                return CensorResult(false, emptyList(), safeCopy(src))
            }

            return CensorResult(boxes.isNotEmpty(), boxes, outBitmap)
        } finally {
            // Guarantee OrtSession.Result is always released, even if processOutput
            // or applySelectiveCensorWithStyle throws an uncaught exception.
            output.close()
        }
    }

    /**
     * Returns a mutable copy of [src], or [src] itself if the copy would OOM.
     * Callers must treat the returned bitmap as potentially the original and
     * must not recycle it unconditionally.
     */
    private fun safeCopy(src: Bitmap): Bitmap = try {
        src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
    } catch (_: OutOfMemoryError) {
        src
    }

    fun hasForbiddenContent(
        src: Bitmap,
        forbiddenClassIds: Set<Int> = defaultForbiddenClassIds,
    ): Boolean {
        val result = censorBitmap(src, forbiddenClassIds = forbiddenClassIds)
        if (!result.outputBitmap.isRecycled) result.outputBitmap.recycle()
        return result.hasForbidden
    }

    fun encodePng(bitmap: Bitmap): ByteArray {
        return ByteArrayOutputStream().use { os ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            os.toByteArray()
        }
    }

    private fun mergeOverlaps(input: List<Rect>): List<Rect> {
        if (input.size <= 1) return input
        val sorted = input.sortedBy { it.left }
        val merged = ArrayList<Rect>()
        var current = Rect(sorted.first())
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (Rect.intersects(current, next) || isNear(current, next, 8)) {
                current = Rect(
                    minOf(current.left, next.left),
                    minOf(current.top, next.top),
                    maxOf(current.right, next.right),
                    maxOf(current.bottom, next.bottom),
                )
            } else {
                merged.add(current)
                current = Rect(next)
            }
        }
        merged.add(current)
        return merged
    }

    private fun isNear(a: Rect, b: Rect, padding: Int): Boolean {
        val pa = Rect(a.left - padding, a.top - padding, a.right + padding, a.bottom + padding)
        return Rect.intersects(pa, b)
    }

    private fun ensureOpen() {
        check(!closed.get()) { "CensorshipEngine is already closed" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { inferenceExecutor.shutdownNow() }
        runCatching { session.close() }
    }
}
