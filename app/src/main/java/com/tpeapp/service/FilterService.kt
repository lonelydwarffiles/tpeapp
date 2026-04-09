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
import androidx.preference.PreferenceManager
import com.tpeapp.R
import com.tpeapp.filter.IFilterCallback
import com.tpeapp.filter.IFilterService
import com.tpeapp.ml.NudeNetClassifier
import com.tpeapp.ui.MainActivity
import com.tpeapp.ble.LovenseManager
import com.tpeapp.ble.PavlokManager
import com.tpeapp.consequence.ConsequenceDispatcher
import com.tpeapp.apps.AppInventoryManager
import com.tpeapp.webhook.WebhookManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
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

        /** SharedPreferences key for the webhook endpoint URL. */
        const val PREF_WEBHOOK_URL             = "webhook_url"
        /** SharedPreferences key for the webhook Bearer token. */
        const val PREF_WEBHOOK_BEARER_TOKEN    = "webhook_bearer_token"

        // ------------------------------------------------------------------
        //  Filter configuration keys — written by PartnerFcmService via FCM
        // ------------------------------------------------------------------

        /** SharedPreferences key for the partner-configured confidence threshold (Float). */
        const val PREF_THRESHOLD               = "filter_confidence_threshold"
        /** SharedPreferences key for filter strict mode (Boolean). */
        const val PREF_STRICT_MODE             = "filter_strict_mode"
        /** SharedPreferences key for the JSON-encoded list of blocked NudeNet class labels. */
        const val PREF_BLOCKED_CLASSES         = "filter_blocked_classes"

        /** Threshold used when strict mode is active and no explicit threshold is set. */
        private const val STRICT_THRESHOLD     = 0.30f
    }

    // ------------------------------------------------------------------
    //  State
    // ------------------------------------------------------------------

    private val serviceJob   = SupervisorJob()
    private val ioScope      = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile private var classifier: NudeNetClassifier? = null
    @Volatile private var threshold: Float = DEFAULT_THRESHOLD

    /** True when the partner has enabled strict content-filter mode. */
    @Volatile private var strictModeEnabled: Boolean = false

    /** Minimum gap between consecutive clean-scan reward triggers (30 minutes). */
    private val CLEAN_SCAN_REWARD_INTERVAL_MS = 30 * 60 * 1_000L
    @Volatile private var lastCleanScanRewardAt: Long = 0L

    /**
     * Listens for live FCM-driven preference changes so the threshold and strict
     * mode take effect without restarting the service.
     */
    private val prefsListener = android.content.SharedPreferences
        .OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                PREF_THRESHOLD -> {
                    threshold = effectiveThreshold(prefs.getFloat(key, DEFAULT_THRESHOLD))
                    Log.i(TAG, "Threshold updated via FCM → $threshold")
                }
                PREF_STRICT_MODE -> {
                    strictModeEnabled = prefs.getBoolean(key, false)
                    threshold = effectiveThreshold(prefs.getFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD))
                    Log.i(TAG, "Strict mode updated via FCM → $strictModeEnabled (threshold=$threshold)")
                }
                PREF_BLOCKED_CLASSES ->
                    Log.i(TAG, "Blocked classes updated via FCM (requires multi-class model to take effect)")
            }
        }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        LovenseManager.init(applicationContext)
        PavlokManager.init(applicationContext)
        loadPersistedSettings()
        initClassifierAsync()
        AppInventoryManager.syncFullInventory(applicationContext)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
        classifier?.close()
        classifier = null
        LovenseManager.close()
        PavlokManager.close()
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(applicationContext)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ------------------------------------------------------------------
    //  Settings — load persisted values and register live listener
    // ------------------------------------------------------------------

    /**
     * Reads the partner-configured threshold and strict-mode flag from
     * SharedPreferences (written by [com.tpeapp.fcm.PartnerFcmService] when an
     * FCM UPDATE_SETTINGS payload arrives).
     *
     * Also registers [prefsListener] so live FCM changes apply without
     * restarting the service.
     */
    private fun loadPersistedSettings() {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(applicationContext)
        strictModeEnabled = prefs.getBoolean(PREF_STRICT_MODE, false)
        threshold = effectiveThreshold(prefs.getFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD))
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Log.i(TAG, "Filter settings loaded — threshold=$threshold strictMode=$strictModeEnabled")
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
                    val clf     = awaitClassifier()
                    val score   = clf.classifyBytes(imageData)
                    val blocked = score >= threshold
                    callback.onScanResult(requestId, blocked, score)
                    if (blocked) {
                        dispatchAppBlockedEvent(requestId, score)
                        triggerToyEscalation()
                    } else {
                        maybeRewardCleanScan()
                    }
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
                        val bytes   = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                        val clf     = awaitClassifier()
                        val score   = clf.classifyBytes(bytes)
                        val blocked = score >= threshold
                        callback.onScanResult(requestId, blocked, score)
                        if (blocked) {
                            dispatchAppBlockedEvent(requestId, score)
                            triggerToyEscalation()
                        } else {
                            maybeRewardCleanScan()
                        }
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
    //  Webhook dispatch
    // ------------------------------------------------------------------

    /**
     * Reads the configurable webhook URL and Bearer token from
     * [SharedPreferences] and forwards an "App Blocked" event to
     * [WebhookManager].  If no URL has been configured the call is a no-op.
     */
    private fun dispatchAppBlockedEvent(requestId: Long, score: Float) {
        val prefs       = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val webhookUrl  = prefs.getString(PREF_WEBHOOK_URL, null)
            ?.takeIf { it.isNotBlank() } ?: return
        val bearerToken = prefs.getString(PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }

        val payload = JSONObject().apply {
            put("event",      "app_blocked")
            put("request_id", requestId)
            put("score",      score.toDouble())
            put("timestamp",  System.currentTimeMillis())
        }

        WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Delegates to [ConsequenceDispatcher] to trigger a punishment stimulus
     * (Lovense max vibration + Pavlok zap + webhook) when a content violation
     * is detected.
     */
    private fun triggerToyEscalation() {
        ConsequenceDispatcher.punish(applicationContext, "content_violation")
    }

    /**
     * Fires a reward stimulus at most once every [cleanScanRewardIntervalMs]
     * to acknowledge sustained compliant browsing without spamming the devices.
     */
    private fun maybeRewardCleanScan() {
        val now = System.currentTimeMillis()
        if (now - lastCleanScanRewardAt >= CLEAN_SCAN_REWARD_INTERVAL_MS) {
            lastCleanScanRewardAt = now
            ConsequenceDispatcher.reward(applicationContext, "clean_content_scan")
        }
    }

    /**
     * Waits (with yields) for the classifier to be initialised and returns a
     * stable local reference.  The local val is used for all subsequent calls
     * so a concurrent null assignment (during service destruction) cannot cause
     * a NullPointerException after the null check.
     */
    /**
     * Returns the effective threshold value for the given raw [base] threshold,
     * clamping to [STRICT_THRESHOLD] when [strictModeEnabled] is active.
     */
    private fun effectiveThreshold(base: Float): Float =
        if (strictModeEnabled) minOf(base, STRICT_THRESHOLD) else base

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
