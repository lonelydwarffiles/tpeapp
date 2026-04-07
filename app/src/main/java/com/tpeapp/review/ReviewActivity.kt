package com.tpeapp.review

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.tpeapp.databinding.ActivityReviewBinding
import com.tpeapp.pairing.PairingActivity

/**
 * ReviewActivity — "Start Review" screen that initiates peer screen-sharing.
 *
 * Flow:
 *  1. The user opens this activity (typically via the [com.tpeapp.ui.MainActivity] dashboard).
 *  2. [MediaProjectionManager] presents the system consent dialog.
 *  3. On approval, [ScreencastService] is started as a foreground service,
 *     which creates the [android.media.projection.MediaProjection] and delegates to
 *     [StreamCoordinator] to establish the WebRTC peer connection.
 *  4. The UI transitions to a "Streaming…" state with a Stop button.
 */
class ReviewActivity : AppCompatActivity() {

    // ------------------------------------------------------------------
    //  Constants
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "ReviewActivity"
    }

    // ------------------------------------------------------------------
    //  State
    // ------------------------------------------------------------------

    private lateinit var binding: ActivityReviewBinding
    private lateinit var projectionManager: MediaProjectionManager
    private var isStreaming = false

    // ------------------------------------------------------------------
    //  Screen-capture permission launcher
    // ------------------------------------------------------------------

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val sessionId = binding.etSessionId.text.toString().trim()
                startScreencastService(result.resultCode, result.data!!, sessionId)
            } else {
                Log.w(TAG, "User denied screen capture permission")
                Toast.makeText(
                    this,
                    "Screen capture permission is required to start a review.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(MediaProjectionManager::class.java)

        // Pre-populate the session ID from paired partner preferences.
        val partnerSessionId =
            PreferenceManager.getDefaultSharedPreferences(this)
                .getString(PairingActivity.PREF_PARTNER_SESSION_ID, "")
        binding.etSessionId.setText(partnerSessionId)

        binding.btnStartReview.setOnClickListener { onStartReviewClicked() }
        binding.btnStopReview.setOnClickListener  { onStopReviewClicked()  }

        updateUiState(streaming = false)
    }

    // ------------------------------------------------------------------
    //  Button handlers
    // ------------------------------------------------------------------

    private fun onStartReviewClicked() {
        val sessionId = binding.etSessionId.text.toString().trim()
        if (sessionId.isBlank()) {
            binding.etSessionId.error = "Please enter a session ID"
            return
        }
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun onStopReviewClicked() {
        stopService(Intent(this, ScreencastService::class.java))
        updateUiState(streaming = false)
    }

    // ------------------------------------------------------------------
    //  Service start
    // ------------------------------------------------------------------

    private fun startScreencastService(
        resultCode: Int,
        resultData: Intent,
        sessionId: String
    ) {
        val serviceIntent = Intent(this, ScreencastService::class.java).apply {
            putExtra(ScreencastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreencastService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreencastService.EXTRA_SESSION_ID,  sessionId)
        }
        startForegroundService(serviceIntent)
        isStreaming = true
        updateUiState(streaming = true)
        Log.i(TAG, "ScreencastService started for session=$sessionId")
    }

    // ------------------------------------------------------------------
    //  UI helpers
    // ------------------------------------------------------------------

    private fun updateUiState(streaming: Boolean) {
        binding.btnStartReview.isEnabled = !streaming
        binding.btnStopReview.isEnabled  = streaming
        binding.tvStreamStatus.text      =
            if (streaming) "🔴 Streaming to partner…" else "Ready"
        binding.etSessionId.isEnabled    = !streaming
    }
}
