import 'package:flutter/services.dart';

/// Dart client for the standalone app's accessibility setup bridge.
class AccessibilitySetupChannel {
  AccessibilitySetupChannel._();

  static const _channel =
      MethodChannel('com.hound.controller/accessibility_setup');

  static Future<bool> isEnabled() async {
    try {
      return await _channel.invokeMethod<bool>('isEnabled') ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  static Future<Map<String, dynamic>> getStatus() async {
    try {
      final raw = await _channel.invokeMapMethod<String, dynamic>('getStatus');
      return raw == null ? const {} : Map<String, dynamic>.from(raw);
    } on PlatformException {
      return const {};
    } on MissingPluginException {
      return const {};
    }
  }

  static Future<Map<String, dynamic>> ensurePersistent() async {
    try {
      final raw =
          await _channel.invokeMapMethod<String, dynamic>('ensurePersistent');
      return raw == null ? const {} : Map<String, dynamic>.from(raw);
    } on PlatformException {
      return const {};
    } on MissingPluginException {
      return const {};
    }
  }

  static Future<void> openSettings() async {
    try {
      await _channel.invokeMethod<void>('openSettings');
    } on PlatformException {
      // Keep setup flow usable even if settings intent cannot be opened.
    } on MissingPluginException {
      // Keep setup flow usable during native channel rollout mismatches.
    }
  }
}