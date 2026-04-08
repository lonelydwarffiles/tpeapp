package com.tpeapp.tasks

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.WorkManager
import com.tpeapp.consequence.ConsequenceDispatcher
import com.tpeapp.databinding.ActivityTaskVerificationBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * TaskVerificationActivity
 *
 * Shows the details of an assigned task and lets the device owner submit a
 * photo as proof of completion.
 *
 * Flow
 * ----
 * 1. Camera preview starts automatically after CAMERA permission is granted.
 * 2. "Capture Photo" takes a still JPEG and shows a confirmation preview.
 * 3. "Submit Proof" marks the task [TaskStatus.COMPLETED], cancels the
 *    deadline alarm, dispatches a [ConsequenceDispatcher.reward], and enqueues
 *    [TaskPhotoUploadWorker] to deliver the photo to the partner backend.
 * 4. If the task deadline has already passed when this activity opens, the
 *    submit button is disabled and an error is shown.
 */
class TaskVerificationActivity : AppCompatActivity() {

    companion object {
        private const val TAG             = "TaskVerificationActivity"
        private const val UPLOAD_WORK_TAG = "task_photo_upload"

        /** Intent extra: UUID string of the task to verify. */
        const val EXTRA_TASK_ID = "extra_task_id"
    }

    private lateinit var binding: ActivityTaskVerificationBinding

    private var imageCapture: ImageCapture? = null
    private var capturedPhotoFile: File?    = null
    private lateinit var task: Task

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showStatus("⚠️ Camera permission required to capture proof.")
        }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId == null) {
            Log.e(TAG, "No task ID in intent — finishing")
            finish()
            return
        }

        val loaded = TaskRepository.findById(this, taskId)
        if (loaded == null) {
            Log.e(TAG, "Task $taskId not found — finishing")
            finish()
            return
        }
        task = loaded

        populateTaskDetails()
        bindButtons()

        if (task.status != TaskStatus.PENDING) {
            disableCapture("This task is already ${task.status.name.lowercase()}.")
            return
        }

        if (System.currentTimeMillis() > task.deadlineMs) {
            disableCapture("⚠️ The deadline for this task has passed.")
            return
        }

        checkPermissionAndStartCamera()
    }

    // ------------------------------------------------------------------
    //  UI population
    // ------------------------------------------------------------------

    private fun populateTaskDetails() {
        binding.tvTaskTitle.text       = task.title
        binding.tvTaskDescription.text = task.description
        val fmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        binding.tvTaskDeadline.text    = "Due: ${fmt.format(Date(task.deadlineMs))}"
    }

    private fun bindButtons() {
        binding.btnCapturePhoto.setOnClickListener { capturePhoto() }
        binding.btnSubmitProof.setOnClickListener  { submitProof()  }
        binding.btnSubmitProof.isEnabled = false
    }

    private fun disableCapture(message: String) {
        binding.btnCapturePhoto.isEnabled = false
        binding.btnSubmitProof.isEnabled  = false
        showStatus(message)
    }

    // ------------------------------------------------------------------
    //  Camera
    // ------------------------------------------------------------------

    private fun checkPermissionAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture!!
                )
                showStatus("Camera ready. Position yourself and tap Capture Photo.")
                binding.btnCapturePhoto.isEnabled = true
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                showStatus("⚠️ Could not start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------------
    //  Capture
    // ------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun capturePhoto() {
        val capture = imageCapture ?: return
        binding.btnCapturePhoto.isEnabled = false
        showStatus("Capturing…")

        val photoFile = File(
            getExternalFilesDir(null),
            "task_proof_${task.id}_${System.currentTimeMillis()}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedPhotoFile = photoFile
                    showStatus("✅ Photo captured. Tap Submit Proof to complete the task.")
                    binding.btnCapturePhoto.isEnabled = true
                    binding.btnSubmitProof.isEnabled  = true
                    Log.i(TAG, "Photo captured: ${photoFile.absolutePath}")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    showStatus("⚠️ Capture failed: ${exception.message}. Try again.")
                    binding.btnCapturePhoto.isEnabled = true
                }
            }
        )
    }

    // ------------------------------------------------------------------
    //  Submit
    // ------------------------------------------------------------------

    private fun submitProof() {
        val photo = capturedPhotoFile
        if (photo == null || !photo.exists()) {
            showStatus("⚠️ No photo captured yet.")
            return
        }

        if (System.currentTimeMillis() > task.deadlineMs) {
            disableCapture("⚠️ Deadline passed before you could submit. Task marked as missed.")
            TaskRepository.upsertTask(this, task.copy(status = TaskStatus.MISSED))
            ConsequenceDispatcher.punish(this, "task_late_submission:${task.title}")
            return
        }

        // Mark complete, cancel alarm, dispatch reward.
        val completed = task.copy(status = TaskStatus.COMPLETED, photoUri = photo.absolutePath)
        TaskRepository.upsertTask(this, completed)
        TaskRepository.cancelDeadlineAlarm(this, task.id)
        ConsequenceDispatcher.reward(this, "task_completed:${task.title}")

        enqueuePhotoUpload(photo.absolutePath)

        showStatus("✅ Task verified! Uploading proof…")
        binding.btnSubmitProof.isEnabled  = false
        binding.btnCapturePhoto.isEnabled = false

        // Return to task list after a short delay.
        binding.root.postDelayed({ finish() }, 1_500)
    }

    private fun enqueuePhotoUpload(photoPath: String) {
        val inputData = Data.Builder()
            .putString(TaskPhotoUploadWorker.KEY_PHOTO_PATH, photoPath)
            .putString(TaskPhotoUploadWorker.KEY_TASK_ID, task.id)
            .build()

        val request = OneTimeWorkRequestBuilder<TaskPhotoUploadWorker>()
            .setInputData(inputData)
            .addTag(UPLOAD_WORK_TAG)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "task_upload_${task.id}",
                ExistingWorkPolicy.KEEP,
                request
            )
        Log.i(TAG, "Task proof upload enqueued for task ${task.id}")
    }

    // ------------------------------------------------------------------
    //  Helper
    // ------------------------------------------------------------------

    private fun showStatus(message: String) {
        runOnUiThread {
            binding.tvStatus.text = message
            binding.tvStatus.visibility = View.VISIBLE
        }
    }
}
