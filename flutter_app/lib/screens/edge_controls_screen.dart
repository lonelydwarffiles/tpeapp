import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';

class EdgeControlsViewData {
  const EdgeControlsViewData({
    required this.edgeCount,
    required this.orgasmCount,
    required this.orgasmPermissionTokens,
    required this.orgasmDeniedCycles,
    required this.orgasmPleadTier,
    required this.edgeTimelineEventCount,
    required this.edgeTimelineLastEvent,
    required this.edgeSafetyProfile,
    required this.edgeBypassCooldownRemainingMs,
    required this.edgePendingCount,
    required this.orgasmPendingCount,
    required this.edgeTargetCount,
    required this.edgeTargetShockAtPeak,
    required this.manualBuzzHoldUntilLowHr,
    required this.counterApproveInFlight,
    required this.edgeTargetStepInFlight,
    required this.lastEdgeSource,
    required this.lastEdgeAtMs,
    required this.buzzSessionEdgeCount,
    required this.outOfItAutoTriggered,
  });

  final int edgeCount;
  final int orgasmCount;
  final int orgasmPermissionTokens;
  final int orgasmDeniedCycles;
  final String orgasmPleadTier;
  final int edgeTimelineEventCount;
  final String edgeTimelineLastEvent;
  final String edgeSafetyProfile;
  final int edgeBypassCooldownRemainingMs;
  final int edgePendingCount;
  final int orgasmPendingCount;
  final int edgeTargetCount;
  final bool edgeTargetShockAtPeak;
  final bool manualBuzzHoldUntilLowHr;
  final bool counterApproveInFlight;
  final bool edgeTargetStepInFlight;
  final String lastEdgeSource;
  final int lastEdgeAtMs;
  final int buzzSessionEdgeCount;
  final bool outOfItAutoTriggered;
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
    required this.onSetSafetyProfile,
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
  final Future<void> Function(String profile) onSetSafetyProfile;
  final Future<void> Function() onRefreshState;
  final Future<void> Function() onStopActuationNow;

  @override
  State<EdgeControlsScreen> createState() => _EdgeControlsScreenState();
}

class _EdgeControlsScreenState extends State<EdgeControlsScreen> {
  late final TextEditingController _targetCtrl;
  Timer? _refreshTimer;
  bool _focusMode = false;
  bool _focusModeAutoEnabled = false;

  @override
  void initState() {
    super.initState();
    _targetCtrl = TextEditingController();
    final data = widget.readState();
    _targetCtrl.text = data.edgeTargetCount.toString();
    _updateAutoFocusMode(data);

    // Keep view state current while this route is open.
    _refreshTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (!mounted) return;
      _updateAutoFocusMode(widget.readState());
      setState(() {
        // Pull latest parent values via readState on rebuild.
      });
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

  Future<void> _runAndRefreshWithHaptic(
    Future<void> Function() action, {
    Future<void> Function()? hapticBefore,
    Future<void> Function()? hapticAfter,
  }) async {
    if (hapticBefore != null) {
      await hapticBefore();
    }
    await _runAndRefresh(action);
    if (hapticAfter != null) {
      await hapticAfter();
    }
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

  String _edgeSourceText(EdgeControlsViewData data) {
    switch (data.lastEdgeSource) {
      case 'auto':
        return 'Auto';
      case 'manual':
        return 'Manual';
      default:
        return 'None';
    }
  }

  String _pleadLabel(String tier) {
    switch (tier) {
      case 'pleading':
        return 'Pleading';
      case 'needy':
        return 'Needy';
      default:
        return 'Neutral';
    }
  }

  String _profileLabel(String profile) {
    switch (profile) {
      case 'recovery_heavy':
        return 'Recovery Heavy';
      case 'training':
        return 'Training';
      case 'chaos':
        return 'Chaos Trial';
      default:
        return 'Strict Handler';
    }
  }

  String _cooldownLabel(int remainingMs) {
    if (remainingMs <= 0) {
      return 'Ready';
    }
    final totalSec = (remainingMs / 1000).ceil();
    final mins = totalSec ~/ 60;
    final secs = totalSec % 60;
    if (mins <= 0) {
      return 'Cooldown ${secs}s';
    }
    return 'Cooldown ${mins}m ${secs}s';
  }

  void _updateAutoFocusMode(EdgeControlsViewData data) {
    if (!_focusModeAutoEnabled && (data.outOfItAutoTriggered || data.buzzSessionEdgeCount >= 3)) {
      _focusModeAutoEnabled = true;
      _focusMode = true;
      unawaited(HapticFeedback.mediumImpact());
    }
  }

  String _edgeAgeText(int lastEdgeAtMs) {
    if (lastEdgeAtMs <= 0) {
      return '--';
    }
    final diff = DateTime.now().millisecondsSinceEpoch - lastEdgeAtMs;
    if (diff < 60000) {
      return 'now';
    }
    final mins = (diff / 60000).floor();
    if (mins < 60) {
      return '${mins}m';
    }
    final hours = (mins / 60).floor();
    return '${hours}h';
  }

  @override
  Widget build(BuildContext context) {
    final data = widget.readState();
    _updateAutoFocusMode(data);
    final canApprove =
        !data.counterApproveInFlight && (data.edgePendingCount > 0 || data.orgasmPendingCount > 0);
    final cs = Theme.of(context).colorScheme;
    final titleStyle = Theme.of(context).textTheme.titleLarge;
    final countStyle = Theme.of(context).textTheme.headlineMedium?.copyWith(
      fontWeight: FontWeight.w700,
      height: 1.1,
    );
    final largeButtonStyle = FilledButton.styleFrom(
      minimumSize: const Size.fromHeight(62),
      textStyle: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
    );
    final largeOutlineButtonStyle = OutlinedButton.styleFrom(
      minimumSize: const Size.fromHeight(56),
      textStyle: Theme.of(context).textTheme.titleMedium,
    );

    return Scaffold(
      appBar: AppBar(
        title: const Text('Edge Controls'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Card(
            child: SwitchListTile.adaptive(
              value: _focusMode,
              contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              title: const Text('Out-of-it mode'),
              subtitle: Text(
                _focusModeAutoEnabled
                  ? 'Auto-on triggered (3+ edges in one buzz session).'
                  : 'Auto-on after 3 edges in one buzz session. (${data.buzzSessionEdgeCount}/3)',
              ),
              onChanged: (enabled) {
                setState(() {
                  _focusMode = enabled;
                });
              },
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Now', style: titleStyle),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                          decoration: BoxDecoration(
                            color: cs.surfaceContainerHighest,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('Edges', style: Theme.of(context).textTheme.bodyMedium),
                              const SizedBox(height: 2),
                              Text('${data.edgeCount}', style: countStyle),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                          decoration: BoxDecoration(
                            color: cs.surfaceContainerHighest,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('Orgasms', style: Theme.of(context).textTheme.bodyMedium),
                              const SizedBox(height: 2),
                              Text('${data.orgasmCount}', style: countStyle),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text(
                    'Pending: ${data.edgePendingCount} edge • ${data.orgasmPendingCount} orgasm',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Chip(
                        avatar: Icon(
                          data.lastEdgeSource == 'auto' ? Icons.smart_toy_outlined : Icons.person_outline,
                          size: 16,
                        ),
                        label: Text('Last edge: ${_edgeSourceText(data)}'),
                      ),
                      const SizedBox(width: 8),
                      Chip(
                        avatar: const Icon(Icons.schedule_outlined, size: 16),
                        label: Text(_edgeAgeText(data.lastEdgeAtMs)),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Chip(
                        avatar: const Icon(Icons.lock_outline, size: 16),
                        label: Text(
                          data.orgasmPermissionTokens > 0
                              ? 'Orgasm unlocked (${data.orgasmPermissionTokens})'
                              : 'Orgasm locked',
                        ),
                      ),
                      const SizedBox(width: 8),
                      Chip(
                        avatar: const Icon(Icons.campaign_outlined, size: 16),
                        label: Text('${_pleadLabel(data.orgasmPleadTier)} • ${data.orgasmDeniedCycles}'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Chip(
                        avatar: const Icon(Icons.timeline_outlined, size: 16),
                        label: Text('Events: ${data.edgeTimelineEventCount}'),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          'Last: ${data.edgeTimelineLastEvent}',
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Chip(
                        avatar: const Icon(Icons.verified_user_outlined, size: 16),
                        label: Text(_profileLabel(data.edgeSafetyProfile)),
                      ),
                      const SizedBox(width: 8),
                      Chip(
                        avatar: const Icon(Icons.timer_outlined, size: 16),
                        label: Text(_cooldownLabel(data.edgeBypassCooldownRemainingMs)),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Log', style: titleStyle),
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: data.counterApproveInFlight
                          ? null
                          : () => _runAndRefreshWithHaptic(
                                widget.onQueueEdge,
                                hapticBefore: HapticFeedback.mediumImpact,
                              ),
                      style: largeButtonStyle,
                      icon: data.counterApproveInFlight
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.add_circle_outline),
                      label: Text(
                        data.counterApproveInFlight
                            ? 'Logging...'
                            : 'Log Edge',
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                    decoration: BoxDecoration(
                      color: data.manualBuzzHoldUntilLowHr
                          ? cs.errorContainer
                          : cs.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      data.manualBuzzHoldUntilLowHr ? 'Recovery active' : 'Ready',
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (!_focusMode) ...[
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Automation', style: titleStyle),
                    const SizedBox(height: 10),
                    Text(
                      data.edgeTargetCount > 0
                          ? 'Target: ${data.edgeCount}/${data.edgeTargetCount}${data.edgeTargetStepInFlight ? ' running' : ''}'
                          : 'Target: off',
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Peak shock: ${data.edgeTargetShockAtPeak ? 'on' : 'off'}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      data.manualBuzzHoldUntilLowHr ? 'Recovery: on' : 'Recovery: off',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const SizedBox(height: 8),
                    DropdownButtonFormField<String>(
                      value: data.edgeSafetyProfile,
                      decoration: const InputDecoration(labelText: 'Safety profile'),
                      items: const [
                        DropdownMenuItem(value: 'strict_handler', child: Text('Strict Handler')),
                        DropdownMenuItem(value: 'recovery_heavy', child: Text('Recovery Heavy')),
                        DropdownMenuItem(value: 'training', child: Text('Training')),
                        DropdownMenuItem(value: 'chaos', child: Text('Chaos Trial')),
                      ],
                      onChanged: (value) {
                        if (value == null) return;
                        unawaited(_runAndRefresh(() => widget.onSetSafetyProfile(value)));
                      },
                    ),
                    const SizedBox(height: 10),
                    Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _targetCtrl,
                            keyboardType: TextInputType.number,
                            decoration: const InputDecoration(
                              labelText: 'Target',
                            ),
                            onSubmitted: (_) => unawaited(_saveTargetFromInput()),
                          ),
                        ),
                        const SizedBox(width: 8),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: FilledButton(
                            onPressed: () => unawaited(_saveTargetFromInput()),
                            style: largeButtonStyle,
                            child: const Text('Save'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: FilledButton.tonal(
                            onPressed: () => unawaited(_addTargetFromInput()),
                            style: FilledButton.styleFrom(
                              minimumSize: const Size.fromHeight(62),
                              textStyle: Theme.of(context).textTheme.titleMedium,
                            ),
                            child: const Text('Add'),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    SwitchListTile.adaptive(
                      value: data.edgeTargetShockAtPeak,
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Peak shock at target'),
                      onChanged: (enabled) => unawaited(
                        _runAndRefresh(() => widget.onSetPeakShockEnabled(enabled)),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
          const SizedBox(height: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FilledButton.tonalIcon(
                onPressed: () => _runAndRefresh(widget.onQueueOrgasm),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(56),
                  textStyle: Theme.of(context).textTheme.titleMedium,
                ),
                icon: const Icon(Icons.favorite),
                label: Text(
                  data.orgasmPermissionTokens > 0
                      ? 'Log Orgasm (Allowed)'
                      : 'Request Orgasm Permission',
                ),
              ),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                onPressed: () => _runAndRefreshWithHaptic(
                  widget.onHoldBuzzUntilLowHr,
                  hapticBefore: HapticFeedback.mediumImpact,
                ),
                style: largeOutlineButtonStyle,
                icon: Icon(
                  data.manualBuzzHoldUntilLowHr
                      ? Icons.pause_circle_filled
                      : Icons.pause_circle_outline,
                ),
                label: Text(data.manualBuzzHoldUntilLowHr ? 'Recovery Active' : 'Start Recovery'),
              ),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                onPressed: () => _runAndRefresh(widget.onStopActuationNow),
                style: largeOutlineButtonStyle,
                icon: const Icon(Icons.stop_circle_outlined),
                label: const Text('Stop'),
              ),
              if (!_focusMode) ...[
                const SizedBox(height: 10),
                OutlinedButton.icon(
                  onPressed: canApprove
                      ? () => _runAndRefresh(widget.onApprovePending)
                      : null,
                  style: largeOutlineButtonStyle,
                  icon: data.counterApproveInFlight
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.cloud_upload_outlined),
                  label: Text(data.counterApproveInFlight ? 'Approving...' : 'Approve Pending'),
                ),
                const SizedBox(height: 10),
                OutlinedButton.icon(
                  onPressed: () => _runAndRefresh(widget.onRefreshState),
                  style: largeOutlineButtonStyle,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Refresh'),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton.icon(
                        onPressed: () => _runAndRefresh(widget.onUndoEdge),
                        style: largeOutlineButtonStyle,
                        icon: const Icon(Icons.remove_circle_outline),
                        label: const Text('Undo Edge'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: OutlinedButton.icon(
                        onPressed: () => _runAndRefresh(widget.onUndoOrgasm),
                        style: largeOutlineButtonStyle,
                        icon: const Icon(Icons.remove_circle_outline),
                        label: const Text('Undo Orgasm'),
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
          const SizedBox(height: 18),
        ],
      ),
    );
  }
}
