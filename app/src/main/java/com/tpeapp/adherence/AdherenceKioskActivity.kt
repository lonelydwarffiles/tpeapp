package com.tpeapp.adherence

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tpeapp.databinding.ActivityAdherenceKioskBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AdherenceKioskActivity
 *
 * A full-screen, un-dismissible "kiosk" activity that enforces the user's daily
 * health routine before unlocking normal device usage.
 *
 * Lifecycle
 * ---------
 * 1. Launched by [AdherenceAlarmReceiver] at the scheduled time via a
 *    full-screen intent notification.
 * 2. Shows a camera preview and guides the user to record a mandatory 15-second
 *    video.
 * 3. During recording [AdherenceVisionAnalyzer] inspects each camera frame for
 *    the presence of required objects ("person" + "medical equipment").
 * 4. After the recording completes:
 *    – AUTO_APPROVED  → activity finishes immediately and enqueues
 *                       [AuditUploadWorker] to upload the .mp4 + ML scores.
 *    – NOT_APPROVED   → error state shown; the user can tap "Try again".
 *
 * Kiosk behavior
 * --------------
 * - Back gesture / button: intercepted and discarded on all API levels.
 * - Show-over-lock-screen: [setShowWhenLocked] + [setTurnScreenOn] flags.
 * - Screen stays on: [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON].
 * - Hardware volume / camera keys: ignored (see [onKeyDown]).
 */
class AdherenceKioskActivity : AppCompatActivity() {

    companion object {
        private const val TAG              = "AdherenceKioskActivity"
        private const val RECORDING_MS     = 15_000L   // 15-second mandatory recording
        private const val TICK_MS          = 1_000L
        private const val ANALYSIS_CHANNEL = "adherence_analysis"

        /** WorkManager unique task name — prevents duplicate uploads. */
        private const val UPLOAD_WORK_NAME = "adherence_audit_upload"
    }

    // ------------------------------------------------------------------
    //  View binding
    // ------------------------------------------------------------------

    private lateinit var binding: ActivityAdherenceKioskBinding

    // ------------------------------------------------------------------
    //  CameraX components
    // ------------------------------------------------------------------

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var analyzer: AdherenceVisionAnalyzer

    /** Currently active recording handle; null when not recording. */
    private var activeRecording: Recording? = null

    /** File that will receive the recorded video. */
    private var videoFile: File? = null

    // ------------------------------------------------------------------
    //  Countdown timer
    // ------------------------------------------------------------------

    private var countDownTimer: CountDownTimer? = null

    // ------------------------------------------------------------------
    //  Permission launcher
    // ------------------------------------------------------------------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraOk = grants[Manifest.permission.CAMERA]        == true
            val audioOk  = grants[Manifest.permission.RECORD_AUDIO]  == true
            if (cameraOk && audioOk) {
                startCamera()
            } else {
                showStatus("⚠️ Camera and microphone permissions are required.")
            }
        }

    // ------------------------------------------------------------------
    //  Activity lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        // ---- Kiosk window flags (applied before setContentView) ----
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        super.onCreate(savedInstanceState)

        binding = ActivityAdherenceKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Swallow back gesture on Android 13+ (API 33) before the system handles it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY
            ) { /* do nothing — kiosk mode */ }
        }

        analysisExecutor = Executors.newSingleThreadExecutor()
        analyzer         = AdherenceVisionAnalyzer(this)

        binding.btnStartRecording.setOnClickListener { startRecordingSession() }

        checkPermissionsAndStartCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        activeRecording?.stop()
        analyzer.close()
        analysisExecutor.shutdown()
    }

    // ------------------------------------------------------------------
    //  Kiosk — back button / key swallowing
    // ------------------------------------------------------------------

    @Deprecated("Handles back for API < 33; API 33+ is covered by OnBackInvokedCallback registered in onCreate")
    override fun onBackPressed() {
        // Intentionally do nothing — the device must not be unlocked until
        // the health routine video has been verified.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Consume all hardware key events to prevent any navigation shortcut
        // from escaping the kiosk.
        return true
    }

    // ------------------------------------------------------------------
    //  Permissions
    // ------------------------------------------------------------------

    private fun checkPermissionsAndStartCamera() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) startCamera() else permissionLauncher.launch(needed)
    }

    // ------------------------------------------------------------------
    //  CameraX setup
    // ------------------------------------------------------------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            analyzer.reset()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture,
                    analysis
                )
                showStatus("Camera ready. Tap the button when you are ready to record.")
                binding.btnStartRecording.isEnabled = true
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                showStatus("⚠️ Could not start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------------
    //  Recording session
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startRecordingSession() {
        binding.btnStartRecording.isEnabled = false
        analyzer.reset()

        videoFile = File(
            getExternalFilesDir(null),
            "adherence_${System.currentTimeMillis()}.mp4"
        )
        val outputOptions = FileOutputOptions.Builder(videoFile!!).build()

        activeRecording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start    -> onRecordingStarted()
                    is VideoRecordEvent.Finalize -> onRecordingFinalized(event)
                    else                         -> Unit
                }
            }
    }

    private fun onRecordingStarted() {
        showStatus("🔴 Recording… Complete your health routine.")
        startCountdown()
    }

    private fun startCountdown() {
        countDownTimer = object : CountDownTimer(RECORDING_MS, TICK_MS) {
            override fun onTick(millisRemaining: Long) {
                val seconds = (millisRemaining / 1000) + 1
                binding.tvTimer.text = "${seconds}s"
            }

            override fun onFinish() {
                binding.tvTimer.text = "0s"
                activeRecording?.stop()
                activeRecording = null
            }
        }.start()
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize) {
        if (event.hasError()) {
            Log.e(TAG, "Recording error ${event.error}: ${event.cause?.message}")
            showStatus("⚠️ Recording failed. Please try again.")
            binding.btnStartRecording.isEnabled = true
            return
        }

        if (analyzer.isAutoApproved()) {
            enqueueUploadAndFinish()
        } else {
            val ratio = String.format("%.0f%%", analyzer.detectionRatio * 100)
            showStatus(
                "❌ Verification failed ($ratio detection rate). " +
                "Ensure the required items are visible and tap Try Again."
            )
            binding.btnStartRecording.text    = "Try Again"
            binding.btnStartRecording.isEnabled = true
            videoFile?.delete()
            videoFile = null
            analyzer.reset()
        }
    }

    // ------------------------------------------------------------------
    //  Upload + unlock
    // ------------------------------------------------------------------

    private fun enqueueUploadAndFinish() {
        showStatus("✅ Verified! Unlocking device…")

        val file = videoFile ?: run {
            Log.w(TAG, "Video file reference lost — skipping upload")
            finish()
            return
        }

        val inputData = Data.Builder()
            .putString(AuditUploadWorker.KEY_VIDEO_PATH,    file.absolutePath)
            .putFloat(AuditUploadWorker.KEY_DETECTION_RATIO, analyzer.detectionRatio)
            .putString(AuditUploadWorker.KEY_LAST_LABEL,    analyzer.lastLabel)
            .putFloat(AuditUploadWorker.KEY_LAST_SCORE,     analyzer.lastScore)
            .build()

        val request = OneTimeWorkRequestBuilder<AuditUploadWorker>()
            .setInputData(inputData)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(UPLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        Log.i(TAG, "Audit upload enqueued for ${file.name}")

        // Unlock immediately — upload continues in the background.
        finish()
    }

    // ------------------------------------------------------------------
    //  Helper
    // ------------------------------------------------------------------

    private fun showStatus(message: String) {
        runOnUiThread { binding.tvStatus.text = message }
    }
}
