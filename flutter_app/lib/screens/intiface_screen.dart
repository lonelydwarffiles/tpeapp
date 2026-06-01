import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../channels/ble_channel.dart';
import '../services/ble_service.dart';
import '../services/intiface_service.dart';

/// Pairing / control screen for the local Intiface Central toy server.
///
/// Lets the user connect to the Intiface server running on the device,
/// monitors scanning progress, and provides a vibration test slider once
/// a toy has been discovered.
class IntifaceScreen extends StatefulWidget {
  const IntifaceScreen({super.key});

  @override
  State<IntifaceScreen> createState() => _IntifaceScreenState();
}

class _IntifaceScreenState extends State<IntifaceScreen> {
  static const EventChannel _nativeBleEvents = EventChannel('com.hound.controller/ble_events');
  double _testIntensity = 0.0;
  late final TextEditingController _endpointController;
  StreamSubscription<dynamic>? _nativeBleSub;
  bool _nativeLovenseReady = false;
  bool _nativePavlokReady = false;
  int _nativeLovenseWriteOkCount = 0;
  int _nativePavlokWriteOkCount = 0;
  int _nativeLovenseWriteFailCount = 0;
  int _nativePavlokWriteFailCount = 0;
  String? _nativeLovenseWriteFailDetail;
  String? _nativePavlokWriteFailDetail;
  String? _nativeLovenseLastError;
  String? _nativePavlokLastError;
  int? _nativeLovenseBatteryPct;
  int? _nativePavlokBatteryPct;
  DateTime? _lastLovenseBatteryRequestAt;
  DateTime? _lastPavlokBatteryRequestAt;

  int? _parseBatteryFromNotificationText(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return null;

    final direct = RegExp(r'^\s*(\d{1,3})(?:\.\d+)?\s*;?\s*$').firstMatch(trimmed);
    if (direct != null) {
      return int.tryParse(direct.group(1) ?? '')?.clamp(0, 100).toInt();
    }

    final withKeyword = RegExp(r'(?i)(?:get_battery|battery|bat)\s*[:=]?\s*(\d{1,3})(?:\.\d+)?')
        .firstMatch(trimmed);
    if (withKeyword != null) {
      return int.tryParse(withKeyword.group(1) ?? '')?.clamp(0, 100).toInt();
    }

    return null;
  }

  Future<bool> _confirmNativePairMode({required String deviceName}) async {
    final useAutoSelect = await showDialog<bool>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text('Choose $deviceName Pairing Mode'),
          content: Text(
            'Auto-Select connects to the first nearby device.\n\n'
            'Use Selector scans natively and lets you choose a specific device.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Use Selector'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Auto-Select'),
            ),
          ],
        );
      },
    );
    return useAutoSelect ?? true;
  }

  String _nativeDeviceLabel(Map<String, dynamic> device) {
    final name = (device['name'] ?? '').toString().trim();
    final address = (device['address'] ?? '').toString().trim();
    if (name.isNotEmpty) return name;
    if (address.isNotEmpty) return address;
    return 'Unnamed device';
  }

  bool _nativeLooksLikeLovense(Map<String, dynamic> device) {
    final name = _nativeDeviceLabel(device).toLowerCase();
    if (name.isEmpty) return false;

    if (name.contains('lovense')) return true;
    if (RegExp(r'(^|[^a-z0-9])lv([\s_-]|$)').hasMatch(name)) return true;

    const modelHints = <String>[
      'lush',
      'hush',
      'dolce',
      'nora',
      'osci',
      'ferri',
      'domi',
      'hyphy',
    ];

    return modelHints.any(
      (hint) => RegExp('(^|[^a-z0-9])$hint([\\s_-]|\$)').hasMatch(name),
    );
  }

  bool _nativeLooksLikePavlok(Map<String, dynamic> device) {
    final name = _nativeDeviceLabel(device).toLowerCase();
    final address = (device['address'] ?? '').toString().toLowerCase();
    return name.contains('pavlok') ||
        address == 'ca:98:6a:5c:fa:68' ||
        address == 'ca:9b:6a:5c:fa:68';
  }

  List<Map<String, dynamic>> _filterLovenseNativeDevices(List<Map<String, dynamic>> devices) {
    return devices.where(_nativeLooksLikeLovense).toList(growable: false);
  }

  List<Map<String, dynamic>> _filterPavlokNativeDevices(List<Map<String, dynamic>> devices) {
    return devices.where(_nativeLooksLikePavlok).toList(growable: false);
  }

  Future<Map<String, dynamic>?> _pickNativeDeviceModal({
    required String title,
    required List<Map<String, dynamic>> devices,
  }) {
    return showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        final maxHeight = MediaQuery.of(context).size.height * 0.62;
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(title, style: Theme.of(context).textTheme.titleMedium),
                ),
              ),
              SizedBox(
                height: maxHeight,
                child: ListView.builder(
                  itemCount: devices.length,
                  itemBuilder: (context, index) {
                    final device = devices[index];
                    final address = (device['address'] ?? '').toString();
                    final rssi = device['rssi'];
                    return ListTile(
                      title: Text(_nativeDeviceLabel(device)),
                      subtitle: Text(
                        rssi is num && address.isNotEmpty
                            ? '$address • RSSI $rssi'
                            : address,
                      ),
                      onTap: () => Navigator.pop(context, device),
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _runToyAction(
    String actionLabel,
    Future<void> Function() action,
  ) async {
    try {
      await action();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('$actionLabel sent.')),
      );
    } catch (error) {
      if (!mounted) return;
      final msg = error
          .toString()
          .replaceFirst('StateError: ', '')
          .replaceFirst('Bad state: ', '');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('$actionLabel failed: $msg')),
      );
    }
  }

  Future<void> _runLovenseAction(
    String actionLabel,
    Future<void> Function() action,
  ) {
    final path = BleChannel.lovensePathLabel;
    return _runToyAction('Lovense $actionLabel ($path)', action);
  }

  Future<void> _testLovenseOnly() {
    return _runLovenseAction('test', () async {
      _resetNativeAttemptState('lovense');
      if (BleChannel.useNativeLovense && !_nativeLovenseReady) {
        final connected = await BleChannel.lovenseIsConnectedNative();
        if (connected) {
          _nativeLovenseReady = true;
        } else {
          await BleChannel.lovenseScan();
          await _waitForNativeReady(
            isReady: () => _nativeLovenseReady,
            device: 'lovense',
            label: 'Lovense',
          );
        }
      }
      await BleChannel.lovenseVibrateFor(level: 12, durationMs: 1500);
      if (BleChannel.useNativeLovense) {
        await _waitForNativeWriteAck(device: 'lovense');
      }
    });
  }

  Future<void> _testPavlokOnly() {
    return _runToyAction('Pavlok test', () async {
      _resetNativeAttemptState('pavlok');
      if (BleChannel.useNativePavlok && !_nativePavlokReady) {
        final connected = await BleChannel.pavlokIsConnectedNative();
        if (connected) {
          _nativePavlokReady = true;
        } else {
          await BleChannel.pavlokScan();
          await _waitForNativeReady(
            isReady: () => _nativePavlokReady,
            device: 'pavlok',
            label: 'Pavlok',
          );
        }
      }
      await BleChannel.pavlokVibrate(intensity: 140, durationMs: 1200);
      if (BleChannel.useNativePavlok) {
        await _waitForNativeWriteAck(device: 'pavlok');
      }
    });
  }

  String _deviceLabel(BluetoothDevice device) {
    final platform = device.platformName.trim();
    final advertised = device.advName.trim();
    final merged = ('$platform $advertised').trim();
    return merged.isEmpty ? 'Unnamed device' : merged;
  }

  Future<BluetoothDevice?> _pickDeviceModal({
    required String title,
    required List<BluetoothDevice> devices,
  }) {
    return showModalBottomSheet<BluetoothDevice>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        final maxHeight = MediaQuery.of(context).size.height * 0.62;
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(title, style: Theme.of(context).textTheme.titleMedium),
                ),
              ),
              SizedBox(
                height: maxHeight,
                child: ListView.builder(
                  itemCount: devices.length,
                  itemBuilder: (context, index) {
                    final device = devices[index];
                    return ListTile(
                      title: Text(_deviceLabel(device)),
                      subtitle: Text(device.remoteId.str),
                      onTap: () => Navigator.pop(context, device),
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _pairLovenseWithPicker(BleService ble) async {
    if (BleChannel.useNativeLovense) {
      _resetNativeAttemptState('lovense');
      await _syncNativeReadyFromBridge('lovense');
      final useAutoSelect = await _confirmNativePairMode(deviceName: 'Lovense');
      if (useAutoSelect) {
        await _runLovenseAction('scan', () async {
          await BleChannel.lovenseScan();
          await _waitForNativeReady(
            isReady: () => _nativeLovenseReady,
            device: 'lovense',
            label: 'Lovense',
          );
        });
        return;
      }
      await _runLovenseAction('selector scan', () async {
        final devices = _filterLovenseNativeDevices(
          await BleChannel.lovenseScanCandidatesNative(),
        );
        if (!mounted) return;
        if (devices.isEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('No Lovense devices found.')),
          );
          return;
        }
        final selected = await _pickNativeDeviceModal(
          title: 'Select Lovense Device',
          devices: devices,
        );
        if (selected == null) return;
        final address = (selected['address'] ?? '').toString();
        if (address.isEmpty) {
          throw StateError('Selected Lovense device has no BLE address.');
        }
        await BleChannel.lovenseConnectAddressNative(address);
        await _waitForNativeReady(
          isReady: () => _nativeLovenseReady,
          device: 'lovense',
          label: 'Lovense',
        );
      });
      return;
    }
    await _runLovenseAction('scan', () async {
      final devices =
          await ble.scanLovenseCandidates(timeout: const Duration(seconds: 8));
      if (!mounted) return;
      if (devices.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('No Lovense devices found.')),
        );
        return;
      }
      final selected = await _pickDeviceModal(
        title: 'Select Lovense Device',
        devices: devices,
      );
      if (selected == null) return;
      await ble.connectLovenseDevice(selected);
    });
  }

  Future<void> _pairPavlokWithPicker(BleService ble) async {
    if (BleChannel.useNativePavlok) {
      _resetNativeAttemptState('pavlok');
      await _syncNativeReadyFromBridge('pavlok');
      final useAutoSelect = await _confirmNativePairMode(deviceName: 'Pavlok');
      if (useAutoSelect) {
        await _runToyAction(
          'Pavlok scan (${BleChannel.pavlokPathLabel})',
          () async {
            await BleChannel.pavlokScan();
            await _waitForNativeReady(
              isReady: () => _nativePavlokReady,
              device: 'pavlok',
              label: 'Pavlok',
            );
          },
        );
        return;
      }
      await _runToyAction('Pavlok selector scan', () async {
        final devices = _filterPavlokNativeDevices(
          await BleChannel.pavlokScanCandidatesNative(),
        );
        if (!mounted) return;
        if (devices.isEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('No Pavlok devices found.')),
          );
          return;
        }
        final selected = await _pickNativeDeviceModal(
          title: 'Select Pavlok Device',
          devices: devices,
        );
        if (selected == null) return;
        final address = (selected['address'] ?? '').toString();
        if (address.isEmpty) {
          throw StateError('Selected Pavlok device has no BLE address.');
        }
        await BleChannel.pavlokConnectAddressNative(address);
        await _waitForNativeReady(
          isReady: () => _nativePavlokReady,
          device: 'pavlok',
          label: 'Pavlok',
        );
      });
      return;
    }
    await _runToyAction('Pavlok scan', () async {
      final devices =
          await ble.scanPavlokCandidates(timeout: const Duration(seconds: 8));
      if (!mounted) return;
      if (devices.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('No Pavlok devices found.')),
        );
        return;
      }
      final selected = await _pickDeviceModal(
        title: 'Select Pavlok Device',
        devices: devices,
      );
      if (selected == null) return;
      await ble.connectPavlokDevice(selected);
    });
  }

  void _resetNativeAttemptState(String device) {
    if (device == 'lovense') {
      _nativeLovenseLastError = null;
      _nativeLovenseWriteFailDetail = null;
    } else if (device == 'pavlok') {
      _nativePavlokLastError = null;
      _nativePavlokWriteFailDetail = null;
    }
  }

  Future<void> _syncNativeReadyFromBridge(String device) async {
    if (device == 'lovense') {
      final connected = await BleChannel.lovenseIsConnectedNative();
      if (!mounted) return;
      setState(() => _nativeLovenseReady = connected);
      return;
    }
    if (device == 'pavlok') {
      final connected = await BleChannel.pavlokIsConnectedNative();
      if (!mounted) return;
      setState(() => _nativePavlokReady = connected);
    }
  }

  @override
  void initState() {
    super.initState();
    _endpointController = TextEditingController();
    BleChannel.setLovensePath(useNative: true);
    BleChannel.setPavlokPath(useNative: true);
    _nativeBleSub = _nativeBleEvents.receiveBroadcastStream().listen(
      _onNativeBleEvent,
      onError: (_) {
        // Optional stream; the native bridge can still pair without it.
      },
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_syncNativeReadyFromBridge('lovense'));
      unawaited(_syncNativeReadyFromBridge('pavlok'));
      final ble = context.read<BleService>();
      unawaited(_refreshLovenseBattery(ble, silent: true));
      unawaited(_refreshPavlokBattery(ble, silent: true));
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final svc = context.read<IntifaceService>();
    if (_endpointController.text != svc.endpoint) {
      _endpointController.text = svc.endpoint;
    }
  }

  @override
  void dispose() {
    unawaited(_nativeBleSub?.cancel());
    _endpointController.dispose();
    super.dispose();
  }

  void _onNativeBleEvent(dynamic raw) {
    if (!mounted || raw is! Map) return;
    final event = Map<Object?, Object?>.from(raw);
    final device = (event['device'] ?? '').toString();
    final type = (event['type'] ?? '').toString();
    final payload = Map<Object?, Object?>.from(event);
    payload.remove('device');
    payload.remove('type');

    final isReady = type == 'ready';
    final isDisconnected = type == 'disconnected';
    final batteryPct = payload['battery_pct'] is num
      ? (payload['battery_pct'] as num).toInt().clamp(0, 100)
      : null;

    if (device == 'lovense') {
      if (type == 'notification') {
        final text = (payload['text'] ?? '').toString();
        final requestedRecently = _lastLovenseBatteryRequestAt != null &&
            DateTime.now().difference(_lastLovenseBatteryRequestAt!) < const Duration(seconds: 20);
        if (requestedRecently) {
          final parsed = _parseBatteryFromNotificationText(text);
          if (parsed != null) {
            setState(() => _nativeLovenseBatteryPct = parsed);
          }
        }
      }
      if (isReady && !_nativeLovenseReady) {
        setState(() => _nativeLovenseReady = true);
        unawaited(_refreshLovenseBattery(context.read<BleService>(), silent: true));
      } else if (isDisconnected && _nativeLovenseReady) {
        setState(() {
          _nativeLovenseReady = false;
          _nativeLovenseBatteryPct = null;
        });
      }
      if (batteryPct != null) {
        setState(() => _nativeLovenseBatteryPct = batteryPct);
      }
      if (type == 'service_missing' ||
          type == 'characteristic_missing' ||
          type == 'services_discovery_failed' ||
          type == 'connect_permission_missing' ||
          type == 'battery_unavailable' ||
          type == 'battery_read_failed') {
        _nativeLovenseLastError = '$type: $payload';
      }
      if (type == 'write_ok') {
        _nativeLovenseWriteOkCount += 1;
      } else if (type == 'write_failed') {
        _nativeLovenseWriteFailCount += 1;
        _nativeLovenseWriteFailDetail = payload.toString();
        _nativeLovenseLastError = 'write_failed: $payload';
      }
    } else if (device == 'pavlok') {
      if (type == 'notification') {
        final text = (payload['text'] ?? '').toString();
        final requestedRecently = _lastPavlokBatteryRequestAt != null &&
            DateTime.now().difference(_lastPavlokBatteryRequestAt!) < const Duration(seconds: 20);
        if (requestedRecently) {
          final parsed = _parseBatteryFromNotificationText(text);
          if (parsed != null) {
            setState(() => _nativePavlokBatteryPct = parsed);
          }
        }
      }
      if (isReady && !_nativePavlokReady) {
        setState(() => _nativePavlokReady = true);
        unawaited(_refreshPavlokBattery(context.read<BleService>(), silent: true));
      } else if (isDisconnected && _nativePavlokReady) {
        setState(() {
          _nativePavlokReady = false;
          _nativePavlokBatteryPct = null;
        });
      }
      if (batteryPct != null) {
        setState(() => _nativePavlokBatteryPct = batteryPct);
      }
      if (type == 'service_missing' ||
          type == 'characteristic_missing' ||
          type == 'services_discovery_failed' ||
          type == 'connect_permission_missing' ||
          type == 'battery_unavailable' ||
          type == 'battery_read_failed') {
        _nativePavlokLastError = '$type: $payload';
      }
      if (type == 'write_ok') {
        _nativePavlokWriteOkCount += 1;
      } else if (type == 'write_failed') {
        _nativePavlokWriteFailCount += 1;
        _nativePavlokWriteFailDetail = payload.toString();
        _nativePavlokLastError = 'write_failed: $payload';
      }
    }
  }

  Future<void> _waitForNativeWriteAck({
    required String device,
    Duration timeout = const Duration(seconds: 4),
  }) async {
    final started = DateTime.now();
    final initialOk =
        device == 'lovense' ? _nativeLovenseWriteOkCount : _nativePavlokWriteOkCount;
    final initialFail = device == 'lovense'
        ? _nativeLovenseWriteFailCount
        : _nativePavlokWriteFailCount;

    while (DateTime.now().difference(started) < timeout) {
      final currentOk =
          device == 'lovense' ? _nativeLovenseWriteOkCount : _nativePavlokWriteOkCount;
      if (currentOk > initialOk) return;

      final currentFail = device == 'lovense'
          ? _nativeLovenseWriteFailCount
          : _nativePavlokWriteFailCount;
      if (currentFail > initialFail) {
        final detail = device == 'lovense'
            ? _nativeLovenseWriteFailDetail
            : _nativePavlokWriteFailDetail;
        throw Exception('Native write failed for $device${detail == null ? '' : ': $detail'}');
      }

      await Future<void>.delayed(const Duration(milliseconds: 150));
    }

    throw Exception('Native write timeout for $device (no write_ok event).');
  }

  Future<void> _waitForNativeReady({
    required bool Function() isReady,
    required String device,
    required String label,
    Duration timeout = const Duration(seconds: 12),
  }) async {
    final started = DateTime.now();
    while (DateTime.now().difference(started) < timeout) {
      if (isReady()) return;

      final lastError =
          device == 'lovense' ? _nativeLovenseLastError : _nativePavlokLastError;
      if (lastError != null) {
        throw Exception('$label connect failed: $lastError');
      }

      await Future<void>.delayed(const Duration(milliseconds: 200));
    }
    throw Exception('$label did not finish connecting in time.');
  }

  Future<void> _refreshLovenseBattery(BleService ble, {bool silent = false}) async {
    try {
      if (BleChannel.useNativeLovense) {
        _lastLovenseBatteryRequestAt = DateTime.now();
        await BleChannel.lovenseBattery();
        await BleChannel.lovenseReadBatteryLevel();
      } else {
        await ble.refreshLovenseBatteryLevel();
      }
      if (!mounted || silent) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Lovense battery refresh requested.')),
      );
    } catch (error) {
      if (!mounted || silent) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Lovense battery refresh failed: $error')),
      );
    }
  }

  Future<void> _refreshPavlokBattery(BleService ble, {bool silent = false}) async {
    try {
      if (BleChannel.useNativePavlok) {
        _lastPavlokBatteryRequestAt = DateTime.now();
        await BleChannel.pavlokReadBatteryLevel();
      } else {
        await ble.refreshPavlokBatteryLevel();
      }
      if (!mounted || silent) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Pavlok battery refresh requested.')),
      );
    } catch (error) {
      if (!mounted || silent) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Pavlok battery refresh failed: $error')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final svc = context.watch<IntifaceService>();
    final ble = context.watch<BleService>();
    final cs = Theme.of(context).colorScheme;
    final lovenseBatteryPct = BleChannel.useNativeLovense
      ? _nativeLovenseBatteryPct
      : ble.lovenseBatteryPct;
    final pavlokBatteryPct = BleChannel.useNativePavlok
      ? _nativePavlokBatteryPct
      : ble.pavlokBatteryPct;

    return Scaffold(
      appBar: AppBar(title: const Text('Buttplug / Intiface')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Text(
                'Lovense and Pavlok pair through the native bridge here. Battery status is pulled from the bridge and the device battery service when available.',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Lovense path: native bridge',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 10),
                  Text(
                    'Pavlok path: native bridge',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          _StatusCard(svc: svc),
          const SizedBox(height: 16),
          TextField(
            controller: _endpointController,
            decoration: const InputDecoration(
              labelText: 'Buttplug / Intiface WebSocket Endpoint',
              hintText: 'ws://127.0.0.1:12345',
            ),
            onSubmitted: svc.setEndpoint,
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: OutlinedButton(
              onPressed: () => svc.setEndpoint(_endpointController.text),
              child: const Text('Save Endpoint'),
            ),
          ),
          if (svc.lastError != null && svc.lastError!.trim().isNotEmpty) ...[
            const SizedBox(height: 8),
            Card(
              color: cs.errorContainer,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(
                  svc.lastError!,
                  style: TextStyle(color: cs.onErrorContainer),
                ),
              ),
            ),
          ],
          const SizedBox(height: 16),
          _ConnectButton(svc: svc),
          if (svc.status == IntifaceStatus.connected) ...[
            const SizedBox(height: 24),
            _ScanningCard(svc: svc),
            if (svc.deviceIndex != null) ...[
              const SizedBox(height: 16),
              _DeviceCard(svc: svc),
              const SizedBox(height: 24),
              _TestPanel(
                intensity: _testIntensity,
                onChanged: (v) {
                  setState(() => _testIntensity = v);
                  svc.setVibration(v);
                },
                onStop: () {
                  setState(() => _testIntensity = 0.0);
                  svc.setVibration(0.0);
                },
                cs: cs,
              ),
            ],
          ],
          const SizedBox(height: 28),
          _ToyPairingCard(
            title: 'Lovense',
            subtitle: 'Pair a Lovense toy through the native bridge. Device stays persisted and auto-repaired.',
            paired: ble.lovenseConnected || _nativeLovenseReady,
            batteryPct: lovenseBatteryPct,
            error: ble.lovenseError,
            onPair: () => _pairLovenseWithPicker(ble),
            onTest: _testLovenseOnly,
            onStop: () =>
                _runLovenseAction('stop', BleChannel.lovenseStopAll),
            onRefreshBattery: () => _refreshLovenseBattery(ble),
          ),
          const SizedBox(height: 16),
          _ToyPairingCard(
            title: 'Pavlok',
            subtitle: 'Pair a Pavlok wristband through the native bridge. Device stays persisted and auto-repaired.',
            paired: ble.pavlokConnected || _nativePavlokReady,
            batteryPct: pavlokBatteryPct,
            error: ble.pavlokError,
            onPair: () => _pairPavlokWithPicker(ble),
            onTest: _testPavlokOnly,
            onStop: () => _runToyAction('Pavlok stop', BleChannel.pavlokStopAll),
            onRefreshBattery: () => _refreshPavlokBattery(ble),
          ),
        ],
      ),
    );
  }
}

// ── Status card ───────────────────────────────────────────────────────────────

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.svc});
  final IntifaceService svc;

  @override
  Widget build(BuildContext context) {
    final (icon, label, color) = switch (svc.status) {
      IntifaceStatus.disconnected => (
          Icons.link_off,
          'Disconnected',
          Theme.of(context).colorScheme.error,
        ),
      IntifaceStatus.connecting => (
          Icons.sync,
          'Connecting…',
          Theme.of(context).colorScheme.secondary,
        ),
      IntifaceStatus.connected => (
          Icons.link,
          svc.serverName != null
              ? 'Connected — ${svc.serverName}'
              : 'Connected',
          Theme.of(context).colorScheme.primary,
        ),
    };

    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            Icon(icon, color: color, size: 28),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                label,
                style: Theme.of(context)
                    .textTheme
                    .titleMedium
                    ?.copyWith(color: color),
              ),
            ),
            if (svc.status == IntifaceStatus.connecting)
              SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: color,
                ),
              ),
          ],
        ),
      ),
    );
  }
}

// ── Connect / Disconnect button ───────────────────────────────────────────────

class _ConnectButton extends StatelessWidget {
  const _ConnectButton({required this.svc});
  final IntifaceService svc;

  @override
  Widget build(BuildContext context) {
    final connecting = svc.status == IntifaceStatus.connecting;
    final connected  = svc.status == IntifaceStatus.connected;

    return FilledButton.icon(
      onPressed: connecting
          ? null
          : () => connected ? svc.disconnect() : svc.connect(),
      icon: Icon(connected ? Icons.link_off : Icons.link),
      label: Text(connected ? 'Disconnect' : 'Connect to Intiface'),
    );
  }
}

// ── Scanning status card ──────────────────────────────────────────────────────

class _ScanningCard extends StatelessWidget {
  const _ScanningCard({required this.svc});
  final IntifaceService svc;

  @override
  Widget build(BuildContext context) {
    final hasDevice = svc.deviceIndex != null;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            if (hasDevice)
              Icon(Icons.bluetooth_connected,
                  color: Theme.of(context).colorScheme.primary)
            else
              SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Theme.of(context).colorScheme.secondary,
                ),
              ),
            const SizedBox(width: 12),
            Text(
              hasDevice ? 'Device found' : 'Scanning for BLE toys…',
              style: Theme.of(context).textTheme.bodyLarge,
            ),
          ],
        ),
      ),
    );
  }
}

// ── Device info card ──────────────────────────────────────────────────────────

class _DeviceCard extends StatelessWidget {
  const _DeviceCard({required this.svc});
  final IntifaceService svc;

  @override
  Widget build(BuildContext context) {
    final name = svc.deviceName ?? 'Unknown device';
    final idx  = svc.deviceIndex!;
    final cs   = Theme.of(context).colorScheme;

    return Card(
      color: cs.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Icon(Icons.vibration, color: cs.onPrimaryContainer, size: 32),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    name,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          color: cs.onPrimaryContainer,
                        ),
                  ),
                  Text(
                    'Device index: $idx',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: cs.onPrimaryContainer.withAlpha(180),
                        ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Test vibration panel ──────────────────────────────────────────────────────

class _TestPanel extends StatelessWidget {
  const _TestPanel({
    required this.intensity,
    required this.onChanged,
    required this.onStop,
    required this.cs,
  });

  final double intensity;
  final ValueChanged<double> onChanged;
  final VoidCallback onStop;
  final ColorScheme cs;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Test vibration',
          style: Theme.of(context).textTheme.titleSmall,
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            const Icon(Icons.volume_down),
            Expanded(
              child: Slider(
                value: intensity,
                onChanged: onChanged,
                label: '${(intensity * 100).round()}%',
                divisions: 20,
              ),
            ),
            const Icon(Icons.volume_up),
          ],
        ),
        const SizedBox(height: 4),
        Center(
          child: OutlinedButton.icon(
            onPressed: intensity > 0 ? onStop : null,
            icon: const Icon(Icons.stop),
            label: const Text('Stop'),
          ),
        ),
      ],
    );
  }
}

class _ToyPairingCard extends StatelessWidget {
  const _ToyPairingCard({
    required this.title,
    required this.subtitle,
    required this.paired,
    required this.batteryPct,
    required this.error,
    required this.onPair,
    required this.onTest,
    required this.onStop,
    required this.onRefreshBattery,
  });

  final String title;
  final String subtitle;
  final bool paired;
  final int? batteryPct;
  final String? error;
  final Future<void> Function() onPair;
  final Future<void> Function() onTest;
  final Future<void> Function() onStop;
  final Future<void> Function() onRefreshBattery;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 6),
            Text(subtitle),
            const SizedBox(height: 10),
            Text(paired ? 'Paired' : 'Not paired'),
            const SizedBox(height: 4),
            Text(
              batteryPct == null
                  ? (paired ? 'Battery: checking…' : 'Battery: unavailable')
                  : 'Battery: $batteryPct%',
            ),
            if (error != null && error!.trim().isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilledButton(onPressed: onPair, child: const Text('Pair')),
                OutlinedButton(onPressed: onTest, child: const Text('Test')),
                OutlinedButton(onPressed: onRefreshBattery, child: const Text('Battery')),
                TextButton(onPressed: onStop, child: const Text('Stop')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
