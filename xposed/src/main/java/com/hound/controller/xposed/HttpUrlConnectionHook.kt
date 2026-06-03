package com.hound.controller.xposed

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Fallback network hook for apps that use java.net.HttpURLConnection directly.
 */
object HttpUrlConnectionHook {

    private const val TAG = "TPE_HttpUrlConnHook"

    fun install(loader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("java.net.HttpURLConnection", false, loader)
            XposedBridge.hookAllMethods(
                clazz,
                "getInputStream",
                getInputStreamHook,
            )
            Log.i(TAG, "HttpURLConnection hook installed")
        }.onFailure {
            Log.w(TAG, "HttpURLConnection hook install failed", it)
        }
    }

    private val getInputStreamHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val conn = param.thisObject as? HttpURLConnection ?: return
            val input = param.result as? InputStream ?: return

            val contentType = runCatching { conn.contentType }.getOrNull()
            if (contentType.isNullOrBlank() || !contentType.startsWith("image/", ignoreCase = true)) {
                return
            }

            val rewritten = NetworkStreamCensor.maybeCensorNetworkImage(contentType, input)
            param.result = ByteArrayInputStream(rewritten)

            CoverageTelemetry.report(
                lane = CoverageTelemetry.LANE_HTTP_URL_CONNECTION,
                stage = CoverageTelemetry.STAGE_SCAN_RESULT,
            )
        }
    }
}
