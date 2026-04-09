package com.tpeapp.mindful

import android.content.Context
import androidx.preference.PreferenceManager

object HonorificManager {

    private const val PREF_ENABLED = "honorific_mode_enabled"
    private const val PREF_TEXT = "honorific_text"
    private const val DEFAULT_TEXT = "Sir, "

    fun isEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_ENABLED, enabled).apply()
    }

    fun getHonorific(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(PREF_TEXT, DEFAULT_TEXT) ?: DEFAULT_TEXT

    fun setHonorific(ctx: Context, text: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_TEXT, text).apply()
    }
}
