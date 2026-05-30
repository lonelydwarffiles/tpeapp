package com.tpeapp.bridge

import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * Native bridge for accessibility setup/status in the app module build.
 */
object AccessibilitySetupChannel {

    private const val CHANNEL = "com.hound.controller/accessibility_setup"

    fun register(messenger: BinaryMessenger, activity: FlutterFragmentActivity) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "isEnabled" -> result.success(isAnyAccessibilityServiceEnabledForPackage(activity))
                "openSettings" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { activity.startActivity(intent) }
                        .onSuccess { result.success(null) }
                        .onFailure { err ->
                            result.error("ACCESSIBILITY_SETTINGS_FAILED", err.message, null)
                        }
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun isAnyAccessibilityServiceEnabledForPackage(activity: FlutterFragmentActivity): Boolean {
        val enabled = Settings.Secure.getInt(
            activity.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabled) return false

        val services = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (services.isBlank()) return false

        val packagePrefix = "${activity.packageName.lowercase()}/"
        return services.split(':')
            .asSequence()
            .map { it.trim().lowercase() }
            .any { it.startsWith(packagePrefix) }
    }
}