package com.tpeapp.bridge

import android.content.Context
import android.util.Log
import com.tpeapp.vault.PasswordVaultManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * PasswordVaultChannel — MethodChannel bridge for [PasswordVaultManager].
 *
 * Channel name: `com.tpeapp/password_vault`
 *
 * Methods exposed to Dart:
 *  - `getEntries`                                         → List<Map>  (passwords redacted)
 *  - `revealPassword`  (id: String)                       → String?    (null = locked / not found)
 *  - `addEntry`        (site, username, password, notes)  → String     (new entry id)
 *  - `updateEntry`     (id, site?, username?, password?, notes?) → Boolean
 *  - `deleteEntry`     (id: String)                       → Boolean
 *  - `lockEntry`       (id: String, durationMs: Long)     → void
 *  - `lockAll`         (durationMs: Long)                 → void
 */
object PasswordVaultChannel {

    private const val TAG     = "PasswordVaultChannel"
    private const val CHANNEL = "com.tpeapp/password_vault"

    fun register(messenger: BinaryMessenger, context: Context) {
        val vault = PasswordVaultManager(context.applicationContext)

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {

                "getEntries" -> {
                    val entries = vault.getEntries().map { obj ->
                        mapOf(
                            "id"          to obj.getString("id"),
                            "site"        to obj.optString("site"),
                            "username"    to obj.optString("username"),
                            "notes"       to obj.optString("notes"),
                            "lockedUntil" to obj.optLong("lockedUntil", 0L),
                        )
                    }
                    result.success(entries)
                }

                "revealPassword" -> {
                    val id = call.argument<String>("id")
                        ?: return@setMethodCallHandler result.error("INVALID", "id required", null)
                    result.success(vault.revealPassword(context.applicationContext, id))
                }

                "addEntry" -> {
                    val site     = call.argument<String>("site")     ?: ""
                    val username = call.argument<String>("username") ?: ""
                    val password = call.argument<String>("password")
                        ?: return@setMethodCallHandler result.error("INVALID", "password required", null)
                    val notes    = call.argument<String>("notes")    ?: ""
                    val id = vault.addEntry(site, username, password, notes)
                    Log.i(TAG, "addEntry via channel: id=$id")
                    result.success(id)
                }

                "updateEntry" -> {
                    val id = call.argument<String>("id")
                        ?: return@setMethodCallHandler result.error("INVALID", "id required", null)
                    val updated = vault.updateEntry(
                        id       = id,
                        site     = call.argument("site"),
                        username = call.argument("username"),
                        password = call.argument("password"),
                        notes    = call.argument("notes"),
                    )
                    result.success(updated)
                }

                "deleteEntry" -> {
                    val id = call.argument<String>("id")
                        ?: return@setMethodCallHandler result.error("INVALID", "id required", null)
                    result.success(vault.deleteEntry(id))
                }

                "lockEntry" -> {
                    val id         = call.argument<String>("id")
                        ?: return@setMethodCallHandler result.error("INVALID", "id required", null)
                    val durationMs = call.argument<Long>("durationMs") ?: 0L
                    vault.lockEntry(id, durationMs)
                    result.success(null)
                }

                "lockAll" -> {
                    val durationMs = call.argument<Long>("durationMs") ?: 0L
                    vault.lockAll(durationMs)
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }
}
