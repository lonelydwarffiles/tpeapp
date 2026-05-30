import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
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
  double _testIntensity = 0.0;
  late final TextEditingController _endpointController;
  bool _useNativeLovense = BleChannel.useNativeLovense;

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
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('$actionLabel failed: $error')),
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

  @override
  void initState() {
    super.initState();
    _endpointController = TextEditingController();
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
    _endpointController.dispose();
    super.dispose();
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
            subtitle: 'Pair a Lovense toy directly over Bluetooth.',
            paired: ble.lovenseConnected,
            error: ble.lovenseError,
            onPair: () => _pairLovenseWithPicker(ble),
            onDisconnect: () =>
                _runLovenseAction('disconnect', BleChannel.lovenseDisconnect),
            onTest: () =>
                _runLovenseAction('test', () => BleChannel.lovenseVibrate(10)),
            onStop: () =>
                _runLovenseAction('stop', BleChannel.lovenseStopAll),
          ),
          const SizedBox(height: 16),
          _ToyPairingCard(
            title: 'Pavlok',
            subtitle: 'Pair a Pavlok wristband directly over Bluetooth.',
            paired: ble.pavlokConnected,
            error: ble.pavlokError,
            onPair: () => _pairPavlokWithPicker(ble),
            onDisconnect: () =>
                _runToyAction('Pavlok disconnect', BleChannel.pavlokDisconnect),
            onTest: () => _runToyAction(
                'Pavlok test',
                () => BleChannel.pavlokBeep(intensity: 96, durationMs: 700)),
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
    required this.onDisconnect,
    required this.onTest,
    required this.onStop,
  });

  final String title;
  final String subtitle;
  final bool paired;
  final String? error;
  final Future<void> Function() onPair;
  final Future<void> Function() onDisconnect;
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
                OutlinedButton(onPressed: onDisconnect, child: const Text('Disconnect')),
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
