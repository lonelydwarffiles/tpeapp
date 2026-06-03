package com.hound.controller.xposed

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.TextureView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap

/**
 * Periodic video tracking framework:
 * - Samples 1 thumbnail every 1500ms from TextureView
 * - Runs local forbidden-content detection through Binder
 * - Applies solid black mask while forbidden content is present
 */
object VideoTrackingHook {

    private const val TAG = "TPE_VideoTracking"
    private const val SAMPLE_MS = 1_500L
    private const val IPC_TIMEOUT_MS = 250L
    private const val SAMPLE_W = 160
    private const val SAMPLE_H = 90

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val jobs = java.util.Collections.synchronizedMap(WeakHashMap<TextureView, Job>())

    fun install(loader: ClassLoader) {
        hookTextureAttach(loader)
        hookTextureDetach(loader)
    }

    private fun hookTextureAttach(loader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.TextureView",
                loader,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? TextureView ?: return
                        startTracking(view)
                    }
                }
            )
        }.onFailure { Log.w(TAG, "Failed TextureView attach hook", it) }
    }

    private fun hookTextureDetach(loader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.TextureView",
                loader,
                "onDetachedFromWindow",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? TextureView ?: return
                        stopTracking(view)
                    }
                }
            )
        }.onFailure { Log.w(TAG, "Failed TextureView detach hook", it) }
    }

    private fun startTracking(view: TextureView) {
        stopTracking(view)
        jobs[view] = scope.launch {
            while (true) {
                delay(SAMPLE_MS)
                val sample = runCatching { view.getBitmap(SAMPLE_W, SAMPLE_H) }.getOrNull()
                if (sample == null) continue

                val bytes = encode(sample)
                if (!sample.isRecycled) sample.recycle()
                if (bytes == null) continue

                val forbidden = runBlocking {
                    withTimeoutOrNull(IPC_TIMEOUT_MS) {
                        MainHook.getContext()?.let { MainHook.ensureServiceBound(it) }
                        val service = MainHook.filterService ?: return@withTimeoutOrNull true
                        service.hasForbiddenContent(bytes)
                    }
                } ?: true

                if (forbidden) {
                    applyMask(view)
                } else {
                    clearMask(view)
                }
            }
        }
    }

    private fun stopTracking(view: TextureView) {
        jobs.remove(view)?.cancel()
        clearMask(view)
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        return runCatching {
            ByteArrayOutputStream().use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, os)
                os.toByteArray()
            }
        }.getOrNull()
    }

    private fun applyMask(view: TextureView) {
        view.post {
            if (view.foreground !is ColorDrawable) {
                view.foreground = ColorDrawable(Color.BLACK)
            }
        }
    }

    private fun clearMask(view: TextureView) {
        view.post {
            view.foreground = null
        }
    }
}
