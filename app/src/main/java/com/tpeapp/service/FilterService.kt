package com.tpeapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.tpeapp.R
import com.tpeapp.censor.CensorshipEngine
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
 * FilterService â€” a long-lived, headless bound service that:
 *
 *  â€¢ Shows a **persistent foreground notification** (transparency/consent requirement).
 *  â€¢ Initialises [NudeNetClassifier] once on a background thread.
 *  â€¢ Exposes an [IFilterService] AIDL interface so any bound client (including
 *    the LSPosed module running inside target apps) can submit images for
 *    asynchronous scanning.
 *
 * Clients bind to this service via:
 *   `Intent("com.hound.controller.BIND_FILTER_SERVICE").setPackage("com.hound.controller")`
 */
class FilterService : Service() {

    // ------------------------------------------------------------------
    //  Constants
    // ------------------------------------------------------------------

    companion object {
        private const val TAG                  = "FilterService"
        const val CORE_CHANNEL_ID              = "tpe_core_active"
        const val CORE_NOTIFICATION_ID         = 1001
        const val ACTION_REFRESH_CORE_NOTIFICATION = "com.hound.controller.action.REFRESH_CORE_NOTIFICATION"
        private const val DEFAULT_THRESHOLD    = 0.55f   // tune to balance FP/FN
        private const val PREF_SUB_STATUS      = "sub_status"
        const val PREF_MQTT_TRANSPORT_STATUS   = "mqtt_transport_status"

        /** SharedPreferences key for the webhook endpoint URL. */
        const val PREF_WEBHOOK_URL             = "webhook_url"
        /** SharedPreferences key for the webhook Bearer token. */
        const val PREF_WEBHOOK_BEARER_TOKEN    = "webhook_bearer_token"

        // ------------------------------------------------------------------
        //  Filter configuration keys â€” written by PartnerMqttService via MQTT
        // ------------------------------------------------------------------

        /** SharedPreferences key for the partner-configured confidence threshold (Float). */
        const val PREF_THRESHOLD               = "filter_confidence_threshold"
        /** SharedPreferences key for filter strict mode (Boolean). */
        const val PREF_STRICT_MODE             = "filter_strict_mode"
        /** SharedPreferences key for the JSON-encoded list of blocked NudeNet class labels. */
        const val PREF_BLOCKED_CLASSES         = "filter_blocked_classes"
        /**
         * SharedPreferences key (String) for the text-replacement dictionary JSON.
         * Maps Regex pattern strings â†’ replacement templates (e.g. {"(?i)\\bfoo\\b": "bar"}).
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
        /** speed|strict â€” speed is non-blocking fail-open, strict is fail-closed in selected lanes. */
        const val PREF_MEDIA_FILTER_MODE        = "media_filter_mode"
        /** pixelate|heavy_blur|blackout â€” style consumed by Xposed media hooks. */
        const val PREF_MEDIA_CENSOR_STYLE       = "media_censor_style"
        /** JSON array of package names that should run strict even when global mode is speed. */
        const val PREF_MEDIA_STRICT_PACKAGES    = "media_filter_strict_packages"
        /** Soft upper bound for concurrent in-flight scans in Xposed lanes. */
        const val PREF_MEDIA_MAX_IN_FLIGHT      = "media_filter_max_in_flight"
        /** JSON array of class IDs the ONNX censor should treat as forbidden. */
        const val PREF_MEDIA_FORBIDDEN_CLASS_IDS = "media_forbidden_class_ids"
        /** Persistent counter of media items detected as forbidden. */
        const val PREF_MEDIA_IMAGES_CAUGHT_COUNT = "media_images_caught_count"
        /** Fail-closed toggle for ImageView interception path. */
        const val PREF_MEDIA_FAIL_CLOSED        = "media_filter_fail_closed"
        /** Fade-in duration for approved image reveals (ms). */
        const val PREF_MEDIA_REVEAL_DURATION_MS = "media_reveal_duration_ms"
        /** Whether handler explicitly permits nudity (bypass media scan reveal gates). */
        const val PREF_NUDITY_PERMITTED_BY_HANDLER = "nudity_permitted_by_handler"
        /** Placeholder text shown while intercepted images are awaiting scan result. */
        const val PREF_MEDIA_PLACEHOLDER_TEXT = "media_placeholder_text"

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
    * Updated live via MQTT command payloads â†’ SharedPreferences listener.
     */
    @Volatile private var nudeNetEnabled: Boolean = false
    @Volatile private var mediaFilterMode: String = "speed"
    @Volatile private var mediaCensorStyle: String = "random"
    @Volatile private var mediaStrictPackagesJson: String = "[]"
    @Volatile private var mediaForbiddenClassIdsJson: String = "[0,1,2,3,4,5]"
    @Volatile private var mediaMaxInFlight: Int = 4
    @Volatile private var mediaImagesCaughtCount: Int = 0
    @Volatile private var mediaFailClosed: Boolean = true
    @Volatile private var mediaRevealDurationMs: Int = 300
    @Volatile private var nudityPermittedByHandler: Boolean = false
    @Volatile private var mediaPlaceholderText: String = "Loading..."
    @Volatile private var censorshipEngine: CensorshipEngine? = null

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
                    Log.i(TAG, "Threshold updated via MQTT â†’ $threshold")
                }
                PREF_STRICT_MODE -> {
                    strictModeEnabled = prefs.getBoolean(key, false)
                    threshold = effectiveThreshold(prefs.getFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD))
                    Log.i(TAG, "Strict mode updated via MQTT â†’ $strictModeEnabled (threshold=$threshold)")
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
                    val requested = false
                    val wasEnabled = nudeNetEnabled
                    nudeNetEnabled = requested
                    if (!requested && wasEnabled) {
                        Log.i(TAG, "NudeNet disabled via settings update")
                        // Null the field before closing so that any in-flight scan that
                        // already holds a local reference completes safely.
                        val old = classifier
                        classifier = null
                        old?.close()
                    }
                    if (prefs.getBoolean(key, false)) {
                        prefs.edit().putBoolean(PREF_NUDENET_ENABLED, false).apply()
                        Log.i(TAG, "Ignoring NudeNet enable request; feature is forced off")
                    }
                }
                PREF_MEDIA_FILTER_MODE -> {
                    mediaFilterMode = normalizeMode(prefs.getString(key, "speed"))
                    Log.i(TAG, "Media filter mode updated via settings -> $mediaFilterMode")
                }
                PREF_MEDIA_CENSOR_STYLE -> {
                    mediaCensorStyle = normalizeCensorStyle(prefs.getString(key, "random"))
                    Log.i(TAG, "Media censor style updated via settings -> $mediaCensorStyle")
                }
                PREF_MEDIA_STRICT_PACKAGES -> {
                    mediaStrictPackagesJson = normalizeStrictPackagesJson(prefs.getString(key, "[]"))
                    Log.i(TAG, "Media strict package list updated")
                }
                PREF_MEDIA_FORBIDDEN_CLASS_IDS -> {
                    mediaForbiddenClassIdsJson = normalizeForbiddenClassIdsJson(
                        prefs.getString(key, "[0,1,2,3,4,5]")
                    )
                    Log.i(TAG, "Media forbidden class IDs updated -> $mediaForbiddenClassIdsJson")
                }
                PREF_MEDIA_MAX_IN_FLIGHT -> {
                    mediaMaxInFlight = prefs.getInt(key, 4).coerceIn(1, 12)
                    Log.i(TAG, "Media in-flight budget updated -> $mediaMaxInFlight")
                }
                PREF_MEDIA_IMAGES_CAUGHT_COUNT -> {
                    mediaImagesCaughtCount = prefs.getInt(key, 0).coerceIn(0, Int.MAX_VALUE)
                }
                PREF_MEDIA_FAIL_CLOSED -> {
                    mediaFailClosed = prefs.getBoolean(key, true)
                    Log.i(TAG, "Media fail-closed updated via settings -> $mediaFailClosed")
                }
                PREF_MEDIA_REVEAL_DURATION_MS -> {
                    mediaRevealDurationMs = normalizeRevealDurationMs(prefs.getInt(key, 300))
                    Log.i(TAG, "Media reveal duration updated via settings -> ${mediaRevealDurationMs}ms")
                }
                PREF_NUDITY_PERMITTED_BY_HANDLER -> {
                    nudityPermittedByHandler = prefs.getBoolean(key, false)
                    Log.i(TAG, "Nudity permission bypass updated via settings -> $nudityPermittedByHandler")
                }
                PREF_MEDIA_PLACEHOLDER_TEXT -> {
                    mediaPlaceholderText = normalizePlaceholderText(prefs.getString(key, "Loading..."))
                    Log.i(TAG, "Media placeholder text updated")
                }
                com.tpeapp.mindful.ToneEnforcementService.PREF_RESTRICTED_VOCABULARY -> {
                    restrictedVocabularyJson = prefs.getString(key, "") ?: ""
                    Log.i(TAG, "Restricted vocabulary updated via MQTT")
                }
                com.tpeapp.mindful.ComplianceManager.PREF_STRICT_TONE_MODE -> {
                    strictToneModeEnabled = prefs.getBoolean(key, false)
                    Log.i(TAG, "Tone strict mode updated via MQTT â†’ $strictToneModeEnabled")
                }
            }
        }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    CORE_NOTIFICATION_ID,
                    buildForegroundNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(CORE_NOTIFICATION_ID, buildForegroundNotification())
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed, running as background service: ${e.message}")
        }
        LovenseManager.init(applicationContext)
        PavlokManager.init(applicationContext)
        loadPersistedSettings()
        initClassifierAsync()
        AppInventoryManager.syncFullInventory(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_CORE_NOTIFICATION) {
            refreshForegroundNotification()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatching {
            startService(Intent(applicationContext, FilterService::class.java))
        }.onFailure { err ->
            Log.w(TAG, "Failed to restart FilterService after task removal", err)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
        classifier?.close()
        classifier = null
        censorshipEngine?.close()
        censorshipEngine = null
        LovenseManager.close()
        PavlokManager.close()
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(applicationContext)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ------------------------------------------------------------------
    //  Settings â€” load persisted values and register live listener
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
        nudeNetEnabled = false
        if (persistedNudeNetEnabled) {
            prefs.edit().putBoolean(PREF_NUDENET_ENABLED, false).apply()
            Log.i(TAG, "Cleared persisted NudeNet enable flag; feature is forced off")
        }
        mediaFilterMode = normalizeMode(prefs.getString(PREF_MEDIA_FILTER_MODE, "speed"))
        mediaCensorStyle = normalizeCensorStyle(prefs.getString(PREF_MEDIA_CENSOR_STYLE, "random"))
        mediaStrictPackagesJson = normalizeStrictPackagesJson(
            prefs.getString(PREF_MEDIA_STRICT_PACKAGES, "[]")
        )
        mediaForbiddenClassIdsJson = normalizeForbiddenClassIdsJson(
            prefs.getString(PREF_MEDIA_FORBIDDEN_CLASS_IDS, "[0,1,2,3,4,5]")
        )
        mediaMaxInFlight = prefs.getInt(PREF_MEDIA_MAX_IN_FLIGHT, 4).coerceIn(1, 12)
        mediaImagesCaughtCount = prefs.getInt(PREF_MEDIA_IMAGES_CAUGHT_COUNT, 0).coerceIn(0, Int.MAX_VALUE)
        mediaFailClosed = prefs.getBoolean(PREF_MEDIA_FAIL_CLOSED, true)
        mediaRevealDurationMs = normalizeRevealDurationMs(
            prefs.getInt(PREF_MEDIA_REVEAL_DURATION_MS, 300)
        )
        nudityPermittedByHandler = prefs.getBoolean(PREF_NUDITY_PERMITTED_BY_HANDLER, false)
        mediaPlaceholderText = normalizePlaceholderText(
            prefs.getString(PREF_MEDIA_PLACEHOLDER_TEXT, "Loading...")
        )
        textReplacementDictJson = prefs.getString(PREF_TEXT_REPLACEMENT_DICT, "") ?: ""
        textReplacementPolicyJson = prefs.getString(PREF_TEXT_REPLACEMENT_POLICY, "") ?: ""
        restrictedVocabularyJson = prefs.getString(
            com.tpeapp.mindful.ToneEnforcementService.PREF_RESTRICTED_VOCABULARY, "") ?: ""
        strictToneModeEnabled = prefs.getBoolean(
            com.tpeapp.mindful.ComplianceManager.PREF_STRICT_TONE_MODE, false)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Log.i(TAG, "Filter settings loaded â€” threshold=$threshold strictMode=$strictModeEnabled nudeNetEnabled=$nudeNetEnabled strictToneMode=$strictToneModeEnabled")
    }

    // ------------------------------------------------------------------
    //  Model initialisation
    // ------------------------------------------------------------------

    private fun initClassifierAsync() {
        if (!nudeNetEnabled) {
            Log.i(TAG, "NudeNet feature flag is disabled â€” skipping classifier init")
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
                        incrementImagesCaughtCounter()
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
                            incrementImagesCaughtCounter()
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
            Log.i(TAG, "Confidence threshold updated â†’ $threshold")
        }

        override fun setTextReplacementDict(json: String?) {
            val normalized = json?.takeIf { it.isNotBlank() } ?: ""
            textReplacementDictJson = normalized
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .edit()
                .putString(PREF_TEXT_REPLACEMENT_DICT, normalized)
                .apply()
            Log.i(TAG, "Text-replacement dictionary updated via binder (${normalized.length} chars)")
        }

        override fun setTextReplacementPolicy(json: String?) {
            val normalized = json?.takeIf { it.isNotBlank() } ?: ""
            textReplacementPolicyJson = normalized
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .edit()
                .putString(PREF_TEXT_REPLACEMENT_POLICY, normalized)
                .apply()
            Log.i(TAG, "Text-replacement policy updated via binder (${normalized.length} chars)")
        }

        override fun getTextReplacementDict(): String = textReplacementDictJson

        override fun getTextReplacementPolicy(): String = textReplacementPolicyJson

        override fun getRestrictedVocabulary(): String = restrictedVocabularyJson

        override fun getToneMode(): String = if (strictToneModeEnabled) "Strict" else "Soft"

        override fun getMediaFilterConfig(): String = JSONObject().apply {
            put("mode", mediaFilterMode)
            put("censor_style", mediaCensorStyle)
            put("strict_packages", JSONArray(mediaStrictPackagesJson))
            put("forbidden_class_ids", JSONArray(mediaForbiddenClassIdsJson))
            put("max_in_flight", mediaMaxInFlight)
            put("images_caught_count", mediaImagesCaughtCount)
            put("fail_closed", mediaFailClosed)
            put("reveal_duration_ms", mediaRevealDurationMs)
            put("nudity_permitted_by_handler", nudityPermittedByHandler)
            put("placeholder_text", mediaPlaceholderText)
        }.toString()

        override fun processImageBytesForDisplay(imageData: ByteArray?): ByteArray? {
            if (imageData == null || imageData.isEmpty()) return null
            val src = decodeBitmap(imageData) ?: return null
            return try {
                val forbiddenClassIds = parseForbiddenClassIdSet(mediaForbiddenClassIdsJson)
                val result = censorEngine().censorBitmap(src, mediaCensorStyle, forbiddenClassIds)
                if (result.hasForbidden) {
                    incrementImagesCaughtCounter()
                }
                try {
                    censorEngine().encodePng(result.outputBitmap)
                } finally {
                    if (!result.outputBitmap.isRecycled) result.outputBitmap.recycle()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "processImageBytesForDisplay failed", t)
                null
            } finally {
                if (!src.isRecycled) src.recycle()
            }
        }

        override fun hasForbiddenContent(imageData: ByteArray?): Boolean {
            if (imageData == null || imageData.isEmpty()) return true
            val src = decodeBitmap(imageData) ?: return true
            return try {
                val forbiddenClassIds = parseForbiddenClassIdSet(mediaForbiddenClassIdsJson)
                censorEngine().hasForbiddenContent(src, forbiddenClassIds)
            } catch (t: Throwable) {
                Log.e(TAG, "hasForbiddenContent failed", t)
                true
            } finally {
                if (!src.isRecycled) src.recycle()
            }
        }
    }

    @Synchronized
    private fun censorEngine(): CensorshipEngine {
        val existing = censorshipEngine
        if (existing != null) return existing
        val created = CensorshipEngine(applicationContext)
        censorshipEngine = created
        return created
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
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
        "blackout" -> "blackout"
        "heavy_blur", "heavyblur", "blur" -> "heavy_blur"
        "random" -> "random"
        else -> "pixelate"
    }

    private fun normalizeRevealDurationMs(raw: Int): Int = raw.coerceIn(0, 3_000)

    private fun normalizeForbiddenClassIdsJson(raw: String?): String {
        if (raw.isNullOrBlank()) return "[0,1,2,3,4,5]"
        return runCatching {
            val inArr = JSONArray(raw)
            val outArr = JSONArray()
            val dedupe = LinkedHashSet<Int>()
            for (i in 0 until inArr.length()) {
                val value = when (val any = inArr.opt(i)) {
                    is Number -> any.toInt()
                    is String -> any.trim().toIntOrNull()
                    else -> null
                }
                if (value != null && value in 0..1000) dedupe.add(value)
            }
            if (dedupe.isEmpty()) {
                outArr.put(0)
                outArr.put(1)
                outArr.put(2)
                outArr.put(3)
                outArr.put(4)
                outArr.put(5)
            } else {
                dedupe.forEach { outArr.put(it) }
            }
            outArr.toString()
        }.getOrDefault("[0,1,2,3,4,5]")
    }

    private fun parseForbiddenClassIdSet(rawJson: String): Set<Int> {
        return runCatching {
            val arr = JSONArray(rawJson)
            val out = LinkedHashSet<Int>()
            for (i in 0 until arr.length()) {
                val value = when (val any = arr.opt(i)) {
                    is Number -> any.toInt()
                    is String -> any.trim().toIntOrNull()
                    else -> null
                }
                if (value != null && value >= 0) out.add(value)
            }
            if (out.isEmpty()) setOf(0, 1, 2, 3, 4, 5) else out
        }.getOrDefault(setOf(0, 1, 2, 3, 4, 5))
    }

    private fun incrementImagesCaughtCounter() {
        val next = (mediaImagesCaughtCount + 1).coerceAtMost(Int.MAX_VALUE)
        mediaImagesCaughtCount = next
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .edit()
            .putInt(PREF_MEDIA_IMAGES_CAUGHT_COUNT, next)
            .apply()
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

    private fun normalizePlaceholderText(raw: String?): String {
        val normalized = raw?.trim()?.take(64).orEmpty()
        return if (normalized.isBlank()) "Loading..." else normalized
    }

    /**
     * Waits (with yields) for the classifier to be initialised and returns a
     * stable local reference.  Also monitors [nudeNetEnabled] â€” if the flag is
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
        if (nm.getNotificationChannel(CORE_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CORE_CHANNEL_ID,
            "Accountability Core Active",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while accountability core services are active"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun refreshForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(CORE_NOTIFICATION_ID, buildForegroundNotification())
    }

    private fun currentStatusLabel(): String {
        val status = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getString(PREF_SUB_STATUS, "free_time")
            ?.trim()
            ?.lowercase()
            ?: "free_time"
        return when (status) {
            "task_active" -> "Task Active"
            "restricted" -> "Restricted"
            "punished" -> "Punished"
            else -> "Free Time"
        }
    }

    private fun currentTransportLabel(): String {
        val transport = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getString(PREF_MQTT_TRANSPORT_STATUS, "unknown")
            ?.trim()
            ?.lowercase()
            ?: "unknown"
        return when (transport) {
            "online" -> "Online"
            "reconnecting" -> "Reconnecting"
            "offline" -> "Offline"
            else -> "Unknown"
        }
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, CORE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Accountability core is active")
            .setContentText("Status: ${currentStatusLabel()} • MQTT: ${currentTransportLabel()}")
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

