package com.tpeapp.checkin

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.tpeapp.R
import com.tpeapp.databinding.ActivityCheckInBinding
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.service.FilterService
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * CheckInActivity — lets the device owner submit a daily mood/compliance
 * check-in to the partner backend.
 *
 * The check-in is sent as JSON to `POST {endpoint}/api/tpe/checkin`:
 * ```json
 * { "mood_score": 7, "note": "Feeling good today." }
 * ```
 *
 * If a webhook bearer token is stored it is included as an
 * `Authorization: Bearer` header (same secret used by ConsequenceDispatcher
 * and TaskPhotoUploadWorker).
 *
 * This activity can be reached:
 *  1. Via the "Daily Check-In" button on [com.tpeapp.ui.MainActivity].
 *  2. By tapping a `REQUEST_CHECKIN` FCM heads-up notification.
 */
class CheckInActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CheckInActivity"

        private val JSON_TYPE = "application/json".toMediaType()

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var binding: ActivityCheckInBinding

    /** Current mood score (1–10); SeekBar range is 0–9 so we add 1. */
    private var moodScore: Int = 5

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckInBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.checkin_title)

        val endpoint = PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString(PairingActivity.PREF_PARTNER_ENDPOINT, null)

        if (endpoint.isNullOrBlank()) {
            showStatus(getString(R.string.checkin_no_endpoint))
            binding.btnSubmit.isEnabled = false
            return
        }

        setupMoodSlider()
        binding.btnSubmit.setOnClickListener { submitCheckIn(endpoint) }
    }

    // ------------------------------------------------------------------
    //  Mood slider
    // ------------------------------------------------------------------

    private fun setupMoodSlider() {
        updateMoodLabel(moodScore)
        binding.seekBarMood.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                moodScore = progress + 1   // SeekBar is 0–9; display 1–10
                updateMoodLabel(moodScore)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar)  {}
        })
        // Start at 5
        binding.seekBarMood.progress = 4
    }

    private fun updateMoodLabel(score: Int) {
        binding.tvMoodValue.text = "$score / 10"
    }

    // ------------------------------------------------------------------
    //  Submission
    // ------------------------------------------------------------------

    private fun submitCheckIn(endpoint: String) {
        val note = binding.etNote.text?.toString()?.trim()

        val prefs       = PreferenceManager.getDefaultSharedPreferences(this)
        val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
        val deviceId    = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }

        val payload = JSONObject().apply {
            put("mood_score", moodScore)
            if (!note.isNullOrBlank()) put("note", note)
        }.toString().toRequestBody(JSON_TYPE)

        val requestBuilder = Request.Builder()
            .url("$endpoint/api/tpe/checkin")
            .post(payload)
        if (bearerToken != null) {
            requestBuilder.addHeader("Authorization", "Bearer $bearerToken")
        }
        if (deviceId != null) {
            requestBuilder.addHeader("X-Device-ID", deviceId)
        }

        binding.btnSubmit.isEnabled = false
        showStatus(getString(R.string.checkin_status_submitting))
        binding.progressBar.visibility = View.VISIBLE

        httpClient.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Check-in submission failed", e)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSubmit.isEnabled    = true
                    showStatus(getString(R.string.checkin_status_failed))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        if (it.isSuccessful) {
                            Log.i(TAG, "Check-in submitted (HTTP ${it.code})")
                            showStatus(getString(R.string.checkin_status_sent))
                            // Close after brief pause so the user can read the confirmation.
                            binding.root.postDelayed({ finish() }, 1_500)
                        } else {
                            Log.w(TAG, "Check-in rejected: HTTP ${it.code}")
                            binding.btnSubmit.isEnabled = true
                            showStatus(getString(R.string.checkin_status_failed) + " (${it.code})")
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
