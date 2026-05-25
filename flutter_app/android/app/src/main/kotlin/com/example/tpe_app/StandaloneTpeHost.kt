package com.example.tpe_app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val FLUTTER_PREFS = "FlutterSharedPreferences"
private const val VAULT_PREFS_KEY = "vault_entries"
private const val PARTNER_PIN_KEY = "partner_pin"
private const val ADMIN_PIN_KEY = "device_admin_pin"
private const val ADMIN_ACTIVE_KEY = "device_admin_active"
private const val FILTER_THRESHOLD_KEY = "filter_confidence_threshold"
private const val FILTER_STRICT_KEY = "filter_strict_mode"
private const val WEBHOOK_URL_KEY = "webhook_url"
private const val WEBHOOK_TOKEN_KEY = "webhook_bearer_token"
private const val INJECTION_MODE_KEY = "remote_control_injection_mode"
private const val TEXT_REPLACEMENT_KEY = "text_replacement_dict"
private var cachedRootAvailable: Boolean? = null

object StandaloneTpeHost {
    fun register(flutterEngine: FlutterEngine, activity: MainActivity) {
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        val context = activity.applicationContext

        registerFilterService(messenger, context)
        registerPartnerPin(messenger, context)
        registerDeviceAdmin(messenger, activity)
        registerMqttEvents(messenger)
        registerRemoteControl(messenger, context)
        registerTextReplacement(messenger, context)
        registerPasswordVault(messenger, context)
        registerNoOpMethods(messenger, "com.tpeapp/screen_share")
        registerNoOpMethods(messenger, "com.tpeapp/device_commands")
        registerNoOpMethods(messenger, "com.tpeapp/ble")
    }

    private fun registerFilterService(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/filter_service").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "start", "stop" -> result.success(null)
                "setThreshold" -> {
                    val threshold = call.argument<Double>("threshold")
                    if (threshold == null) {
                        result.error("INVALID", "threshold required", null)
                    } else {
                        prefs.edit().putFloat(flutterKey(FILTER_THRESHOLD_KEY), threshold.toFloat()).apply()
                        result.success(null)
                    }
                }
                "setStrictMode" -> {
                    val enabled = call.argument<Boolean>("enabled")
                    if (enabled == null) {
                        result.error("INVALID", "enabled required", null)
                    } else {
                        prefs.edit().putBoolean(flutterKey(FILTER_STRICT_KEY), enabled).apply()
                        result.success(null)
                    }
                }
                "getWebhookUrl" -> result.success(prefs.getString(flutterKey(WEBHOOK_URL_KEY), null))
                "setWebhookUrl" -> {
                    val url = call.argument<String>("url")
                    if (url == null) {
                        result.error("INVALID", "url required", null)
                    } else {
                        prefs.edit().putString(flutterKey(WEBHOOK_URL_KEY), url).apply()
                        result.success(null)
                    }
                }
                "getWebhookToken" -> result.success(prefs.getString(flutterKey(WEBHOOK_TOKEN_KEY), null))
                "setWebhookToken" -> {
                    val token = call.argument<String>("token")
                    if (token == null) {
                        result.error("INVALID", "token required", null)
                    } else {
                        prefs.edit().putString(flutterKey(WEBHOOK_TOKEN_KEY), token).apply()
                        result.success(null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerPartnerPin(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/partner_pin").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "isPinSet" -> result.success(!prefs.getString(flutterKey(PARTNER_PIN_KEY), null).isNullOrEmpty())
                "setPin" -> {
                    val pin = call.argument<String>("pin")
                    if (pin == null) {
                        result.error("INVALID", "pin required", null)
                    } else {
                        prefs.edit().putString(flutterKey(PARTNER_PIN_KEY), pin).apply()
                        result.success(null)
                    }
                }
                "verifyPin" -> {
                    val pin = call.argument<String>("pin")
                    result.success(pin != null && pin == prefs.getString(flutterKey(PARTNER_PIN_KEY), null))
                }
                "clearPin" -> {
                    prefs.edit().remove(flutterKey(PARTNER_PIN_KEY)).apply()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerDeviceAdmin(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        activity: MainActivity,
    ) {
        MethodChannel(messenger, "com.tpeapp/device_admin").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(activity.applicationContext)
            when (call.method) {
                "isAdminActive" -> result.success(prefs.getBoolean(flutterKey(ADMIN_ACTIVE_KEY), false))
                "requestActivation" -> {
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }
                    result.success(null)
                }
                "deactivate" -> {
                    val pin = call.argument<String>("pin")
                    val stored = prefs.getString(flutterKey(ADMIN_PIN_KEY), null)
                    val ok = pin != null && pin == stored
                    if (ok) {
                        prefs.edit().putBoolean(flutterKey(ADMIN_ACTIVE_KEY), false).apply()
                    }
                    result.success(ok)
                }
                "isPinSet" -> result.success(!prefs.getString(flutterKey(ADMIN_PIN_KEY), null).isNullOrEmpty())
                "setPin" -> {
                    val pin = call.argument<String>("pin")
                    if (pin == null) {
                        result.error("INVALID", "pin required", null)
                    } else {
                        prefs.edit()
                            .putString(flutterKey(ADMIN_PIN_KEY), pin)
                            .putBoolean(flutterKey(ADMIN_ACTIVE_KEY), true)
                            .apply()
                        result.success(null)
                    }
                }
                "verifyPin" -> {
                    val pin = call.argument<String>("pin")
                    result.success(pin != null && pin == prefs.getString(flutterKey(ADMIN_PIN_KEY), null))
                }
                "clearPin" -> {
                    prefs.edit().remove(flutterKey(ADMIN_PIN_KEY)).apply()
                    result.success(null)
                }
                "blockUninstall" -> result.success(null)
                else -> result.notImplemented()
            }
        }
    }

    private fun registerMqttEvents(messenger: io.flutter.plugin.common.BinaryMessenger) {
        EventChannel(messenger, "com.tpeapp/mqtt_events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) = Unit

            override fun onCancel(arguments: Any?) = Unit
        })
    }

    private fun registerRemoteControl(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/remote_control").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "getInjectionMode" -> result.success(prefs.getString(flutterKey(INJECTION_MODE_KEY), "auto"))
                "setInjectionMode" -> {
                    val mode = call.argument<String>("mode")
                    if (mode == null) {
                        result.error("INVALID", "mode required", null)
                    } else {
                        prefs.edit().putString(flutterKey(INJECTION_MODE_KEY), mode).apply()
                        result.success(null)
                    }
                }
                "isRootAvailable" -> result.success(isRootAvailable())
                else -> result.notImplemented()
            }
        }
    }

    private fun registerTextReplacement(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/text_replacement").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "getDict" -> result.success(prefs.getString(flutterKey(TEXT_REPLACEMENT_KEY), "") ?: "")
                "setDict" -> {
                    val json = call.argument<String>("json") ?: ""
                    prefs.edit().putString(flutterKey(TEXT_REPLACEMENT_KEY), json).apply()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerPasswordVault(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/password_vault").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "getEntries" -> result.success(getEntriesForFlutter(prefs))
                "revealPassword" -> {
                    val id = call.argument<String>("id")
                    result.success(revealPassword(prefs, id))
                }
                "addEntry" -> {
                    val id = UUID.randomUUID().toString()
                    val entry = JSONObject().apply {
                        put("id", id)
                        put("site", call.argument<String>("site") ?: "")
                        put("username", call.argument<String>("username") ?: "")
                        put("password", call.argument<String>("password") ?: "")
                        put("notes", call.argument<String>("notes") ?: "")
                        put("lockedUntil", 0L)
                    }
                    val items = loadVaultEntries(prefs)
                    items.put(entry)
                    saveVaultEntries(prefs, items)
                    result.success(id)
                }
                "updateEntry" -> {
                    val id = call.argument<String>("id")
                    val items = loadVaultEntries(prefs)
                    var updated = false
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        if (item.optString("id") != id) continue
                        updateIfPresent(call, "site") { item.put("site", it) }
                        updateIfPresent(call, "username") { item.put("username", it) }
                        updateIfPresent(call, "password") { item.put("password", it) }
                        updateIfPresent(call, "notes") { item.put("notes", it) }
                        updated = true
                        break
                    }
                    if (updated) saveVaultEntries(prefs, items)
                    result.success(updated)
                }
                "deleteEntry" -> {
                    val id = call.argument<String>("id")
                    val items = loadVaultEntries(prefs)
                    val filtered = JSONArray()
                    var deleted = false
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        if (item.optString("id") == id) {
                            deleted = true
                        } else {
                            filtered.put(item)
                        }
                    }
                    if (deleted) saveVaultEntries(prefs, filtered)
                    result.success(deleted)
                }
                "lockEntry" -> {
                    val id = call.argument<String>("id")
                    val durationMs = call.argument<Number>("durationMs")?.toLong() ?: 0L
                    val items = loadVaultEntries(prefs)
                    val until = System.currentTimeMillis() + durationMs
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        if (item.optString("id") == id) {
                            item.put("lockedUntil", until)
                            break
                        }
                    }
                    saveVaultEntries(prefs, items)
                    result.success(null)
                }
                "lockAll" -> {
                    val durationMs = call.argument<Number>("durationMs")?.toLong() ?: 0L
                    val until = System.currentTimeMillis() + durationMs
                    val items = loadVaultEntries(prefs)
                    for (index in 0 until items.length()) {
                        items.getJSONObject(index).put("lockedUntil", until)
                    }
                    saveVaultEntries(prefs, items)
                    result.success(null)
                }
                "importEntries" -> {
                    val rawEntries = call.argument<List<Map<String, Any?>>>("entries") ?: emptyList()
                    val items = loadVaultEntries(prefs)
                    val existingPairs = mutableSetOf<String>()
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        existingPairs += item.optString("site") + "\u0000" + item.optString("username")
                    }
                    var inserted = 0
                    for (raw in rawEntries) {
                        val site = raw["site"] as? String ?: continue
                        val username = raw["username"] as? String ?: continue
                        val password = raw["password"] as? String ?: continue
                        val key = site + "\u0000" + username
                        if (!existingPairs.add(key)) continue
                        items.put(JSONObject().apply {
                            put("id", UUID.randomUUID().toString())
                            put("site", site)
                            put("username", username)
                            put("password", password)
                            put("notes", raw["notes"] as? String ?: "")
                            put("lockedUntil", 0L)
                        })
                        inserted += 1
                    }
                    saveVaultEntries(prefs, items)
                    result.success(inserted)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerNoOpMethods(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        channelName: String,
    ) {
        MethodChannel(messenger, channelName).setMethodCallHandler { _, result ->
            result.success(null)
        }
    }

    private fun flutterPrefs(context: Context) =
        context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)

    private fun flutterKey(key: String) = "flutter.$key"

    private fun loadVaultEntries(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(flutterKey(VAULT_PREFS_KEY), null)
        return if (raw.isNullOrBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun saveVaultEntries(prefs: android.content.SharedPreferences, items: JSONArray) {
        prefs.edit().putString(flutterKey(VAULT_PREFS_KEY), items.toString()).apply()
    }

    private fun getEntriesForFlutter(prefs: android.content.SharedPreferences): List<Map<String, Any>> {
        val items = loadVaultEntries(prefs)
        val result = ArrayList<Map<String, Any>>(items.length())
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            result += mapOf(
                "id" to item.optString("id"),
                "site" to item.optString("site"),
                "username" to item.optString("username"),
                "notes" to item.optString("notes"),
                "lockedUntil" to item.optLong("lockedUntil", 0L),
            )
        }
        return result
    }

    private fun revealPassword(prefs: android.content.SharedPreferences, id: String?): String? {
        if (id == null) return null
        val items = loadVaultEntries(prefs)
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            if (item.optString("id") != id) continue
            if (item.optLong("lockedUntil", 0L) > System.currentTimeMillis()) return null
            return item.optString("password")
        }
        return null
    }

    private fun updateIfPresent(call: MethodCall, key: String, block: (String) -> Unit) {
        val value = call.argument<String>(key) ?: return
        block(value)
    }

    private fun isRootAvailable(): Boolean {
        cachedRootAvailable?.let { return it }
        val available = runCatching {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                false
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.exitValue() == 0 && output.contains("uid=0")
            }
        }.getOrDefault(false)
        cachedRootAvailable = available
        return available
    }
}