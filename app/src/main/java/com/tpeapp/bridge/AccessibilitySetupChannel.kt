package com.tpeapp.bridge

import android.content.Intent
import android.provider.Settings
import com.tpeapp.accessibility.AccessibilityServiceKeeper
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
                "isEnabled" -> result.success(AccessibilityServiceKeeper.isFullyEnabled(activity))
                "getStatus" -> result.success(AccessibilityServiceKeeper.getStatus(activity).toMap())
                "ensurePersistent" -> result.success(
                    AccessibilityServiceKeeper.ensurePersistent(activity, "flutter_channel").toMap()
                )
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
}