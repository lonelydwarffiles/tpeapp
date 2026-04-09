package com.tpeapp.device

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.tpeapp.databinding.ActivityOverlayBinding

/**
 * OverlayActivity — a full-screen, lock-screen-aware activity used for:
 *
 *  1. **Message overlay** ([EXTRA_TITLE] + [EXTRA_MESSAGE]) — shows a full-screen
 *     message the device owner must acknowledge before continuing.
 *  2. **URL open** ([EXTRA_OPEN_URL]) — immediately launches the URL in the system
 *     browser and finishes so the stack is not cluttered.
 *  3. **Keyguard dismiss** ([EXTRA_DISMISS_KEYGUARD]) — requests keyguard dismissal
 *     so the partner can surface content over the lock screen.
 *
 * The activity is declared in the manifest with `showWhenLocked`, `turnScreenOn`,
 * and `FLAG_KEEP_SCREEN_ON` so it appears even on a locked, dark screen.
 */
class OverlayActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OverlayActivity"

        /** Optional display title for the overlay. */
        const val EXTRA_TITLE           = "tpe_overlay_title"
        /** Optional body message text for the overlay. */
        const val EXTRA_MESSAGE         = "tpe_overlay_message"
        /** Optional HTTPS image URL to display in the overlay. */
        const val EXTRA_IMAGE_URL       = "tpe_overlay_image_url"
        /** When `true`, [OverlayActivity] requests keyguard dismissal on start. */
        const val EXTRA_DISMISS_KEYGUARD = "tpe_dismiss_keyguard"
        /** When set, the activity opens the URL immediately and finishes. */
        const val EXTRA_OPEN_URL        = "tpe_open_url"
    }

    private lateinit var binding: ActivityOverlayBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on and make it visible over the lock screen.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        binding = ActivityOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val openUrl         = intent.getStringExtra(EXTRA_OPEN_URL)
        val dismissKeyguard = intent.getBooleanExtra(EXTRA_DISMISS_KEYGUARD, false)
        val title           = intent.getStringExtra(EXTRA_TITLE)
        val message         = intent.getStringExtra(EXTRA_MESSAGE)

        if (openUrl != null) {
            launchUrl(openUrl)
            return
        }

        if (dismissKeyguard) {
            requestKeyguardDismiss()
        }

        binding.tvOverlayTitle.text   = title ?: ""
        binding.tvOverlayTitle.visibility =
            if (title.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

        binding.tvOverlayMessage.text = message ?: ""
        binding.tvOverlayMessage.visibility =
            if (message.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

        binding.btnOverlayDismiss.setOnClickListener { finish() }

        Log.i(TAG, "OverlayActivity shown: title='$title' dismissKeyguard=$dismissKeyguard")
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    private fun launchUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure { e ->
            Log.e(TAG, "launchUrl failed: $url", e)
        }
        finish()
    }

    private fun requestKeyguardDismiss() {
        val km = getSystemService(KeyguardManager::class.java)
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = Log.i(TAG, "Keyguard dismissed")
            override fun onDismissCancelled() = Log.i(TAG, "Keyguard dismiss cancelled")
            override fun onDismissError()     = Log.i(TAG, "Keyguard dismiss error")
        })
    }
}
