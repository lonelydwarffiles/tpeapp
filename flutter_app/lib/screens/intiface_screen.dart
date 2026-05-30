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
  static const EventChannel _nativeBleEvents =
      EventChannel('com.hound.controller/ble_events');

  double _testIntensity = 0.0;
  late final TextEditingController _endpointController;
  bool _useNativeLovense = BleChannel.useNativeLovense;
  bool _useNativePavlok = BleChannel.useNativePavlok;
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
  StreamSubscription<dynamic>? _nativeBleSubscription;

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

  Future<Map<String, dynamic>?> _pickNativeDeviceModal({
    required String title,
    required List<Map<String, dynamic>> devices,
  }) {
    return showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      showDragHandle: true,
      builder: (context) {
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
              Flexible(
                child: ListView.builder(
                  shrinkWrap: true,
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
              Flexible(
                child: ListView.builder(
                  shrinkWrap: true,
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
        final devices = await BleChannel.lovenseScanCandidatesNative();
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
        final devices = await BleChannel.pavlokScanCandidatesNative();
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
    _nativeBleSubscription = _nativeBleEvents.receiveBroadcastStream().listen(
      _onNativeBleEvent,
      onError: (_) {
        // Optional stream; ignore if unavailable.
      },
    );
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
    _nativeBleSubscription?.cancel();
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

    if (device == 'lovense') {
      if (isReady && !_nativeLovenseReady) {
        setState(() => _nativeLovenseReady = true);
      } else if (isDisconnected && _nativeLovenseReady) {
        setState(() => _nativeLovenseReady = false);
      }
      if (type == 'service_missing' ||
          type == 'characteristic_missing' ||
          type == 'services_discovery_failed' ||
          type == 'connect_permission_missing') {
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
      if (isReady && !_nativePavlokReady) {
        setState(() => _nativePavlokReady = true);
      } else if (isDisconnected && _nativePavlokReady) {
        setState(() => _nativePavlokReady = false);
      }
      if (type == 'service_missing' ||
          type == 'characteristic_missing' ||
          type == 'services_discovery_failed' ||
          type == 'connect_permission_missing') {
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

  @override
  Widget build(BuildContext context) {
    final svc = context.watch<IntifaceService>();
    final ble = context.watch<BleService>();
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(title: const Text('Buttplug / Intiface')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Text(
                'We-Vibe devices pair through Buttplug / Intiface. Pavlok uses direct Bluetooth. Lovense can use native bridge (SDK-ready) or direct Bluetooth fallback.',
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
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          'Lovense native bridge path',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                      Switch(
                        value: _useNativeLovense,
                        onChanged: (value) {
                          setState(() => _useNativeLovense = value);
                          BleChannel.setLovensePath(useNative: value);
                        },
                      ),
                    ],
                  ),
                  Text(
                    'Active Lovense path: ${BleChannel.lovensePathLabel}',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          'Pavlok native bridge path',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                      Switch(
                        value: _useNativePavlok,
                        onChanged: (value) {
                          setState(() => _useNativePavlok = value);
                          BleChannel.setPavlokPath(useNative: value);
                        },
                      ),
                    ],
                  ),
                  Text(
                    'Active Pavlok path: ${BleChannel.pavlokPathLabel}',
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
            subtitle: 'Pair a Lovense toy directly over Bluetooth. Device stays persisted and auto-repaired.',
            paired: ble.lovenseConnected || _nativeLovenseReady,
            error: ble.lovenseError,
            onPair: () => _pairLovenseWithPicker(ble),
            onTest: _testLovenseOnly,
            onStop: () =>
                _runLovenseAction('stop', BleChannel.lovenseStopAll),
          ),
          const SizedBox(height: 16),
          _ToyPairingCard(
            title: 'Pavlok',
            subtitle: 'Pair a Pavlok wristband directly over Bluetooth. Device stays persisted and auto-repaired.',
            paired: ble.pavlokConnected || _nativePavlokReady,
            error: ble.pavlokError,
            onPair: () => _pairPavlokWithPicker(ble),
            onTest: _testPavlokOnly,
            onStop: () => _runToyAction('Pavlok stop', BleChannel.pavlokStopAll),
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
    required this.error,
    required this.onPair,
    required this.onTest,
    required this.onStop,
  });

  final String title;
  final String subtitle;
  final bool paired;
  final String? error;
  final Future<void> Function() onPair;
  final Future<void> Function() onTest;
  final Future<void> Function() onStop;

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
                TextButton(onPressed: onStop, child: const Text('Stop')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
