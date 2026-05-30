import 'dart:async';

import 'package:flutter/services.dart';

import '../services/ble_service.dart';

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

  static final BleService _service = BleService();
  static const MethodChannel _native = MethodChannel('com.tpeapp/ble');
  static Timer? _lovenseTimedStopTimer;

  // A/B switch for Lovense path: native bridge (SDK-ready) vs Dart BLE.
  static bool _useNativeLovense = false;

  static bool get useNativeLovense => _useNativeLovense;

  static String get lovensePathLabel =>
      _useNativeLovense ? 'native bridge' : 'direct BLE';

  static void setLovensePath({required bool useNative}) {
    _useNativeLovense = useNative;
  }

  // ── Lovense ──────────────────────────────────────────────────────────

  /// Starts a BLE scan for the first Lovense toy in range.
  static Future<void> lovenseScan() async {
    if (_useNativeLovense) {
      await _invokeLovenseNative('lovense.scan', () => _service.lovenseScan());
      return;
    }
    await _service.lovenseScan();
  }

  /// Stops the Lovense BLE scan.
  static Future<void> lovenseStopScan() async {
    if (_useNativeLovense) {
      await _invokeLovenseNative('lovense.stopScan', () => _service.lovenseStopScan());
      return;
    }
    await _service.lovenseStopScan();
  }

  /// Disconnects from the Lovense toy.
  static Future<void> lovenseDisconnect() async {
    if (_useNativeLovense) {
      await _invokeLovenseNative('lovense.disconnect', () => _service.lovenseDisconnect());
      return;
    }
    await _service.lovenseDisconnect();
  }

  /// Sets vibration intensity (0–20).
  static Future<void> lovenseVibrate(int level) async {
    final safeLevel = level.clamp(0, 20);
    if (_useNativeLovense) {
      await _invokeLovenseNative(
        'lovense.vibrate',
        () => _service.lovenseVibrate(safeLevel),
        {'level': safeLevel},
      );
      return;
    }
    await _service.lovenseVibrate(safeLevel);
  }

  /// Sets vibration intensity for a bounded duration, then stops automatically.
  static Future<void> lovenseVibrateFor({
    required int level,
    required int durationMs,
  }) async {
    await lovenseVibrate(level);
    _scheduleLovenseStop(durationMs.clamp(0, 1200000));
  }

  /// Sets rotation intensity (0–20).
  static Future<void> lovenseRotate(int level) async {
    final safeLevel = level.clamp(0, 20);
    if (_useNativeLovense) {
      await _invokeLovenseNative(
        'lovense.rotate',
        () => _service.lovenseRotate(safeLevel),
        {'level': safeLevel},
      );
      return;
    }
    await _service.lovenseRotate(safeLevel);
  }

  /// Sets rotation intensity for a bounded duration, then stops automatically.
  static Future<void> lovenseRotateFor({
    required int level,
    required int durationMs,
  }) async {
    await lovenseRotate(level);
    _scheduleLovenseStop(durationMs.clamp(0, 1200000));
  }

  /// Sets air-pump intensity (0–3).
  static Future<void> lovensePump(int level) async {
    final safeLevel = level.clamp(0, 3);
    if (_useNativeLovense) {
      await _invokeLovenseNative(
        'lovense.pump',
        () => _service.lovensePump(safeLevel),
        {'level': safeLevel},
      );
      return;
    }
    await _service.lovensePump(safeLevel);
  }

  /// Sets pump intensity for a bounded duration, then stops automatically.
  static Future<void> lovensePumpFor({
    required int level,
    required int durationMs,
  }) async {
    await lovensePump(level);
    _scheduleLovenseStop(durationMs.clamp(0, 1200000));
  }

  /// Stops all active functions on the Lovense toy.
  static Future<void> lovenseStopAll() async {
    _lovenseTimedStopTimer?.cancel();
    _lovenseTimedStopTimer = null;
    if (_useNativeLovense) {
      await _invokeLovenseNative('lovense.stopAll', () => _service.lovenseStopAll());
      return;
    }
    await _service.lovenseStopAll();
  }

  /// Requests the Lovense toy's battery level.
  static Future<void> lovenseBattery() async {
    if (_useNativeLovense) {
      await _invokeLovenseNative('lovense.battery', () => _service.lovenseBattery());
      return;
    }
    await _service.lovenseBattery();
  }

  // ── Pavlok ──────────────────────────────────────────────────────────

  /// Starts a BLE scan for a Pavlok wristband in range.
  static Future<void> pavlokScan() => _service.pavlokScan();

  /// Stops the Pavlok BLE scan.
  static Future<void> pavlokStopScan() => _service.pavlokStopScan();

  /// Disconnects from the Pavlok wristband.
  static Future<void> pavlokDisconnect() => _service.pavlokDisconnect();

  /// Delivers an electric zap.
  /// [intensity] 0–255; [durationMs] in milliseconds.
  static Future<void> pavlokZap({int intensity = 64, int durationMs = 500}) =>
      _service.pavlokZap(intensity: intensity, durationMs: durationMs);

  /// Activates wristband vibration.
  static Future<void> pavlokVibrate(
          {int intensity = 128, int durationMs = 2000}) =>
      _service.pavlokVibrate(intensity: intensity, durationMs: durationMs);

  /// Triggers an audible beep.
  static Future<void> pavlokBeep(
          {int intensity = 128, int durationMs = 1000}) =>
      _service.pavlokBeep(intensity: intensity, durationMs: durationMs);

  /// Stops all active Pavlok stimulation.
  static Future<void> pavlokStopAll() => _service.pavlokStopAll();

  static Future<void> _invokeLovenseNative(
    String method,
    Future<void> Function() fallback, [
    Map<String, Object?>? arguments,
  ]) async {
    try {
      await _native.invokeMethod<void>(method, arguments);
    } on MissingPluginException {
      await fallback();
    } on PlatformException {
      await fallback();
    }
  }

  static void _scheduleLovenseStop(int durationMs) {
    _lovenseTimedStopTimer?.cancel();
    _lovenseTimedStopTimer = null;
    if (durationMs <= 0) {
      return;
    }
    _lovenseTimedStopTimer = Timer(Duration(milliseconds: durationMs), () {
      lovenseStopAll();
    });
  }
}
