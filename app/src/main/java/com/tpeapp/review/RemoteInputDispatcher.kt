package com.tpeapp.review

import android.content.Context
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * RemoteInputDispatcher — receives normalized input events forwarded over the WebRTC DataChannel
 * from the accountability partner and injects them into the device using `su -c "input ..."`.
 *
 * Events are JSON objects with the following shape:
 * ```json
 * { "type": "tap",     "x": 0.5,  "y": 0.3 }
 * { "type": "swipe",   "x": 0.1,  "y": 0.9, "x2": 0.8, "y2": 0.2, "duration": 300 }
 * { "type": "text",    "text": "hello" }
 * { "type": "keyevent","keycode": 4 }
 * { "type": "scroll",  "dx": 0.0, "dy": -0.1 }
 * ```
 *
 * Coordinates `x`, `y`, `x2`, `y2` are normalized to [0.0, 1.0] and are scaled to physical
 * pixels before dispatch. `dx`/`dy` for scroll events are also normalized and scaled.
 *
 * Dispatch is only active when [remoteControlEnabled] is `true` (set by the user via the
 * consent toggle in [ReviewActivity]).
 */
object RemoteInputDispatcher {

    private const val TAG = "RemoteInputDispatcher"

    @Volatile var remoteControlEnabled: Boolean = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Dispatch a remote input event encoded as a JSON string.
     *
     * @param context   Application context (used to resolve screen dimensions).
     * @param eventJson Raw JSON string received from the DataChannel.
     */
    fun dispatch(context: Context, eventJson: String) {
        if (!remoteControlEnabled) return
        scope.launch {
            try {
                val json = JSONObject(eventJson)
                val type = json.getString("type")

                val bounds = windowBounds(context)
                val screenW = bounds.width()
                val screenH = bounds.height()

                when (type) {
                    "tap" -> {
                        val px = (json.getDouble("x") * screenW).toInt()
                        val py = (json.getDouble("y") * screenH).toInt()
                        exec("input tap $px $py")
                    }
                    "swipe" -> {
                        val x1 = (json.getDouble("x")  * screenW).toInt()
                        val y1 = (json.getDouble("y")  * screenH).toInt()
                        val x2 = (json.getDouble("x2") * screenW).toInt()
                        val y2 = (json.getDouble("y2") * screenH).toInt()
                        val dur = if (json.has("duration")) json.getInt("duration") else 300
                        exec("input swipe $x1 $y1 $x2 $y2 $dur")
                    }
                    "text" -> {
                        val raw = json.getString("text")
                        val safe = raw.replace("'", "'\\''")
                        exec("input text '$safe'")
                    }
                    "keyevent" -> {
                        val keycode = json.getInt("keycode")
                        exec("input keyevent $keycode")
                    }
                    "scroll" -> {
                        val dx = (json.getDouble("dx") * screenW).toInt()
                        val dy = (json.getDouble("dy") * screenH).toInt()
                        exec("input roll $dx $dy")
                    }
                    else -> Log.w(TAG, "Unknown remote-input event type: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch remote input event: $eventJson", e)
            }
        }
    }

    // ------------------------------------------------------------------
    //  Internal helpers
    // ------------------------------------------------------------------

    private fun exec(cmd: String) {
        Log.d(TAG, "su -c \"$cmd\"")
        try {
            ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
                // Do not call waitFor() here; the coroutine dispatcher handles backpressure.
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $cmd", e)
        }
    }

    private fun windowBounds(context: Context): android.graphics.Rect {
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.currentWindowMetrics.bounds
    }
}
