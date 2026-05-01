import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/device_commands` MethodChannel.
///
/// Maps 1-to-1 to the methods in [DeviceCommandChannel] (Kotlin side).
/// All calls are fire-and-forget — they return null on success.
class DeviceCommandChannel {
  DeviceCommandChannel._();

  static const _channel = MethodChannel('com.tpeapp/device_commands');

  static Future<void> openUrl(String url) =>
      _channel.invokeMethod('openUrl', {'url': url});

  /// [level] 0–255 display brightness.
  static Future<void> setBrightness(int level) =>
      _channel.invokeMethod('setBrightness', {'level': level});

  static Future<void> screenOn() => _channel.invokeMethod('screenOn');
  static Future<void> screenOff() => _channel.invokeMethod('screenOff');

  /// [ms] screen-off timeout in milliseconds.
  static Future<void> setScreenTimeout(int ms) =>
      _channel.invokeMethod('setScreenTimeout', {'ms': ms});

  /// [stream] one of: music | ring | alarm | notification | system | voice_call
  static Future<void> setVolume(
          {required String stream, required int level, bool max = false}) =>
      _channel.invokeMethod(
          'setVolume', {'stream': stream, 'level': level, 'max': max});

  /// [mode] one of: silent | vibrate | normal
  static Future<void> setRingerMode(String mode) =>
      _channel.invokeMethod('setRingerMode', {'mode': mode});

  static Future<void> speakText(String text) =>
      _channel.invokeMethod('speakText', {'text': text});

  static Future<void> lockDevice() => _channel.invokeMethod('lockDevice');
  static Future<void> takeScreenshot() =>
      _channel.invokeMethod('takeScreenshot');

  static Future<void> setFlashlight({required bool on}) =>
      _channel.invokeMethod('setFlashlight', {'on': on});

  static Future<void> getLocation() => _channel.invokeMethod('getLocation');

  static Future<void> sendNotification({
    required String title,
    required String body,
    String? channelId,
  }) =>
      _channel.invokeMethod(
          'sendNotification', {'title': title, 'body': body, 'channelId': channelId});

  /// [policy] one of: total_silence | priority | alarms_only | all
  static Future<void> setDnd(String policy) =>
      _channel.invokeMethod('setDnd', {'policy': policy});

  static Future<void> setWallpaper(String url) =>
      _channel.invokeMethod('setWallpaper', {'url': url});

  static Future<void> showOverlay({
    required String title,
    required String message,
    String? imageUrl,
  }) =>
      _channel.invokeMethod('showOverlay',
          {'title': title, 'message': message, 'imageUrl': imageUrl});

  static Future<void> suspendApp(String packageName) =>
      _channel.invokeMethod('suspendApp', {'packageName': packageName});

  static Future<void> unsuspendApp(String packageName) =>
      _channel.invokeMethod('unsuspendApp', {'packageName': packageName});
}
