package com.hound.controller.xposed

import android.content.Intent
import android.util.Log

/**
 * Shared schema emitter for Xposed media-lane coverage telemetry.
 * Events are sent as explicit broadcasts to the main TPE app process.
 */
object CoverageTelemetry {

    private const val TAG = "TPE_CoverageTelemetry"

    private const val ACTION_XPOSED_COVERAGE_EVENT = "com.hound.controller.ACTION_XPOSED_COVERAGE_EVENT"

    const val MEDIA_IMAGE = "image"

    const val LANE_IMAGEVIEW = "imageview"
    const val LANE_GLIDE = "glide"
    const val LANE_COIL = "coil"
    const val LANE_OKHTTP = "okhttp"

    const val STAGE_SCAN_RESULT = "scan_result"
    const val STAGE_BYPASS_PERMITTED = "bypass_permitted"
    const val STAGE_SERVICE_UNAVAILABLE = "service_unavailable"
    const val STAGE_SCAN_TIMEOUT = "scan_timeout"
    const val STAGE_SCAN_ERROR = "scan_error"
    const val STAGE_ENCODE_FAILED = "encode_failed"

    fun report(
        lane: String,
        stage: String,
        mediaType: String = MEDIA_IMAGE,
        sensitive: Boolean = false,
        confidence: Float? = null,
        latencyMs: Long? = null,
        reason: String? = null,
    ) {
        val context = MainHook.getContext() ?: return

        val intent = Intent(ACTION_XPOSED_COVERAGE_EVENT).apply {
            setPackage("com.hound.controller")
            putExtra("lane", lane)
            putExtra("stage", stage)
            putExtra("media_type", mediaType)
            putExtra("source_package", context.packageName)
            putExtra("sensitive", sensitive)
            putExtra("timestamp", System.currentTimeMillis())
            confidence?.let { putExtra("confidence", it) }
            latencyMs?.let { putExtra("latency_ms", it) }
            reason?.takeIf { it.isNotBlank() }?.let { putExtra("reason", it) }
        }

        runCatching {
            context.sendBroadcast(intent)
        }.onFailure {
            Log.w(TAG, "Failed to send coverage event lane=$lane stage=$stage", it)
        }
    }
}

