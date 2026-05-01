import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/ble` MethodChannel.
///
/// Sends commands to the native [BleChannel] which delegates to
/// [LovenseManager] and [PavlokManager] on the Kotlin side.
///
/// The underlying BLE connections are shared with [ConsequenceDispatcher]
/// (punishment / reward stimuli) — commands sent from Dart coexist safely
/// with FCM-triggered commands.
class BleChannel {
  BleChannel._();

  static const _channel = MethodChannel('com.tpeapp/ble');

  // ── Lovense ──────────────────────────────────────────────────────────

  /// Starts a BLE scan for the first Lovense toy in range.
  static Future<void> lovenseScan() => _channel.invokeMethod('lovense.scan');

  /// Stops the Lovense BLE scan.
  static Future<void> lovenseStopScan() =>
      _channel.invokeMethod('lovense.stopScan');

  /// Disconnects from the Lovense toy.
  static Future<void> lovenseDisconnect() =>
      _channel.invokeMethod('lovense.disconnect');

  /// Sets vibration intensity (0–20).
  static Future<void> lovenseVibrate(int level) =>
      _channel.invokeMethod('lovense.vibrate', {'level': level.clamp(0, 20)});

  /// Sets rotation intensity (0–20).
  static Future<void> lovenseRotate(int level) =>
      _channel.invokeMethod('lovense.rotate', {'level': level.clamp(0, 20)});

  /// Sets air-pump intensity (0–3).
  static Future<void> lovensePump(int level) =>
      _channel.invokeMethod('lovense.pump', {'level': level.clamp(0, 3)});

  /// Stops all active functions on the Lovense toy.
  static Future<void> lovenseStopAll() =>
      _channel.invokeMethod('lovense.stopAll');

  /// Requests the Lovense toy's battery level.
  static Future<void> lovenseBattery() =>
      _channel.invokeMethod('lovense.battery');

  // ── Pavlok ──────────────────────────────────────────────────────────

  /// Starts a BLE scan for a Pavlok wristband in range.
  static Future<void> pavlokScan() => _channel.invokeMethod('pavlok.scan');

  /// Stops the Pavlok BLE scan.
  static Future<void> pavlokStopScan() =>
      _channel.invokeMethod('pavlok.stopScan');

  /// Disconnects from the Pavlok wristband.
  static Future<void> pavlokDisconnect() =>
      _channel.invokeMethod('pavlok.disconnect');

  /// Delivers an electric zap.
  /// [intensity] 0–255; [durationMs] in milliseconds.
  static Future<void> pavlokZap({int intensity = 64, int durationMs = 500}) =>
      _channel.invokeMethod(
          'pavlok.zap', {'intensity': intensity, 'durationMs': durationMs});

  /// Activates wristband vibration.
  static Future<void> pavlokVibrate(
          {int intensity = 128, int durationMs = 2000}) =>
      _channel.invokeMethod('pavlok.vibrate',
          {'intensity': intensity, 'durationMs': durationMs});

  /// Triggers an audible beep.
  static Future<void> pavlokBeep(
          {int intensity = 128, int durationMs = 1000}) =>
      _channel.invokeMethod(
          'pavlok.beep', {'intensity': intensity, 'durationMs': durationMs});

  /// Stops all active Pavlok stimulation.
  static Future<void> pavlokStopAll() =>
      _channel.invokeMethod('pavlok.stopAll');
}
