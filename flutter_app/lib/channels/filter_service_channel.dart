import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/filter_service` MethodChannel.
///
/// All calls delegate to [FilterServiceChannel] on the native side, which
/// proxies into [FilterService] or its SharedPreferences keys.
class FilterServiceChannel {
  FilterServiceChannel._();

  static const _channel = MethodChannel('com.tpeapp/filter_service');

  /// Starts [FilterService] as a foreground service (idempotent).
  static Future<void> start() => _channel.invokeMethod('start');

  /// Sets the NudeNet confidence threshold in SharedPreferences.
  /// [threshold] must be in [0.0, 1.0].
  static Future<void> setThreshold(double threshold) =>
      _channel.invokeMethod('setThreshold', {'threshold': threshold});

  /// Enables or disables strict-mode content filtering.
  static Future<void> setStrictMode({required bool enabled}) =>
      _channel.invokeMethod('setStrictMode', {'enabled': enabled});

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
