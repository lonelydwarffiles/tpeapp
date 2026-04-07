package com.tpeapp.adherence

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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AuditUploadWorker
 *
 * WorkManager [CoroutineWorker] that silently uploads the adherence audit
 * artifact — the recorded `.mp4` file and ML confidence scores — to the
 * Node.js backend at `POST /api/audit/upload`.
 *
 * Retry behavior
 * --------------
 * The worker returns [Result.retry] on any network or server error.
 * The caller enqueues the request with exponential back-off so that a
 * temporary loss of connectivity does not permanently drop the audit record.
 * WorkManager reschedules the worker automatically even if the app process
 * was killed while the upload was in flight.
 *
 * Input data keys
 * ---------------
 * Pass via [androidx.work.Data.Builder]:
 *  - [KEY_VIDEO_PATH]       – absolute path of the `.mp4` file to upload.
 *  - [KEY_DETECTION_RATIO]  – fraction of frames where all required objects
 *                             were detected (float, 0–1).
 *  - [KEY_LAST_LABEL]       – last detected object label string.
 *  - [KEY_LAST_SCORE]       – confidence score for [KEY_LAST_LABEL].
 */
class AuditUploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AuditUploadWorker"

        // Input data keys (used by AdherenceKioskActivity to pass data)
        const val KEY_VIDEO_PATH       = "video_path"
        const val KEY_DETECTION_RATIO  = "detection_ratio"
        const val KEY_LAST_LABEL       = "last_label"
        const val KEY_LAST_SCORE       = "last_score"

        private const val NOTIFICATION_CHANNEL_ID  = "adherence_upload"
        private const val NOTIFICATION_CHANNEL_NAME = "Adherence Upload"
        private const val NOTIFICATION_ID          = 0xAD1

        private val MP4_MEDIA_TYPE  = "video/mp4".toMediaType()

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60,    TimeUnit.SECONDS)
            .writeTimeout(120,  TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------------
    //  CoroutineWorker entry point
    // ------------------------------------------------------------------

    override suspend fun doWork(): Result {
        val videoPath       = inputData.getString(KEY_VIDEO_PATH)
        val detectionRatio  = inputData.getFloat(KEY_DETECTION_RATIO, 0f)
        val lastLabel       = inputData.getString(KEY_LAST_LABEL) ?: "unknown"
        val lastScore       = inputData.getFloat(KEY_LAST_SCORE, 0f)

        if (videoPath.isNullOrBlank()) {
            Log.e(TAG, "No video path in input data — cannot upload")
            return Result.failure()
        }

        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
            Log.e(TAG, "Video file not found: $videoPath")
            return Result.failure()
        }

        val endpoint = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString(PairingActivity.PREF_PARTNER_ENDPOINT, null)

        if (endpoint.isNullOrBlank()) {
            Log.e(TAG, "Partner endpoint not set — cannot upload audit")
            return Result.failure()
        }

        // Promote to foreground so the OS does not kill the upload on Android 12+.
        setForeground(buildForegroundInfo())

        val scoresJson = JSONObject().apply {
            put("detection_ratio", detectionRatio)
            put("last_label",      lastLabel)
            put("last_score",      lastScore)
            put("session_ts",      System.currentTimeMillis())
        }.toString()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "video",
                videoFile.name,
                videoFile.asRequestBody(MP4_MEDIA_TYPE)
            )
            // Send scores as a plain string form field so the server reads it
            // from req.body.scores without needing file-based handling.
            .addFormDataPart("scores", scoresJson)
            .build()

        val request = Request.Builder()
            .url("$endpoint/api/audit/upload")
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Audit uploaded successfully (HTTP ${response.code})")
                    videoFile.delete()
                    Result.success()
                } else {
                    Log.w(TAG, "Server rejected upload: HTTP ${response.code} — will retry")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed — will retry: ${e.message}")
            Result.retry()
        }
    }

    // ------------------------------------------------------------------
    //  Foreground info (required for setForeground on API 31+)
    // ------------------------------------------------------------------

    private fun buildForegroundInfo(): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Silent channel for adherence audit upload" }
            )
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Syncing health routine audit…")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
