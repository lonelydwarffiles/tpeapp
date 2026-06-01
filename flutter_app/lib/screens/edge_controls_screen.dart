import 'package:flutter/material.dart';
import 'dart:async';

class EdgeControlsViewData {
  const EdgeControlsViewData({
    required this.edgeCount,
    required this.orgasmCount,
    required this.edgePendingCount,
    required this.orgasmPendingCount,
    required this.edgeTargetCount,
    required this.edgeTargetShockAtPeak,
    required this.manualBuzzHoldUntilLowHr,
    required this.counterApproveInFlight,
    required this.edgeTargetStepInFlight,
  });

  final int edgeCount;
  final int orgasmCount;
  final int edgePendingCount;
  final int orgasmPendingCount;
  final int edgeTargetCount;
  final bool edgeTargetShockAtPeak;
  final bool manualBuzzHoldUntilLowHr;
  final bool counterApproveInFlight;
  final bool edgeTargetStepInFlight;
}

class EdgeControlsScreen extends StatefulWidget {
  const EdgeControlsScreen({
    super.key,
    required this.readState,
    required this.onQueueEdge,
    required this.onQueueOrgasm,
    required this.onHoldBuzzUntilLowHr,
    required this.onApprovePending,
    required this.onUndoEdge,
    required this.onUndoOrgasm,
    required this.onSetEdgeTargetCount,
    required this.onSetPeakShockEnabled,
    required this.onRefreshState,
    required this.onStopActuationNow,
  });

  final EdgeControlsViewData Function() readState;
  final Future<void> Function() onQueueEdge;
  final Future<void> Function() onQueueOrgasm;
  final Future<void> Function() onHoldBuzzUntilLowHr;
  final Future<void> Function() onApprovePending;
  final Future<void> Function() onUndoEdge;
  final Future<void> Function() onUndoOrgasm;
  final Future<void> Function(int targetCount) onSetEdgeTargetCount;
  final Future<void> Function(bool enabled) onSetPeakShockEnabled;
  final Future<void> Function() onRefreshState;
  final Future<void> Function() onStopActuationNow;

  @override
  State<EdgeControlsScreen> createState() => _EdgeControlsScreenState();
}

class _EdgeControlsScreenState extends State<EdgeControlsScreen> {
  late final TextEditingController _targetCtrl;
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    _targetCtrl = TextEditingController();
    final data = widget.readState();
    _targetCtrl.text = data.edgeTargetCount.toString();

    // Keep view state current while this route is open.
    _refreshTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (!mounted) return;
      setState(() {});
    });
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    _targetCtrl.dispose();
    super.dispose();
  }

  Future<void> _runAndRefresh(Future<void> Function() action) async {
    await action();
    if (!mounted) return;
    setState(() {
      // Pull latest parent values via readState on rebuild.
    });
  }

  Future<void> _saveTargetFromInput() async {
    final parsed = int.tryParse(_targetCtrl.text.trim());
    if (parsed == null || parsed < 0) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Enter a valid target (0 or higher).')),
        );
      }
      return;
    }
    await _runAndRefresh(() => widget.onSetEdgeTargetCount(parsed));
  }

  Future<void> _addTargetFromInput() async {
    final parsed = int.tryParse(_targetCtrl.text.trim());
    if (parsed == null || parsed < 0) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Enter a valid number to add (0 or higher).')),
        );
      }
      return;
    }
    final data = widget.readState();
    final nextTarget = (data.edgeCount + parsed).clamp(0, 1000000);
    await _runAndRefresh(() => widget.onSetEdgeTargetCount(nextTarget));
  }

  @override
  Widget build(BuildContext context) {
    final data = widget.readState();
    final canApprove =
        !data.counterApproveInFlight && (data.edgePendingCount > 0 || data.orgasmPendingCount > 0);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Edge Controls'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Session Counters', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 8),
                  Text('Edges: ${data.edgeCount} • Orgasms: ${data.orgasmCount}'),
                  const SizedBox(height: 4),
                  Text(
                    'Pending: ${data.edgePendingCount} edges • ${data.orgasmPendingCount} orgasms',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Edge Automation', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 8),
                  Text(
                    data.edgeTargetCount > 0
                        ? 'Target: ${data.edgeCount}/${data.edgeTargetCount}${data.edgeTargetStepInFlight ? ' (running)' : ''}'
                        : 'Target: off',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Peak shock: ${data.edgeTargetShockAtPeak ? 'on' : 'off'}',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    data.manualBuzzHoldUntilLowHr
                        ? 'Buzz hold: active until HR is back in range'
                        : 'Buzz hold: off',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _targetCtrl,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(
                            labelText: 'Edge goal input',
                            hintText: 'Use Save as absolute or Add To Current',
                          ),
                          onSubmitted: (_) => unawaited(_saveTargetFromInput()),
                        ),
                      ),
                      const SizedBox(width: 8),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      FilledButton(
                        onPressed: () => unawaited(_saveTargetFromInput()),
                        child: const Text('Save Absolute'),
                      ),
                      FilledButton.tonal(
                        onPressed: () => unawaited(_addTargetFromInput()),
                        child: const Text('Add To Current'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  SwitchListTile.adaptive(
                    value: data.edgeTargetShockAtPeak,
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Peak Shock At Target Edge'),
                    subtitle: const Text('Fire Pavlok zap when HR pause threshold is hit'),
                    onChanged: (enabled) => unawaited(
                      _runAndRefresh(() => widget.onSetPeakShockEnabled(enabled)),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              FilledButton.icon(
                onPressed: () => _runAndRefresh(widget.onQueueEdge),
                icon: const Icon(Icons.trending_up),
                label: const Text('Queue Edge'),
              ),
              FilledButton.tonalIcon(
                onPressed: () => _runAndRefresh(widget.onQueueOrgasm),
                icon: const Icon(Icons.favorite),
                label: const Text('Queue Orgasm'),
              ),
              OutlinedButton.icon(
                onPressed: () => _runAndRefresh(widget.onHoldBuzzUntilLowHr),
                icon: Icon(
                  data.manualBuzzHoldUntilLowHr
                      ? Icons.pause_circle_filled
                      : Icons.pause_circle_outline,
                ),
                label: Text(
                  data.manualBuzzHoldUntilLowHr
                      ? 'Buzz Hold Active'
                      : 'Hold Buzz Until HR Low',
                ),
              ),
              OutlinedButton.icon(
                onPressed: canApprove
                    ? () => _runAndRefresh(widget.onApprovePending)
                    : null,
                icon: data.counterApproveInFlight
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.cloud_upload_outlined),
                label: Text(data.counterApproveInFlight ? 'Approving...' : 'Approve Pending'),
              ),
              OutlinedButton.icon(
                onPressed: () => _runAndRefresh(widget.onStopActuationNow),
                icon: const Icon(Icons.stop_circle_outlined),
                label: const Text('Stop Actuation'),
              ),
              OutlinedButton.icon(
                onPressed: () => _runAndRefresh(widget.onRefreshState),
                icon: const Icon(Icons.refresh),
                label: const Text('Refresh State'),
              ),
              OutlinedButton.icon(
                onPressed: () => _runAndRefresh(widget.onUndoEdge),
                icon: const Icon(Icons.remove_circle_outline),
                label: const Text('Undo Edge'),
              ),
              OutlinedButton.icon(
                onPressed: () => _runAndRefresh(widget.onUndoOrgasm),
                icon: const Icon(Icons.remove_circle_outline),
                label: const Text('Undo Orgasm'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
