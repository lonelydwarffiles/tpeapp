package com.hound.controller.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Service
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.hound.controller.filter.IOnnxIpcService
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistent IPC service used by in-process hooks for synchronous NSFW checks.
 */
class OnnxIpcService : Service() {

    companion object {
        private const val TAG = "OnnxIpcService"
        private const val MODEL_ASSET_NAME = "nudenet.ort"
        private const val MODEL_INPUT_WIDTH = 320
        private const val MODEL_INPUT_HEIGHT = 320
        private const val NSFW_CLASS_INDEX = 1
        private const val NSFW_THRESHOLD = 0.5f
        private const val ANALYZE_TIMEOUT_MS = 1200L
        private const val ANALYSIS_THREADS = 4
    }

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    private val analysisExecutor = Executors.newFixedThreadPool(ANALYSIS_THREADS) { runnable ->
        Thread(runnable, "onnx-ipc-analysis").apply { isDaemon = true }
    }
    private val analysisDispatcher = analysisExecutor.asCoroutineDispatcher()
    private val inferenceMutex = Mutex()

    private val binder = object : IOnnxIpcService.Stub() {
        override fun analyzeImage(imageBytes: ByteArray?): Boolean {
            if (imageBytes == null || imageBytes.isEmpty()) return false
            return analyzeImageInternal(imageBytes)
        }
    }

    override fun onCreate() {
        super.onCreate()
        initializeOrt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        runCatching { session?.close() }
        runCatching { env?.close() }
        runCatching { analysisDispatcher.close() }
        runCatching { analysisExecutor.shutdownNow() }
        session = null
        env = null
        super.onDestroy()
    }

    private fun initializeOrt() {
        runCatching {
            val modelBytes = assets.open(MODEL_ASSET_NAME).use { it.readBytes() }
            val ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                configureNnapi(this)
            }
            val ortSession = ortEnv.createSession(modelBytes, options)
            env = ortEnv
            session = ortSession
            Log.i(TAG, "ORT initialized with NNAPI configuration")
        }.onFailure {
            Log.e(TAG, "Failed to initialize ORT session", it)
            env = null
            session = null
        }
    }

    private fun configureNnapi(options: OrtSession.SessionOptions) {
        // Keep this reflective to tolerate ORT API differences across versions.
        val candidates = listOf(
            "addNnapi",
            "appendNnapi",
            "appendExecutionProvider_Nnapi",
        )
        for (name in candidates) {
            val method = options.javaClass.methods.firstOrNull { m ->
                m.name == name && m.parameterTypes.isEmpty()
            } ?: continue
            runCatching {
                method.invoke(options)
                Log.i(TAG, "Enabled NNAPI via $name()")
                return
            }
        }
        Log.w(TAG, "NNAPI provider method not found; running with default provider stack")
    }

    private fun analyzeImageInternal(imageBytes: ByteArray): Boolean {
        if (imageBytes.isEmpty()) return false
        return runBlocking {
            withTimeoutOrNull(ANALYZE_TIMEOUT_MS) {
                withContext(analysisDispatcher) {
                    runInference(imageBytes)
                }
            } ?: false
        }
    }

    private suspend fun runInference(imageBytes: ByteArray): Boolean {
        val ortSession = session ?: return false
        val ortEnv = env ?: return false
        val inputName = ortSession.inputNames.firstOrNull() ?: run {
            Log.e(TAG, "ORT session has no input names")
            return false
        }

        return try {
            val buffer = preprocessImageForOnnx(imageBytes)
            inferenceMutex.withLock {
                var inputTensor: OnnxTensor? = null
                try {
                    inputTensor = OnnxTensor.createTensor(
                        ortEnv,
                        buffer,
                        longArrayOf(1, 3, MODEL_INPUT_HEIGHT.toLong(), MODEL_INPUT_WIDTH.toLong()),
                    )

                    var result: OrtSession.Result? = null
                    try {
                        result = ortSession.run(mapOf(inputName to inputTensor))
                        val probabilities = extractProbabilities(result)
                        if (probabilities.isEmpty()) return@withLock false

                        val nsfwProbability = if (NSFW_CLASS_INDEX in probabilities.indices) {
                            probabilities[NSFW_CLASS_INDEX]
                        } else {
                            probabilities.maxOrNull() ?: 0f
                        }
                        nsfwProbability > NSFW_THRESHOLD
                    } finally {
                        result?.close()
                    }
                } finally {
                    inputTensor?.close()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ONNX inference failed", t)
            false
        }
    }

    private fun extractProbabilities(result: OrtSession.Result): FloatArray {
        if (result.size() <= 0) return FloatArray(0)
        val value = runCatching { result[0].value }.getOrNull()
        return flattenToFloatArray(value)
    }

    private fun flattenToFloatArray(value: Any?): FloatArray {
        return when (value) {
            null -> FloatArray(0)
            is FloatArray -> value
            is DoubleArray -> FloatArray(value.size) { idx -> value[idx].toFloat() }
            is Array<*> -> {
                val out = ArrayList<Float>(128)
                for (item in value) {
                    val chunk = flattenToFloatArray(item)
                    for (v in chunk) out.add(v)
                }
                FloatArray(out.size) { idx -> out[idx] }
            }
            else -> FloatArray(0)
        }
    }

    private fun preprocessImageForOnnx(imageBytes: ByteArray): FloatBuffer {
        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("Unable to decode image bytes")

        val scaled = Bitmap.createScaledBitmap(decoded, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT, true)
        if (scaled !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }

        try {
            val width = MODEL_INPUT_WIDTH
            val height = MODEL_INPUT_HEIGHT
            val pixelCount = width * height
            val pixels = IntArray(pixelCount)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)

            val chw = FloatArray(1 * 3 * pixelCount)
            var i = 0
            while (i < pixelCount) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF) / 255.0f
                val g = ((p shr 8) and 0xFF) / 255.0f
                val b = (p and 0xFF) / 255.0f

                chw[i] = r
                chw[pixelCount + i] = g
                chw[(2 * pixelCount) + i] = b
                i += 1
            }

            return FloatBuffer.wrap(chw)
        } finally {
            if (!scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }
}
