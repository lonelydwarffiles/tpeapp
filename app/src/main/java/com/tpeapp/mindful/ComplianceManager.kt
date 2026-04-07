package com.tpeapp.mindful

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * ComplianceManager — centralises access to the remote "Strict Mode" flag for
 * tone enforcement.
 *
 * When [isStrictToneModeEnabled] returns `true`, [ToneEnforcementService]
 * ignores its session whitelist and bypass logic, relentlessly applying
 * vocabulary corrections on every TYPE_VIEW_TEXT_CHANGED event.
 *
 * The flag is persisted in [SharedPreferences] under [PREF_STRICT_TONE_MODE]
 * and can be toggled remotely by the Accountability Partner via
 * [com.tpeapp.fcm.PartnerFcmService].
 */
object ComplianceManager {

    /** SharedPreferences key for the strict tone-enforcement mode flag. */
    const val PREF_STRICT_TONE_MODE = "strict_tone_mode"

    /**
     * Returns `true` when the remote partner has enabled strict tone mode.
     * Defaults to `false` if the key has never been set.
     */
    fun isStrictToneModeEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_STRICT_TONE_MODE, false)

    /**
     * Persists the strict tone mode flag.  Intended to be called only by
     * [com.tpeapp.fcm.PartnerFcmService] when an FCM payload is received.
     */
    fun setStrictToneMode(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_STRICT_TONE_MODE, enabled)
            .apply()
    }
}
