package com.tpeapp.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tpeapp.consequence.ConsequenceDispatcher

/**
 * TaskDeadlineReceiver
 *
 * Receives the [AlarmManager] broadcast that fires when a task's deadline
 * arrives.  If the task is still [TaskStatus.PENDING] the device owner missed
 * the deadline: the task is marked [TaskStatus.MISSED] and
 * [ConsequenceDispatcher.punish] is called, mirroring the pattern used by
 * [com.tpeapp.adherence.AdherenceAlarmReceiver].
 *
 * A notification is shown in either case so the user always knows a deadline
 * event occurred.
 *
 * Scheduling
 * ----------
 * Alarms are scheduled by [TaskRepository.scheduleDeadlineAlarm] using
 * [android.app.AlarmManager.setExactAndAllowWhileIdle].
 */
class TaskDeadlineReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TaskDeadlineReceiver"

        /** Intent action used when scheduling the deadline alarm. */
        const val ACTION_TASK_DEADLINE  = "com.tpeapp.ACTION_TASK_DEADLINE"

        /** Intent extra carrying the task UUID string. */
        const val EXTRA_TASK_ID         = "task_id"

        private const val CHANNEL_ID    = "task_deadline"
        private const val CHANNEL_NAME  = "Task Deadlines"
        private const val NOTIF_ID_BASE = 0x7A51   // arbitrary base; task hash added
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TASK_DEADLINE) return

        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: run {
            Log.w(TAG, "Deadline broadcast received without task_id — ignoring")
            return
        }

        val task = TaskRepository.findById(context, taskId) ?: run {
            Log.w(TAG, "Task $taskId not found — deadline alarm stale")
            return
        }

        Log.i(TAG, "Deadline fired for task ${task.id} (status=${task.status})")

        when (task.status) {
            TaskStatus.PENDING -> {
                // Mark missed and dispatch a punishment.
                TaskRepository.upsertTask(context, task.copy(status = TaskStatus.MISSED))
                ConsequenceDispatcher.punish(context, "task_missed:${task.title}")
                showDeadlineNotification(context, task, missed = true)
                Log.i(TAG, "Task '${task.title}' missed — punishment dispatched")
            }
            TaskStatus.COMPLETED -> {
                // Completed before alarm fired (race between upload and alarm).
                Log.i(TAG, "Task '${task.title}' was already completed — no action")
                showDeadlineNotification(context, task, missed = false)
            }
            TaskStatus.MISSED -> {
                // Duplicate alarm — no-op.
                Log.d(TAG, "Task already marked MISSED — duplicate alarm ignored")
            }
        }
    }

    // ------------------------------------------------------------------
    //  Notification
    // ------------------------------------------------------------------

    private fun showDeadlineNotification(context: Context, task: Task, missed: Boolean) {
        ensureChannel(context)

        val title   = if (missed) "Task deadline missed!" else "Task completed in time ✅"
        val text    = if (missed)
            "\"${task.title}\" was not verified before the deadline."
        else
            "\"${task.title}\" was verified before the deadline."

        // Tap → opens TaskListActivity so the owner can see all tasks.
        val tapIntent = Intent(context, TaskListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapPending = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIF_ID_BASE + (task.id.hashCode() and 0xFF), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — cannot show deadline notification", e)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Alerts when an assigned task deadline passes"
                    enableVibration(true)
                }
        )
    }
}
