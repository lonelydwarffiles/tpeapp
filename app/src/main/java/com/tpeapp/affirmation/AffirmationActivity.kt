package com.tpeapp.affirmation

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.tpeapp.R
import com.tpeapp.databinding.ActivityAffirmationBinding
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONObject

/**
 * AffirmationActivity — full-screen, lock-screen-aware interstitial.
 *
 * The sub must type the exact affirmation text (trimmed, case-insensitive)
 * to dismiss. The back button is suppressed.
 *
 * Launched by [MantraAlarmReceiver] or directly via FCM SHOW_AFFIRMATION.
 */
class AffirmationActivity : AppCompatActivity() {

    companion object {
        /** String extra: affirmation text to display and require. */
        const val EXTRA_TEXT = "affirmation_text"

        /** Boolean extra: whether the sub must type the text (default true). */
        const val EXTRA_REQUIRE_TYPING = "affirmation_require_typing"
    }

    private lateinit var binding: ActivityAffirmationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        binding = ActivityAffirmationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: return finish()
        val requireTyping = intent.getBooleanExtra(EXTRA_REQUIRE_TYPING, true)

        binding.tvAffirmationText.text = text
        binding.tvAffirmationInstruction.text = getString(R.string.affirmation_instruction)
        binding.etAffirmationInput.hint = getString(R.string.affirmation_hint)

        if (!requireTyping) {
            binding.etAffirmationInput.isEnabled = false
            binding.btnAffirmationSubmit.setOnClickListener {
                onSuccess(text)
            }
        } else {
            binding.btnAffirmationSubmit.setOnClickListener {
                val input = binding.etAffirmationInput.text?.toString()?.trim() ?: ""
                if (input.equals(text.trim(), ignoreCase = true)) {
                    onSuccess(text)
                } else {
                    Toast.makeText(this, getString(R.string.affirmation_incorrect), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Toast.makeText(this, "Complete the affirmation to continue.", Toast.LENGTH_SHORT).show()
    }

    private fun onSuccess(text: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() }
        if (url != null) {
            val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            WebhookManager.dispatchEvent(url, token, JSONObject().apply {
                put("event", "affirmation_completed")
                put("affirmation", text)
                put("timestamp", System.currentTimeMillis())
            })
        }
        finish()
    }
}
