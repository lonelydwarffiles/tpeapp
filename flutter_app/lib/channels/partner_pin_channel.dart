import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/partner_pin` MethodChannel.
///
/// Use this for standalone PIN management screens; use [DeviceAdminChannel]
/// when PIN operations are inline with Device Admin operations.
class PartnerPinChannel {
  PartnerPinChannel._();

  static const _channel = MethodChannel('com.tpeapp/partner_pin');

  static Future<bool> isPinSet() async =>
      await _channel.invokeMethod<bool>('isPinSet') ?? false;

  static Future<void> setPin(String pin) =>
      _channel.invokeMethod('setPin', {'pin': pin});

  static Future<bool> verifyPin(String pin) async =>
      await _channel.invokeMethod<bool>('verifyPin', {'pin': pin}) ?? false;

  static Future<void> clearPin() => _channel.invokeMethod('clearPin');
}
