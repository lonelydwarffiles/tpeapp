package com.tpeapp.ml

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
 * Wraps a NudeNet-derived TFLite model and provides a thread-safe inference
 * method.  The model file (`nudenet.tflite`) must be placed in `assets/`.
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
        private const val MODEL_FILE   = "nudenet.tflite"
        private const val INPUT_SIZE   = 480
        private const val PIXEL_SIZE   = 3          // RGB
        private const val FLOAT_BYTES  = 4
        private const val OUTPUT_SIZE  = 2          // [safe_score, unsafe_score]
        private const val UNSAFE_IDX   = 1          // index of the "unsafe" class
    }

    // NNAPI delegate leverages the Tensor G4 NPU on Pixel 9 Pro XL.
    private val nnApiDelegate: NnApiDelegate = NnApiDelegate(
        NnApiDelegate.Options().apply {
            executionPreference = NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
            allowFp16            = true
            useNnapiCpu          = false
        }
    )

    private val interpreter: Interpreter = run {
        val model = FileUtil.loadMappedFile(context, MODEL_FILE)
        val options = Interpreter.Options().apply {
            addDelegate(nnApiDelegate)
            setNumThreads(2)          // keep thermals manageable
        }
        Interpreter(model, options)
    }

    // Pre-allocated input buffer to avoid GC pressure in the hot path.
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE * FLOAT_BYTES)
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
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        loadBitmapIntoBuffer(scaled)
        if (scaled !== bitmap) scaled.recycle()

        outputArray[0].fill(0f)
        interpreter.run(inputBuffer, outputArray)

        val unsafe = outputArray[0][UNSAFE_IDX]
        Log.v(TAG, "Inference → unsafe score = $unsafe")
        return unsafe
    }

    private fun loadBitmapIntoBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            // Normalize [0, 255] → [0.0, 1.0]
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr  8) and 0xFF) / 255.0f) // G
            inputBuffer.putFloat(( pixel         and 0xFF) / 255.0f) // B
        }
    }

    override fun close() {
        try { interpreter.close()   } catch (e: Exception) { Log.w(TAG, "interpreter close", e) }
        try { nnApiDelegate.close() } catch (e: Exception) { Log.w(TAG, "nnApiDelegate close", e) }
    }
}
