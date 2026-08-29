package com.hound.controller.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight on-device content-safety classifier backed by [assets/nudenet.tflite].
 *
 * The TFLite interpreter is loaded once and kept alive for the lifetime of this
 * object.  An NNAPI delegate is requested to utilise the device NPU where available
 * (e.g. Pixel Tensor chips), with a transparent fall-back to CPU.
 *
 * Model contract:
 *   Input  : [1, INPUT_SIZE, INPUT_SIZE, 3]  FLOAT32  (NHWC, normalised to [0, 1])
 *   Output : [1, 2]                          FLOAT32  (index 0 = safe, index 1 = unsafe)
 *
 * Thread safety: [classifyBytes] and [classifyBitmap] are [Synchronized].
 * [FilterService] additionally dispatches all calls from [Dispatchers.IO] so
 * the UI thread is never blocked.
 */
class NudeNetClassifier(context: Context) : Closeable {

    companion object {
        private const val TAG          = "NudeNetClassifier"
        private const val MODEL_FILE   = "nudenet.tflite"
        private const val INPUT_SIZE   = 320          // matches Flutter NudeNetService._inputSize
        private const val PIXEL_SIZE   = 3            // RGB
        private const val FLOAT_BYTES  = 4
        private const val OUTPUT_SIZE  = 2            // [safe_score, unsafe_score]
        private const val UNSAFE_IDX   = 1
    }

    // NNAPI delegate leverages the NPU on supported devices; falls back silently.
    private val nnApiDelegate: NnApiDelegate? = runCatching {
        NnApiDelegate(
            NnApiDelegate.Options().apply {
                executionPreference = NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
                allowFp16            = true
                useNnapiCpu          = false
            }
        )
    }.getOrNull()

    private val interpreter: Interpreter

    init {
        val model = FileUtil.loadMappedFile(context, MODEL_FILE)
        val options = Interpreter.Options().apply {
            nnApiDelegate?.let { addDelegate(it) }
            setNumThreads(2)
        }
        interpreter = Interpreter(model, options)
        Log.i(TAG, "TFLite interpreter ready (model=$MODEL_FILE input=${INPUT_SIZE}x${INPUT_SIZE})")
    }

    // Pre-allocated buffers to avoid GC pressure on the hot path.
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder())

    private val outputArray: Array<FloatArray> = Array(1) { FloatArray(OUTPUT_SIZE) }

    /**
     * Runs inference on raw image bytes (JPEG/PNG).
     *
     * @return probability that the image contains adult/sensitive content [0, 1].
     * @throws IllegalArgumentException if the bytes cannot be decoded to a Bitmap.
     */
    @Synchronized
    fun classifyBytes(imageBytes: ByteArray): Float {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("Cannot decode image bytes to Bitmap")
        return classifyBitmap(bitmap).also { bitmap.recycle() }
    }

    /**
     * Runs inference on a [Bitmap].
     *
     * The bitmap is scaled to [INPUT_SIZE]×[INPUT_SIZE] internally.
     * The caller retains ownership and must recycle when appropriate.
     *
     * @return probability in [0, 1].
     */
    @Synchronized
    fun classifyBitmap(bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        loadBitmapIntoBuffer(scaled, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        outputArray[0].fill(0f)
        interpreter.run(inputBuffer, outputArray)

        val unsafe = outputArray[0].getOrElse(UNSAFE_IDX) { outputArray[0].firstOrNull() ?: 0f }
        val score = normalizeScore(unsafe)
        Log.v(TAG, "TFLite inference -> unsafe score = $score")
        return score
    }

    private fun loadBitmapIntoBuffer(bitmap: Bitmap, width: Int, height: Int) {
        inputBuffer.rewind()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr  8) and 0xFF) / 255.0f) // G
            inputBuffer.putFloat(( pixel         and 0xFF) / 255.0f) // B
        }
    }

    private fun normalizeScore(raw: Float): Float {
        if (raw.isNaN() || raw.isInfinite()) return 0f
        if (raw in 0f..1f) return raw
        val clipped = raw.coerceIn(-20f, 20f)
        return (1.0f / (1.0f + kotlin.math.exp(-clipped)))
    }

    override fun close() {
        try { interpreter.close() } catch (e: Exception) { Log.w(TAG, "interpreter close", e) }
        try { nnApiDelegate?.close() } catch (e: Exception) { Log.w(TAG, "nnApiDelegate close", e) }
    }
}

