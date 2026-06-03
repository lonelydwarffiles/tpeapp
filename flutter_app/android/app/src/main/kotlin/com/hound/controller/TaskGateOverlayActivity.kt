package com.hound.controller

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

private const val FLUTTER_PREFS = "FlutterSharedPreferences"
private const val TASKS_JSON_KEY = "flutter.tasks_json"

class TaskGateOverlayActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TITLE = "task_gate_title"
        const val EXTRA_MESSAGE = "task_gate_message"
        const val EXTRA_TASK_ID = "task_gate_task_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setFinishOnTouchOutside(false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Hard block: ignore back presses.
            }
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
            setBackgroundColor(0xF2111111.toInt())
        }

        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Task Lock Active"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val message = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_MESSAGE)
                ?: "Complete the active task to remove this lock."
            textSize = 18f
            setTextColor(0xFFE0E0E0.toInt())
            setPadding(0, 20, 0, 36)
        }

        val completeButton = Button(this).apply {
            text = "Mark as Complete"
            textSize = 18f
            setOnClickListener {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.trim()?.takeIf { it.isNotEmpty() }
                if (markPendingTaskCompleted(this@TaskGateOverlayActivity, taskId)) {
                    finish()
                }
            }
        }

        root.addView(title)
        root.addView(message)
        root.addView(completeButton)
        setContentView(root)
    }

    private fun markPendingTaskCompleted(context: Context, taskId: String?): Boolean {
        val prefs = context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(TASKS_JSON_KEY, null) ?: return false

        return runCatching {
            val tasks = JSONArray(raw)
            var updated = false
            for (i in 0 until tasks.length()) {
                val task = tasks.optJSONObject(i) ?: continue
                val id = task.optString("id", "").trim()
                val status = task.optString("status", "").trim().lowercase()
                if (status != "pending") {
                    continue
                }
                if (taskId != null && id != taskId) {
                    continue
                }
                if (status == "pending") {
                    task.put("status", "COMPLETED")
                    updated = true
                    break
                }
            }

            if (!updated && taskId != null) {
                for (i in 0 until tasks.length()) {
                    val task = tasks.optJSONObject(i) ?: continue
                    val status = task.optString("status", "").trim().lowercase()
                    if (status == "pending") {
                        task.put("status", "COMPLETED")
                        updated = true
                        break
                    }
                }
            }

            if (!updated) return false

            val normalized = JSONArray()
            for (i in 0 until tasks.length()) {
                val task = tasks.optJSONObject(i)
                if (task != null) {
                    normalized.put(task)
                } else {
                    normalized.put(JSONObject(tasks.opt(i).toString()))
                }
            }

            prefs.edit().putString(TASKS_JSON_KEY, normalized.toString()).apply()
            true
        }.getOrDefault(false)
    }
}
