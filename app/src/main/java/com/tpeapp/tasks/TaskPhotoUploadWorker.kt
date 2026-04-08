package com.tpeapp.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.tpeapp.pairing.PairingActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TaskPhotoUploadWorker
 *
 * [CoroutineWorker] that uploads the task verification photo to the partner
 * backend at `POST /api/tasks/verify`.
 *
 * Mirrors [com.tpeapp.adherence.AuditUploadWorker] in structure: the request
 * is a multipart/form-data body carrying the task ID and the JPEG photo file.
 * Failures trigger an automatic [Result.retry] with exponential back-off.
 *
 * Input data keys
 * ---------------
 * Pass via [androidx.work.Data.Builder]:
 *  - [KEY_PHOTO_PATH] – absolute path of the JPEG proof photo.
 *  - [KEY_TASK_ID]    – UUID of the task being verified.
 */
class TaskPhotoUploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TaskPhotoUploadWorker"

        const val KEY_PHOTO_PATH = "photo_path"
        const val KEY_TASK_ID    = "task_id"

        private const val NOTIFICATION_CHANNEL_ID   = "task_photo_upload"
        private const val NOTIFICATION_CHANNEL_NAME = "Task Proof Upload"
        private const val NOTIFICATION_ID           = 0x7A52

        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private val TEXT_MEDIA_TYPE = "text/plain".toMediaType()

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result {
        val photoPath = inputData.getString(KEY_PHOTO_PATH)
        val taskId    = inputData.getString(KEY_TASK_ID)

        if (photoPath.isNullOrBlank() || taskId.isNullOrBlank()) {
            Log.e(TAG, "Missing input data — cannot upload task proof")
            return Result.failure()
        }

        val photoFile = File(photoPath)
        if (!photoFile.exists()) {
            Log.e(TAG, "Photo file not found: $photoPath")
            return Result.failure()
        }

        val endpoint = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString(PairingActivity.PREF_PARTNER_ENDPOINT, null)

        if (endpoint.isNullOrBlank()) {
            Log.e(TAG, "Partner endpoint not configured — cannot upload task proof")
            return Result.failure()
        }

        setForeground(buildForegroundInfo())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "photo",
                photoFile.name,
                photoFile.asRequestBody(JPEG_MEDIA_TYPE)
            )
            .addFormDataPart("task_id", taskId, taskId.toRequestBody(TEXT_MEDIA_TYPE))
            .addFormDataPart(
                "timestamp",
                System.currentTimeMillis().toString(),
                System.currentTimeMillis().toString().toRequestBody(TEXT_MEDIA_TYPE)
            )
            .build()

        val request = Request.Builder()
            .url("$endpoint/api/tasks/verify")
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Task proof uploaded (HTTP ${response.code}) for task $taskId")
                    photoFile.delete()
                    Result.success()
                } else {
                    Log.w(TAG, "Server rejected task proof: HTTP ${response.code} — will retry")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Task proof upload failed — will retry: ${e.message}")
            Result.retry()
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Silent channel for task proof upload" }
            )
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Uploading task proof…")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
