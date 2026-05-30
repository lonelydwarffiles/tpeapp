import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/remote_control` MethodChannel.
///
/// Exposes the gesture-injection mode selector and root-availability query
/// to Flutter so that the Settings screen can present a user-facing toggle.
///
/// ## Injection modes
///
/// | Mode            | Behaviour                                                       |
/// |-----------------|-----------------------------------------------------------------|
/// | `auto`          | Try root first if available; fall back to AccessibilityService  |
/// | `root`          | Always use `su -c input tap X Y` (rooted devices only)         |
/// | `accessibility` | Always use the standalone TPE Accessibility companion service   |
///
/// The selected mode is persisted in SharedPreferences by the native layer
/// and takes effect immediately on the next `injectTap` call — no app
/// restart is required.
class RemoteControlChannel {
  RemoteControlChannel._();

  static const _channel = MethodChannel('com.tpeapp/remote_control');

  // ── Injection mode constants ─────────────────────────────────────────────

  /// Auto-detect: use root when available, otherwise fall back to
  /// the AccessibilityService.
  static const modeAuto = 'auto';

  /// Force root: always attempt `su -c input tap X Y`.
  static const modeRoot = 'root';

  /// Force accessibility: always use the standalone TPE Accessibility
  /// companion service (no root required).
  static const modeAccessibility = 'accessibility';

  // ── API ──────────────────────────────────────────────────────────────────

  /// Returns the currently persisted injection mode.
  ///
  /// One of [modeAuto], [modeRoot], or [modeAccessibility].
  /// Defaults to [modeAuto] if not yet configured.
  static Future<String> getInjectionMode() async {
    final mode = await _channel.invokeMethod<String>('getInjectionMode');
    return mode ?? modeAuto;
  }

  /// Persists [mode] as the active injection strategy.
  ///
  /// [mode] must be one of [modeAuto], [modeRoot], or [modeAccessibility].
  /// Throws a [PlatformException] for unrecognised values.
  static Future<void> setInjectionMode(String mode) =>
      _channel.invokeMethod<void>('setInjectionMode', {'mode': mode});

  /// Returns `true` if the device is rooted and the superuser manager has
  /// granted execution rights to this process.
  ///
  /// The first call may block briefly while the native layer waits for a
  /// superuser-prompt response (up to ~2 seconds).  Subsequent calls return the
  /// cached result immediately.
  static Future<bool> isRootAvailable() async {
    final available = await _channel.invokeMethod<bool>('isRootAvailable');
    return available ?? false;
  }
}
