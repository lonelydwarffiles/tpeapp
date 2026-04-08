package com.tpeapp.tasks

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.tpeapp.R
import com.tpeapp.databinding.ActivityTaskListBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TaskListActivity
 *
 * Displays all tasks assigned by the accountability partner, grouped into
 * "Pending" and "Completed / Missed" sections.  Tapping a pending task opens
 * [TaskVerificationActivity] to submit photo proof.
 *
 * Uses a programmatically-populated [LinearLayout] inside a [ScrollView]
 * rather than a RecyclerView, keeping the implementation lightweight and
 * consistent with the rest of the app's simple UI approach.
 */
class TaskListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskListBinding

    private val dateFmt = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.task_list_title)
    }

    override fun onResume() {
        super.onResume()
        populateList()
    }

    // ------------------------------------------------------------------
    //  List population
    // ------------------------------------------------------------------

    private fun populateList() {
        binding.containerPending.removeAllViews()
        binding.containerDone.removeAllViews()

        val tasks = TaskRepository.loadTasks(this)

        val pending = tasks.filter { it.status == TaskStatus.PENDING }
        val done    = tasks.filter { it.status != TaskStatus.PENDING }

        if (pending.isEmpty()) {
            binding.tvNoPending.visibility = View.VISIBLE
        } else {
            binding.tvNoPending.visibility = View.GONE
            pending.forEach { task -> binding.containerPending.addView(buildTaskRow(task)) }
        }

        if (done.isEmpty()) {
            binding.tvNoDone.visibility = View.VISIBLE
        } else {
            binding.tvNoDone.visibility = View.GONE
            done.sortedByDescending { it.deadlineMs }
                .forEach { task -> binding.containerDone.addView(buildTaskRow(task)) }
        }
    }

    // ------------------------------------------------------------------
    //  Row builder
    // ------------------------------------------------------------------

    private fun buildTaskRow(task: Task): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dpToPx(12))
        }

        // Title + status badge on one line
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val tvTitle = TextView(this).apply {
            text      = task.title
            textSize  = 16f
            setTextColor(ContextCompat.getColor(context, android.R.color.primary_text_light))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvStatus = TextView(this).apply {
            text     = statusLabel(task.status)
            textSize = 12f
            setTextColor(statusColor(task.status))
        }

        headerRow.addView(tvTitle)
        headerRow.addView(tvStatus)
        row.addView(headerRow)

        // Deadline line
        val tvDeadline = TextView(this).apply {
            text     = "Due: ${dateFmt.format(Date(task.deadlineMs))}"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, android.R.color.secondary_text_light))
        }
        row.addView(tvDeadline)

        // "Verify" button only for pending tasks
        if (task.status == TaskStatus.PENDING) {
            val btn = MaterialButton(this).apply {
                text = getString(R.string.task_btn_verify)
                setOnClickListener {
                    val intent = Intent(this@TaskListActivity, TaskVerificationActivity::class.java)
                        .putExtra(TaskVerificationActivity.EXTRA_TASK_ID, task.id)
                    startActivity(intent)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(4) }
            }
            row.addView(btn)
        }

        // Divider
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(1)
            ).apply { topMargin = dpToPx(8) }
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        row.addView(divider)

        return row
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun statusLabel(status: TaskStatus) = when (status) {
        TaskStatus.PENDING   -> "PENDING"
        TaskStatus.COMPLETED -> "✅ DONE"
        TaskStatus.MISSED    -> "❌ MISSED"
    }

    private fun statusColor(status: TaskStatus) = ContextCompat.getColor(
        this,
        when (status) {
            TaskStatus.PENDING   -> android.R.color.holo_blue_dark
            TaskStatus.COMPLETED -> android.R.color.holo_green_dark
            TaskStatus.MISSED    -> android.R.color.holo_red_dark
        }
    )

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
