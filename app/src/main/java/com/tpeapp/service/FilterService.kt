package com.tpeapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tpeapp.R
import com.tpeapp.filter.IFilterCallback
import com.tpeapp.filter.IFilterService
import com.tpeapp.ml.NudeNetClassifier
import com.tpeapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream

/**
 * FilterService — a long-lived, headless bound service that:
 *
 *  • Shows a **persistent foreground notification** (transparency/consent requirement).
 *  • Initialises [NudeNetClassifier] once on a background thread.
 *  • Exposes an [IFilterService] AIDL interface so any bound client (including
 *    the LSPosed module running inside target apps) can submit images for
 *    asynchronous scanning.
 *
 * Clients bind to this service via:
 *   `Intent("com.tpeapp.BIND_FILTER_SERVICE").setPackage("com.tpeapp")`
 */
class FilterService : Service() {

    // ------------------------------------------------------------------
    //  Constants
    // ------------------------------------------------------------------

    companion object {
        private const val TAG                  = "FilterService"
        private const val CHANNEL_ID           = "tpe_filter_active"
        private const val NOTIFICATION_ID      = 1001
        private const val DEFAULT_THRESHOLD    = 0.55f   // tune to balance FP/FN
    }

    // ------------------------------------------------------------------
    //  State
    // ------------------------------------------------------------------

    private val serviceJob   = SupervisorJob()
    private val ioScope      = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile private var classifier: NudeNetClassifier? = null
    @Volatile private var threshold: Float = DEFAULT_THRESHOLD

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        initClassifierAsync()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
        classifier?.close()
        classifier = null
    }

    // ------------------------------------------------------------------
    //  Model initialisation
    // ------------------------------------------------------------------

    private fun initClassifierAsync() {
        ioScope.launch {
            try {
                classifier = NudeNetClassifier(applicationContext)
                Log.i(TAG, "NudeNetClassifier ready")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load TFLite model", e)
            }
        }
    }

    // ------------------------------------------------------------------
    //  AIDL binder implementation
    // ------------------------------------------------------------------

    private val binder = object : IFilterService.Stub() {

        override fun scanImageBytes(
            requestId: Long,
            imageData: ByteArray,
            callback: IFilterCallback
        ) {
            ioScope.launch {
                runCatching {
                    val clf = awaitClassifier()
                    val score = clf.classifyBytes(imageData)
                    callback.onScanResult(requestId, score >= threshold, score)
                }.onFailure { e ->
                    Log.e(TAG, "scanImageBytes [$requestId] failed", e)
                    // Report as not-sensitive so the UI does not get stuck.
                    callback.onScanResult(requestId, false, 0f)
                }
            }
        }

        override fun scanImageFd(
            requestId: Long,
            fd: ParcelFileDescriptor,
            callback: IFilterCallback
        ) {
            ioScope.launch {
                fd.use { pfd ->
                    runCatching {
                        val bytes = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                        val clf   = awaitClassifier()
                        val score = clf.classifyBytes(bytes)
                        callback.onScanResult(requestId, score >= threshold, score)
                    }.onFailure { e ->
                        Log.e(TAG, "scanImageFd [$requestId] failed", e)
                        callback.onScanResult(requestId, false, 0f)
                    }
                }
            }
        }

        override fun isReady(): Boolean = classifier != null

        override fun setConfidenceThreshold(newThreshold: Float) {
            threshold = newThreshold.coerceIn(0f, 1f)
            Log.i(TAG, "Confidence threshold updated → $threshold")
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Waits (with yields) for the classifier to be initialised and returns a
     * stable local reference.  The local val is used for all subsequent calls
     * so a concurrent null assignment (during service destruction) cannot cause
     * a NullPointerException after the null check.
     */
    private suspend fun awaitClassifier(): NudeNetClassifier {
        var local: NudeNetClassifier?
        do {
            local = classifier
            if (local == null) kotlinx.coroutines.delay(50)
        } while (local == null)
        return local
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Accountability Filter Active",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while content filtering is running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Content filter is active")
            .setContentText("Tap to view accountability settings")
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
}
