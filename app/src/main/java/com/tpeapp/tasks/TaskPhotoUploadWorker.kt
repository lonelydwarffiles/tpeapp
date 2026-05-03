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
import com.tpeapp.service.FilterService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TaskPhotoUploadWorker
 *
 * [CoroutineWorker] that reports task completion to the partner backend at
 * `POST /api/tpe/task/status`.
 *
 * The request is a JSON body with `task_id`, `status`, and an optional
 * `proof_note`.  If a webhook bearer token is configured it is included as a
 * `Authorization: Bearer` header (same secret used by ConsequenceDispatcher).
 *
 * Failures trigger an automatic [Result.retry] with exponential back-off.
 *
 * Input data keys
 * ---------------
 * Pass via [androidx.work.Data.Builder]:
 *  - [KEY_PHOTO_PATH] – absolute path of the JPEG proof photo (kept locally).
 *  - [KEY_TASK_ID]    – UUID of the task being reported as completed.
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

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)

        if (taskId.isNullOrBlank()) {
            Log.e(TAG, "Missing task_id input — cannot report task status")
            return Result.failure()
        }

        val prefs    = PreferenceManager.getDefaultSharedPreferences(context)
        val endpoint = prefs.getString(PairingActivity.PREF_PARTNER_ENDPOINT, null)

        if (endpoint.isNullOrBlank()) {
            Log.e(TAG, "Partner endpoint not configured — cannot report task status")
            return Result.failure()
        }

        val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
        val deviceId = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }

        setForeground(buildForegroundInfo())

        val body = JSONObject().apply {
            put("task_id",    taskId)
            put("status",     "completed")
            put("proof_note", "Photo proof captured on device")
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder = Request.Builder()
            .url("$endpoint/api/tpe/task/status")
            .post(body)
        if (bearerToken != null) {
            requestBuilder.addHeader("Authorization", "Bearer $bearerToken")
        }
        if (deviceId != null) {
            requestBuilder.addHeader("X-Device-ID", deviceId)
        }

        return try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Task status reported (HTTP ${response.code}) for task $taskId")
                    Result.success()
                } else {
                    Log.w(TAG, "Server rejected task status: HTTP ${response.code} — will retry")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Task status report failed — will retry: ${e.message}")
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
                ).apply { description = "Silent channel for task status reporting" }
            )
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Reporting task completion…")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
