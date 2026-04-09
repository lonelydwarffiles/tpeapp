package com.tpeapp.consequence

import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.tpeapp.databinding.ActivityCornerTimeBinding
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * CornerTimeActivity — full-screen countdown lock with front-camera preview.
 *
 * Launched by [com.tpeapp.fcm.PartnerFcmService] via full-screen intent when the
 * Dom sends a START_CORNER_TIME FCM message.
 *
 * The back button is suppressed.  When the timer expires the activity fires a
 * webhook and a reward stimulus, then finishes.
 */
class CornerTimeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CornerTimeActivity"

        /** Intent extra: how many minutes of corner time (Int, default 5). */
        const val EXTRA_DURATION_MINUTES = "corner_time_duration_minutes"

        /** Intent extra: optional title string shown at the top. */
        const val EXTRA_TITLE = "corner_time_title"
    }

    private lateinit var binding: ActivityCornerTimeBinding
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        binding = ActivityCornerTimeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 5)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(com.tpeapp.R.string.corner_time_title)

        binding.tvCornerTimeTitle.text = title
        binding.tvCornerTimeInstruction.text = getString(com.tpeapp.R.string.corner_time_instruction)

        startCamera()
        startCountdown(durationMinutes)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Toast.makeText(this, "Corner time is not over yet.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }

    // ------------------------------------------------------------------
    //  Camera
    // ------------------------------------------------------------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewCornerCamera.surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview
                )
            } catch (e: Exception) {
                Log.w(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------------
    //  Countdown
    // ------------------------------------------------------------------

    private fun startCountdown(minutes: Int) {
        val totalMs = TimeUnit.MINUTES.toMillis(minutes.toLong())
        timer = object : CountDownTimer(totalMs, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val mins = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)
                val secs = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60
                binding.tvCountdown.text = String.format(Locale.US, "%02d:%02d", mins, secs)
            }

            override fun onFinish() {
                binding.tvCountdown.text = "00:00"
                binding.tvCornerTimeInstruction.text = getString(com.tpeapp.R.string.corner_time_done)
                ConsequenceDispatcher.reward(this@CornerTimeActivity, "corner_time_complete")
                dispatchWebhook()
                finish()
            }
        }.start()
    }

    // ------------------------------------------------------------------
    //  Webhook
    // ------------------------------------------------------------------

    private fun dispatchWebhook() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() } ?: return
        val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
        WebhookManager.dispatchEvent(url, token, JSONObject().apply {
            put("event", "corner_time_complete")
            put("timestamp", System.currentTimeMillis())
        })
    }
}
