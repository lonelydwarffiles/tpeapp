import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
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
class BleService extends ChangeNotifier {
  factory BleService() => _instance;

  BleService._internal();

  static final BleService _instance = BleService._internal();

  // ── Lovense GATT profile ─────────────────────────────────────────────
  static final _lovenseServiceUuid =
      Guid('0000fff0-0000-1000-8000-00805f9b34fb');
  static final _lovenseTxUuid = Guid('0000fff2-0000-1000-8000-00805f9b34fb');
  static final _lovenseAltTxUuid =
      Guid('cfa3fac5-48bb-4d87-817e-a439965956e1');
  static final _lovenseUartTxUuid =
      Guid('6e400002-b5a3-f393-e0a9-e50e24dcca9e');
    static final _lovenseAltServiceUuid =
      Guid('1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0');
    static final _lovenseAltTx2Uuid =
      Guid('984227f3-34fc-4045-a5d0-2c581f81a153');
    static final _lovenseAltTx3Uuid =
      Guid('17bf3564-fb6d-4e53-88a4-5e37e0326063');
    static final _lovenseQppServiceUuid =
      Guid('52300001-0023-4bd4-bbd5-a6920e4c5653');
    static final _lovenseQppTxUuid =
      Guid('52300002-0023-4bd4-bbd5-a6920e4c5653');

  // ── Pavlok GATT profile ──────────────────────────────────────────────
  static final _pavlokServiceUuid =
      Guid('0000fee9-0000-1000-8000-00805f9b34fb');
  static final _pavlokTxUuid =
      Guid('d44bc439-abfd-45a2-b575-925416129600');
    static const _pavlokServicePrefix = '156e';
  static const _knownPavlokMac = 'ca:98:6a:5c:fa:68';
  static const _strictPavlokMacMatch = true;

  static const _lovenseNameHints = <String>{
    'lovense',
    'lush',
    'hush',
    'ferri',
    'domi',
    'max',
    'nora',
    'ambi',
    'ridge',
    'solace',
    'vulse',
    'lapis',
    'tenera',
    'exomoon',
  };

  // ── Pavlok command bytes ─────────────────────────────────────────────
  static const _cmdZap     = 0x04;
  static const _cmdVibrate = 0x01;
  static const _cmdBeep    = 0x02;

  // ── State ────────────────────────────────────────────────────────────
  BluetoothDevice? _lovenseDevice;
  BluetoothCharacteristic? _lovenseTx;
  List<BluetoothCharacteristic> _lovenseTxCandidates = const [];
  String? _lovenseError;

  BluetoothDevice? _pavlokDevice;
  BluetoothCharacteristic? _pavlokTx;
  String? _pavlokError;

  StreamSubscription<List<ScanResult>>? _scanSub;

  bool get lovenseConnected => _lovenseDevice != null && _lovenseTx != null;
  bool get pavlokConnected => _pavlokDevice != null && _pavlokTx != null;
  String? get lovenseError => _lovenseError;
  String? get pavlokError => _pavlokError;

  // ═══════════════════════════════════════════════════════════════════
  //  Lovense
  // ═══════════════════════════════════════════════════════════════════

  /// Starts a 10-second BLE scan and connects to the first Lovense toy found.
  Future<void> lovenseScan({Duration timeout = const Duration(seconds: 10)}) async {
    _lovenseError = null;
    notifyListeners();
    final candidates = await scanLovenseCandidates(timeout: timeout);
    if (candidates.isEmpty) {
      _lovenseError = 'No Lovense devices found.';
      notifyListeners();
      return;
    }
    await _connectLovense(candidates.first);
  }

  Future<List<BluetoothDevice>> scanLovenseCandidates({
    Duration timeout = const Duration(seconds: 10),
  }) {
    return _scanCandidates(_looksLikeLovense, timeout: timeout);
  }

  Future<void> connectLovenseDevice(BluetoothDevice device) =>
      _connectLovense(device);

  Future<void> lovenseStopScan() => FlutterBluePlus.stopScan();

  Future<void> lovenseDisconnect() async {
    await _lovenseDevice?.disconnect();
    _lovenseDevice = null;
    _lovenseTx = null;
    _lovenseTxCandidates = const [];
    _lovenseError = null;
    notifyListeners();
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
    try {
      await device.connect(license: License.nonprofit, autoConnect: false);
      _lovenseDevice = device;
      final services = await device.discoverServices();
      _lovenseTxCandidates = _findLovenseTxCandidates(services);
      _lovenseTx =
          _lovenseTxCandidates.isNotEmpty ? _lovenseTxCandidates.first : null;
      if (_lovenseTx != null) {
        notifyListeners();
        return;
      }
      final label = _deviceName(device);
      _lovenseError =
          'Lovense service not exposed by $label. Services: ${_serviceSummary(services)}. Writable chars: ${_writableSummary(services)}';
      notifyListeners();
    } catch (error) {
      _lovenseError = 'Lovense connect failed: $error';
      notifyListeners();
    }
  }

  Future<void> _lovenseSend(String command) async {
    final candidates = _lovenseTxCandidates;
    if (candidates.isEmpty) {
      _lovenseError = 'Lovense device is not paired.';
      notifyListeners();
      throw StateError(_lovenseError!);
    }

    Object? lastError;
    var successCount = 0;
    for (final char in candidates) {
      try {
        await char.write(
          utf8.encode(command),
          withoutResponse: _useWriteWithoutResponse(char),
        );
        successCount++;
      } catch (error) {
        lastError = error;
      }
    }

    if (successCount == 0) {
      _lovenseError =
          'Lovense command failed on all candidates: $lastError';
      notifyListeners();
      throw StateError(_lovenseError!);
    }
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Pavlok
  // ═══════════════════════════════════════════════════════════════════

  Future<void> pavlokScan({Duration timeout = const Duration(seconds: 10)}) async {
    _pavlokError = null;
    notifyListeners();
    final candidates = await scanPavlokCandidates(timeout: timeout);
    if (candidates.isEmpty) {
      _pavlokError = 'No Pavlok devices found.';
      notifyListeners();
      return;
    }
    await _connectPavlok(candidates.first);
  }

  Future<List<BluetoothDevice>> scanPavlokCandidates({
    Duration timeout = const Duration(seconds: 10),
  }) {
    return _scanCandidates(_looksLikePavlok, timeout: timeout);
  }

  Future<void> connectPavlokDevice(BluetoothDevice device) =>
      _connectPavlok(device);

  Future<void> pavlokStopScan() => FlutterBluePlus.stopScan();

  Future<void> pavlokDisconnect() async {
    await _pavlokDevice?.disconnect();
    _pavlokDevice = null;
    _pavlokTx = null;
    _pavlokError = null;
    notifyListeners();
  }

  Future<void> pavlokZap({int intensity = 64, int durationMs = 500}) =>
      _pavlokSend(_cmdZap, intensity, durationMs);

  Future<void> pavlokVibrate({int intensity = 128, int durationMs = 2000}) =>
      _pavlokSend(_cmdVibrate, intensity, durationMs);

  Future<void> pavlokBeep({int intensity = 128, int durationMs = 1000}) =>
      _pavlokSend(_cmdBeep, intensity, durationMs);

  Future<void> pavlokStopAll() => _pavlokSend(_cmdZap, 0, 0);

  Future<void> _connectPavlok(BluetoothDevice device) async {
    try {
      await device.connect(license: License.nonprofit, autoConnect: false);
      _pavlokDevice = device;
      final services = await device.discoverServices();
      _pavlokTx = _findPavlokTx(services);
      if (_pavlokTx != null) {
        notifyListeners();
        return;
      }
      final label = _deviceName(device);
      _pavlokError =
          'Pavlok service not exposed by $label. Services: ${_serviceSummary(services)}. Writable chars: ${_writableSummary(services)}';
      notifyListeners();
    } catch (error) {
      _pavlokError = 'Pavlok connect failed: $error';
      notifyListeners();
    }
  }

  Future<void> _pavlokSend(int type, int intensity, int durationMs) async {
    final char = _pavlokTx;
    if (char == null) {
      _pavlokError = 'Pavlok device is not paired.';
      notifyListeners();
      return;
    }
    final durationUnit = ((durationMs + 50) ~/ 100).clamp(0, 255);
    final payloads = _buildPavlokPayloadCandidates(
      char.characteristicUuid,
      type,
      intensity.clamp(0, 255),
      durationUnit,
    );

    Object? lastError;
    var successCount = 0;
    for (final payload in payloads) {
      try {
        await char.write(
          payload,
          withoutResponse: _useWriteWithoutResponse(char),
        );
        successCount++;
      } catch (error) {
        lastError = error;
      }
    }

    if (successCount > 0) {
      return;
    }

    _pavlokError =
        'Pavlok command failed on ${char.characteristicUuid.str.toLowerCase()}: $lastError';
    notifyListeners();
    throw StateError(_pavlokError!);
  }

  // ── Disposal ─────────────────────────────────────────────────────────

  Future<void> dispose() async {
    _scanSub?.cancel();
    await lovenseDisconnect();
    await pavlokDisconnect();
  }

  bool _looksLikeLovense(BluetoothDevice device) {
    final name = _deviceName(device);
    if (_isKnownPavlok(device)) {
      return false;
    }
    for (final hint in _lovenseNameHints) {
      if (name.contains(hint)) {
        return true;
      }
    }
    return false;
  }

  bool _looksLikePavlok(BluetoothDevice device) {
    final name = _deviceName(device);
    if (_strictPavlokMacMatch) {
      return _isKnownPavlok(device);
    }
    return _isKnownPavlok(device) || name.contains('pavlok');
  }

  List<BluetoothCharacteristic> _findLovenseTxCandidates(
      List<BluetoothService> services) {
    final byId = <String, BluetoothCharacteristic>{};

    void add(BluetoothCharacteristic char) {
      byId[char.characteristicUuid.str.toLowerCase()] = char;
    }

    for (final service in services) {
      if (service.serviceUuid == _lovenseServiceUuid) {
        for (final char in service.characteristics) {
          if (char.characteristicUuid == _lovenseTxUuid && _canWrite(char)) {
            add(char);
          }
        }
      }
    }

    for (final service in services) {
      for (final char in service.characteristics) {
        if ((char.characteristicUuid == _lovenseTxUuid ||
                char.characteristicUuid == _lovenseAltTxUuid ||
                char.characteristicUuid == _lovenseUartTxUuid ||
                char.characteristicUuid == _lovenseAltTx2Uuid ||
                char.characteristicUuid == _lovenseAltTx3Uuid ||
                char.characteristicUuid == _lovenseQppTxUuid) &&
            _canWrite(char)) {
          add(char);
        }
      }
    }

    for (final service in services) {
      if (service.serviceUuid == _lovenseAltServiceUuid ||
          service.serviceUuid == _lovenseQppServiceUuid) {
        for (final char in service.characteristics) {
          if (_canWrite(char)) {
            add(char);
          }
        }
      }
    }

    for (final service in services) {
      final serviceId = service.serviceUuid.str.toLowerCase();
      if (!serviceId.contains('fff0') && !serviceId.contains('cfa3fac5')) {
        continue;
      }
      for (final char in service.characteristics) {
        if (_canWrite(char) && _isLikelyLovenseTx(char.characteristicUuid)) {
          add(char);
        }
      }
    }

    final ordered = byId.values.toList();
    ordered.sort((a, b) =>
        _lovenseTxPriority(a.characteristicUuid)
            .compareTo(_lovenseTxPriority(b.characteristicUuid)));
    return ordered;
  }

  BluetoothCharacteristic? _findPavlokTx(List<BluetoothService> services) {
    for (final service in services) {
      for (final char in service.characteristics) {
        if (char.characteristicUuid == _pavlokTxUuid && _canWrite(char)) {
          return char;
        }
      }
    }

    for (final service in services) {
      if (service.serviceUuid == _pavlokServiceUuid) {
        for (final char in service.characteristics) {
          if (_canWrite(char) && _isLikelyPavlokTx(char.characteristicUuid)) {
            return char;
          }
        }
      }
    }

    // Newer Pavlok firmware advertises 156e* vendor services. Prefer 0007/0008
    // command channels first and only fall back to 1001 when necessary.
    for (final service in services) {
      final sid = service.serviceUuid.str.toLowerCase();
      if (!sid.startsWith(_pavlokServicePrefix)) {
        continue;
      }
      for (final char in service.characteristics) {
        if (!_canWrite(char)) {
          continue;
        }
        final cid = char.characteristicUuid.str.toLowerCase();
        if (cid == '0007' || cid.endsWith('0007')) {
          return char;
        }
      }
    }

    for (final service in services) {
      final sid = service.serviceUuid.str.toLowerCase();
      if (!sid.startsWith(_pavlokServicePrefix)) {
        continue;
      }
      for (final char in service.characteristics) {
        if (!_canWrite(char)) {
          continue;
        }
        final cid = char.characteristicUuid.str.toLowerCase();
        if (cid == '0008' || cid.endsWith('0008')) {
          return char;
        }
      }
    }

    for (final service in services) {
      final sid = service.serviceUuid.str.toLowerCase();
      if (!sid.startsWith(_pavlokServicePrefix)) {
        continue;
      }
      for (final char in service.characteristics) {
        if (_canWrite(char)) {
          final cid = char.characteristicUuid.str.toLowerCase();
          if (cid == '1001' || cid.endsWith('1001')) {
            return char;
          }
        }
      }
    }

    for (final service in services) {
      final sid = service.serviceUuid.str.toLowerCase();
      if (!sid.startsWith(_pavlokServicePrefix)) {
        continue;
      }
      for (final char in service.characteristics) {
        if (_canWrite(char) && _isLikelyPavlokTx(char.characteristicUuid)) {
          return char;
        }
      }
    }

    return null;
  }

  bool _canWrite(BluetoothCharacteristic char) {
    return char.properties.write || char.properties.writeWithoutResponse;
  }

  bool _useWriteWithoutResponse(BluetoothCharacteristic char) {
    return !char.properties.write && char.properties.writeWithoutResponse;
  }

  bool _isStandardService(Guid serviceUuid) {
    final id = serviceUuid.str.toLowerCase();
    return id.startsWith('000018');
  }

  bool _isLikelyLovenseTx(Guid characteristicUuid) {
    final id = characteristicUuid.str.toLowerCase();
    return id.contains('fff1') ||
        id.contains('fff2') ||
        id.contains('6e400002') ||
        id.contains('cfa3fac5') ||
        id.contains('984227f3') ||
        id.contains('17bf3564') ||
        id.contains('52300002');
  }

  bool _isLikelyPavlokTx(Guid characteristicUuid) {
    final id = characteristicUuid.str.toLowerCase();
    return id.contains('d44bc439') ||
        id == '1001' ||
        id.endsWith('1001') ||
        id == '0007' ||
        id == '0008';
  }

  List<List<int>> _buildPavlokPayloadCandidates(
    Guid characteristicUuid,
    int type,
    int intensity,
    int durationUnit,
  ) {
    final id = characteristicUuid.str.toLowerCase();
    final base = [type, intensity, durationUnit];

    if (id == '0007' || id.endsWith('0007') || id == '0008' || id.endsWith('0008')) {
      final swapped = <int>[type, durationUnit, intensity];
      final prefixed4 = <int>[0, type, intensity, durationUnit];
      final prefixed4Swapped = <int>[0, type, durationUnit, intensity];
      final padded8 = <int>[...base, 0, 0, 0, 0, 0];
      return [
        base,
        swapped,
        prefixed4,
        prefixed4Swapped,
        padded8,
      ];
    }

    if (!(id == '1001' || id.endsWith('1001'))) {
      return [base];
    }

    final padded8 = <int>[...base, 0, 0, 0, 0, 0];
    final prefixed4 = <int>[0, ...base];
    final padded20 = List<int>.filled(20, 0);
    padded20[0] = type;
    padded20[1] = intensity;
    padded20[2] = durationUnit;

    return [
      base,
      prefixed4,
      padded8,
      padded20,
    ];
  }

  int _lovenseTxPriority(Guid uuid) {
    final id = uuid.str.toLowerCase();
    if (id.contains('984227f3')) return 0;
    if (id.contains('17bf3564')) return 1;
    if (id.contains('52300002')) return 2;
    if (id.contains('fff2')) return 3;
    if (id.contains('6e400002')) return 4;
    return 10;
  }

  bool _isKnownPavlok(BluetoothDevice device) {
    return _normalizeId(device.remoteId.str) == _normalizeId(_knownPavlokMac);
  }

  String _normalizeId(String raw) {
    return raw.toLowerCase().replaceAll(RegExp(r'[^0-9a-f]'), '');
  }

  String _serviceSummary(List<BluetoothService> services) {
    final ids = services.map((s) => s.serviceUuid.str.toLowerCase()).toList();
    ids.sort();
    return ids.join(', ');
  }

  String _writableSummary(List<BluetoothService> services) {
    final entries = <String>[];
    for (final service in services) {
      for (final char in service.characteristics) {
        if (_canWrite(char)) {
          entries.add(
            '${service.serviceUuid.str.toLowerCase()}/${char.characteristicUuid.str.toLowerCase()}',
          );
        }
      }
    }
    entries.sort();
    return entries.join(', ');
  }

  String _deviceName(BluetoothDevice device) {
    return '${device.platformName} ${device.advName} ${device.remoteId.str}'.toLowerCase();
  }

  Future<List<BluetoothDevice>> _scanCandidates(
    bool Function(BluetoothDevice) matcher, {
    required Duration timeout,
  }) async {
    final allById = <String, BluetoothDevice>{};
    final matchedById = <String, BluetoothDevice>{};
    await FlutterBluePlus.stopScan();
    _scanSub?.cancel();

    // Run a manual scan window because timeout-based scans can be stopped
    // prematurely on some Android stacks/devices.
    await FlutterBluePlus.startScan();
    _scanSub = FlutterBluePlus.scanResults.listen((results) {
      for (final result in results) {
        allById[result.device.remoteId.str] = result.device;
        if (matcher(result.device)) {
          matchedById[result.device.remoteId.str] = result.device;
        }
      }
    });

    await Future<void>.delayed(timeout);
    await FlutterBluePlus.stopScan();
    await _scanSub?.cancel();
    _scanSub = null;

    // If strict matching finds nothing, still show discovered devices so users
    // can pick explicitly from the modal instead of seeing an empty result.
    final selectedSource =
        matchedById.isNotEmpty ? matchedById : allById;
    final devices = selectedSource.values.toList();
    devices.sort((a, b) =>
        _readableDeviceName(a).compareTo(_readableDeviceName(b)));
    return devices;
  }

  String _readableDeviceName(BluetoothDevice device) {
    final platform = device.platformName.trim();
    final advertised = device.advName.trim();
    final merged = ('$platform $advertised').trim();
    if (merged.isNotEmpty) {
      return merged.toLowerCase();
    }
    return device.remoteId.str.toLowerCase();
  }
}
