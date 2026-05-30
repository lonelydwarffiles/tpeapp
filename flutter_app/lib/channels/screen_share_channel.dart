import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/screen_share` MethodChannel.
///
/// Bridges the Flutter-side [ScreenShareService] (which manages the WebRTC
/// peer connection and DataChannel) with the native Kotlin layer.
///
/// Methods:
///  - [injectTap] — forwards normalised tap coordinates received from the
///    remote-control DataChannel to the standalone native accessibility
///    companion service or falls back to `su -c input tap X Y`.
///  - [stopNativeScreenShare] — stops the companion native [ScreencastService]
///    if it was started separately.
class ScreenShareChannel {
  ScreenShareChannel._();

  static const _channel = MethodChannel('com.tpeapp/screen_share');

  /// Inject a tap gesture at normalised coordinates [x], [y] (both 0.0–1.0).
  ///
    /// The native handler scales these to physical-pixel coordinates, then tries
    /// the standalone accessibility companion first. If that service is not
    /// running, it falls back to executing `su -c input tap X Y` for rooted
    /// devices when available.
  static Future<void> injectTap(double x, double y) =>
      _channel.invokeMethod<void>('injectTap', {'x': x, 'y': y});

  /// Stop the native [ScreencastService] if running.
  static Future<void> stopNativeScreenShare() =>
      _channel.invokeMethod<void>('stopNativeScreenShare');

    /// Sets touch lock state for API-managed remote-control sessions.
    static Future<Map<String, dynamic>> setTouchLock({
        required bool enabled,
        required String mode,
        required bool allowRemoteInput,
        String? sessionId,
        int? ttlSec,
        String? reason,
    }) async {
        final result = await _channel.invokeMethod<Map<Object?, Object?>>(
            'setTouchLock',
            {
                'enabled': enabled,
                'mode': mode,
                'allowRemoteInput': allowRemoteInput,
                'sessionId': sessionId,
                'ttlSec': ttlSec,
                'reason': reason,
            },
        );

        return result?.map(
                    (key, value) => MapEntry(
                        key?.toString() ?? '',
                        value,
                    ),
                ) ??
                const <String, dynamic>{};
    }

    /// Returns the last known native touch-lock state.
    static Future<Map<String, dynamic>> getTouchLockState() async {
        final result = await _channel
                .invokeMethod<Map<Object?, Object?>>('getTouchLockState');
        return result?.map(
                    (key, value) => MapEntry(
                        key?.toString() ?? '',
                        value,
                    ),
                ) ??
                const <String, dynamic>{};
    }
}
