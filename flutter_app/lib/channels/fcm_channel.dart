import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/fcm` MethodChannel and
/// `com.tpeapp/fcm_events` EventChannel.
///
/// The EventChannel delivers FCM data payloads that [PartnerFcmService]
/// chooses to forward to the Flutter layer (e.g. REQUEST_CHECKIN, SHOW_AFFIRMATION).
class FcmChannel {
  FcmChannel._();

  static const _channel = MethodChannel('com.tpeapp/fcm');
  static const _events = EventChannel('com.tpeapp/fcm_events');

  /// Returns the cached FCM registration token, or null if not yet set.
  static Future<String?> getToken() =>
      _channel.invokeMethod<String>('getToken');

  /// Forces a fresh FCM token fetch.  Returns the new token on success.
  static Future<String?> refresh() =>
      _channel.invokeMethod<String>('refresh');

  /// Stream of FCM data payloads forwarded from [PartnerFcmService].
  /// Each event is a `Map<String, String>` matching the FCM data map.
  static Stream<Map<String, String>> get events =>
      _events.receiveBroadcastStream().map((event) =>
          Map<String, String>.from(event as Map));
}
