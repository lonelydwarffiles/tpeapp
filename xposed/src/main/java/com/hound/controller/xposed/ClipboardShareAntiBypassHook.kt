package com.hound.controller.xposed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Anti-bypass hook for clipboard + share-sheet paths.
 *
 * Fail-closed behavior:
 * - If outbound media cannot be re-processed in time, block/neutralize export.
 */
object ClipboardShareAntiBypassHook {

    private const val TAG = "TPE_ClipboardShare"
    private const val IPC_TIMEOUT_MS = 200L

    fun install(loader: ClassLoader) {
        installClipboardHook(loader)
        installStartActivityHook(loader)
    }

    private fun installClipboardHook(loader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.content.ClipboardManager",
                loader,
                "setPrimaryClip",
                ClipData::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val manager = param.thisObject as? ClipboardManager ?: return
                        val clip = param.args[0] as? ClipData ?: return
                        val item = clip.getItemAt(0)
                        val text = item.coerceToText(manager.primaryClipDescription?.let { null } ?: return).toString()
                        val looksImageUrl = text.contains(".jpg", true) ||
                            text.contains(".jpeg", true) ||
                            text.contains(".png", true) ||
                            text.contains(".webp", true)
                        if (!looksImageUrl) return

                        // Links cannot be safely sanitized offline without a fetch pipeline.
                        param.args[0] = ClipData.newPlainText("locked", "LOCKED / UNVERIFIED")
                        Log.i(TAG, "Clipboard image-link export blocked and neutralized")
                    }
                }
            )
        }.onFailure { Log.w(TAG, "Failed clipboard hook", it) }
    }

    private fun installStartActivityHook(loader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.content.ContextWrapper",
                loader,
                "startActivity",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context ?: return
                        val intent = param.args[0] as? Intent ?: return
                        if (!isShareIntent(intent)) return

                        val sanitized = sanitizeShareIntent(context, intent)
                        if (!sanitized) {
                            // Fail closed when share sanitization fails.
                            throw SecurityException("tpeapp blocked unverified media export")
                        }
                    }
                }
            )
        }.onFailure { Log.w(TAG, "Failed startActivity share hook", it) }
    }

    private fun isShareIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
    }

    private fun sanitizeShareIntent(context: Context, intent: Intent): Boolean {
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return true
        val bytes = runCatching {
            context.contentResolver.openInputStream(stream)?.use { input ->
                ByteArrayOutputStream().use { os ->
                    input.copyTo(os)
                    os.toByteArray()
                }
            }
        }.getOrNull() ?: return false

        val processed = runBlocking {
            withTimeoutOrNull(IPC_TIMEOUT_MS) {
                MainHook.getContext()?.let { MainHook.ensureServiceBound(it) }
                val service = MainHook.filterService ?: return@withTimeoutOrNull null
                service.processImageBytesForDisplay(bytes)
            }
        } ?: return false

        val outFile = File(context.cacheDir, "tpe_censored_share_${System.nanoTime()}.png")
        runCatching { outFile.writeBytes(processed) }.onFailure { return false }

        // Note: file:// export may be rejected by some apps; fail-closed fallback above
        // blocks shares where replacement cannot be guaranteed.
        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(outFile))
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return true
    }
}
