import 'package:flutter/services.dart';
import 'dart:convert';

/// Dart client for the `com.hound.controller/filter_service` MethodChannel.
///
/// All calls delegate to [FilterServiceChannel] on the native side, which
/// proxies into [FilterService] or its SharedPreferences keys.
class FilterServiceChannel {
  FilterServiceChannel._();

  static const _channel = MethodChannel('com.hound.controller/filter_service');

  /// Starts [FilterService] as a foreground service (idempotent).
  static Future<void> start() => _channel.invokeMethod('start');

  /// Sets the NudeNet confidence threshold in SharedPreferences.
  /// [threshold] must be in [0.0, 1.0].
  static Future<void> setThreshold(double threshold) =>
      _channel.invokeMethod('setThreshold', {'threshold': threshold});

  /// Enables or disables strict-mode content filtering.
  static Future<void> setStrictMode({required bool enabled}) =>
      _channel.invokeMethod('setStrictMode', {'enabled': enabled});

  static Future<void> setMediaFilterMode(String mode) =>
      _channel.invokeMethod('setMediaFilterMode', {'mode': mode});

  static Future<void> setMediaCensorStyle(String style) =>
      _channel.invokeMethod('setMediaCensorStyle', {'style': style});

  static Future<void> setMediaStrictPackages(List<String> packages) =>
      _channel.invokeMethod('setMediaStrictPackages', {'packages': packages});

  static Future<void> setMediaMaxInFlight(int maxInFlight) =>
      _channel.invokeMethod('setMediaMaxInFlight', {'maxInFlight': maxInFlight});

  static Future<Map<String, dynamic>> getMediaFilterConfig() async {
    final raw = await _channel.invokeMethod<String>('getMediaFilterConfig');
    if (raw == null || raw.trim().isEmpty) return <String, dynamic>{};
    final decoded = jsonDecode(raw);
    if (decoded is Map<String, dynamic>) return decoded;
    return <String, dynamic>{};
  }

  /// Returns the currently configured webhook URL, or null if not set.
  static Future<String?> getWebhookUrl() =>
      _channel.invokeMethod<String>('getWebhookUrl');

  /// Persists a new webhook URL.
  static Future<void> setWebhookUrl(String url) =>
      _channel.invokeMethod('setWebhookUrl', {'url': url});

  /// Returns the currently configured webhook bearer token, or null.
  static Future<String?> getWebhookToken() =>
      _channel.invokeMethod<String>('getWebhookToken');

  /// Persists a new webhook bearer token.
  static Future<void> setWebhookToken(String token) =>
      _channel.invokeMethod('setWebhookToken', {'token': token});
}

