package com.hound.controller.xposed

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.InputStream

object OkHttpHook {

    private const val TAG = "TPE_OkHttpHook"

    fun install(loader: ClassLoader) {
        val responseBodyClass = resolveResponseBodyClass(loader) ?: return
        installBytesHook(responseBodyClass)
        installByteStreamHook(responseBodyClass)
    }

    private fun resolveResponseBodyClass(loader: ClassLoader): Class<*>? {
        return runCatching {
            Class.forName("okhttp3.ResponseBody", false, loader)
        }.getOrElse {
            null
        }
    }

    private fun installBytesHook(responseBodyClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllMethods(
                responseBodyClass,
                "bytes",
                bytesHook,
            )
            Log.i(TAG, "Installed ResponseBody.bytes hook")
        }.onFailure {
            Log.w(TAG, "Failed to install ResponseBody.bytes hook", it)
        }
    }

    private fun installByteStreamHook(responseBodyClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllMethods(
                responseBodyClass,
                "byteStream",
                byteStreamHook,
            )
            Log.i(TAG, "Installed ResponseBody.byteStream hook")
        }.onFailure {
            Log.w(TAG, "Failed to install ResponseBody.byteStream hook", it)
        }
    }

    private val bytesHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val body = param.thisObject ?: return
            val payload = param.result as? ByteArray ?: return
            val contentType = contentTypeOf(body)
            if (!isImageContentType(contentType)) return

            val rewritten = NetworkStreamCensor.maybeCensorNetworkImage(contentType, payload)
            if (rewritten !== payload) {
                param.result = rewritten
            }
        }
    }

    private val byteStreamHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val body = param.thisObject ?: return
            val stream = param.result as? InputStream ?: return
            val contentType = contentTypeOf(body)
            if (!isImageContentType(contentType)) return

            val rewritten = NetworkStreamCensor.maybeCensorNetworkImage(contentType, stream)
            param.result = ByteArrayInputStream(rewritten)
        }
    }

    private fun contentTypeOf(responseBody: Any): String? {
        return runCatching {
            val method = responseBody.javaClass.getMethod("contentType")
            method.invoke(responseBody)?.toString()
        }.getOrNull()
    }

    private fun isImageContentType(contentType: String?): Boolean {
        return contentType?.trim()?.startsWith("image/", ignoreCase = true) == true
    }
}
