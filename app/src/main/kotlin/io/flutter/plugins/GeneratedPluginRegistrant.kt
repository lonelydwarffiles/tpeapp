package io.flutter.plugins

import io.flutter.embedding.engine.FlutterEngine

/**
 * Fallback registrant for hybrid host builds where Flutter does not generate
 * io.flutter.plugins.GeneratedPluginRegistrant in the APK.
 *
 * BLE command execution in this app uses native channels, so startup should not
 * fail if Flutter plugin auto-registration artifacts are absent in this build
 * flow.
 */
object GeneratedPluginRegistrant {
    @JvmStatic
    fun registerWith(@Suppress("UNUSED_PARAMETER") flutterEngine: FlutterEngine) {
        // Intentionally no-op for this host build path.
    }
}
