package com.tpeapp.tasks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * TaskRepository
 *
 * Stores and retrieves [Task] objects in [SharedPreferences] using a JSON array
 * keyed by [PREF_TASKS_JSON].  Follows the same SharedPreferences-only persistence
 * pattern used throughout the rest of the TPE app.
 *
 * Also owns the [AlarmManager] exact-alarm lifecycle for task deadlines: call
 * [scheduleDeadlineAlarm] when a task is first saved, and [cancelDeadlineAlarm]
 * when it is completed or deleted.
 */
object TaskRepository {

    private const val TAG            = "TaskRepository"
    private const val PREF_TASKS_JSON = "tasks_json"

    // ------------------------------------------------------------------
    //  CRUD
    // ------------------------------------------------------------------

    /** Returns all persisted tasks, newest deadline first. */
    fun loadTasks(context: Context): List<Task> {
        val json = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString(PREF_TASKS_JSON, null)
            ?: return emptyList()

        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getJSONObject(it).toTask() }
                .sortedBy { it.deadlineMs }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize tasks", e)
            emptyList()
        }
    }

    /**
     * Inserts or replaces the task with the same [Task.id].
     * All other tasks are preserved.
     */
    fun upsertTask(context: Context, task: Task) {
        val existing = loadTasks(context).toMutableList()
        val idx = existing.indexOfFirst { it.id == task.id }
        if (idx >= 0) existing[idx] = task else existing.add(task)
        saveTasks(context, existing)
    }

    /** Returns the task with the given [id], or null if not found. */
    fun findById(context: Context, id: String): Task? =
        loadTasks(context).firstOrNull { it.id == id }

    // ------------------------------------------------------------------
    //  AlarmManager — deadline enforcement
    // ------------------------------------------------------------------

    /**
     * Schedules an [AlarmManager.setExactAndAllowWhileIdle] alarm that fires
     * [TaskDeadlineReceiver] when the task's deadline is reached.
     *
     * No-ops if the deadline is already in the past (the missed check is
     * handled immediately by the caller).
     */
    fun scheduleDeadlineAlarm(context: Context, task: Task) {
        if (task.deadlineMs <= System.currentTimeMillis()) return

        val am = context.getSystemService(AlarmManager::class.java)
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            task.deadlineMs,
            buildDeadlinePendingIntent(context, task.id)
        )
        Log.i(TAG, "Deadline alarm scheduled for task ${task.id} at ${task.deadlineMs}")
    }

    /**
     * Cancels a previously-scheduled deadline alarm for the given task ID.
     * Safe to call even if no alarm was scheduled.
     */
    fun cancelDeadlineAlarm(context: Context, taskId: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(buildDeadlinePendingIntent(context, taskId))
        Log.i(TAG, "Deadline alarm cancelled for task $taskId")
    }

    // ------------------------------------------------------------------
    //  Internal helpers
    // ------------------------------------------------------------------

    private fun saveTasks(context: Context, tasks: List<Task>) {
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_TASKS_JSON, array.toString())
            .apply()
    }

    private fun buildDeadlinePendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TaskDeadlineReceiver::class.java).apply {
            action = TaskDeadlineReceiver.ACTION_TASK_DEADLINE
            putExtra(TaskDeadlineReceiver.EXTRA_TASK_ID, taskId)
        }
        // Use a stable request code derived from the task ID so each task
        // gets its own independent PendingIntent slot.
        val requestCode = taskId.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ------------------------------------------------------------------
    //  JSON ↔ Task helpers
    // ------------------------------------------------------------------

    private fun Task.toJson(): JSONObject = JSONObject().apply {
        put("id",          id)
        put("title",       title)
        put("description", description)
        put("deadlineMs",  deadlineMs)
        put("status",      status.name)
        putOpt("photoUri", photoUri)
    }

    private fun JSONObject.toTask(): Task = Task(
        id          = getString("id"),
        title       = getString("title"),
        description = getString("description"),
        deadlineMs  = getLong("deadlineMs"),
        status      = TaskStatus.valueOf(getString("status")),
        photoUri    = optString("photoUri", null).takeIf { !it.isNullOrEmpty() }
    )
}
