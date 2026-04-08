package com.tpeapp.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.tpeapp.R
import com.tpeapp.databinding.ActivityAssignTaskBinding
import com.tpeapp.pairing.PairingActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * AssignTaskActivity
 *
 * Allows the accountability partner to compose a task (title, description,
 * deadline) and send it to the submissive device via the partner backend.
 *
 * The task payload is sent as JSON to `POST {endpoint}/api/tasks/assign`.
 * The backend is expected to store the task and forward an FCM
 * `TASK_ASSIGNED` push to the paired device's FCM token.
 *
 * This activity is PIN-protected in [MainActivity] before launch so only the
 * partner can open it (consistent with how Deactivate Admin is guarded).
 */
class AssignTaskActivity : AppCompatActivity() {

    companion object {
        private const val TAG      = "AssignTaskActivity"
        private val JSON_TYPE      = "application/json".toMediaType()

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var binding: ActivityAssignTaskBinding

    /** Epoch milliseconds for the chosen deadline; null until picker is used. */
    private var deadlineMs: Long? = null

    private val dateFmt = SimpleDateFormat("EEE, MMM d yyyy 'at' h:mm a", Locale.getDefault())

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssignTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.assign_task_title)

        binding.btnPickDeadline.setOnClickListener { pickDeadline() }
        binding.btnSendTask.setOnClickListener     { sendTask()     }
    }

    // ------------------------------------------------------------------
    //  Deadline picker
    // ------------------------------------------------------------------

    private fun pickDeadline() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        val cal = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        deadlineMs = cal.timeInMillis
                        binding.tvDeadlineValue.text = dateFmt.format(cal.time)
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    false
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = now.timeInMillis
        }.show()
    }

    // ------------------------------------------------------------------
    //  Send task
    // ------------------------------------------------------------------

    private fun sendTask() {
        val title       = binding.etTitle.text?.toString()?.trim() ?: ""
        val description = binding.etDescription.text?.toString()?.trim() ?: ""
        val deadline    = deadlineMs

        if (title.isBlank()) {
            showStatus("⚠️ Title is required.")
            return
        }
        if (deadline == null) {
            showStatus("⚠️ Please pick a deadline.")
            return
        }
        if (deadline <= System.currentTimeMillis()) {
            showStatus("⚠️ Deadline must be in the future.")
            return
        }

        val endpoint = PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString(PairingActivity.PREF_PARTNER_ENDPOINT, null)

        if (endpoint.isNullOrBlank()) {
            showStatus("⚠️ Device is not paired with a partner backend.")
            return
        }

        val taskId  = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("task_id",     taskId)
            put("title",       title)
            put("description", description)
            put("deadline_ms", deadline)
        }

        binding.btnSendTask.isEnabled = false
        showStatus("Sending task…")
        binding.progressBar.visibility = View.VISIBLE

        val request = Request.Builder()
            .url("$endpoint/api/tasks/assign")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Task assignment request failed", e)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSendTask.isEnabled  = true
                    showStatus("⚠️ Network error: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        if (it.isSuccessful) {
                            Log.i(TAG, "Task $taskId assigned successfully")
                            showStatus("✅ Task sent to device.")
                            binding.btnSendTask.isEnabled = false
                        } else {
                            Log.w(TAG, "Server rejected task assignment: HTTP ${it.code}")
                            binding.btnSendTask.isEnabled = true
                            showStatus("⚠️ Server rejected request (${it.code}). Try again.")
                        }
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    //  Helper
    // ------------------------------------------------------------------

    private fun showStatus(message: String) {
        binding.tvStatus.text       = message
        binding.tvStatus.visibility = View.VISIBLE
    }
}
