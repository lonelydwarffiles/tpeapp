import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

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

  BleService._internal() {
    _startAutoRepairLoop();
  }

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
    static final _pavlokVibrateUuid =
      Guid('00001001-0000-1000-8000-00805f9b34fb');
    static final _pavlokBeepUuid =
      Guid('00001002-0000-1000-8000-00805f9b34fb');
    static final _pavlokZapUuid =
      Guid('00001003-0000-1000-8000-00805f9b34fb');
    static const _pavlokServicePrefix = '156e';
  static const _knownPavlokMac = 'ca:98:6a:5c:fa:68';
  static const _strictPavlokMacMatch = false;

  static const _lovenseNameHints = <String>{
    'lv',
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

  static const _prefLovenseId = 'ble_saved_lovense_id';
  static const _prefPavlokId = 'ble_saved_pavlok_id';

  static final _deviceInfoServiceUuid =
      Guid('0000180a-0000-1000-8000-00805f9b34fb');
  static final _modelNumberUuid = Guid('00002a24-0000-1000-8000-00805f9b34fb');
  static final _manufacturerUuid = Guid('00002a29-0000-1000-8000-00805f9b34fb');
  static final _firmwareUuid = Guid('00002a26-0000-1000-8000-00805f9b34fb');
  static final _hardwareUuid = Guid('00002a27-0000-1000-8000-00805f9b34fb');
  static final _softwareUuid = Guid('00002a28-0000-1000-8000-00805f9b34fb');
  static final _batteryServiceUuid =
      Guid('0000180f-0000-1000-8000-00805f9b34fb');
  static final _batteryLevelUuid = Guid('00002a19-0000-1000-8000-00805f9b34fb');

  // ── State ────────────────────────────────────────────────────────────
  BluetoothDevice? _lovenseDevice;
  BluetoothCharacteristic? _lovenseTx;
  List<BluetoothCharacteristic> _lovenseTxCandidates = const [];
  String? _lovenseError;
  StreamSubscription<BluetoothConnectionState>? _lovenseConnSub;
  String? _lastLovenseId;
  Map<String, dynamic> _lovenseInfo = const {};
  int? _lovenseBatteryPct;

  BluetoothDevice? _pavlokDevice;
  BluetoothCharacteristic? _pavlokTx;
  List<BluetoothCharacteristic> _pavlokTxCandidates = const [];
  String? _pavlokError;
  StreamSubscription<BluetoothConnectionState>? _pavlokConnSub;
  String? _lastPavlokId;
  Map<String, dynamic> _pavlokInfo = const {};
  int? _pavlokBatteryPct;

  Timer? _autoRepairTimer;
  bool _autoRepairBusy = false;
  bool _autoRepairEnabled = true;
  bool _blockManualDisconnect = true;
  SharedPreferences? _prefs;

  StreamSubscription<List<ScanResult>>? _scanSub;

  bool get lovenseConnected => _lovenseDevice != null && _lovenseTx != null;
  bool get pavlokConnected => _pavlokDevice != null && _pavlokTx != null;
  String? get lovenseError => _lovenseError;
  String? get pavlokError => _pavlokError;
  int? get lovenseBatteryPct => _lovenseBatteryPct;
  int? get pavlokBatteryPct => _pavlokBatteryPct;
  bool get autoRepairEnabled => _autoRepairEnabled;
  bool get blockManualDisconnect => _blockManualDisconnect;

  Map<String, dynamic> get toyInfoForBackend => {
        'lovense': {
          'connected': lovenseConnected,
          if (_lastLovenseId != null) 'device_id': _lastLovenseId,
          if (_lovenseBatteryPct != null) 'battery_pct': _lovenseBatteryPct,
          ..._lovenseInfo,
        },
        'pavlok': {
          'connected': pavlokConnected,
          if (_lastPavlokId != null) 'device_id': _lastPavlokId,
          if (_pavlokBatteryPct != null) 'battery_pct': _pavlokBatteryPct,
          ..._pavlokInfo,
        },
      };

  void setAutoRepairEnabled(bool enabled) {
    _autoRepairEnabled = enabled;
    notifyListeners();
  }

  Future<void> configurePersistence(SharedPreferences prefs) async {
    _prefs = prefs;
    _lastLovenseId = (prefs.getString(_prefLovenseId) ?? '').trim();
    if (_lastLovenseId?.isEmpty ?? true) {
      _lastLovenseId = null;
    }
    _lastPavlokId = (prefs.getString(_prefPavlokId) ?? '').trim();
    if (_lastPavlokId?.isEmpty ?? true) {
      _lastPavlokId = null;
    }
    notifyListeners();
    // Startup must not wait on BLE scans; repair runs best-effort in background.
    unawaited(_runAutoRepairTick());
  }

  void setManualDisconnectBlocked(bool blocked) {
    _blockManualDisconnect = blocked;
    notifyListeners();
  }

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
    if (_blockManualDisconnect) {
      _lovenseError = 'Lovense disconnect is disabled in this build.';
      notifyListeners();
      throw StateError(_lovenseError!);
    }
    await _disconnectLovense(clearSavedDevice: true);
  }

  Future<void> _disconnectLovense({required bool clearSavedDevice}) async {
    _lastLovenseId = null;
    _lovenseInfo = const {};
    _lovenseBatteryPct = null;
    await _lovenseConnSub?.cancel();
    _lovenseConnSub = null;
    await _lovenseDevice?.disconnect();
    _lovenseDevice = null;
    _lovenseTx = null;
    _lovenseTxCandidates = const [];
    _lovenseError = null;
    if (!clearSavedDevice) {
      _lastLovenseId = _prefs?.getString(_prefLovenseId);
    } else {
      await _prefs?.remove(_prefLovenseId);
    }
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

  Future<int?> refreshLovenseBatteryLevel() async {
    final device = _lovenseDevice;
    if (device == null) {
      return null;
    }
    try {
      final services = await device.discoverServices();
      _lovenseBatteryPct = await _readBatteryPctFromServices(services);
      if (_lovenseBatteryPct != null) {
        _lovenseInfo = {
          ..._lovenseInfo,
          'battery_pct': _lovenseBatteryPct,
        };
      } else {
        final next = Map<String, dynamic>.from(_lovenseInfo);
        next.remove('battery_pct');
        _lovenseInfo = next;
      }
      notifyListeners();
      return _lovenseBatteryPct;
    } catch (_) {
      return null;
    }
  }

  Future<void> _connectLovense(BluetoothDevice device) async {
    try {
      await device.connect(license: License.nonprofit, autoConnect: false);
      _lovenseDevice = device;
      _lastLovenseId = device.remoteId.str;
      await _persistSavedIds();
      _attachLovenseConnectionWatcher(device);
      final services = await device.discoverServices();
      _lovenseInfo = await _collectDeviceInfo(device, services, brand: 'lovense');
      _lovenseBatteryPct = await _readBatteryPctFromServices(services);
      if (_lovenseBatteryPct != null) {
        _lovenseInfo = {
          ..._lovenseInfo,
          'battery_pct': _lovenseBatteryPct,
        };
      }
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
    if (_blockManualDisconnect) {
      _pavlokError = 'Pavlok disconnect is disabled in this build.';
      notifyListeners();
      throw StateError(_pavlokError!);
    }
    await _disconnectPavlok(clearSavedDevice: true);
  }

  Future<void> _disconnectPavlok({required bool clearSavedDevice}) async {
    _lastPavlokId = null;
    _pavlokInfo = const {};
    _pavlokBatteryPct = null;
    await _pavlokConnSub?.cancel();
    _pavlokConnSub = null;
    await _pavlokDevice?.disconnect();
    _pavlokDevice = null;
    _pavlokTx = null;
    _pavlokTxCandidates = const [];
    _pavlokError = null;
    if (!clearSavedDevice) {
      _lastPavlokId = _prefs?.getString(_prefPavlokId);
    } else {
      await _prefs?.remove(_prefPavlokId);
    }
    notifyListeners();
  }

  Future<void> pavlokZap({int intensity = 64, int durationMs = 500}) =>
      _pavlokSend(_cmdZap, intensity, durationMs);

  Future<void> pavlokVibrate({int intensity = 128, int durationMs = 2000}) =>
      _pavlokSend(_cmdVibrate, intensity, durationMs);

  Future<void> pavlokBeep({int intensity = 128, int durationMs = 1000}) =>
      _pavlokSend(_cmdBeep, intensity, durationMs);

  Future<void> pavlokStopAll() => _pavlokSend(_cmdZap, 0, 0);

  Future<int?> refreshPavlokBatteryLevel() async {
    final device = _pavlokDevice;
    if (device == null) {
      return null;
    }
    try {
      final services = await device.discoverServices();
      _pavlokBatteryPct = await _readBatteryPctFromServices(services);
      if (_pavlokBatteryPct != null) {
        _pavlokInfo = {
          ..._pavlokInfo,
          'battery_pct': _pavlokBatteryPct,
        };
      } else {
        final next = Map<String, dynamic>.from(_pavlokInfo);
        next.remove('battery_pct');
        _pavlokInfo = next;
      }
      notifyListeners();
      return _pavlokBatteryPct;
    } catch (_) {
      return null;
    }
  }

  Future<void> _connectPavlok(BluetoothDevice device) async {
    try {
      await device.connect(license: License.nonprofit, autoConnect: false);
      _pavlokDevice = device;
      _lastPavlokId = device.remoteId.str;
      await _persistSavedIds();
      _attachPavlokConnectionWatcher(device);
      final services = await device.discoverServices();
      _pavlokInfo = await _collectDeviceInfo(device, services, brand: 'pavlok');
      _pavlokBatteryPct = await _readBatteryPctFromServices(services);
      if (_pavlokBatteryPct != null) {
        _pavlokInfo = {
          ..._pavlokInfo,
          'battery_pct': _pavlokBatteryPct,
        };
      }
      _pavlokTxCandidates = _findPavlokTxCandidates(services);
      _pavlokTx = _pavlokTxCandidates.isNotEmpty ? _pavlokTxCandidates.first : null;
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
    final candidates = _pavlokTxCandidates;
    if (candidates.isEmpty) {
      _pavlokError = 'Pavlok device is not paired.';
      notifyListeners();
      return;
    }
    final intensityValue = intensity.clamp(0, 100);
    final preferredIds = _preferredPavlokCharIdsForType(type);

    Object? lastError;
    String lastCharId = 'unknown';
    var noResourceHits = 0;
    final prioritizedCandidates = _prioritizePavlokCandidates(candidates, preferredIds);
    for (final char in prioritizedCandidates) {
      lastCharId = char.characteristicUuid.str.toLowerCase();
      final payloads = _buildPavlokPayloadCandidates(
        char.characteristicUuid,
        type,
        intensityValue,
        durationMs,
      );
      if (payloads.isEmpty) {
        continue;
      }
      for (final payload in payloads) {
        try {
          await char.write(
            payload,
            withoutResponse: _pavlokUseWriteWithoutResponse(char),
          );
          // Stop at first successful write to avoid duplicate actuation.
          return;
        } catch (error) {
          lastError = error;
          if (error.toString().toLowerCase().contains('gatt_no_resources')) {
            noResourceHits++;
            // Back off when queue pressure is reported.
            await Future<void>.delayed(const Duration(milliseconds: 180));
          }
        }
      }
      // Small guard interval between characteristics.
      await Future<void>.delayed(const Duration(milliseconds: 80));
    }

    _pavlokError =
        'Pavlok command failed on $lastCharId (queue hits: $noResourceHits): $lastError';
    notifyListeners();
    throw StateError(_pavlokError!);
  }

  // ── Disposal ─────────────────────────────────────────────────────────

  Future<void> dispose() async {
    _autoRepairTimer?.cancel();
    await _lovenseConnSub?.cancel();
    await _pavlokConnSub?.cancel();
    _scanSub?.cancel();
    await _disconnectLovense(clearSavedDevice: false);
    await _disconnectPavlok(clearSavedDevice: false);
  }

  void _startAutoRepairLoop() {
    _autoRepairTimer?.cancel();
    _autoRepairTimer = Timer.periodic(
      const Duration(seconds: 10),
      (_) => _runAutoRepairTick(),
    );
  }

  Future<void> _runAutoRepairTick() async {
    if (!_autoRepairEnabled || _autoRepairBusy) {
      return;
    }
    _autoRepairBusy = true;
    try {
      if (!lovenseConnected && _lastLovenseId != null) {
        final found = await _scanForDeviceId(_lastLovenseId!);
        if (found != null) {
          await _connectLovense(found);
        } else {
          final candidates = await scanLovenseCandidates(timeout: const Duration(seconds: 5));
          if (candidates.isNotEmpty) {
            await _connectLovense(candidates.first);
          }
        }
      }

      if (!pavlokConnected && _lastPavlokId != null) {
        final found = await _scanForDeviceId(_lastPavlokId!);
        if (found != null) {
          await _connectPavlok(found);
        } else {
          final candidates = await scanPavlokCandidates(timeout: const Duration(seconds: 5));
          if (candidates.isNotEmpty) {
            await _connectPavlok(candidates.first);
          }
        }
      }
    } catch (_) {
      // Auto-repair is best-effort and should stay quiet.
    } finally {
      _autoRepairBusy = false;
    }
  }

  Future<BluetoothDevice?> _scanForDeviceId(String remoteId) async {
    final normalized = _normalizeId(remoteId);
    final devices = await _scanCandidates(
      (d) => _normalizeId(d.remoteId.str) == normalized,
      timeout: const Duration(seconds: 5),
    );
    return devices.isEmpty ? null : devices.first;
  }

  void _attachLovenseConnectionWatcher(BluetoothDevice device) {
    _lovenseConnSub?.cancel();
    _lovenseConnSub = device.connectionState.listen((state) {
      if (state == BluetoothConnectionState.disconnected) {
        _lovenseDevice = null;
        _lovenseTx = null;
        _lovenseTxCandidates = const [];
        _lovenseBatteryPct = null;
        _lovenseError = 'Lovense disconnected. Auto-repair is retrying.';
        notifyListeners();
      }
    });
  }

  void _attachPavlokConnectionWatcher(BluetoothDevice device) {
    _pavlokConnSub?.cancel();
    _pavlokConnSub = device.connectionState.listen((state) {
      if (state == BluetoothConnectionState.disconnected) {
        _pavlokDevice = null;
        _pavlokTx = null;
        _pavlokTxCandidates = const [];
        _pavlokBatteryPct = null;
        _pavlokError = 'Pavlok disconnected. Auto-repair is retrying.';
        notifyListeners();
      }
    });
  }

  Future<Map<String, dynamic>> _collectDeviceInfo(
    BluetoothDevice device,
    List<BluetoothService> services, {
    required String brand,
  }) async {
    final info = <String, dynamic>{
      'brand': brand,
      'name': _readableDeviceName(device),
      'remote_id': device.remoteId.str,
    };
    final dis = services.where((s) => s.serviceUuid == _deviceInfoServiceUuid);
    for (final service in dis) {
      for (final char in service.characteristics) {
        final value = await _tryReadStringCharacteristic(char);
        if (value == null || value.isEmpty) {
          continue;
        }
        if (char.characteristicUuid == _modelNumberUuid) {
          info['model'] = value;
        } else if (char.characteristicUuid == _manufacturerUuid) {
          info['manufacturer'] = value;
        } else if (char.characteristicUuid == _firmwareUuid) {
          info['firmware'] = value;
        } else if (char.characteristicUuid == _hardwareUuid) {
          info['hardware'] = value;
        } else if (char.characteristicUuid == _softwareUuid) {
          info['software'] = value;
        }
      }
    }
    return info;
  }

  Future<String?> _tryReadStringCharacteristic(
    BluetoothCharacteristic char,
  ) async {
    try {
      if (!char.properties.read) {
        return null;
      }
      final bytes = await char.read();
      final text = utf8.decode(bytes, allowMalformed: true).trim();
      if (text.isEmpty) {
        return null;
      }
      return text;
    } catch (_) {
      return null;
    }
  }

  Future<int?> _readBatteryPctFromServices(List<BluetoothService> services) async {
    for (final service in services) {
      if (service.serviceUuid != _batteryServiceUuid) {
        continue;
      }
      for (final char in service.characteristics) {
        if (char.characteristicUuid != _batteryLevelUuid) {
          continue;
        }
        try {
          if (!char.properties.read) {
            return null;
          }
          final bytes = await char.read();
          return _parseBatteryPct(bytes);
        } catch (_) {
          return null;
        }
      }
    }
    return null;
  }

  int? _parseBatteryPct(List<int> bytes) {
    if (bytes.isEmpty) {
      return null;
    }
    // Battery Level characteristic is a single uint8 (0-100).
    return bytes.first.clamp(0, 100).toInt();
  }

  Future<void> _persistSavedIds() async {
    if (_prefs == null) {
      return;
    }
    if (_lastLovenseId != null && _lastLovenseId!.isNotEmpty) {
      await _prefs!.setString(_prefLovenseId, _lastLovenseId!);
    }
    if (_lastPavlokId != null && _lastPavlokId!.isNotEmpty) {
      await _prefs!.setString(_prefPavlokId, _lastPavlokId!);
    }
  }

  bool _looksLikeLovense(BluetoothDevice device) {
    final name = _deviceName(device);
    if (_isKnownPavlok(device)) {
      return false;
    }
    if (name.contains(' lv') || name.startsWith('lv') || name.contains('lv-')) {
      return true;
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
    return _isKnownPavlok(device) || (!_strictPavlokMacMatch && name.contains('pavlok'));
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

  List<BluetoothCharacteristic> _findPavlokTxCandidates(
      List<BluetoothService> services) {
    final byId = <String, BluetoothCharacteristic>{};

    void add(BluetoothCharacteristic char) {
      byId[char.characteristicUuid.str.toLowerCase()] = char;
    }

    for (final service in services) {
      for (final char in service.characteristics) {
        if (char.characteristicUuid == _pavlokTxUuid && _canWrite(char)) {
          add(char);
        }
      }
    }

    for (final service in services) {
      if (service.serviceUuid == _pavlokServiceUuid) {
        for (final char in service.characteristics) {
          if (_canWrite(char) && _isLikelyPavlokTx(char.characteristicUuid)) {
            add(char);
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
          add(char);
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
          add(char);
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
            add(char);
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
          add(char);
        }
      }
    }

    final ordered = byId.values.toList();
    ordered.sort((a, b) =>
        _pavlokTxPriority(a.characteristicUuid)
            .compareTo(_pavlokTxPriority(b.characteristicUuid)));
    return ordered;
  }

  bool _canWrite(BluetoothCharacteristic char) {
    return char.properties.write || char.properties.writeWithoutResponse;
  }

  bool _useWriteWithoutResponse(BluetoothCharacteristic char) {
    return !char.properties.write && char.properties.writeWithoutResponse;
  }

  bool _pavlokUseWriteWithoutResponse(BluetoothCharacteristic char) {
    // Prefer acknowledged writes for Pavlok to avoid queue saturation.
    if (char.properties.write) {
      return false;
    }
    return _useWriteWithoutResponse(char);
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
      id == '1002' ||
      id.endsWith('1002') ||
      id == '1003' ||
      id.endsWith('1003') ||
        id == '0007' ||
        id == '0008';
  }

  List<List<int>> _buildPavlokPayloadCandidates(
    Guid characteristicUuid,
    int type,
    int intensity,
    int durationMs,
  ) {
    final id = characteristicUuid.str.toLowerCase();
    final rrSingle = 0x81;
    final ta = _pavlokTimeCodeForMs(durationMs);
    final to = _pavlokTimeCodeForMs(durationMs);

    final isVibrateChar =
        id == '1001' || id.endsWith('1001') || id == '0007' || id.endsWith('0007');
    final isBeepChar =
        id == '1002' || id.endsWith('1002') || id == '0008' || id.endsWith('0008');
    final isZapChar = id == '1003' || id.endsWith('1003');

    if (type == _cmdVibrate && isVibrateChar) {
      // Pavlok-S vibrate packet: RR 0C II TA TO
      return [<int>[rrSingle, 0x0c, intensity, ta, to]];
    }

    if (type == _cmdBeep && isBeepChar) {
      // Pavlok-S beep packet: RR 0C II TA TO
      return [<int>[rrSingle, 0x0c, intensity, ta, to]];
    }

    if (type == _cmdZap && isZapChar) {
      // Pavlok-S zap packet: RR II
      return [<int>[rrSingle, intensity]];
    }

    // Conservative fallback for older/alternate profiles.
    if ((id == '0007' || id.endsWith('0007') || id == '0008' || id.endsWith('0008')) &&
        (type == _cmdVibrate || type == _cmdBeep)) {
      return [<int>[rrSingle, 0x0c, intensity, ta, to]];
    }

    return const [];
  }

  int _pavlokTimeCodeForMs(int durationMs) {
    if (durationMs > 10000) {
      return 62;
    }
    if (durationMs >= 3000) {
      return ((durationMs - 3000) ~/ 500) | 48;
    }
    if (durationMs >= 1000) {
      return ((durationMs - 1000) ~/ 100) | 32;
    }
    if (durationMs >= 200) {
      return ((durationMs - 200) ~/ 50) | 16;
    }
    return durationMs ~/ 10;
  }

  Set<String> _preferredPavlokCharIdsForType(int type) {
    if (type == _cmdVibrate) {
      return const {'1001', '0007'};
    }
    if (type == _cmdBeep) {
      return const {'1002', '0008'};
    }
    if (type == _cmdZap) {
      return const {'1003'};
    }
    return const <String>{};
  }

  List<BluetoothCharacteristic> _prioritizePavlokCandidates(
    List<BluetoothCharacteristic> input,
    Set<String> preferredIds,
  ) {
    if (preferredIds.isEmpty) {
      return input;
    }
    final preferred = <BluetoothCharacteristic>[];
    final fallback = <BluetoothCharacteristic>[];
    for (final char in input) {
      final id = char.characteristicUuid.str.toLowerCase();
      final shortId = _shortGuidTail(id);
      if (preferredIds.contains(shortId)) {
        preferred.add(char);
      } else {
        fallback.add(char);
      }
    }
    return [...preferred, ...fallback];
  }

  String _shortGuidTail(String raw) {
    if (raw.length < 4) {
      return raw;
    }
    return raw.substring(raw.length - 4);
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

  int _pavlokTxPriority(Guid uuid) {
    final id = uuid.str.toLowerCase();
    if (id == '1001' || id.endsWith('1001')) return 0;
    if (id == '1002' || id.endsWith('1002')) return 1;
    if (id == '1003' || id.endsWith('1003')) return 2;
    if (id == '0007' || id.endsWith('0007')) return 0;
    if (id == '0008' || id.endsWith('0008')) return 1;
    if (id.contains('d44bc439')) return 4;
    return 10;
  }

  List<int> _pavlokTypeCandidates(int type) {
    final out = <int>[type];
    if (type > 0) out.add(type - 1);
    if (type < 255) out.add(type + 1);
    return out.toSet().toList(growable: false);
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
    await _ensureScanPermissions();

    final supported = await FlutterBluePlus.isSupported;
    if (!supported) {
      throw StateError('Bluetooth LE is not supported on this device.');
    }
    final adapterState = await FlutterBluePlus.adapterState.first
        .timeout(const Duration(seconds: 3));
    if (adapterState != BluetoothAdapterState.on) {
      throw StateError('Bluetooth is off. Turn it on and try scanning again.');
    }

    try {
      await FlutterBluePlus.stopScan();
      await _scanSub?.cancel();
      _scanSub = null;

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
    } catch (error) {
      throw StateError('BLE scan failed: $error');
    } finally {
      await FlutterBluePlus.stopScan();
      await _scanSub?.cancel();
      _scanSub = null;
    }

    // If strict matching finds nothing, still show discovered devices so users
    // can pick explicitly from the modal instead of seeing an empty result.
    final selectedSource =
        matchedById.isNotEmpty ? matchedById : allById;
    final devices = selectedSource.values.toList();
    devices.sort((a, b) =>
        _readableDeviceName(a).compareTo(_readableDeviceName(b)));
    return devices;
  }

  Future<void> _ensureScanPermissions() async {
    final statuses = <Permission, PermissionStatus>{
      Permission.bluetoothScan: await Permission.bluetoothScan.status,
      Permission.bluetoothConnect: await Permission.bluetoothConnect.status,
      Permission.locationWhenInUse: await Permission.locationWhenInUse.status,
    };

    final needsRequest = statuses.entries
        .where((entry) => !entry.value.isGranted)
        .map((entry) => entry.key)
        .toList(growable: false);
    if (needsRequest.isEmpty) {
      return;
    }

    final requested = await needsRequest.request();
    final denied = requested.entries
        .where((entry) => !entry.value.isGranted)
        .map((entry) => entry.key)
        .toList(growable: false);
    if (denied.isEmpty) {
      return;
    }

    final permanentlyDenied = requested.entries
        .where((entry) => entry.value.isPermanentlyDenied)
        .map((entry) => entry.key)
        .toList(growable: false);
    if (permanentlyDenied.isNotEmpty) {
      throw StateError(
        'Bluetooth permissions are permanently denied. Open app settings and allow Bluetooth + Location.',
      );
    }

    throw StateError('Bluetooth scan requires Bluetooth + Location permissions.');
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
