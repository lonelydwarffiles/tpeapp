package com.tpeapp.questions

import android.app.AlertDialog
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.button.MaterialButton
import com.tpeapp.R
import com.tpeapp.databinding.ActivityQuestionsBinding
import com.tpeapp.pairing.PairingActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * QuestionsActivity — lets the accountability partner view and answer
 * anonymous questions submitted via the Camera-Site "Puppy Pouch" feature.
 *
 * Questions are fetched from:
 *   GET  {endpoint}/api/admin/questions          — unanswered questions
 *
 * Answers are posted to:
 *   POST {endpoint}/api/admin/questions/{id}/answer
 *   Body: { "answer": "<text>" }
 *
 * Questions are deleted via:
 *   DELETE {endpoint}/api/admin/questions/{id}
 *
 * Both calls use HTTP Basic Auth (admin_username / admin_password stored in
 * SharedPreferences).  If credentials are not yet stored the activity prompts
 * the partner to enter them before continuing.
 */
class QuestionsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "QuestionsActivity"

        /** SharedPreferences keys for admin HTTP Basic Auth credentials. */
        const val PREF_ADMIN_USER = "admin_username"
        const val PREF_ADMIN_PASS = "admin_password"

        private val JSON_TYPE = "application/json".toMediaType()

        // Shared client reuses the app-wide connection pool defined alongside
        // other HTTP workers (WebhookManager, AuditUploadWorker).  A companion-object
        // singleton is the established pattern in this codebase; the client and its
        // pool are intentionally long-lived so connections can be reused across
        // refresh cycles without re-establishing TLS each time.
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var binding: ActivityQuestionsBinding

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuestionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.questions_title)

        binding.btnRefresh.setOnClickListener { loadQuestions() }

        ensureCredentialsThenLoad()
    }

    // ------------------------------------------------------------------
    //  Credential guard
    // ------------------------------------------------------------------

    /**
     * If admin credentials are already stored, load questions immediately.
     * Otherwise show a one-time dialog so the partner can enter them.
     */
    private fun ensureCredentialsThenLoad() {
        val (user, pass) = storedCredentials()
        if (user.isNotBlank() && pass.isNotBlank()) {
            loadQuestions()
        } else {
            showCredentialsDialog(onSaved = { loadQuestions() })
        }
    }

    private fun showCredentialsDialog(onSaved: () -> Unit) {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_admin_credentials, null)
        val etUser = view.findViewById<EditText>(R.id.etAdminUser)
        val etPass = view.findViewById<EditText>(R.id.etAdminPass)

        // Pre-fill if any value was saved before
        val (u, p) = storedCredentials()
        etUser.setText(u)
        etPass.setText(p)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.questions_credentials_title))
            .setMessage(getString(R.string.questions_credentials_message))
            .setView(view)
            .setPositiveButton(getString(R.string.questions_credentials_save)) { _, _ ->
                val username = etUser.text.toString().trim()
                val password = etPass.text.toString()   // no trim — spaces may be intentional
                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(
                        this,
                        getString(R.string.questions_credentials_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                saveCredentials(username, password)
                onSaved()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ------------------------------------------------------------------
    //  Fetch questions
    // ------------------------------------------------------------------

    private fun loadQuestions() {
        val endpoint = partnerEndpoint()
        if (endpoint.isBlank()) {
            showError(getString(R.string.questions_no_endpoint))
            return
        }

        showLoading(true)
        binding.containerQuestions.removeAllViews()
        binding.tvEmpty.visibility = View.GONE

        val request = Request.Builder()
            .url("$endpoint/api/admin/questions")
            .addHeader("Authorization", basicAuthHeader())
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Failed to fetch questions", e)
                runOnUiThread {
                    showLoading(false)
                    showError(getString(R.string.questions_fetch_failed))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.code == 401) {
                        runOnUiThread {
                            showLoading(false)
                            showError(getString(R.string.questions_auth_failed))
                            showCredentialsDialog(onSaved = { loadQuestions() })
                        }
                        return
                    }
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Unexpected HTTP ${resp.code} fetching questions")
                        runOnUiThread {
                            showLoading(false)
                            showError(getString(R.string.questions_fetch_failed))
                        }
                        return
                    }
                    val body = resp.body?.string() ?: "[]"
                    val array = try { JSONArray(body) } catch (e: Exception) { JSONArray() }
                    runOnUiThread {
                        showLoading(false)
                        renderQuestions(array)
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    //  Render
    // ------------------------------------------------------------------

    private fun renderQuestions(array: JSONArray) {
        binding.containerQuestions.removeAllViews()
        if (array.length() == 0) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = getString(R.string.questions_none_pending)
            return
        }
        binding.tvEmpty.visibility = View.GONE

        for (i in 0 until array.length()) {
            val obj  = array.getJSONObject(i)
            val id   = obj.optString("id")
            val text = obj.optString("text")
            addQuestionRow(id, text)
        }
    }

    private fun addQuestionRow(id: String, text: String) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_question, binding.containerQuestions, false)

        row.findViewById<TextView>(R.id.tvQuestionText).text = text

        row.findViewById<MaterialButton>(R.id.btnAnswer).setOnClickListener {
            showAnswerDialog(id, text, row)
        }

        row.findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            confirmDelete(id, row)
        }

        binding.containerQuestions.addView(row)
    }

    // ------------------------------------------------------------------
    //  Answer dialog
    // ------------------------------------------------------------------

    private fun showAnswerDialog(questionId: String, questionText: String, row: View) {
        val input = EditText(this).apply {
            hint = getString(R.string.questions_answer_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.questions_answer_title))
            .setMessage("\u201c$questionText\u201d")
            .setView(input)
            .setPositiveButton(getString(R.string.questions_answer_send)) { _, _ ->
                val answer = input.text.toString().trim()
                if (answer.isBlank()) {
                    Toast.makeText(
                        this,
                        getString(R.string.questions_answer_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                postAnswer(questionId, answer, row)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    //  Post answer
    // ------------------------------------------------------------------

    private fun postAnswer(questionId: String, answer: String, row: View) {
        val endpoint = partnerEndpoint()
        if (endpoint.isBlank()) return

        val body = JSONObject().put("answer", answer).toString()
            .toRequestBody(JSON_TYPE)

        val request = Request.Builder()
            .url("$endpoint/api/admin/questions/$questionId/answer")
            .addHeader("Authorization", basicAuthHeader())
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Failed to post answer for $questionId", e)
                runOnUiThread {
                    Toast.makeText(
                        this@QuestionsActivity,
                        getString(R.string.questions_answer_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    runOnUiThread {
                        if (resp.isSuccessful) {
                            binding.containerQuestions.removeView(row)
                            if (binding.containerQuestions.childCount == 0) {
                                binding.tvEmpty.text = getString(R.string.questions_none_pending)
                                binding.tvEmpty.visibility = View.VISIBLE
                            }
                            Toast.makeText(
                                this@QuestionsActivity,
                                getString(R.string.questions_answer_sent),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Log.w(TAG, "Server rejected answer: HTTP ${resp.code}")
                            Toast.makeText(
                                this@QuestionsActivity,
                                getString(R.string.questions_answer_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    //  Delete question
    // ------------------------------------------------------------------

    private fun confirmDelete(questionId: String, row: View) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.questions_delete_title))
            .setMessage(getString(R.string.questions_delete_confirm))
            .setPositiveButton(getString(R.string.questions_delete_btn)) { _, _ ->
                deleteQuestion(questionId, row)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteQuestion(questionId: String, row: View) {
        val endpoint = partnerEndpoint()
        if (endpoint.isBlank()) return

        val request = Request.Builder()
            .url("$endpoint/api/admin/questions/$questionId")
            .addHeader("Authorization", basicAuthHeader())
            .delete()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Failed to delete question $questionId", e)
                runOnUiThread {
                    Toast.makeText(
                        this@QuestionsActivity,
                        getString(R.string.questions_delete_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    runOnUiThread {
                        if (resp.isSuccessful || resp.code == 204) {
                            binding.containerQuestions.removeView(row)
                            if (binding.containerQuestions.childCount == 0) {
                                binding.tvEmpty.text = getString(R.string.questions_none_pending)
                                binding.tvEmpty.visibility = View.VISIBLE
                            }
                        } else {
                            Log.w(TAG, "Server rejected delete: HTTP ${resp.code}")
                            Toast.makeText(
                                this@QuestionsActivity,
                                getString(R.string.questions_delete_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun storedCredentials(): Pair<String, String> {
        val prefs = encryptedPrefs()
        return Pair(
            prefs.getString(PREF_ADMIN_USER, "") ?: "",
            prefs.getString(PREF_ADMIN_PASS, "") ?: ""
        )
    }

    /**
     * Persists admin credentials using [EncryptedSharedPreferences] so they
     * are protected at rest by AES-256-GCM / AES-256-SIV.
     */
    private fun saveCredentials(username: String, password: String) {
        encryptedPrefs().edit()
            .putString(PREF_ADMIN_USER, username)
            .putString(PREF_ADMIN_PASS, password)
            .apply()
    }

    private fun encryptedPrefs() = EncryptedSharedPreferences.create(
        this,
        "questions_admin_prefs",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun basicAuthHeader(): String {
        val (user, pass) = storedCredentials()
        val encoded = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    private fun partnerEndpoint(): String =
        PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PairingActivity.PREF_PARTNER_ENDPOINT, "") ?: ""

    private fun showLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRefresh.isEnabled   = !loading
    }

    private fun showError(message: String) {
        binding.tvEmpty.text = message
        binding.tvEmpty.visibility = View.VISIBLE
    }
}
