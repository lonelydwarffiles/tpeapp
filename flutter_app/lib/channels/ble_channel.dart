import 'dart:async';

import 'package:flutter/services.dart';

import '../services/ble_service.dart';

/// Dart client for the `com.hound.controller/ble` MethodChannel.
///
/// Sends commands to the native [BleChannel] which delegates to
/// [LovenseManager] and [PavlokManager] on the Kotlin side.
///
/// The underlying BLE connections are shared with [ConsequenceDispatcher]
/// (punishment / reward stimuli) â€” commands sent from Dart coexist safely
/// with FCM-triggered commands.
class BleChannel {
  BleChannel._();

  static final BleService _service = BleService();
  static const MethodChannel _native = MethodChannel('com.hound.controller/ble');
  static Timer? _lovenseTimedStopTimer;

  // A/B switch for Lovense path: native bridge (SDK-ready) vs Dart BLE.
  static bool _useNativeLovense = true;
  static bool _useNativePavlok = true;

  static bool get useNativeLovense => _useNativeLovense;
  static bool get useNativePavlok => _useNativePavlok;

  static String get lovensePathLabel =>
      _useNativeLovense ? 'native bridge' : 'direct BLE';

  static String get pavlokPathLabel =>
      _useNativePavlok ? 'native bridge' : 'direct BLE';

  static void setLovensePath({required bool useNative}) {
    _useNativeLovense = useNative;
  }

  static void setPavlokPath({required bool useNative}) {
    _useNativePavlok = useNative;
  }

  /// Triggers native restore of saved Lovense/Pavlok pairings.
  static Future<void> restorePairings() async {
    if (_useNativeLovense || _useNativePavlok) {
      try {
        await _native.invokeMethod<void>('restorePairings');
      } on PlatformException {
        // Keep startup resilient if native restore is unavailable.
      } on MissingPluginException {
        // Keep startup resilient during channel rollout mismatches.
      }
    }
  }

  // â”€â”€ Lovense â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  /// Starts a BLE scan for the first Lovense toy in range.
  static Future<void> lovenseScan() async {
    if (_useNativeLovense) {
      await _invokeLovenseNative('lovense.scan', () => _service.lovenseScan());
      return;
    }
    await _service.lovenseScan();
  }

  /// Scans Lovense candidates via native BLE without auto-connect.
  static Future<List<Map<String, dynamic>>> lovenseScanCandidatesNative({
    int timeoutMs = 8000,
  }) async {
    final raw = await _native.invokeMethod<List<dynamic>>(
      'lovense.scanCandidates',
      {'timeoutMs': timeoutMs},
    );
    return _asNativeCandidateList(raw);
  }

  /// Connects Lovense to a selected native BLE address.
  static Future<void> lovenseConnectAddressNative(String address) async {
    await _native.invokeMethod<void>('lovense.connectAddress', {'address': address});
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

  static Future<bool> lovenseIsConnectedNative() async {
    final connected = await _native.invokeMethod<bool>('lovense.isConnected');
    return connected ?? false;
  }

  /// Sets vibration intensity (0â€“20).
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

  /// Sets rotation intensity (0â€“20).
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

  /// Sets air-pump intensity (0â€“3).
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

  /// Reads the Lovense battery level from the standard BLE Battery Service.
  static Future<void> lovenseReadBatteryLevel() async {
    if (_useNativeLovense) {
      await _invokeLovenseNative(
        'lovense.readBatteryLevel',
        () async {
          await _service.refreshLovenseBatteryLevel();
        },
      );
      return;
    }
    await _service.refreshLovenseBatteryLevel();
  }

  // â”€â”€ Pavlok â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  /// Starts a BLE scan for a Pavlok wristband in range.
  static Future<void> pavlokScan() async {
    if (_useNativePavlok) {
      await _invokeNative('pavlok.scan', () => _service.pavlokScan());
      return;
    }
    await _service.pavlokScan();
  }

  /// Scans Pavlok candidates via native BLE without auto-connect.
  static Future<List<Map<String, dynamic>>> pavlokScanCandidatesNative({
    int timeoutMs = 8000,
  }) async {
    final raw = await _native.invokeMethod<List<dynamic>>(
      'pavlok.scanCandidates',
      {'timeoutMs': timeoutMs},
    );
    return _asNativeCandidateList(raw);
  }

  /// Connects Pavlok to a selected native BLE address.
  static Future<void> pavlokConnectAddressNative(String address) async {
    await _native.invokeMethod<void>('pavlok.connectAddress', {'address': address});
  }

  /// Stops the Pavlok BLE scan.
  static Future<void> pavlokStopScan() async {
    if (_useNativePavlok) {
      await _invokeNative('pavlok.stopScan', () => _service.pavlokStopScan());
      return;
    }
    await _service.pavlokStopScan();
  }

  /// Disconnects from the Pavlok wristband.
  static Future<void> pavlokDisconnect() async {
    if (_useNativePavlok) {
      await _invokeNative('pavlok.disconnect', () => _service.pavlokDisconnect());
      return;
    }
    await _service.pavlokDisconnect();
  }

  static Future<bool> pavlokIsConnectedNative() async {
    final connected = await _native.invokeMethod<bool>('pavlok.isConnected');
    return connected ?? false;
  }

  /// Delivers an electric zap.
  /// [intensity] 0â€“255; [durationMs] in milliseconds.
  static Future<void> pavlokZap({int intensity = 64, int durationMs = 500}) async {
    if (_useNativePavlok) {
      await _invokeNative(
        'pavlok.zap',
        () => _service.pavlokZap(intensity: intensity, durationMs: durationMs),
        {'intensity': intensity, 'durationMs': durationMs},
      );
      return;
    }
    await _service.pavlokZap(intensity: intensity, durationMs: durationMs);
  }

  /// Activates wristband vibration.
  static Future<void> pavlokVibrate(
      {int intensity = 128, int durationMs = 2000}) async {
    if (_useNativePavlok) {
      await _invokeNative(
        'pavlok.vibrate',
        () => _service.pavlokVibrate(intensity: intensity, durationMs: durationMs),
        {'intensity': intensity, 'durationMs': durationMs},
      );
      return;
    }
    await _service.pavlokVibrate(intensity: intensity, durationMs: durationMs);
  }

  /// Triggers an audible beep.
  static Future<void> pavlokBeep(
      {int intensity = 128, int durationMs = 1000}) async {
    if (_useNativePavlok) {
      await _invokeNative(
        'pavlok.beep',
        () => _service.pavlokBeep(intensity: intensity, durationMs: durationMs),
        {'intensity': intensity, 'durationMs': durationMs},
      );
      return;
    }
    await _service.pavlokBeep(intensity: intensity, durationMs: durationMs);
  }

  /// Stops all active Pavlok stimulation.
  static Future<void> pavlokStopAll() async {
    if (_useNativePavlok) {
      await _invokeNative('pavlok.stopAll', () => _service.pavlokStopAll());
      return;
    }
    await _service.pavlokStopAll();
  }

  /// Reads the Pavlok battery level from the standard BLE Battery Service.
  static Future<void> pavlokReadBatteryLevel() async {
    if (_useNativePavlok) {
      await _invokeNative(
        'pavlok.readBatteryLevel',
        () async {
          await _service.refreshPavlokBatteryLevel();
        },
      );
      return;
    }
    await _service.refreshPavlokBatteryLevel();
  }

  static Future<void> _invokeLovenseNative(
    String method,
    Future<void> Function() fallback, [
    Map<String, Object?>? arguments,
  ]) async {
    await _invokeNative(method, fallback, arguments);
  }

  static Future<void> _invokeNative(
    String method,
    Future<void> Function() _, [
    Map<String, Object?>? arguments,
  ]) async {
    await _native.invokeMethod<void>(method, arguments);
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

  static List<Map<String, dynamic>> _asNativeCandidateList(List<dynamic>? raw) {
    if (raw == null) return const <Map<String, dynamic>>[];
    return raw
        .whereType<Map>()
        .map((e) => Map<String, dynamic>.from(e))
        .toList(growable: false);
  }
}

