import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/screen_share` MethodChannel.
///
/// Bridges the Flutter-side [ScreenShareService] (which manages the WebRTC
/// peer connection and DataChannel) with the native Kotlin layer.
///
/// Methods:
///  - [injectTap] — forwards normalised tap coordinates received from the
///    remote-control DataChannel to the native [RemoteControlService]
///    (AccessibilityService) or falls back to `su -c input tap X Y`.
///  - [stopNativeScreenShare] — stops the companion native [ScreencastService]
///    if it was started separately.
class ScreenShareChannel {
  ScreenShareChannel._();

  static const _channel = MethodChannel('com.tpeapp/screen_share');

  /// Inject a tap gesture at normalised coordinates [x], [y] (both 0.0–1.0).
  ///
  /// The native handler scales these to physical-pixel coordinates, then tries
  /// [RemoteControlService.dispatchGesture] first.  If the AccessibilityService
  /// is not running (e.g., permission not granted), it falls back to executing
  /// `su -c input tap X Y` via [RemoteInputDispatcher] for rooted devices.
  static Future<void> injectTap(double x, double y) =>
      _channel.invokeMethod<void>('injectTap', {'x': x, 'y': y});

  /// Stop the native [ScreencastService] if running.
  static Future<void> stopNativeScreenShare() =>
      _channel.invokeMethod<void>('stopNativeScreenShare');
}
