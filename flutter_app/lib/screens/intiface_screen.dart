import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

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

  @override
  Widget build(BuildContext context) {
    final svc = context.watch<IntifaceService>();
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(title: const Text('Toy Control')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _StatusCard(svc: svc),
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
