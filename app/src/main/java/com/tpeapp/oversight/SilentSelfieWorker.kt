package com.tpeapp.oversight

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * SilentSelfieWorker — takes a silent front-camera photo and uploads it to
 * `{endpoint}/api/tpe/upload` for Dom review.
 *
 * After each capture it re-enqueues itself with a random delay between
 * [EXTRA_MIN_INTERVAL_MINUTES] and [EXTRA_MAX_INTERVAL_MINUTES].
 */
class SilentSelfieWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SilentSelfieWorker"
        private const val WORK_NAME = "silent_selfie"
        const val EXTRA_MIN_INTERVAL_MINUTES = "min_interval_minutes"
        const val EXTRA_MAX_INTERVAL_MINUTES = "max_interval_minutes"
        private const val DEFAULT_MIN = 30
        private const val DEFAULT_MAX = 120

        fun schedule(ctx: Context, minMinutes: Int = DEFAULT_MIN, maxMinutes: Int = DEFAULT_MAX) {
            val delayMs = Random.nextLong(
                minMinutes * 60_000L,
                maxMinutes * 60_000L
            )
            val data = workDataOf(
                EXTRA_MIN_INTERVAL_MINUTES to minMinutes,
                EXTRA_MAX_INTERVAL_MINUTES to maxMinutes
            )
            val request = OneTimeWorkRequestBuilder<SilentSelfieWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            Log.i(TAG, "Silent selfie scheduled in ${delayMs / 60_000}m")
        }
    }

    override suspend fun doWork(): Result {
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val endpoint = prefs.getString(PairingActivity.PREF_PARTNER_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() } ?: return Result.success()
        val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
        val deviceId = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }

        return try {
            val photoFile = capturePhoto()
            uploadPhoto(photoFile, endpoint, token, deviceId)
            photoFile.delete()

            val webhookUrl = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() }
            if (webhookUrl != null) {
                WebhookManager.dispatchEvent(webhookUrl, token, JSONObject().apply {
                    put("event", "silent_selfie_uploaded")
                    put("timestamp", System.currentTimeMillis())
                })
            }

            // Re-schedule with same interval bounds
            val min = inputData.getInt(EXTRA_MIN_INTERVAL_MINUTES, DEFAULT_MIN)
            val max = inputData.getInt(EXTRA_MAX_INTERVAL_MINUTES, DEFAULT_MAX)
            schedule(appContext, min, max)

            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Silent selfie failed", e)
            Result.retry()
        }
    }

    // ------------------------------------------------------------------
    //  Camera capture
    // ------------------------------------------------------------------

    private suspend fun capturePhoto(): File = suspendCancellableCoroutine { cont ->
        val outputFile = File(appContext.cacheDir, "selfie_${System.currentTimeMillis()}.jpg")

        // CameraX needs a LifecycleOwner — create a minimal one.
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        lifecycleOwner.registry.currentState = Lifecycle.State.STARTED

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageCapture
                )

                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(appContext),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            provider.unbindAll()
                            lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
                            cont.resume(outputFile)
                        }

                        override fun onError(exc: ImageCaptureException) {
                            provider.unbindAll()
                            lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
                            cont.resumeWithException(exc)
                        }
                    }
                )
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    // ------------------------------------------------------------------
    //  Upload
    // ------------------------------------------------------------------

    private fun uploadPhoto(file: File, endpoint: String, token: String?, deviceId: String?) {
        val url = "$endpoint/api/tpe/upload"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()

        val reqBuilder = Request.Builder().url(url).post(body)
        if (!token.isNullOrBlank()) reqBuilder.addHeader("Authorization", "Bearer $token")
        if (!deviceId.isNullOrBlank()) reqBuilder.addHeader("X-Device-ID", deviceId)

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        client.newCall(reqBuilder.build()).execute().use { resp ->
            Log.i(TAG, "Selfie upload → HTTP ${resp.code}")
        }
    }
}
