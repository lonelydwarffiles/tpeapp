package com.tpeapp.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tpeapp.R
import com.tpeapp.databinding.ActivityAssignTaskBinding
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.questions.QuestionsActivity
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
import java.util.concurrent.TimeUnit

/**
 * AssignTaskActivity
 *
 * Allows the accountability partner to compose a task (title, description,
 * deadline) and send it to the submissive device via the partner backend.
 *
 * The task payload is sent as JSON to `POST {endpoint}/api/admin/tpe/tasks`
 * using HTTP Basic Auth.  The backend stores the task and forwards a
 * `TASK_ASSIGNED` FCM push to every paired device.
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

        val creds = loadAdminCredentials()
        if (creds == null) {
            promptForCredentials { sendTask() }
            return
        }

        val payload = JSONObject().apply {
            put("title",       title)
            put("description", description)
            put("deadline_ms", deadline)
        }

        binding.btnSendTask.isEnabled = false
        showStatus("Sending task…")
        binding.progressBar.visibility = View.VISIBLE

        val authHeader = "Basic " + Base64.encodeToString(
            "${creds.first}:${creds.second}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val request = Request.Builder()
            .url("$endpoint/api/admin/tpe/tasks")
            .addHeader("Authorization", authHeader)
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
                        when {
                            it.isSuccessful -> {
                                Log.i(TAG, "Task assigned successfully (HTTP ${it.code})")
                                showStatus("✅ Task sent to device.")
                                binding.btnSendTask.isEnabled = false
                            }
                            it.code == 401 -> {
                                Log.w(TAG, "Admin credentials rejected (HTTP 401)")
                                binding.btnSendTask.isEnabled = true
                                showStatus("⚠️ Invalid admin credentials. Re-enter them.")
                                promptForCredentials { sendTask() }
                            }
                            else -> {
                                Log.w(TAG, "Server rejected task assignment: HTTP ${it.code}")
                                binding.btnSendTask.isEnabled = true
                                showStatus("⚠️ Server rejected request (${it.code}). Try again.")
                            }
                        }
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    //  Admin credentials (EncryptedSharedPreferences, same store as QuestionsActivity)
    // ------------------------------------------------------------------

    private fun loadAdminCredentials(): Pair<String, String>? {
        return try {
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                applicationContext,
                "questions_admin_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val user = prefs.getString(QuestionsActivity.PREF_ADMIN_USER, null)
            val pass = prefs.getString(QuestionsActivity.PREF_ADMIN_PASS, null)
            if (user.isNullOrBlank() || pass.isNullOrBlank()) null else Pair(user, pass)
        } catch (e: Exception) {
            Log.e(TAG, "Could not load admin credentials", e)
            null
        }
    }

    private fun promptForCredentials(onSaved: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etUser = EditText(this).apply { hint = getString(R.string.questions_credentials_hint_user) }
        val etPass = EditText(this).apply {
            hint = getString(R.string.questions_credentials_hint_pass)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etUser)
        layout.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.questions_credentials_title))
            .setMessage(getString(R.string.questions_credentials_message))
            .setView(layout)
            .setPositiveButton(getString(R.string.questions_credentials_save)) { _, _ ->
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString()
                if (user.isBlank() || pass.isBlank()) {
                    showStatus("⚠️ ${getString(R.string.questions_credentials_empty)}")
                    return@setPositiveButton
                }
                saveAdminCredentials(user, pass)
                onSaved()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveAdminCredentials(user: String, pass: String) {
        try {
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                applicationContext,
                "questions_admin_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.edit()
                .putString(QuestionsActivity.PREF_ADMIN_USER, user)
                .putString(QuestionsActivity.PREF_ADMIN_PASS, pass)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not save admin credentials", e)
        }
    }

    // ------------------------------------------------------------------
    //  Helper
    // ------------------------------------------------------------------

    private fun showStatus(message: String) {
        binding.tvStatus.text       = message
        binding.tvStatus.visibility = View.VISIBLE
    }
}
