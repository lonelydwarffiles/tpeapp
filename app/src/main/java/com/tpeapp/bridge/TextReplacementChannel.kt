package com.hound.controller.bridge

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.hound.controller.service.FilterService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/**
 * TextReplacementChannel — MethodChannel bridge for the text-replacement dictionary.
 *
 * Channel name: `com.hound.controller/text_replacement`
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
 *  - `getPackagePolicy` (packageName) → String? → package override mode, if set
 *  - `setPackagePolicy` (packageName, mode)     → updates/removes one package override
 */
object TextReplacementChannel {

    private const val TAG     = "TextReplacementChannel"
    private const val CHANNEL = "com.hound.controller/text_replacement"

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
                "getPackagePolicy" -> {
                    val packageName = call.argument<String>("packageName")
                        ?.trim()
                        ?.lowercase()
                        ?: return@setMethodCallHandler result.error("INVALID", "packageName required", null)

                    val policyJson = prefs.getString(FilterService.PREF_TEXT_REPLACEMENT_POLICY, "") ?: ""
                    if (policyJson.isBlank()) {
                        result.success(null)
                        return@setMethodCallHandler
                    }

                    val mode = runCatching {
                        val root = JSONObject(policyJson)
                        root.optJSONObject("packages")?.optString(packageName, null)
                    }.getOrNull()
                    result.success(mode)
                }
                "setPackagePolicy" -> {
                    val packageName = call.argument<String>("packageName")
                        ?.trim()
                        ?.lowercase()
                        ?: return@setMethodCallHandler result.error("INVALID", "packageName required", null)
                    val rawMode = call.argument<String>("mode")
                        ?.trim()
                        ?.lowercase()
                        ?: return@setMethodCallHandler result.error("INVALID", "mode required", null)

                    val policyJson = prefs.getString(FilterService.PREF_TEXT_REPLACEMENT_POLICY, "") ?: ""
                    val root = runCatching {
                        if (policyJson.isBlank()) JSONObject() else JSONObject(policyJson)
                    }.getOrElse { JSONObject() }
                    val packages = root.optJSONObject("packages") ?: JSONObject().also { root.put("packages", it) }

                    val normalizedMode = when (rawMode) {
                        "off", "none", "disabled" -> "off"
                        "identity", "identity_only", "identity-only" -> "identity"
                        "full", "all" -> "full"
                        "auto", "loose", "soft" -> "auto"
                        "inherit", "default", "" -> ""
                        else -> return@setMethodCallHandler result.error(
                            "INVALID",
                            "mode must be one of auto/full/identity/off/inherit",
                            null,
                        )
                    }

                    if (normalizedMode.isEmpty()) {
                        packages.remove(packageName)
                    } else {
                        packages.put(packageName, normalizedMode)
                    }

                    val saved = root.toString()
                    prefs.edit()
                        .putString(FilterService.PREF_TEXT_REPLACEMENT_POLICY, saved)
                        .apply()
                    Log.i(TAG, "Text-replacement package policy updated for $packageName -> ${normalizedMode.ifEmpty { "inherit" }}")
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }
}
