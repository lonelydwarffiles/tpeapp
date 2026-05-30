import 'package:flutter/services.dart';

/// Dart client for the standalone app's accessibility setup bridge.
class AccessibilitySetupChannel {
  AccessibilitySetupChannel._();

    static const _channel = MethodChannel('com.hound.controller/accessibility_setup');

  static Future<bool> isEnabled() async =>
      await _channel.invokeMethod<bool>('isEnabled') ?? false;

  static Future<void> openSettings() =>
      _channel.invokeMethod<void>('openSettings');
}