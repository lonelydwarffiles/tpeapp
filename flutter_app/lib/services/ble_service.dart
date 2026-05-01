import 'dart:async';
import 'dart:convert';

import 'package:flutter_blue_plus/flutter_blue_plus.dart';

/// Pure-Dart BLE layer for Lovense and Pavlok devices.
///
/// This service is the Dart equivalent of the Kotlin [BleManager],
/// [LovenseManager], and [PavlokManager] classes.  It handles BLE scanning,
/// connecting, and command dispatch from the Flutter UI layer.
///
/// The native Kotlin [LovenseManager] / [PavlokManager] singletons are still
/// used by [ConsequenceDispatcher] (automated punishment / reward stimuli
/// triggered by FilterService or FCM).  Both paths share the physical BLE
/// radio but maintain independent GATT connections — one from Dart, one from
/// the native service layer.  Use [BleChannel] when you need the native
/// singleton instead (e.g., to ensure a single shared connection).
class BleService {
  // ── Lovense GATT profile ─────────────────────────────────────────────
  static final _lovenseServiceUuid =
      Guid('0000fff0-0000-1000-8000-00805f9b34fb');
  static final _lovenseTxUuid = Guid('0000fff2-0000-1000-8000-00805f9b34fb');

  // ── Pavlok GATT profile ──────────────────────────────────────────────
  static final _pavlokServiceUuid =
      Guid('0000fee9-0000-1000-8000-00805f9b34fb');
  static final _pavlokTxUuid =
      Guid('d44bc439-abfd-45a2-b575-925416129600');

  // ── Pavlok command bytes ─────────────────────────────────────────────
  static const _cmdZap     = 0x04;
  static const _cmdVibrate = 0x01;
  static const _cmdBeep    = 0x02;

  // ── State ────────────────────────────────────────────────────────────
  BluetoothDevice? _lovenseDevice;
  BluetoothCharacteristic? _lovenseTx;

  BluetoothDevice? _pavlokDevice;
  BluetoothCharacteristic? _pavlokTx;

  StreamSubscription<List<ScanResult>>? _scanSub;

  // ═══════════════════════════════════════════════════════════════════
  //  Lovense
  // ═══════════════════════════════════════════════════════════════════

  /// Starts a 10-second BLE scan and connects to the first Lovense toy found.
  Future<void> lovenseScan({Duration timeout = const Duration(seconds: 10)}) async {
    await FlutterBluePlus.startScan(timeout: timeout, withServices: [_lovenseServiceUuid]);
    _scanSub?.cancel();
    _scanSub = FlutterBluePlus.scanResults.listen((results) async {
      if (results.isEmpty) return;
      final result = results.first;
      await FlutterBluePlus.stopScan();
      await _connectLovense(result.device);
    });
  }

  Future<void> lovenseStopScan() => FlutterBluePlus.stopScan();

  Future<void> lovenseDisconnect() async {
    await _lovenseDevice?.disconnect();
    _lovenseDevice = null;
    _lovenseTx = null;
  }

  Future<void> lovenseVibrate(int level) =>
      _lovenseSend('Vibrate:${level.clamp(0, 20)};');

  Future<void> lovenseRotate(int level) =>
      _lovenseSend('Rotate:${level.clamp(0, 20)};');

  Future<void> lovensePump(int level) =>
      _lovenseSend('Pump:${level.clamp(0, 3)};');

  Future<void> lovenseStopAll() async {
    await lovenseVibrate(0);
    await lovenseRotate(0);
    await lovensePump(0);
  }

  Future<void> lovenseBattery() => _lovenseSend('Battery;');

  Future<void> _connectLovense(BluetoothDevice device) async {
    await device.connect(autoConnect: false);
    _lovenseDevice = device;
    final services = await device.discoverServices();
    for (final svc in services) {
      if (svc.serviceUuid == _lovenseServiceUuid) {
        for (final char in svc.characteristics) {
          if (char.characteristicUuid == _lovenseTxUuid) {
            _lovenseTx = char;
            return;
          }
        }
      }
    }
  }

  Future<void> _lovenseSend(String command) async {
    final char = _lovenseTx;
    if (char == null) return;
    await char.write(utf8.encode(command), withoutResponse: false);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Pavlok
  // ═══════════════════════════════════════════════════════════════════

  Future<void> pavlokScan({Duration timeout = const Duration(seconds: 10)}) async {
    await FlutterBluePlus.startScan(timeout: timeout, withServices: [_pavlokServiceUuid]);
    _scanSub?.cancel();
    _scanSub = FlutterBluePlus.scanResults.listen((results) async {
      if (results.isEmpty) return;
      await FlutterBluePlus.stopScan();
      await _connectPavlok(results.first.device);
    });
  }

  Future<void> pavlokStopScan() => FlutterBluePlus.stopScan();

  Future<void> pavlokDisconnect() async {
    await _pavlokDevice?.disconnect();
    _pavlokDevice = null;
    _pavlokTx = null;
  }

  Future<void> pavlokZap({int intensity = 64, int durationMs = 500}) =>
      _pavlokSend(_cmdZap, intensity, durationMs);

  Future<void> pavlokVibrate({int intensity = 128, int durationMs = 2000}) =>
      _pavlokSend(_cmdVibrate, intensity, durationMs);

  Future<void> pavlokBeep({int intensity = 128, int durationMs = 1000}) =>
      _pavlokSend(_cmdBeep, intensity, durationMs);

  Future<void> pavlokStopAll() => _pavlokSend(_cmdZap, 0, 0);

  Future<void> _connectPavlok(BluetoothDevice device) async {
    await device.connect(autoConnect: false);
    _pavlokDevice = device;
    final services = await device.discoverServices();
    for (final svc in services) {
      if (svc.serviceUuid == _pavlokServiceUuid) {
        for (final char in svc.characteristics) {
          if (char.characteristicUuid == _pavlokTxUuid) {
            _pavlokTx = char;
            return;
          }
        }
      }
    }
  }

  Future<void> _pavlokSend(int type, int intensity, int durationMs) async {
    final char = _pavlokTx;
    if (char == null) return;
    final durationUnit = ((durationMs + 50) ~/ 100).clamp(0, 255);
    await char.write(
      [type, intensity.clamp(0, 255), durationUnit],
      withoutResponse: false,
    );
  }

  // ── Disposal ─────────────────────────────────────────────────────────

  Future<void> dispose() async {
    _scanSub?.cancel();
    await lovenseDisconnect();
    await pavlokDisconnect();
  }
}
