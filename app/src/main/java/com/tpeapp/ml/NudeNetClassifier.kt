package com.tpeapp.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Wraps a NudeNet-derived classifier and provides a thread-safe inference
 * method.
 *
 * Preferred backend order:
 *  1) TFLite from assets/nudenet.tflite
 *  2) ONNX Runtime from assets/nudenet.onnx
 *
 * This allows local development with ONNX-only checkpoints when a converted
 * TFLite model is not yet available.
 *
 * The model is assumed to accept a single 480×480 RGB input tensor
 * (shape [1, 480, 480, 3], dtype FLOAT32) and produce a 1-D output vector
 * whose first element represents the probability of sensitive/adult content.
 *
 * Adjust [INPUT_SIZE] and [OUTPUT_SIZE] to match the actual NudeNet variant
 * you ship.
 */
class NudeNetClassifier(context: Context) : Closeable {

    companion object {
        private const val TAG          = "NudeNetClassifier"
        private const val MODEL_FILE_TFLITE = "nudenet.tflite"
        private const val MODEL_FILE_ONNX   = "nudenet.onnx"
        private const val DEFAULT_INPUT_SIZE = 480
        private const val PIXEL_SIZE   = 3          // RGB
        private const val FLOAT_BYTES  = 4
        private const val OUTPUT_SIZE  = 2          // [safe_score, unsafe_score]
        private const val UNSAFE_IDX   = 1          // index of the "unsafe" class
    }

    private enum class Backend {
        TFLITE,
        ONNX,
    }

    private data class OnnxModelMeta(
        val inputName: String,
        val inputShape: LongArray,
        val nchw: Boolean,
        val width: Int,
        val height: Int,
    )

    private val backend: Backend

    // NNAPI delegate leverages the Tensor G4 NPU on Pixel 9 Pro XL.
    private val nnApiDelegate: NnApiDelegate? = runCatching {
        NnApiDelegate(
        NnApiDelegate.Options().apply {
            executionPreference = NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
            allowFp16            = true
            useNnapiCpu          = false
        }
        )
    }.getOrNull()

    private val interpreter: Interpreter?
    private val onnxEnv: OrtEnvironment?
    private val onnxSession: OrtSession?
    private val onnxMeta: OnnxModelMeta?

    private val inputSize: Int

    init {
        var localBackend: Backend? = null
        var localInterpreter: Interpreter? = null
        var localOnnxEnv: OrtEnvironment? = null
        var localOnnxSession: OrtSession? = null
        var localOnnxMeta: OnnxModelMeta? = null
        var localInputSize = DEFAULT_INPUT_SIZE

        runCatching {
            val model = FileUtil.loadMappedFile(context, MODEL_FILE_TFLITE)
            val options = Interpreter.Options().apply {
                nnApiDelegate?.let { addDelegate(it) }
                setNumThreads(2)
            }
            Interpreter(model, options)
        }.onSuccess {
            localInterpreter = it
            localBackend = Backend.TFLITE
            localInputSize = DEFAULT_INPUT_SIZE
            Log.i(TAG, "Using TFLite backend from $MODEL_FILE_TFLITE")
        }.onFailure { tfliteErr ->
            Log.w(TAG, "TFLite model not available: ${tfliteErr.message}")

            runCatching {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                val bytes = context.assets.open(MODEL_FILE_ONNX).use { it.readBytes() }
                val session = env.createSession(bytes, opts)
                val inputName = session.inputNames.firstOrNull()
                    ?: throw IllegalStateException("ONNX model has no inputs")
                val nodeInfo = session.inputInfo[inputName]?.info
                    ?: throw IllegalStateException("ONNX input info unavailable")
                val tensorInfo = nodeInfo as? TensorInfo
                    ?: throw IllegalStateException("ONNX input is not a tensor")

                val rawShape = tensorInfo.shape.copyOf()
                if (rawShape.size != 4) {
                    throw IllegalStateException("Expected rank-4 input, got ${rawShape.contentToString()}")
                }

                val nchw = rawShape[1] == 3L || (rawShape[3] != 3L && rawShape[1] > 0)
                val h = if (nchw) sanitizeDim(rawShape[2], DEFAULT_INPUT_SIZE) else sanitizeDim(rawShape[1], DEFAULT_INPUT_SIZE)
                val w = if (nchw) sanitizeDim(rawShape[3], DEFAULT_INPUT_SIZE) else sanitizeDim(rawShape[2], DEFAULT_INPUT_SIZE)

                val resolvedShape = if (nchw) longArrayOf(1, 3, h.toLong(), w.toLong())
                else longArrayOf(1, h.toLong(), w.toLong(), 3)

                val meta = OnnxModelMeta(
                    inputName = inputName,
                    inputShape = resolvedShape,
                    nchw = nchw,
                    width = w,
                    height = h,
                )

                Triple(env, session, meta)
            }.onSuccess { (env, session, meta) ->
                localOnnxEnv = env
                localOnnxSession = session
                localOnnxMeta = meta
                localBackend = Backend.ONNX
                localInputSize = maxOf(meta.width, meta.height)
                Log.i(TAG, "Using ONNX backend from $MODEL_FILE_ONNX; input=${meta.inputShape.contentToString()} nchw=${meta.nchw}")
            }.onFailure { onnxErr ->
                throw IllegalStateException(
                    "Neither $MODEL_FILE_TFLITE nor $MODEL_FILE_ONNX could be loaded",
                    onnxErr,
                )
            }
        }

        backend = localBackend ?: throw IllegalStateException("Classifier backend unavailable")
        interpreter = localInterpreter
        onnxEnv = localOnnxEnv
        onnxSession = localOnnxSession
        onnxMeta = localOnnxMeta
        inputSize = localInputSize
    }

    // Pre-allocated input buffer to avoid GC pressure in the hot path.
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * inputSize * inputSize * PIXEL_SIZE * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder())

    // Pre-allocated output array.
    private val outputArray: Array<FloatArray> = Array(1) { FloatArray(OUTPUT_SIZE) }

    /**
     * Runs inference on raw image bytes (JPEG/PNG).
     *
     * @return probability that the image contains adult/sensitive content [0,1].
     * @throws IllegalArgumentException if the bytes cannot be decoded to a Bitmap.
     */
    @Synchronized
    fun classifyBytes(imageBytes: ByteArray): Float {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("Cannot decode image bytes to Bitmap")
        return classifyBitmap(bitmap).also { bitmap.recycle() }
    }

    /**
     * Runs inference on a [Bitmap].  The bitmap is scaled to [INPUT_SIZE]×[INPUT_SIZE]
     * internally; the caller retains ownership and must recycle if appropriate.
     *
     * @return probability in [0, 1].
     */
    @Synchronized
    fun classifyBitmap(bitmap: Bitmap): Float {
        return when (backend) {
            Backend.TFLITE -> runTflite(bitmap)
            Backend.ONNX -> runOnnx(bitmap)
        }
    }

    private fun runTflite(bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        loadBitmapIntoBuffer(scaled, inputSize, inputSize)
        if (scaled !== bitmap) scaled.recycle()

        outputArray[0].fill(0f)
        interpreter?.run(inputBuffer, outputArray)

        val unsafe = outputArray[0].getOrElse(UNSAFE_IDX) { outputArray[0].firstOrNull() ?: 0f }
        val score = normalizeScore(unsafe)
        Log.v(TAG, "TFLite inference -> unsafe score = $score")
        return score
    }

    private fun runOnnx(bitmap: Bitmap): Float {
        val meta = onnxMeta ?: throw IllegalStateException("ONNX metadata missing")
        val scaled = Bitmap.createScaledBitmap(bitmap, meta.width, meta.height, true)

        val inputArray = if (meta.nchw) {
            toNchwFloatArray(scaled, meta.width, meta.height)
        } else {
            toNhwcFloatArray(scaled, meta.width, meta.height)
        }
        if (scaled !== bitmap) scaled.recycle()

        val env = onnxEnv ?: throw IllegalStateException("ONNX environment missing")
        val session = onnxSession ?: throw IllegalStateException("ONNX session missing")

        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputArray), meta.inputShape)
        inputTensor.use { tensor ->
            session.run(mapOf(meta.inputName to tensor)).use { result ->
                val value = result.firstOrNull()?.value
                    ?: throw IllegalStateException("ONNX produced no outputs")
                val flat = flattenToFloatList(value)
                val raw = when {
                    flat.isEmpty() -> 0f
                    flat.size == 1 -> flat[0]
                    flat.size > UNSAFE_IDX -> flat[UNSAFE_IDX]
                    else -> flat.last()
                }
                val score = normalizeScore(raw)
                Log.v(TAG, "ONNX inference -> unsafe score = $score (raw=$raw, outputs=${flat.size})")
                return score
            }
        }
    }

    private fun loadBitmapIntoBuffer(bitmap: Bitmap, width: Int, height: Int) {
        inputBuffer.rewind()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            // Normalize [0, 255] → [0.0, 1.0]
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr  8) and 0xFF) / 255.0f) // G
            inputBuffer.putFloat(( pixel         and 0xFF) / 255.0f) // B
        }
    }

    private fun toNhwcFloatArray(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = FloatArray(width * height * 3)
        var idx = 0
        for (pixel in pixels) {
            out[idx++] = ((pixel shr 16) and 0xFF) / 255.0f
            out[idx++] = ((pixel shr 8) and 0xFF) / 255.0f
            out[idx++] = (pixel and 0xFF) / 255.0f
        }
        return out
    }

    private fun toNchwFloatArray(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = FloatArray(width * height * 3)
        var rIdx = 0
        var gIdx = width * height
        var bIdx = 2 * width * height
        for (pixel in pixels) {
            out[rIdx++] = ((pixel shr 16) and 0xFF) / 255.0f
            out[gIdx++] = ((pixel shr 8) and 0xFF) / 255.0f
            out[bIdx++] = (pixel and 0xFF) / 255.0f
        }
        return out
    }

    private fun flattenToFloatList(value: Any?): List<Float> = when (value) {
        null -> emptyList()
        is Float -> listOf(value)
        is Number -> listOf(value.toFloat())
        is FloatArray -> value.toList()
        is Array<*> -> value.flatMap { flattenToFloatList(it) }
        else -> emptyList()
    }

    private fun normalizeScore(raw: Float): Float {
        if (raw.isNaN() || raw.isInfinite()) return 0f
        if (raw in 0f..1f) return raw
        val clipped = raw.coerceIn(-20f, 20f)
        return (1.0f / (1.0f + kotlin.math.exp(-clipped)))
    }

    private fun sanitizeDim(dim: Long, fallback: Int): Int {
        if (dim <= 0L) return fallback
        return dim.toInt().coerceAtLeast(1)
    }

    override fun close() {
        try { interpreter?.close() } catch (e: Exception) { Log.w(TAG, "interpreter close", e) }
        try { nnApiDelegate?.close() } catch (e: Exception) { Log.w(TAG, "nnApiDelegate close", e) }
        try { onnxSession?.close() } catch (e: OrtException) { Log.w(TAG, "onnx session close", e) }
    }
}
