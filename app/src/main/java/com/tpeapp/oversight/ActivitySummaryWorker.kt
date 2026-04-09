package com.tpeapp.oversight

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tpeapp.service.FilterService
import com.tpeapp.tasks.TaskRepository
import com.tpeapp.tasks.TaskStatus
import com.tpeapp.webhook.WebhookManager
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * ActivitySummaryWorker — sends a daily digest webhook to the Dom summarising
 * completed/missed tasks, check-in count, and content filter hits.
 *
 * Schedule via [ActivitySummaryWorker.schedule].
 */
class ActivitySummaryWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ActivitySummaryWorker"
        private const val WORK_NAME = "daily_activity_summary"
        const val PREF_FILTER_HIT_COUNT = "filter_hit_count"
        const val PREF_CHECKIN_COUNT = "checkin_count"

        fun schedule(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<ActivitySummaryWorker>()
                .setInitialDelay(msUntilMidnight(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            Log.i(TAG, "Activity summary scheduled")
        }

        private fun msUntilMidnight(): Long {
            val cal = Calendar.getInstance()
            val now = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return (cal.timeInMillis - now).coerceAtLeast(1_000L)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
            val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() }
                ?: return Result.success()
            val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)

            val tasks = TaskRepository.loadTasks(appContext)
            val completed = tasks.count { it.status == TaskStatus.COMPLETED }
            val missed = tasks.count { it.status == TaskStatus.MISSED }
            val filterHits = prefs.getInt(PREF_FILTER_HIT_COUNT, 0)
            val checkinCount = prefs.getInt(PREF_CHECKIN_COUNT, 0)

            val payload = JSONObject().apply {
                put("event", "daily_activity_summary")
                put("tasks_completed", completed)
                put("tasks_missed", missed)
                put("filter_hits", filterHits)
                put("checkin_count", checkinCount)
                put("timestamp", System.currentTimeMillis())
            }

            WebhookManager.dispatchEvent(url, token, payload)
            Log.i(TAG, "Activity summary dispatched: completed=$completed missed=$missed hits=$filterHits")

            // Reset daily counters
            prefs.edit()
                .putInt(PREF_FILTER_HIT_COUNT, 0)
                .putInt(PREF_CHECKIN_COUNT, 0)
                .apply()

            // Re-schedule for tomorrow
            schedule(appContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Activity summary failed", e)
            Result.retry()
        }
    }
}
