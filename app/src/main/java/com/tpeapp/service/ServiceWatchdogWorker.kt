package com.hound.controller.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodically re-asserts that critical foreground services are running.
 */
class ServiceWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ServiceWatchdogWorker"
        private const val UNIQUE_WORK_NAME = "tpe_core_service_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            Log.i(TAG, "Scheduled periodic core-service watchdog")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            CoreServiceKeeper.ensureCoreServicesRunning(applicationContext, "watchdog")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Watchdog failed", e)
            Result.retry()
        }
    }
}
