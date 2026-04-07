package com.tpeapp.adherence

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.atomic.AtomicInteger

/**
 * AdherenceVisionAnalyzer
 *
 * Lightweight CameraX [ImageAnalysis.Analyzer] that runs on-device TFLite object
 * detection against each camera frame to verify that the user is physically
 * completing their health routine.
 *
 * The model file (`adherence_detector.tflite`) must be placed in `assets/`.
 * It is assumed to be compatible with the TFLite Task Vision [ObjectDetector] API
 * (i.e., a standard SSD/EfficientDet detection model with an associated metadata
 * file describing label names).
 *
 * Detection logic
 * ---------------
 * Every analyzed frame is checked for the presence of ALL [REQUIRED_LABELS].  A
 * frame is counted as "verified" when every required label appears in at least
 * one detected object whose score exceeds [SCORE_THRESHOLD].
 *
 * After recording the caller reads [detectionRatio] and calls [isAutoApproved]
 * to determine whether the session qualifies for AUTO_APPROVED status.
 *
 * Thread-safety
 * -------------
 * Frame counters use [AtomicInteger] so they can be read from any thread while
 * [analyze] runs on the CameraX analysis executor.
 *
 * Resource management
 * -------------------
 * Call [close] when the hosting activity is destroyed to release the native
 * TFLite interpreter.
 */
class AdherenceVisionAnalyzer(context: Context) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "AdherenceVisionAnalyzer"
        private const val MODEL_FILE = "adherence_detector.tflite"

        /** Minimum detection confidence to accept a bounding box. */
        private const val SCORE_THRESHOLD = 0.50f

        /** Maximum number of objects returned per frame by the detector. */
        private const val MAX_RESULTS = 5

        /**
         * Minimum fraction of analyzed frames that must be "verified" for the
         * session to be classified as AUTO_APPROVED.
         */
        const val MIN_DETECTION_RATIO = 0.60f

        /**
         * Only run inference on every Nth frame so that TFLite analysis never
         * competes with the VideoCapture pipeline.  With a 30 fps camera feed
         * this gives ~10 analyzed frames per second, which is sufficient for
         * reliable detection over a 15-second session.
         */
        private const val FRAME_SKIP = 3

        /**
         * Object categories that MUST all be present in a frame for it to be
         * counted as verified.  Labels are matched case-insensitively via
         * substring so that model-specific label variants (e.g.
         * "medical_equipment") still match.
         */
        private val REQUIRED_LABELS = listOf("person", "medical equipment")
    }

    // Opened once; closed in close().
    private val detector: ObjectDetector = ObjectDetector.createFromFileAndOptions(
        context,
        MODEL_FILE,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .build()
    )

    // Running frame counters — updated by the analysis thread, read by the UI thread.
    private val totalFrames    = AtomicInteger(0)
    private val detectedFrames = AtomicInteger(0)

    /** Incremented for every frame received; used to implement FRAME_SKIP. */
    private val frameCounter = AtomicInteger(0)

    /** Last top-label and score forwarded to observers (informational). */
    @Volatile var lastLabel: String = "–"
    @Volatile var lastScore: Float  = 0f

    /** Fraction of analyzed frames where ALL required objects were detected. */
    val detectionRatio: Float
        get() {
            val total = totalFrames.get()
            return if (total == 0) 0f else detectedFrames.get().toFloat() / total
        }

    /** Returns true if [detectionRatio] meets the [MIN_DETECTION_RATIO] threshold. */
    fun isAutoApproved(): Boolean = detectionRatio >= MIN_DETECTION_RATIO

    /** Resets counters for a fresh recording session. */
    fun reset() {
        totalFrames.set(0)
        detectedFrames.set(0)
        frameCounter.set(0)
        lastLabel = "–"
        lastScore = 0f
    }

    // ------------------------------------------------------------------
    //  ImageAnalysis.Analyzer
    // ------------------------------------------------------------------

    override fun analyze(image: ImageProxy) {
        // Skip frames to keep inference lightweight alongside VideoCapture.
        val count = frameCounter.incrementAndGet()
        if (count % FRAME_SKIP != 0) {
            image.close()
            return
        }

        // toBitmap() converts the YUV_420_888 ImageProxy to an RGB Bitmap without
        // requiring @ExperimentalGetImage; available since CameraX 1.1.0.
        val bitmap = image.toBitmap()
        image.close() // release CameraX buffer immediately after copy

        totalFrames.incrementAndGet()

        try {
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val results     = detector.detect(tensorImage)

            // Collect all detected label strings (lower-cased) above the threshold.
            val detectedLabelSet = results
                .flatMap { detection -> detection.categories }
                .map    { category  -> category.label.lowercase() }
                .toSet()

            // A frame is "verified" when every required label has at least one match.
            val allPresent = REQUIRED_LABELS.all { required ->
                detectedLabelSet.any { detected -> detected.contains(required) }
            }

            if (allPresent) detectedFrames.incrementAndGet()

            // Expose the top result for UI feedback.
            results.firstOrNull()?.categories?.firstOrNull()?.let { top ->
                lastLabel = top.label
                lastScore = top.score
            }

            Log.v(TAG, "frame verified=$allPresent labels=$detectedLabelSet " +
                    "ratio=${String.format("%.2f", detectionRatio)}")
        } catch (e: Exception) {
            Log.w(TAG, "Frame analysis error — skipping frame", e)
        }
    }

    // ------------------------------------------------------------------
    //  Resource management
    // ------------------------------------------------------------------

    fun close() {
        try { detector.close() } catch (e: Exception) { Log.w(TAG, "detector close", e) }
    }
}
