import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/device_admin` MethodChannel.
///
/// Mirrors the Device Admin and partner-PIN operations exposed by
/// [DeviceAdminChannel] on the native side.
class DeviceAdminChannel {
  DeviceAdminChannel._();

  static const _channel = MethodChannel('com.tpeapp/device_admin');

  /// Returns true if the app currently holds Device Admin rights.
  static Future<bool> isAdminActive() async =>
      await _channel.invokeMethod<bool>('isAdminActive') ?? false;

  /// Launches the system Device Admin activation intent.
  static Future<void> requestActivation() =>
      _channel.invokeMethod('requestActivation');

  /// Deactivates Device Admin after verifying [pin].
  /// Returns true if the PIN matched and admin was deactivated.
  static Future<bool> deactivate(String pin) async =>
      await _channel.invokeMethod<bool>('deactivate', {'pin': pin}) ?? false;

  /// Returns true if a partner PIN has been configured.
  static Future<bool> isPinSet() async =>
      await _channel.invokeMethod<bool>('isPinSet') ?? false;

  /// Stores a new partner PIN (PBKDF2-hashed on the native side).
  static Future<void> setPin(String pin) =>
      _channel.invokeMethod('setPin', {'pin': pin});

  /// Returns true if [pin] matches the stored partner PIN.
  static Future<bool> verifyPin(String pin) async =>
      await _channel.invokeMethod<bool>('verifyPin', {'pin': pin}) ?? false;

  /// Removes the stored partner PIN (call after voluntary deactivation).
  static Future<void> clearPin() => _channel.invokeMethod('clearPin');

  /// Calls [DevicePolicyManager.setUninstallBlocked].
  static Future<void> blockUninstall({required bool block}) =>
      _channel.invokeMethod('blockUninstall', {'block': block});
}
