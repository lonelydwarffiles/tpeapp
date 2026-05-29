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
import org.json.JSONArray
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
        //  Filter configuration keys — written by PartnerMqttService via MQTT
        // ------------------------------------------------------------------

        /** SharedPreferences key for the partner-configured confidence threshold (Float). */
        const val PREF_THRESHOLD               = "filter_confidence_threshold"
        /** SharedPreferences key for filter strict mode (Boolean). */
        const val PREF_STRICT_MODE             = "filter_strict_mode"
        /** SharedPreferences key for the JSON-encoded list of blocked NudeNet class labels. */
        const val PREF_BLOCKED_CLASSES         = "filter_blocked_classes"
        /**
         * SharedPreferences key (String) for the text-replacement dictionary JSON.
         * Maps Regex pattern strings → replacement templates (e.g. {"(?i)\\bfoo\\b": "bar"}).
         */
        const val PREF_TEXT_REPLACEMENT_DICT   = "text_replacement_dict"
        /**
         * SharedPreferences key (String) for text-replacement policy overrides.
         * JSON schema: { default_mode, packages, package_prefixes }.
         */
        const val PREF_TEXT_REPLACEMENT_POLICY = "text_replacement_policy"
        /**
         * SharedPreferences key (Boolean) for the NudeNet TFLite feature flag.
         * When `false` the classifier is not initialised and all scan requests
         * return a safe (not-blocked) result, saving memory and CPU on low-end
         * or non-rooted devices.  Defaults to `false` (disabled).
         */
        const val PREF_NUDENET_ENABLED         = "nudenet_enabled"
        /** speed|strict — speed is non-blocking fail-open, strict is fail-closed in selected lanes. */
        const val PREF_MEDIA_FILTER_MODE        = "media_filter_mode"
        /** pixelate|blur — blur is heavier and only available in bitmap lanes. */
        const val PREF_MEDIA_CENSOR_STYLE       = "media_censor_style"
        /** JSON array of package names that should run strict even when global mode is speed. */
        const val PREF_MEDIA_STRICT_PACKAGES    = "media_filter_strict_packages"
        /** Soft upper bound for concurrent in-flight scans in Xposed lanes. */
        const val PREF_MEDIA_MAX_IN_FLIGHT      = "media_filter_max_in_flight"

        /** Threshold used when strict mode is active and no explicit threshold is set. */
        private const val STRICT_THRESHOLD     = 0.30f
        /** Max time a scan request waits for model readiness before failing open. */
        private const val CLASSIFIER_WAIT_TIMEOUT_MS = 1_200L
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

    /** Cached text-replacement dictionary JSON (empty string = no replacements). */
    @Volatile private var textReplacementDictJson: String = ""
    /** Cached text-replacement policy JSON (empty string = use built-in auto policy). */
    @Volatile private var textReplacementPolicyJson: String = ""

    /**
    * Cached restricted-vocabulary JSON array (empty string = no vocabulary).
    * Written by PartnerMqttService; read by the Xposed tone-enforcement hook
     * via [IFilterService.getRestrictedVocabulary].
     */
    @Volatile private var restrictedVocabularyJson: String = ""

    /**
     * Whether the partner has enabled strict tone-enforcement mode.
     * Mirrors [com.tpeapp.mindful.ComplianceManager.PREF_STRICT_TONE_MODE]
     * and is re-cached whenever the SharedPreferences value changes.
     */
    @Volatile private var strictToneModeEnabled: Boolean = false

    /**
    * Feature flag for the NudeNet TFLite classifier.  When `false` the
     * classifier is not initialised and all scan requests immediately return
     * a safe (not-blocked) result, saving performance on low-end devices.
    * Updated live via MQTT command payloads → SharedPreferences listener.
     */
    @Volatile private var nudeNetEnabled: Boolean = false
    @Volatile private var mediaFilterMode: String = "speed"
    @Volatile private var mediaCensorStyle: String = "pixelate"
    @Volatile private var mediaStrictPackagesJson: String = "[]"
    @Volatile private var mediaMaxInFlight: Int = 4

    /** Minimum gap between consecutive clean-scan reward triggers (30 minutes). */
    private val CLEAN_SCAN_REWARD_INTERVAL_MS = 30 * 60 * 1_000L
    @Volatile private var lastCleanScanRewardAt: Long = 0L

    /**
     * Listens for live MQTT-driven preference changes so the threshold and strict
     * mode take effect without restarting the service.
     */
    private val prefsListener = android.content.SharedPreferences
        .OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                PREF_THRESHOLD -> {
                    threshold = effectiveThreshold(prefs.getFloat(key, DEFAULT_THRESHOLD))
                    Log.i(TAG, "Threshold updated via MQTT → $threshold")
                }
                PREF_STRICT_MODE -> {
                    strictModeEnabled = prefs.getBoolean(key, false)
                    threshold = effectiveThreshold(prefs.getFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD))
                    Log.i(TAG, "Strict mode updated via MQTT → $strictModeEnabled (threshold=$threshold)")
                }
                PREF_BLOCKED_CLASSES ->
                    Log.i(TAG, "Blocked classes updated via MQTT (requires multi-class model to take effect)")
                PREF_TEXT_REPLACEMENT_DICT -> {
                    textReplacementDictJson = prefs.getString(key, "") ?: ""
                    Log.i(TAG, "Text-replacement dictionary updated")
                }
                PREF_TEXT_REPLACEMENT_POLICY -> {
                    textReplacementPolicyJson = prefs.getString(key, "") ?: ""
                    Log.i(TAG, "Text-replacement policy updated")
                }
                PREF_NUDENET_ENABLED -> {
                    val requested = prefs.getBoolean(key, false)
                    val wasEnabled = nudeNetEnabled
                    nudeNetEnabled = requested
                    if (requested && !wasEnabled) {
                        Log.i(TAG, "NudeNet enabled via settings update")
                        initClassifierAsync()
                    }
                    if (!requested && wasEnabled) {
                        Log.i(TAG, "NudeNet disabled via settings update")
                        // Null the field before closing so that any in-flight scan that
                        // already holds a local reference completes safely.
                        val old = classifier
                        classifier = null
                        old?.close()
                    }
                }
                PREF_MEDIA_FILTER_MODE -> {
                    mediaFilterMode = normalizeMode(prefs.getString(key, "speed"))
                    Log.i(TAG, "Media filter mode updated via settings -> $mediaFilterMode")
                }
                PREF_MEDIA_CENSOR_STYLE -> {
                    mediaCensorStyle = normalizeCensorStyle(prefs.getString(key, "pixelate"))
                    Log.i(TAG, "Media censor style updated via settings -> $mediaCensorStyle")
                }
                PREF_MEDIA_STRICT_PACKAGES -> {
                    mediaStrictPackagesJson = normalizeStrictPackagesJson(prefs.getString(key, "[]"))
                    Log.i(TAG, "Media strict package list updated")
                }
                PREF_MEDIA_MAX_IN_FLIGHT -> {
                    mediaMaxInFlight = prefs.getInt(key, 4).coerceIn(1, 12)
                    Log.i(TAG, "Media in-flight budget updated -> $mediaMaxInFlight")
                }
                com.tpeapp.mindful.ToneEnforcementService.PREF_RESTRICTED_VOCABULARY -> {
                    restrictedVocabularyJson = prefs.getString(key, "") ?: ""
                    Log.i(TAG, "Restricted vocabulary updated via MQTT")
                }
                com.tpeapp.mindful.ComplianceManager.PREF_STRICT_TONE_MODE -> {
                    strictToneModeEnabled = prefs.getBoolean(key, false)
                    Log.i(TAG, "Tone strict mode updated via MQTT → $strictToneModeEnabled")
                }
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
    * SharedPreferences (written by [com.tpeapp.mqtt.PartnerMqttService] when an
    * MQTT UPDATE_SETTINGS payload arrives).
     *
    * Also registers [prefsListener] so live MQTT changes apply without
     * restarting the service.
     */
    private fun loadPersistedSettings() {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(applicationContext)
        strictModeEnabled = prefs.getBoolean(PREF_STRICT_MODE, false)
        threshold = effectiveThreshold(prefs.getFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD))
        val persistedNudeNetEnabled = prefs.getBoolean(PREF_NUDENET_ENABLED, false)
        nudeNetEnabled = persistedNudeNetEnabled
        mediaFilterMode = normalizeMode(prefs.getString(PREF_MEDIA_FILTER_MODE, "speed"))
        mediaCensorStyle = normalizeCensorStyle(prefs.getString(PREF_MEDIA_CENSOR_STYLE, "pixelate"))
        mediaStrictPackagesJson = normalizeStrictPackagesJson(
            prefs.getString(PREF_MEDIA_STRICT_PACKAGES, "[]")
        )
        mediaMaxInFlight = prefs.getInt(PREF_MEDIA_MAX_IN_FLIGHT, 4).coerceIn(1, 12)
        textReplacementDictJson = prefs.getString(PREF_TEXT_REPLACEMENT_DICT, "") ?: ""
        textReplacementPolicyJson = prefs.getString(PREF_TEXT_REPLACEMENT_POLICY, "") ?: ""
        restrictedVocabularyJson = prefs.getString(
            com.tpeapp.mindful.ToneEnforcementService.PREF_RESTRICTED_VOCABULARY, "") ?: ""
        strictToneModeEnabled = prefs.getBoolean(
            com.tpeapp.mindful.ComplianceManager.PREF_STRICT_TONE_MODE, false)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Log.i(TAG, "Filter settings loaded — threshold=$threshold strictMode=$strictModeEnabled nudeNetEnabled=$nudeNetEnabled strictToneMode=$strictToneModeEnabled")
    }

    // ------------------------------------------------------------------
    //  Model initialisation
    // ------------------------------------------------------------------

    private fun initClassifierAsync() {
        if (!nudeNetEnabled) {
            Log.i(TAG, "NudeNet feature flag is disabled — skipping classifier init")
            return
        }
        ioScope.launch {
            try {
                classifier = NudeNetClassifier(applicationContext)
                Log.i(TAG, "NudeNetClassifier ready")
            } catch (e: Exception) {
                Log.e(TAG, "NudeNet classifier initialization failed", e)
                // Fail open: do not leave callers waiting for a classifier that never comes up.
                nudeNetEnabled = false
                classifier = null
                androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(applicationContext)
                    .edit()
                    .putBoolean(PREF_NUDENET_ENABLED, false)
                    .apply()
                Log.w(TAG, "NudeNet disabled after init failure to avoid scan stalls")
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
                if (!nudeNetEnabled) {
                    callback.onScanResult(requestId, false, 0f)
                    return@launch
                }
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
                    if (!nudeNetEnabled) {
                        callback.onScanResult(requestId, false, 0f)
                        return@use
                    }
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

        override fun isReady(): Boolean = nudeNetEnabled && classifier != null

        override fun setConfidenceThreshold(newThreshold: Float) {
            threshold = newThreshold.coerceIn(0f, 1f)
            Log.i(TAG, "Confidence threshold updated → $threshold")
        }

        override fun getTextReplacementDict(): String = textReplacementDictJson

        override fun getTextReplacementPolicy(): String = textReplacementPolicyJson

        override fun getRestrictedVocabulary(): String = restrictedVocabularyJson

        override fun getToneMode(): String = if (strictToneModeEnabled) "Strict" else "Soft"

        override fun getMediaFilterConfig(): String = JSONObject().apply {
            put("mode", mediaFilterMode)
            put("censor_style", mediaCensorStyle)
            put("strict_packages", JSONArray(mediaStrictPackagesJson))
            put("max_in_flight", mediaMaxInFlight)
        }.toString()
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

    private fun normalizeMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "strict" -> "strict"
        else -> "speed"
    }

    private fun normalizeCensorStyle(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "blur" -> "blur"
        else -> "pixelate"
    }

    private fun normalizeStrictPackagesJson(raw: String?): String {
        if (raw.isNullOrBlank()) return "[]"
        return runCatching {
            val inArr = JSONArray(raw)
            val outArr = JSONArray()
            for (i in 0 until inArr.length()) {
                val pkg = inArr.optString(i).trim()
                if (pkg.isNotEmpty()) outArr.put(pkg)
            }
            outArr.toString()
        }.getOrDefault("[]")
    }

    /**
     * Waits (with yields) for the classifier to be initialised and returns a
     * stable local reference.  Also monitors [nudeNetEnabled] — if the flag is
     * cleared while waiting, an [IllegalStateException] is thrown so that the
     * caller's `runCatching` block handles it cleanly and reports a safe result
     * rather than looping forever.
     *
     * The returned local val is used for all subsequent calls so a concurrent
     * null assignment (during service destruction or flag toggle) cannot cause a
     * NullPointerException after the null check.
     */
    private suspend fun awaitClassifier(): NudeNetClassifier {
        val startedAt = System.currentTimeMillis()
        var local: NudeNetClassifier?
        do {
            if (!nudeNetEnabled) throw IllegalStateException("NudeNet disabled during await")
            local = classifier
            if (local == null && (System.currentTimeMillis() - startedAt) >= CLASSIFIER_WAIT_TIMEOUT_MS) {
                throw IllegalStateException("Classifier unavailable after ${CLASSIFIER_WAIT_TIMEOUT_MS}ms")
            }
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
