package com.tpeapp.bridge

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.service.FilterService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * TextReplacementChannel — MethodChannel bridge for the text-replacement dictionary.
 *
 * Channel name: `com.tpeapp/text_replacement`
 *
 * The dictionary is stored as a JSON string in [PreferenceManager.getDefaultSharedPreferences]
 * under the key [FilterService.PREF_TEXT_REPLACEMENT_DICT].  The LSPosed module reads the
 * same key (via the FilterService AIDL interface) to apply replacements system-wide.
 *
 * Methods exposed to Dart:
 *  - `getDict`           → String   → current dictionary JSON (empty string if not set)
 *  - `setDict`  (json: String)      → persists the dictionary JSON
 *  - `getPolicy`         → String   → current policy JSON (empty string if not set)
 *  - `setPolicy` (json: String)     → persists the policy JSON
 */
object TextReplacementChannel {

    private const val TAG     = "TextReplacementChannel"
    private const val CHANNEL = "com.tpeapp/text_replacement"

    fun register(messenger: BinaryMessenger, context: Context) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            when (call.method) {
                "getDict" -> {
                    val json = prefs.getString(FilterService.PREF_TEXT_REPLACEMENT_DICT, "") ?: ""
                    result.success(json)
                }
                "getPolicy" -> {
                    val json = prefs.getString(FilterService.PREF_TEXT_REPLACEMENT_POLICY, "") ?: ""
                    result.success(json)
                }
                "setDict" -> {
                    val json = call.argument<String>("json")
                        ?: return@setMethodCallHandler result.error("INVALID", "json required", null)
                    prefs.edit()
                        .putString(FilterService.PREF_TEXT_REPLACEMENT_DICT, json)
                        .apply()
                    Log.i(TAG, "Text-replacement dictionary updated (${json.length} chars)")
                    result.success(null)
                }
                "setPolicy" -> {
                    val json = call.argument<String>("json")
                        ?: return@setMethodCallHandler result.error("INVALID", "json required", null)
                    prefs.edit()
                        .putString(FilterService.PREF_TEXT_REPLACEMENT_POLICY, json)
                        .apply()
                    Log.i(TAG, "Text-replacement policy updated (${json.length} chars)")
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }
}
