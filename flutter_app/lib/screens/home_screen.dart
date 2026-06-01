import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/ble_channel.dart';
import '../channels/device_command_channel.dart';
import '../channels/filter_service_channel.dart';
import '../services/api_service.dart';
import '../services/ble_service.dart';
import '../services/websocket_service.dart';
import 'check_in_screen.dart';
import 'chat_screen.dart';
import 'edge_controls_screen.dart';
import 'intiface_screen.dart';
import 'questions_screen.dart';
import 'settings_screen.dart';
import 'task_list_screen.dart';

/// Main screen showing a flat, modern dashboard of available app features.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  static const String _fallbackLiveEndpoint = 'https://mochii.live';
  static const EventChannel _nativeBleEvents =
      EventChannel('com.hound.controller/ble_events');
  static const String _edgeCountKey = 'home_edge_count';
  static const String _orgasmCountKey = 'home_orgasm_count';
  static const String _edgeCountOffsetKey = 'home_edge_count_offset';
  static const String _orgasmCountOffsetKey = 'home_orgasm_count_offset';
  static const String _orgasmManualOverrideKey = 'home_orgasm_manual_override';
  static const String _orgasmPermissionTokensKey = 'home_orgasm_permission_tokens';
  static const String _orgasmDeniedCyclesKey = 'home_orgasm_denied_cycles';
  static const String _edgeTimelineKey = 'edge_timeline_events_json';
  static const String _edgeSafetyProfileKey = 'edge_safety_profile';
  static const String _edgeBypassCooldownUntilMsKey = 'edge_bypass_cooldown_until_ms';
  static const String _edgePendingCountKey = 'home_edge_pending_count';
  static const String _orgasmPendingCountKey = 'home_orgasm_pending_count';
  static const String _edgeTargetShockAtPeakKey = 'edge_target_shock_at_peak';
  static const String _lastEdgeSourceKey = 'edge_last_source';
  static const String _lastEdgeAtMsKey = 'edge_last_at_ms';
  static const String _lovenseBatteryAlertMaskKey = 'lovense_battery_alert_mask';
  static const String _pavlokBatteryAlertMaskKey = 'pavlok_battery_alert_mask';
  static const int _edgeHrSafetyMarginBpm = 2;
  static const int _edgeGuardSliceMs = 1200;
  static const int _edgeHrFreshMaxAgeMs = 12000;
  static const int _edgeResumeStableMs = 6000;
  static const int _edgeResumeWaitMaxMs = 20000;
  static const int _edgeStatusNotifyMinGapMs = 7000;
  static const int _edgePeakZapIntensity = 255;
  static const int _edgePeakZapDurationMs = 500;
  static const int _pavlokZapConnectWaitMs = 8000;
  static const int _pavlokZapConnectPollMs = 400;
  static const int _recoveryLowBuzzLevel = 1;
  static const int _edgeTimelineRetentionMs = 7 * 24 * 60 * 60 * 1000;
  static const int _edgeTimelineMaxEvents = 1200;
  static const int _edgeBypassCooldownMs = 2 * 60 * 1000;

  String _enrollmentState = 'enrolling';
  String _enrollmentError = '';

  StreamSubscription<dynamic>? _nativeBleSub;
  Timer? _enrollmentStatusTimer;
  Timer? _toyStatusTimer;
  Timer? _edgeTargetTimer;
  ApiService? _api;
  WebSocketService? _webSocketService;
  bool _homeOpenedTracked = false;
  String _lastConnectedEndpoint = '';
  bool _nativeLovenseConnected = false;
  bool _nativePavlokConnected = false;
  int? _nativeLovenseBatteryPct;
  int? _nativePavlokBatteryPct;
  bool _countersLoaded = false;
  bool _counterApproveInFlight = false;
  bool _edgeTargetStepInFlight = false;
  int _edgeCount = 0;
  int _orgasmCount = 0;
  int _orgasmPermissionTokens = 0;
  int _orgasmDeniedCycles = 0;
  String _orgasmPleadTier = 'neutral';
  int _edgeTimelineEventCount = 0;
  String _edgeTimelineLastEvent = 'none';
  int _edgePendingCount = 0;
  int _orgasmPendingCount = 0;
  int _edgeTargetCount = 0;
  bool _edgeTargetShockAtPeak = true;
  String _edgeSafetyProfile = 'strict_handler';
  int _edgeBypassCooldownUntilMs = 0;
  bool _manualBuzzHoldUntilLowHr = false;
  String _lastEdgeSource = 'none';
  int _lastEdgeAtMs = 0;
  int _buzzSessionEdgeCount = 0;
  bool _outOfItAutoTriggered = false;
  bool? _lastEdgeStatusActive;
  int _lastEdgeStatusNotifyAtMs = 0;
  bool _toyBatteryAlertStateLoaded = false;
  int _lovenseBatteryAlertMask = 0;
  int _pavlokBatteryAlertMask = 0;
  int _sessionCountersLoadGeneration = 0;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _api ??= ApiService(context.read<SharedPreferences>());
    _webSocketService ??= context.read<WebSocketService>();
    final currentEndpoint =
        (context.read<SharedPreferences>().getString('partner_endpoint_url') ?? '')
            .trim();
    if (currentEndpoint.isNotEmpty) {
      if (currentEndpoint != _lastConnectedEndpoint) {
        _lastConnectedEndpoint = currentEndpoint;
        unawaited(_webSocketService?.connect() ?? Future<void>.value());
      } else {
        unawaited(_webSocketService?.ensureConnected() ?? Future<void>.value());
      }
    }
    if (!_homeOpenedTracked) {
      _homeOpenedTracked = true;
      unawaited(_trackBehavior('app_home_opened', reason: _enrollmentState));
    }
    if (!_countersLoaded) {
      _countersLoaded = true;
      unawaited(_loadSessionCounters());
    }
    if (!_toyBatteryAlertStateLoaded) {
      _toyBatteryAlertStateLoaded = true;
      final prefs = context.read<SharedPreferences>();
      _lovenseBatteryAlertMask =
          (prefs.getInt(_lovenseBatteryAlertMaskKey) ?? 0).clamp(0, 15);
      _pavlokBatteryAlertMask =
          (prefs.getInt(_pavlokBatteryAlertMaskKey) ?? 0).clamp(0, 15);
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _nativeBleSub = _nativeBleEvents.receiveBroadcastStream().listen(
      _onNativeBleEvent,
      onError: (_) {
        // Optional stream; ignore when native channel is unavailable.
      },
    );
    _refreshEnrollmentState();
    _enrollmentStatusTimer = Timer.periodic(const Duration(seconds: 5), (_) {
      _refreshEnrollmentState();
      unawaited(_syncOrgasmPermissionStateFromPrefs());
    });
    _toyStatusTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      unawaited(_refreshToyStatus());
    });
    _edgeTargetTimer = Timer.periodic(const Duration(seconds: 20), (_) {
      unawaited(_runEdgeTargetStepIfNeeded());
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_refreshToyStatus());
      unawaited(_syncOrgasmPermissionStateFromPrefs());
      unawaited(_runEdgeTargetStepIfNeeded());
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _nativeBleSub?.cancel();
    _enrollmentStatusTimer?.cancel();
    _toyStatusTimer?.cancel();
    _edgeTargetTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed || state == AppLifecycleState.inactive) {
      final endpoint =
          (context.read<SharedPreferences>().getString('partner_endpoint_url') ?? '')
              .trim();
      if (endpoint.isNotEmpty) {
        _lastConnectedEndpoint = endpoint;
        unawaited(_webSocketService?.ensureConnected() ?? Future<void>.value());
      }
      unawaited(_refreshToyStatus());
    }

    unawaited(
      _trackBehavior(
        'app_lifecycle',
        reason: state.name,
        payload: {'enrollment_state': _enrollmentState},
      ),
    );
  }

  Future<void> _refreshEnrollmentState() async {
    final prefs = context.read<SharedPreferences>();
    final paired = prefs.getBool('is_paired') ?? false;
    final state = (prefs.getString('auto_enrollment_state') ?? '').trim();
    final error = (prefs.getString('auto_enrollment_error') ?? '').trim();
    final normalized =
        paired ? 'connected' : (state.isEmpty ? 'enrolling' : state);
    if (!mounted ||
        (normalized == _enrollmentState && error == _enrollmentError)) {
      return;
    }
    setState(() {
      _enrollmentState = normalized;
      _enrollmentError = error;
    });
  }

  String _enrollmentLabel() {
    switch (_enrollmentState) {
      case 'connected':
        return 'Enrollment: Connected';
      case 'retrying':
        return 'Enrollment: Retrying';
      default:
        return 'Enrollment: Connecting';
    }
  }

  Color _enrollmentColor(ColorScheme cs) {
    switch (_enrollmentState) {
      case 'connected':
        return Colors.greenAccent.shade400;
      case 'retrying':
        return Colors.orangeAccent.shade200;
      default:
        return cs.primary;
    }
  }

  void _onNativeBleEvent(dynamic raw) {
    if (!mounted || raw is! Map) return;
    final event = Map<Object?, Object?>.from(raw);
    final device = (event['device'] ?? '').toString();
    final type = (event['type'] ?? '').toString();
    final batteryPct = event['battery_pct'] is num
        ? (event['battery_pct'] as num).toInt().clamp(0, 100)
        : null;

    if (device == 'lovense') {
      if (type == 'ready' || type == 'connected') {
        setState(() => _nativeLovenseConnected = true);
      } else if (type == 'disconnected') {
        setState(() {
          _nativeLovenseConnected = false;
          _nativeLovenseBatteryPct = null;
        });
      }
      if (batteryPct != null) {
        setState(() => _nativeLovenseBatteryPct = batteryPct);
        unawaited(_maybeNotifyToyBatteryLevel(device: 'lovense', batteryPct: batteryPct));
      }
      return;
    }

    if (device == 'pavlok') {
      if (type == 'ready' || type == 'connected') {
        setState(() => _nativePavlokConnected = true);
      } else if (type == 'disconnected') {
        setState(() {
          _nativePavlokConnected = false;
          _nativePavlokBatteryPct = null;
        });
      }
      if (batteryPct != null) {
        setState(() => _nativePavlokBatteryPct = batteryPct);
        unawaited(_maybeNotifyToyBatteryLevel(device: 'pavlok', batteryPct: batteryPct));
      }
    }
  }

  Future<void> _maybeNotifyToyBatteryLevel({
    required String device,
    required int batteryPct,
  }) async {
    final safePct = batteryPct.clamp(0, 100);
    final prefs = context.read<SharedPreferences>();

    final isLovense = device == 'lovense';
    var mask = isLovense ? _lovenseBatteryAlertMask : _pavlokBatteryAlertMask;

    // Recovery clears deeper-level alert bits so future discharge can notify again.
    if (safePct > 50) {
      mask = 0;
    } else if (safePct > 25) {
      mask &= 0x01;
    } else if (safePct > 10) {
      mask &= 0x03;
    } else if (safePct > 1) {
      mask &= 0x07;
    }

    var thresholdLabel = '';
    var severityBit = 0;
    var fillMask = 0;
    if (safePct <= 1) {
      thresholdLabel = 'dead';
      severityBit = 0x08;
      fillMask = 0x0F;
    } else if (safePct <= 10) {
      thresholdLabel = '10%';
      severityBit = 0x04;
      fillMask = 0x07;
    } else if (safePct <= 25) {
      thresholdLabel = '25%';
      severityBit = 0x02;
      fillMask = 0x03;
    } else if (safePct <= 50) {
      thresholdLabel = '50%';
      severityBit = 0x01;
      fillMask = 0x01;
    }

    if (severityBit != 0 && (mask & severityBit) == 0) {
      try {
        await DeviceCommandChannel.sendNotification(
          title: '${isLovense ? 'Lovense' : 'Pavlok'} Battery Low',
          body: thresholdLabel == 'dead'
              ? 'Battery is nearly dead ($safePct%).'
              : 'Battery hit $thresholdLabel ($safePct%).',
          channelId: 'tpe_toy_battery',
        );
      } catch (_) {
        // Keep battery notifications best-effort.
      }
      mask |= fillMask;
    }

    if (isLovense) {
      _lovenseBatteryAlertMask = mask;
      await prefs.setInt(_lovenseBatteryAlertMaskKey, mask);
    } else {
      _pavlokBatteryAlertMask = mask;
      await prefs.setInt(_pavlokBatteryAlertMaskKey, mask);
    }
  }

  Future<void> _refreshToyStatus() async {
    if (!mounted) return;
    final ble = context.read<BleService>();

    bool lovenseConnected = _nativeLovenseConnected;
    bool pavlokConnected = _nativePavlokConnected;

    try {
      lovenseConnected = await BleChannel.lovenseIsConnectedNative();
    } catch (_) {
      // Keep existing value when native bridge is unavailable.
    }
    try {
      pavlokConnected = await BleChannel.pavlokIsConnectedNative();
    } catch (_) {
      // Keep existing value when native bridge is unavailable.
    }

    if (!mounted) return;
    setState(() {
      _nativeLovenseConnected = lovenseConnected;
      _nativePavlokConnected = pavlokConnected;
      if (!lovenseConnected) {
        _nativeLovenseBatteryPct = null;
      }
      if (!pavlokConnected) {
        _nativePavlokBatteryPct = null;
      }
    });

    if (lovenseConnected) {
      try {
        await BleChannel.lovenseBattery();
        await BleChannel.lovenseReadBatteryLevel();
      } catch (_) {}
    }
    if (pavlokConnected) {
      try {
        await BleChannel.pavlokReadBatteryLevel();
      } catch (_) {}
    }

    // Keep direct-BLE values fresh as fallback when native path is off.
    unawaited(ble.refreshLovenseBatteryLevel());
    unawaited(ble.refreshPavlokBatteryLevel());
  }

  Future<void> _refreshToyStatusFromCard() async {
    await _refreshToyStatus();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Toy battery/status refreshed.')),
    );
  }

  Future<void> _openCheckIn() async {
    await _trackBehavior('screen_opened', reason: 'checkin');
    if (!mounted) return;
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const CheckInScreen()),
    );
  }

  Future<void> _openEnrollmentSetup() async {
    final prefs = context.read<SharedPreferences>();
    final currentEndpoint =
        (prefs.getString('partner_endpoint_url') ?? '').trim();
    final currentAutoPairKey = (prefs.getString('auto_pair_key') ?? '').trim();

    final endpointCtrl = TextEditingController(
      text: currentEndpoint.isEmpty ? _fallbackLiveEndpoint : currentEndpoint,
    );
    final keyCtrl = TextEditingController(text: currentAutoPairKey);

    try {
      final shouldApply = await showDialog<bool>(
            context: context,
            builder: (ctx) => AlertDialog(
              title: const Text('Enrollment Setup'),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: endpointCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Backend Endpoint',
                      hintText: 'https://mochii.live',
                    ),
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    controller: keyCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Auto Pair Key (optional)',
                    ),
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('Cancel'),
                ),
                FilledButton(
                  onPressed: () => Navigator.pop(ctx, true),
                  child: const Text('Save & Reconnect'),
                ),
              ],
            ),
          ) ??
          false;

      if (!shouldApply || !mounted) return;

      var endpoint = endpointCtrl.text.trim();
      if (endpoint.isNotEmpty && !endpoint.contains('://')) {
        endpoint = 'https://$endpoint';
      }
      endpoint = endpoint.replaceAll(RegExp(r'/$'), '');
      final autoPairKey = keyCtrl.text.trim();

      if (endpoint.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Please enter a backend endpoint.')),
        );
        return;
      }

      await prefs.setString('partner_endpoint_url', endpoint);
      if (autoPairKey.isEmpty) {
        await prefs.remove('auto_pair_key');
      } else {
        await prefs.setString('auto_pair_key', autoPairKey);
      }

      await prefs.setBool('is_paired', false);
      await prefs.setString('auto_enrollment_state', 'enrolling');
      await prefs.remove('auto_enrollment_error');
      await _refreshEnrollmentState();
      _lastConnectedEndpoint = endpoint;
      unawaited(_webSocketService?.connect() ?? Future<void>.value());

      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Enrollment updated. Reconnecting...')),
      );
    } finally {
      endpointCtrl.dispose();
      keyCtrl.dispose();
    }
  }

  Future<void> _navigateAndTrack(Widget screen) async {
    final route = screen.runtimeType.toString();
    await _trackBehavior('screen_opened', reason: route);
    if (!mounted) return;
    await Navigator.push(context, MaterialPageRoute(builder: (_) => screen));
  }

  Future<void> _openNativeChat() async {
    await _trackBehavior('screen_opened', reason: 'ChatScreen');
    if (!mounted) return;
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const ChatScreen()),
    );
  }

  Future<void> _trackBehavior(
    String event, {
    String? reason,
    Map<String, dynamic>? payload,
  }) async {
    final api = _api;
    if (api == null) return;
    try {
      await api.postBehaviorEvent(
        event: event,
        reason: reason,
        payload: payload,
      );
    } catch (_) {
      // Best-effort telemetry only.
    }
  }

  Future<void> _loadSessionCounters() async {
    final generation = ++_sessionCountersLoadGeneration;
    final prefs = context.read<SharedPreferences>();
    var edgeCount = prefs.getInt(_edgeCountKey) ?? 0;
    var orgasmCount = prefs.getInt(_orgasmCountKey) ?? 0;
    final orgasmManualOverride = prefs.getInt(_orgasmManualOverrideKey);
    final edgeCountOffset = (prefs.getInt(_edgeCountOffsetKey) ?? 0).clamp(-1000000, 1000000);
    final orgasmCountOffset = (prefs.getInt(_orgasmCountOffsetKey) ?? 0).clamp(-1000000, 1000000);
    var edgeTargetCount = prefs.getInt('edge_target_count') ?? 0;
    var edgeTargetShockAtPeak = prefs.getBool(_edgeTargetShockAtPeakKey) ?? true;
    final lastEdgeSource = (prefs.getString(_lastEdgeSourceKey) ?? 'none').trim().toLowerCase();
    final lastEdgeAtMs = (prefs.getInt(_lastEdgeAtMsKey) ?? 0).clamp(0, 9223372036854775807);
    final edgePendingCount =
      (prefs.getInt(_edgePendingCountKey) ?? 0).clamp(0, 1000000);
    final orgasmPendingCount =
      (prefs.getInt(_orgasmPendingCountKey) ?? 0).clamp(0, 1000000);
    final orgasmPermissionTokens =
      (prefs.getInt(_orgasmPermissionTokensKey) ?? 0).clamp(0, 1000000);
    final orgasmDeniedCycles =
      (prefs.getInt(_orgasmDeniedCyclesKey) ?? 0).clamp(0, 1000000);
    final timelineSummary = _timelineSummaryFromJson(prefs.getString(_edgeTimelineKey));
    final edgeSafetyProfile = _sanitizeSafetyProfile(
      prefs.getString(_edgeSafetyProfileKey),
    );
    final edgeBypassCooldownUntilMs =
      (prefs.getInt(_edgeBypassCooldownUntilMsKey) ?? 0).clamp(0, 9223372036854775807);

    final api = _api;
    if (api != null) {
      final remote = await api.fetchPublicStatusCounters();
      if (generation != _sessionCountersLoadGeneration) {
        return;
      }
      if (remote.isNotEmpty) {
        final remoteEdge = remote['tasks_completed'];
        final remoteOrgasm = remote['confessions_posted'];
        final remoteTarget = remote['edge_target_count'];
        final remoteShock = remote['edge_target_shock_at_peak'];
        final parsedEdge = remoteEdge is num ? remoteEdge.toInt() : int.tryParse('${remoteEdge ?? ''}');
        final parsedOrgasm = remoteOrgasm is num ? remoteOrgasm.toInt() : int.tryParse('${remoteOrgasm ?? ''}');
        final parsedTarget = remoteTarget is num ? remoteTarget.toInt() : int.tryParse('${remoteTarget ?? ''}');
        final parsedShock = remoteShock is bool
          ? remoteShock
          : (remoteShock != null
            ? <String>{'1', 'true', 'yes', 'on'}.contains('${remoteShock}'.trim().toLowerCase())
            : null);
        if (parsedEdge != null && parsedEdge >= 0) {
          final latestEdgeOffset =
              (prefs.getInt(_edgeCountOffsetKey) ?? 0).clamp(-1000000, 1000000);
          edgeCount = (parsedEdge + latestEdgeOffset).clamp(0, 1000000);
          await prefs.setInt(_edgeCountKey, edgeCount);
        }
        if (parsedOrgasm != null && parsedOrgasm >= 0) {
          final latestOrgasmOffset =
              (prefs.getInt(_orgasmCountOffsetKey) ?? 0).clamp(-1000000, 1000000);
          orgasmCount = (parsedOrgasm + latestOrgasmOffset).clamp(0, 1000000);
          await prefs.setInt(_orgasmCountKey, orgasmCount);
        }
        if (parsedTarget != null && parsedTarget >= 0) {
          edgeTargetCount = parsedTarget;
          await prefs.setInt('edge_target_count', edgeTargetCount);
        }
        if (parsedShock != null) {
          edgeTargetShockAtPeak = parsedShock;
          await prefs.setBool(_edgeTargetShockAtPeakKey, edgeTargetShockAtPeak);
        }
      }
    }

    if (orgasmManualOverride != null) {
      orgasmCount = orgasmManualOverride.clamp(0, 1000000);
      await prefs.setInt(_orgasmCountKey, orgasmCount);
    }

    if (!mounted || generation != _sessionCountersLoadGeneration) return;
    setState(() {
      _edgeCount = edgeCount;
      _orgasmCount = orgasmCount;
      _edgePendingCount = edgePendingCount;
      _orgasmPendingCount = orgasmPendingCount;
      _orgasmPermissionTokens = orgasmPermissionTokens;
      _orgasmDeniedCycles = orgasmDeniedCycles;
      _orgasmPleadTier = _orgasmPleadTierForDeniedCycles(orgasmDeniedCycles);
      _edgeTimelineEventCount = timelineSummary.item1;
      _edgeTimelineLastEvent = timelineSummary.item2;
      _edgeTargetCount = edgeTargetCount;
      _edgeTargetShockAtPeak = edgeTargetShockAtPeak;
      _edgeSafetyProfile = edgeSafetyProfile;
      _edgeBypassCooldownUntilMs = edgeBypassCooldownUntilMs;
      _lastEdgeSource = lastEdgeSource;
      _lastEdgeAtMs = lastEdgeAtMs;
    });

    unawaited(_runEdgeTargetStepIfNeeded());
  }

  Future<void> _syncOrgasmPermissionStateFromPrefs() async {
    final prefs = context.read<SharedPreferences>();
    final tokens = (prefs.getInt(_orgasmPermissionTokensKey) ?? 0).clamp(0, 1000000);
    final denied = (prefs.getInt(_orgasmDeniedCyclesKey) ?? 0).clamp(0, 1000000);
    final tier = _orgasmPleadTierForDeniedCycles(denied);
    final edgeSafetyProfile = _sanitizeSafetyProfile(
      prefs.getString(_edgeSafetyProfileKey),
    );
    final edgeBypassCooldownUntilMs =
      (prefs.getInt(_edgeBypassCooldownUntilMsKey) ?? 0).clamp(0, 9223372036854775807);
    final timelineSummary = _timelineSummaryFromJson(prefs.getString(_edgeTimelineKey));
    if (!mounted) return;
    if (tokens == _orgasmPermissionTokens &&
        denied == _orgasmDeniedCycles &&
        tier == _orgasmPleadTier &&
        edgeSafetyProfile == _edgeSafetyProfile &&
        edgeBypassCooldownUntilMs == _edgeBypassCooldownUntilMs &&
        timelineSummary.item1 == _edgeTimelineEventCount &&
        timelineSummary.item2 == _edgeTimelineLastEvent) {
      return;
    }
    setState(() {
      _orgasmPermissionTokens = tokens;
      _orgasmDeniedCycles = denied;
      _orgasmPleadTier = tier;
      _edgeSafetyProfile = edgeSafetyProfile;
      _edgeBypassCooldownUntilMs = edgeBypassCooldownUntilMs;
      _edgeTimelineEventCount = timelineSummary.item1;
      _edgeTimelineLastEvent = timelineSummary.item2;
    });
  }

  ({int item1, String item2}) _timelineSummaryFromJson(String? rawJson) {
    if (rawJson == null || rawJson.trim().isEmpty) {
      return (item1: 0, item2: 'none');
    }
    try {
      final decoded = jsonDecode(rawJson);
      if (decoded is! List) {
        return (item1: 0, item2: 'none');
      }
      if (decoded.isEmpty) {
        return (item1: 0, item2: 'none');
      }
      final last = decoded.last;
      if (last is Map) {
        final event = '${last['event'] ?? 'event'}'.trim();
        return (item1: decoded.length, item2: event.isEmpty ? 'event' : event);
      }
      return (item1: decoded.length, item2: 'event');
    } catch (_) {
      return (item1: 0, item2: 'none');
    }
  }

  Future<void> _appendEdgeTimelineEvent(String event, {Map<String, dynamic>? payload}) async {
    final prefs = context.read<SharedPreferences>();
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final cutoffMs = nowMs - _edgeTimelineRetentionMs;
    final raw = prefs.getString(_edgeTimelineKey);
    final events = <Map<String, dynamic>>[];

    if (raw != null && raw.trim().isNotEmpty) {
      try {
        final decoded = jsonDecode(raw);
        if (decoded is List) {
          for (final item in decoded) {
            if (item is Map) {
              final map = Map<String, dynamic>.from(item.cast<String, dynamic>());
              final at = map['at_ms'];
              final atMs = at is num ? at.toInt() : int.tryParse('${at ?? ''}');
              if (atMs != null && atMs >= cutoffMs) {
                events.add(map);
              }
            }
          }
        }
      } catch (_) {
        // Best-effort timeline parsing.
      }
    }

    events.add({
      'event': event,
      'at_ms': nowMs,
      if (payload != null) 'payload': payload,
    });

    if (events.length > _edgeTimelineMaxEvents) {
      events.removeRange(0, events.length - _edgeTimelineMaxEvents);
    }

    await prefs.setString(_edgeTimelineKey, jsonEncode(events));
    if (!mounted) return;
    setState(() {
      _edgeTimelineEventCount = events.length;
      _edgeTimelineLastEvent = event;
    });
  }

  String _orgasmPleadTierForDeniedCycles(int deniedCycles) {
    if (deniedCycles >= 6) {
      return 'pleading';
    }
    if (deniedCycles >= 2) {
      return 'needy';
    }
    return 'neutral';
  }

  String _sanitizeSafetyProfile(String? raw) {
    switch ('${raw ?? ''}'.trim().toLowerCase()) {
      case 'strict_handler':
      case 'recovery_heavy':
      case 'training':
      case 'chaos':
        return '${raw ?? ''}'.trim().toLowerCase();
      default:
        return 'strict_handler';
    }
  }

  int _recoveryLowBuzzLevelForProfile(String profile) {
    switch (profile) {
      case 'recovery_heavy':
        return 1;
      case 'training':
        return 2;
      case 'chaos':
        return 3;
      default:
        return _recoveryLowBuzzLevel;
    }
  }

  int _edgeBuzzLevelForProfile(String profile) {
    switch (profile) {
      case 'recovery_heavy':
        return 10;
      case 'training':
        return 12;
      case 'chaos':
        return 14;
      default:
        return 11;
    }
  }

  int _remainingBypassCooldownMs() {
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final remaining = _edgeBypassCooldownUntilMs - nowMs;
    return remaining > 0 ? remaining : 0;
  }

  Future<void> _setEdgeSafetyProfile(String profile) async {
    final next = _sanitizeSafetyProfile(profile);
    final prefs = context.read<SharedPreferences>();
    await prefs.setString(_edgeSafetyProfileKey, next);
    await _appendEdgeTimelineEvent(
      'edge_safety_profile_changed',
      payload: {'profile': next},
    );
    if (!mounted) return;
    setState(() {
      _edgeSafetyProfile = next;
    });
    await _trackBehavior(
      'edge_safety_profile_changed',
      reason: 'edge_controls_screen',
      payload: {'profile': next},
    );
  }

  Future<void> _armBypassCooldown({required String reason}) async {
    final prefs = context.read<SharedPreferences>();
    final untilMs = DateTime.now().millisecondsSinceEpoch + _edgeBypassCooldownMs;
    await prefs.setInt(_edgeBypassCooldownUntilMsKey, untilMs);
    if (mounted) {
      setState(() {
        _edgeBypassCooldownUntilMs = untilMs;
      });
    }
    await _appendEdgeTimelineEvent(
      'edge_bypass_cooldown_armed',
      payload: {
        'reason': reason,
        'cooldown_until_ms': untilMs,
      },
    );
  }

  String _orgasmRequestMessageForTier(String tier) {
    switch (tier) {
      case 'pleading':
        return 'Please, may this mutt have permission to orgasm?';
      case 'needy':
        return 'Mutt needs permission first. Please ask clearly.';
      default:
        return 'Orgasm is locked. Ask for permission.';
    }
  }

  Future<void> _requestOrgasmPermissionOrLog() async {
    final prefs = context.read<SharedPreferences>();

    if (_orgasmPermissionTokens > 0) {
      final remainingTokens = (_orgasmPermissionTokens - 1).clamp(0, 1000000);
      await prefs.setInt(_orgasmPermissionTokensKey, remainingTokens);
      await prefs.setInt(_orgasmDeniedCyclesKey, 0);
      if (mounted) {
        setState(() {
          _orgasmPermissionTokens = remainingTokens;
          _orgasmDeniedCycles = 0;
          _orgasmPleadTier = 'neutral';
        });
      }

      await _queueSessionCounter(orgasm: true);
      await _approvePendingCounters(includeEdge: false, includeOrgasm: true);
      await _appendEdgeTimelineEvent(
        'orgasm_permission_consumed',
        payload: {
          'remaining_permission_tokens': remainingTokens,
        },
      );
      await _trackBehavior(
        'orgasm_permission_consumed',
        reason: 'edge_controls_screen',
        payload: {
          'remaining_permission_tokens': remainingTokens,
        },
      );
      return;
    }

    final nextDeniedCycles = (_orgasmDeniedCycles + 1).clamp(0, 1000000);
    final nextTier = _orgasmPleadTierForDeniedCycles(nextDeniedCycles);
    await prefs.setInt(_orgasmDeniedCyclesKey, nextDeniedCycles);
    await _appendEdgeTimelineEvent(
      'orgasm_permission_required',
      payload: {
        'denied_cycles': nextDeniedCycles,
        'plead_tier': nextTier,
      },
    );
    if (mounted) {
      setState(() {
        _orgasmDeniedCycles = nextDeniedCycles;
        _orgasmPleadTier = nextTier;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_orgasmRequestMessageForTier(nextTier))),
      );
    }

    await _trackBehavior(
      'orgasm_permission_required',
      reason: 'edge_controls_screen',
      payload: {
        'denied_cycles': nextDeniedCycles,
        'plead_tier': nextTier,
      },
    );
  }

  Future<void> _markEdgeLogged({required String source}) async {
    final normalized = source.trim().toLowerCase();
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final prefs = context.read<SharedPreferences>();
    await prefs.setString(_lastEdgeSourceKey, normalized);
    await prefs.setInt(_lastEdgeAtMsKey, nowMs);
    await _appendEdgeTimelineEvent(
      'edge_logged',
      payload: {
        'source': normalized,
        'edge_count': _edgeCount,
        'orgasm_count': _orgasmCount,
      },
    );
    if (!mounted) return;
    setState(() {
      _lastEdgeSource = normalized;
      _lastEdgeAtMs = nowMs;
    });
  }

  void _recordEdgeInBuzzSession() {
    if (!mounted) return;
    setState(() {
      _buzzSessionEdgeCount = (_buzzSessionEdgeCount + 1).clamp(0, 1000000);
      if (_buzzSessionEdgeCount >= 3) {
        _outOfItAutoTriggered = true;
      }
    });
  }

  Future<void> _queueSessionCounter({required bool orgasm}) async {
    final prefs = context.read<SharedPreferences>();
    final edgePendingCount = orgasm ? _edgePendingCount : _edgePendingCount + 1;
    final orgasmPendingCount = orgasm ? _orgasmPendingCount + 1 : _orgasmPendingCount;

    await prefs.setInt(_edgePendingCountKey, edgePendingCount);
    await prefs.setInt(_orgasmPendingCountKey, orgasmPendingCount);

    if (!mounted) return;
    setState(() {
      _edgePendingCount = edgePendingCount;
      _orgasmPendingCount = orgasmPendingCount;
    });

    await _trackBehavior(
      orgasm ? 'orgasm_pending_added' : 'edge_pending_added',
      reason: 'home_counter_button_pending',
      payload: {
        'edge_pending_count': edgePendingCount,
        'orgasm_pending_count': orgasmPendingCount,
      },
    );
  }

  Future<void> _undoSessionCounter({required bool orgasm}) async {
    // Invalidate any in-flight counter refresh that may still be using stale pre-undo values.
    _sessionCountersLoadGeneration++;
    final prefs = context.read<SharedPreferences>();

    var edgePendingCount = _edgePendingCount;
    var orgasmPendingCount = _orgasmPendingCount;
    var edgeCount = _edgeCount;
    var orgasmCount = _orgasmCount;
    var edgeCountOffset = (prefs.getInt(_edgeCountOffsetKey) ?? 0).clamp(-1000000, 1000000);
    var orgasmCountOffset = (prefs.getInt(_orgasmCountOffsetKey) ?? 0).clamp(-1000000, 1000000);
    var removedFromPending = false;

    if (orgasm) {
      if (orgasmCount > 0) {
        orgasmCount -= 1;
        orgasmCountOffset = (orgasmCountOffset - 1).clamp(-1000000, 1000000);
      } else if (orgasmPendingCount > 0) {
        orgasmPendingCount -= 1;
        removedFromPending = true;
      } else {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('No orgasm counter to remove.')),
          );
        }
        return;
      }
    } else {
      if (edgeCount > 0) {
        edgeCount -= 1;
        edgeCountOffset = (edgeCountOffset - 1).clamp(-1000000, 1000000);
      } else if (edgePendingCount > 0) {
        edgePendingCount -= 1;
        removedFromPending = true;
      } else {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('No edge counter to remove.')),
          );
        }
        return;
      }
    }

    await prefs.setInt(_edgePendingCountKey, edgePendingCount);
    await prefs.setInt(_orgasmPendingCountKey, orgasmPendingCount);
    await prefs.setInt(_edgeCountKey, edgeCount);
    await prefs.setInt(_orgasmCountKey, orgasmCount);
    await prefs.setInt(_edgeCountOffsetKey, edgeCountOffset);
    await prefs.setInt(_orgasmCountOffsetKey, orgasmCountOffset);
    if (orgasm && !removedFromPending) {
      await prefs.setInt(_orgasmManualOverrideKey, orgasmCount);
    }

    if (!mounted) return;
    setState(() {
      _edgePendingCount = edgePendingCount;
      _orgasmPendingCount = orgasmPendingCount;
      _edgeCount = edgeCount;
      _orgasmCount = orgasmCount;
    });

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            orgasm
                ? (removedFromPending
                      ? 'Removed 1 pending orgasm.'
                      : 'Removed 1 approved orgasm.')
                : (removedFromPending
                      ? 'Removed 1 pending edge.'
                      : 'Removed 1 approved edge.'),
          ),
        ),
      );
    }

    await _trackBehavior(
      orgasm ? 'orgasm_counter_removed' : 'edge_counter_removed',
      reason: 'home_counter_button_undo',
      payload: {
        'edge_count': edgeCount,
        'orgasm_count': orgasmCount,
        'edge_pending_count': edgePendingCount,
        'orgasm_pending_count': orgasmPendingCount,
        'edge_count_offset': edgeCountOffset,
        'orgasm_count_offset': orgasmCountOffset,
      },
    );
  }

  Future<void> _approvePendingCounters({
    bool includeEdge = true,
    bool includeOrgasm = true,
  }) async {
    if (_counterApproveInFlight) return;

    final edgeDelta = includeEdge ? _edgePendingCount : 0;
    final orgasmDelta = includeOrgasm ? _orgasmPendingCount : 0;
    if (edgeDelta <= 0 && orgasmDelta <= 0) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('No pending counters to approve.')),
        );
      }
      return;
    }

    final api = _api;
    if (api == null) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('API unavailable. Try again after reconnecting.')),
        );
      }
      return;
    }

    setState(() {
      _counterApproveInFlight = true;
    });

    final nextEdge = _edgeCount + edgeDelta;
    final nextOrgasm = _orgasmCount + orgasmDelta;

    try {
      if (edgeDelta > 0) {
        await api.postBehaviorEvent(
          event: 'edge_recorded',
          reason: 'home_counter_approved',
          payload: {
            'edge_count': nextEdge,
            'orgasm_count': nextOrgasm,
            'pending_edge_delta': edgeDelta,
            'pending_orgasm_delta': orgasmDelta,
          },
        );
      }

      if (orgasmDelta > 0) {
        await api.postBehaviorEvent(
          event: 'orgasm_recorded',
          reason: 'home_counter_approved',
          payload: {
            'edge_count': nextEdge,
            'orgasm_count': nextOrgasm,
            'pending_edge_delta': edgeDelta,
            'pending_orgasm_delta': orgasmDelta,
          },
        );
      }

      final prefs = context.read<SharedPreferences>();
      await prefs.setInt(_edgeCountKey, nextEdge);
      await prefs.setInt(_orgasmCountKey, nextOrgasm);
      await prefs.setInt(_edgePendingCountKey, includeEdge ? 0 : _edgePendingCount);
      await prefs.setInt(
        _orgasmPendingCountKey,
        includeOrgasm ? 0 : _orgasmPendingCount,
      );
      if (orgasmDelta > 0) {
        await prefs.remove(_orgasmManualOverrideKey);
      }

      if (!mounted) return;
      setState(() {
        _edgeCount = nextEdge;
        _orgasmCount = nextOrgasm;
        if (includeEdge) {
          _edgePendingCount = 0;
        }
        if (includeOrgasm) {
          _orgasmPendingCount = 0;
        }
      });

      await _trackBehavior(
        'counter_pending_approved',
        reason: 'home_counter_approve_button',
        payload: {
          'approved_edge_delta': edgeDelta,
          'approved_orgasm_delta': orgasmDelta,
          'edge_count': nextEdge,
          'orgasm_count': nextOrgasm,
        },
      );

      // Kick the edge automation immediately after the approved counts land.
      unawaited(_runEdgeTargetStepIfNeeded());

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Approved: +$edgeDelta edges, +$orgasmDelta orgasms.'),
          ),
        );
      }

      unawaited(
        Future<void>.delayed(const Duration(seconds: 3), () async {
          if (!mounted) return;
          await _loadSessionCounters();
        }),
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Approval failed. Pending counts were kept locally.')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _counterApproveInFlight = false;
        });
      }
    }
  }

  Future<void> _setEdgeTargetCount(int targetCount) async {
    final safeTarget = targetCount.clamp(0, 1000000);
    final prefs = context.read<SharedPreferences>();
    await prefs.setInt('edge_target_count', safeTarget);
    if (!mounted) return;
    setState(() {
      _edgeTargetCount = safeTarget;
    });

    await _trackBehavior(
      'edge_target_updated',
      reason: 'edge_controls_screen',
      payload: {
        'edge_target_count': safeTarget,
        'current_edge_count': _edgeCount,
      },
    );

    unawaited(_runEdgeTargetStepIfNeeded());
  }

  Future<void> _setEdgeTargetShockAtPeak(bool enabled) async {
    final prefs = context.read<SharedPreferences>();
    await prefs.setBool(_edgeTargetShockAtPeakKey, enabled);
    if (!mounted) return;
    setState(() {
      _edgeTargetShockAtPeak = enabled;
    });
    await _trackBehavior(
      'edge_target_peak_shock_updated',
      reason: 'edge_controls_screen',
      payload: {
        'edge_target_shock_at_peak': enabled,
      },
    );
  }

  int? _intFromAny(dynamic value) {
    if (value is num) return value.toInt();
    return int.tryParse('${value ?? ''}'.trim());
  }

  bool _boolFromAny(dynamic value) {
    if (value is bool) return value;
    final normalized = '${value ?? ''}'.trim().toLowerCase();
    return normalized == '1' || normalized == 'true' || normalized == 'yes' || normalized == 'on';
  }

  bool _hrLimitReachedForPeakShock(Map<String, dynamic> status) {
    if (status.isEmpty) {
      return false;
    }

    if (_boolFromAny(status['edge_hr_zap_allowed'])) {
      return true;
    }

    final state = '${status['edge_hr_state'] ?? ''}'.trim().toLowerCase();
    final lastHr = _intFromAny(status['edge_hr_last']) ?? _intFromAny(status['latest_heart_rate']);
    final pauseBpm = _intFromAny(status['edge_hr_pause_bpm']);
    return state == 'holding_edge' &&
        lastHr != null &&
        pauseBpm != null &&
        lastHr >= pauseBpm;
  }

  bool _hrAtOrAboveSoftStopForEdge(Map<String, dynamic> status) {
    if (status.isEmpty) {
      return true;
    }
    final pauseBpm = _intFromAny(status['edge_hr_pause_bpm']);
    final lastHr = _intFromAny(status['edge_hr_last']) ?? _intFromAny(status['latest_heart_rate']);
    if (pauseBpm == null || lastHr == null) {
      return true;
    }
    return lastHr >= (pauseBpm - _edgeHrSafetyMarginBpm);
  }

  bool _isEdgeHrFresh(Map<String, dynamic> status) {
    final updatedAt = _parseIsoToMs(status['edge_hr_updated_at']) ??
        _parseIsoToMs(status['latest_vitals_at']);
    if (updatedAt == null) {
      return false;
    }
    final ageMs = DateTime.now().millisecondsSinceEpoch - updatedAt;
    return ageMs >= 0 && ageMs <= _edgeHrFreshMaxAgeMs;
  }

  bool _isEdgeHrBelowResume(Map<String, dynamic> status) {
    final lastHr = _intFromAny(status['edge_hr_last']) ?? _intFromAny(status['latest_heart_rate']);
    final resumeBpm = _intFromAny(status['edge_hr_resume_bpm']);
    if (lastHr == null || resumeBpm == null) {
      return false;
    }
    return lastHr <= resumeBpm;
  }

  Future<bool> _waitForEdgeResumeWindow() async {
    var stableMs = 0;
    var waitedMs = 0;
    while (waitedMs < _edgeResumeWaitMaxMs) {
      final status = await _fetchRealtimeEdgeStatus();
      final safe = _isEdgeHrFresh(status) && _isEdgeHrBelowResume(status);
      if (safe) {
        stableMs += _edgeGuardSliceMs;
        if (stableMs >= _edgeResumeStableMs) {
          return true;
        }
      } else {
        stableMs = 0;
      }
      await Future<void>.delayed(const Duration(milliseconds: _edgeGuardSliceMs));
      waitedMs += _edgeGuardSliceMs;
    }
    return false;
  }

  int? _parseIsoToMs(dynamic value) {
    final raw = '${value ?? ''}'.trim();
    if (raw.isEmpty) {
      return null;
    }
    final parsed = DateTime.tryParse(raw);
    return parsed?.toUtc().millisecondsSinceEpoch;
  }

  Future<Map<String, dynamic>> _fetchRealtimeEdgeStatus() async {
    final api = _api;
    if (api == null) {
      return const <String, dynamic>{};
    }
    await api.syncRealtimeVitals(window: const Duration(seconds: 20));
    return api.fetchPublicStatusCounters();
  }

  Future<void> _stopBuzzOutputNow() async {
    final ble = context.read<BleService>();
    try {
      await ble.lovenseStopAll();
    } catch (_) {
      // Keep stop best-effort.
    }
    try {
      await BleChannel.lovenseStopAll();
    } catch (_) {
      // Keep stop best-effort.
    }
    try {
      await ble.pavlokStopAll();
    } catch (_) {
      // Keep stop best-effort.
    }
    try {
      await BleChannel.pavlokStopAll();
    } catch (_) {
      // Keep stop best-effort.
    }
  }

  Future<void> _setRecoveryLowBuzzNow() async {
    final ble = context.read<BleService>();
    final lowBuzzLevel = _recoveryLowBuzzLevelForProfile(_edgeSafetyProfile);
    try {
      await ble.lovenseVibrate(lowBuzzLevel);
    } catch (_) {
      // Keep low-buzz hold best-effort.
    }
    try {
      await ble.pavlokStopAll();
    } catch (_) {
      // Keep safety stop best-effort.
    }
    try {
      await BleChannel.pavlokStopAll();
    } catch (_) {
      // Keep safety stop best-effort.
    }
  }

  Future<void> _enterRecoveryLowBuzzHold({
    required String reason,
    bool forceStatus = true,
    String statusBody = 'Recovery low-buzz hold is active (2%) until HR is back in range.',
    Map<String, dynamic>? payload,
    bool armBypassCooldown = false,
  }) async {
    if (armBypassCooldown) {
      await _armBypassCooldown(reason: reason);
    }
    if (mounted) {
      setState(() => _manualBuzzHoldUntilLowHr = true);
    }
    await _appendEdgeTimelineEvent(
      'edge_recovery_started',
      payload: {
        'reason': reason,
        'profile': _edgeSafetyProfile,
        if (armBypassCooldown)
          'cooldown_until_ms': _edgeBypassCooldownUntilMs,
      },
    );
    await _setRecoveryLowBuzzNow();
    await _notifyEdgeSystemStatus(
      active: false,
      body: statusBody,
      force: forceStatus,
    );
    await _trackBehavior(
      'edge_recovery_low_buzz_started',
      reason: reason,
      payload: payload,
    );
  }

  Future<void> _notifyEdgeSystemStatus({
    required bool active,
    required String body,
    bool includeStopAction = false,
    bool includeEdgeDownOnTap = false,
    bool force = false,
  }) async {
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final recentlyNotified = (nowMs - _lastEdgeStatusNotifyAtMs) < _edgeStatusNotifyMinGapMs;
    final sameState = _lastEdgeStatusActive != null && _lastEdgeStatusActive == active;
    if (!force && sameState && recentlyNotified) {
      return;
    }

    _lastEdgeStatusActive = active;
    _lastEdgeStatusNotifyAtMs = nowMs;

    try {
      await DeviceCommandChannel.sendNotification(
        title: active ? 'Edge System Active' : 'Edge System Inactive',
        body: body,
        channelId: 'tpe_edge_status',
        includeStopAction: includeStopAction,
        includeEdgeDownOnTap: includeEdgeDownOnTap,
      );
    } catch (_) {
      // Keep edge status notifications best-effort.
    }
  }

  Future<bool> _consumeEdgeDownRequest() async {
    try {
      return await DeviceCommandChannel.consumeEdgeDownRequest();
    } catch (_) {
      return false;
    }
  }

  Future<void> _activateManualBuzzHoldUntilLowHr() async {
    if (_manualBuzzHoldUntilLowHr) {
      await _setRecoveryLowBuzzNow();
      await _notifyEdgeSystemStatus(
        active: false,
        body: 'Recovery low-buzz hold active (2%). Waiting for HR to return to resume range.',
      );
      return;
    }
    await _enterRecoveryLowBuzzHold(
      reason: 'home_button',
      statusBody: 'Manual recovery enabled. Holding low buzz (2%) until HR is back in range.',
      armBypassCooldown: true,
      payload: {
        'edge_count': _edgeCount,
        'orgasm_count': _orgasmCount,
      },
    );
  }

  Future<void> _addEdgeFromControlsWithSafety() async {
    if (!mounted) {
      return;
    }

    final status = await _fetchRealtimeEdgeStatus();
    final hrFresh = _isEdgeHrFresh(status);
    final aboveSoftStop = _hrAtOrAboveSoftStopForEdge(status);
    final manualHoldWasActive = _manualBuzzHoldUntilLowHr;

    await _attemptPeakShockZap(
      status: status,
      source: 'manual_edge_log',
    );

    // Manual self-report is always accepted and acts as a recovery trigger.
    await _queueSessionCounter(orgasm: false);
    await _approvePendingCounters(includeEdge: true, includeOrgasm: false);
    await _markEdgeLogged(source: 'manual');
    _recordEdgeInBuzzSession();

    await _enterRecoveryLowBuzzHold(
      reason: 'edge_controls_screen',
      statusBody: 'Edge logged. Recovery low-buzz hold is active (2%) until HR is back in range.',
      armBypassCooldown: true,
      payload: {
        'edge_hr_state': '${status['edge_hr_state'] ?? ''}',
        'hr_fresh': hrFresh,
        'above_soft_stop': aboveSoftStop,
        'manual_hold_already_active': manualHoldWasActive,
        'edge_count': _edgeCount,
        'orgasm_count': _orgasmCount,
        'edge_target_count': _edgeTargetCount,
        'edge_hr_last': _intFromAny(status['edge_hr_last']) ?? _intFromAny(status['latest_heart_rate']),
        'edge_hr_pause_bpm': _intFromAny(status['edge_hr_pause_bpm']),
        'edge_hr_resume_bpm': _intFromAny(status['edge_hr_resume_bpm']),
      },
    );
  }

  Future<bool> _manualBuzzHoldReadyToRelease() async {
    if (!_manualBuzzHoldUntilLowHr) {
      return true;
    }
    if (_remainingBypassCooldownMs() > 0) {
      return false;
    }
    final status = await _fetchRealtimeEdgeStatus();
    final ready = _isEdgeHrFresh(status) && _isEdgeHrBelowResume(status);
    if (!ready) {
      return false;
    }
    if (mounted) {
      setState(() => _manualBuzzHoldUntilLowHr = false);
    }
    await _appendEdgeTimelineEvent(
      'edge_recovery_released',
      payload: {
        'profile': _edgeSafetyProfile,
        'edge_hr_last': _intFromAny(status['edge_hr_last']) ?? _intFromAny(status['latest_heart_rate']),
        'edge_hr_resume_bpm': _intFromAny(status['edge_hr_resume_bpm']),
      },
    );
    await _trackBehavior(
      'manual_buzz_hold_released',
      reason: 'hr_low_end_reached',
      payload: {
        'edge_hr_last': _intFromAny(status['edge_hr_last']),
        'latest_heart_rate': _intFromAny(status['latest_heart_rate']),
        'edge_hr_resume_bpm': _intFromAny(status['edge_hr_resume_bpm']),
      },
    );
    await _notifyEdgeSystemStatus(
      active: true,
      body: 'HR recovered. Edge automation can buzz again when target conditions are met.',
      force: true,
    );
    return true;
  }

  Future<void> _runEdgeTargetStepIfNeeded() async {
    if (!mounted || _edgeTargetStepInFlight || _counterApproveInFlight) {
      return;
    }

    final target = _edgeTargetCount;
    if (target <= 0 || _edgeCount >= target) {
      return;
    }

    final holdReleased = await _manualBuzzHoldReadyToRelease();
    if (!holdReleased) {
      return;
    }

    final ble = context.read<BleService>();
    final lovenseReady = _nativeLovenseConnected || ble.lovenseConnected;
    if (!lovenseReady) {
      return;
    }

    _edgeTargetStepInFlight = true;
    try {
      await _trackBehavior(
        'edge_target_step_started',
        reason: 'edge_target_automation',
        payload: {
          'edge_target_count': target,
          'current_edge_count': _edgeCount,
        },
      );

      final ready = await _waitForEdgeResumeWindow();
      if (!ready) {
        return;
      }

      var status = await _fetchRealtimeEdgeStatus();
      if (_hrAtOrAboveSoftStopForEdge(status) || !_isEdgeHrFresh(status)) {
        return;
      }

      var remainingMs = 12000;
      var didBuzz = false;
      var stopReason = 'cycle complete';
      final buzzLevel = _edgeBuzzLevelForProfile(_edgeSafetyProfile);
      await _notifyEdgeSystemStatus(
        active: true,
        body: 'Edge automation buzz cycle started (${_edgeCount + 1}/$target).',
        includeStopAction: true,
        includeEdgeDownOnTap: true,
      );
      await ble.lovenseVibrate(buzzLevel);
      didBuzz = true;
      try {
        while (remainingMs > 0) {
          if (await _consumeEdgeDownRequest()) {
            stopReason = 'edge down requested from notification';
            await _enterRecoveryLowBuzzHold(
              reason: 'edge_down_notification',
              statusBody: 'Edge-down requested. Recovery low-buzz hold is active (2%).',
              armBypassCooldown: true,
              payload: {
                'edge_count': _edgeCount,
                'orgasm_count': _orgasmCount,
                'edge_target_count': _edgeTargetCount,
              },
            );
            break;
          }

          if (_manualBuzzHoldUntilLowHr) {
            stopReason = 'manual hold enabled';
            break;
          }
          final sliceMs = remainingMs < _edgeGuardSliceMs ? remainingMs : _edgeGuardSliceMs;
          await Future<void>.delayed(Duration(milliseconds: sliceMs));
          remainingMs -= sliceMs;
          status = await _fetchRealtimeEdgeStatus();
          if (_hrAtOrAboveSoftStopForEdge(status) || !_isEdgeHrFresh(status)) {
            stopReason = 'heart-rate safety threshold reached';
            break;
          }

        }
        if (remainingMs <= 0) {
          stopReason = 'buzz cycle finished';
        }
      } finally {
        await ble.lovenseVibrate(0);
        if (didBuzz) {
          await _notifyEdgeSystemStatus(
            active: false,
            body: 'Edge automation buzz stopped: $stopReason.',
            includeStopAction: false,
          );
        }
      }

      final peakStatus = await _fetchRealtimeEdgeStatus();
      await _attemptPeakShockZap(
        status: peakStatus,
        source: 'edge_target_automation',
      );

      await _queueSessionCounter(orgasm: false);
      await _approvePendingCounters(includeEdge: true, includeOrgasm: false);
      await _markEdgeLogged(source: 'auto');
      _recordEdgeInBuzzSession();

      // Enforce edge -> recovery cadence after every edge.
      await _enterRecoveryLowBuzzHold(
        reason: 'edge_target_automation',
        statusBody: 'Edge logged. Recovery low-buzz hold is active (2%) until HR is back in range.',
        payload: {
          'edge_count': _edgeCount,
          'orgasm_count': _orgasmCount,
          'edge_target_count': _edgeTargetCount,
        },
      );
    } catch (_) {
      // Keep automation best-effort and retry on next tick.
    } finally {
      _edgeTargetStepInFlight = false;
    }
  }

  Future<bool> _ensurePavlokConnectedForZap() async {
    final ble = context.read<BleService>();
    if (_nativePavlokConnected || ble.pavlokConnected) {
      return true;
    }

    try {
      if (await BleChannel.pavlokIsConnectedNative()) {
        if (mounted) {
          setState(() => _nativePavlokConnected = true);
        }
        return true;
      }
    } catch (_) {
      // Keep reconnect path best-effort.
    }

    try {
      await BleChannel.pavlokScan();
    } catch (_) {
      return false;
    }

    var waitedMs = 0;
    while (waitedMs < _pavlokZapConnectWaitMs) {
      await Future<void>.delayed(const Duration(milliseconds: _pavlokZapConnectPollMs));
      waitedMs += _pavlokZapConnectPollMs;
      final connectedNow = _nativePavlokConnected || ble.pavlokConnected;
      if (connectedNow) {
        return true;
      }
      try {
        if (await BleChannel.pavlokIsConnectedNative()) {
          if (mounted) {
            setState(() => _nativePavlokConnected = true);
          }
          return true;
        }
      } catch (_) {
        // Keep polling until timeout.
      }
    }

    return _nativePavlokConnected || ble.pavlokConnected;
  }

  Future<bool> _attemptPeakShockZap({
    required Map<String, dynamic> status,
    required String source,
  }) async {
    final forceEdgeZap =
        source == 'edge_target_automation' || source == 'manual_edge_log';
    final hrGateEligible = _hrLimitReachedForPeakShock(status);
    final allowEdgeAutomationFallback = source == 'edge_target_automation';
    if (!forceEdgeZap &&
        (!_edgeTargetShockAtPeak || (!hrGateEligible && !allowEdgeAutomationFallback))) {
      return false;
    }

    try {
      await BleChannel.pavlokZap(
        intensity: _edgePeakZapIntensity,
        durationMs: _edgePeakZapDurationMs,
      );
      await _trackBehavior(
        'edge_target_peak_shock_fired',
        reason: forceEdgeZap
            ? 'edge_zap_forced'
            : hrGateEligible
            ? 'hr_limit_hit_while_buzzing'
            : 'edge_target_completion_fallback',
        payload: {
          'source': source,
          'edge_zap_forced': forceEdgeZap,
          'hr_gate_eligible': hrGateEligible,
          'pavlok_intensity': _edgePeakZapIntensity,
          'pavlok_duration_ms': _edgePeakZapDurationMs,
          'edge_hr_state': '${status['edge_hr_state'] ?? ''}',
          'edge_hr_last': _intFromAny(status['edge_hr_last']),
          'edge_hr_pause_bpm': _intFromAny(status['edge_hr_pause_bpm']),
          'latest_heart_rate': _intFromAny(status['latest_heart_rate']),
        },
      );
      return true;
    } catch (_) {
      // Quick-action parity: try dispatch first; only reconnect if initial send fails.
      final reconnected = await _ensurePavlokConnectedForZap();
      if (reconnected) {
        try {
          await BleChannel.pavlokZap(
            intensity: _edgePeakZapIntensity,
            durationMs: _edgePeakZapDurationMs,
          );
          await _trackBehavior(
            'edge_target_peak_shock_fired',
            reason: 'edge_zap_retry_after_reconnect',
            payload: {
              'source': source,
              'edge_zap_forced': forceEdgeZap,
              'hr_gate_eligible': hrGateEligible,
              'pavlok_intensity': _edgePeakZapIntensity,
              'pavlok_duration_ms': _edgePeakZapDurationMs,
              'edge_hr_state': '${status['edge_hr_state'] ?? ''}',
              'edge_hr_last': _intFromAny(status['edge_hr_last']),
              'edge_hr_pause_bpm': _intFromAny(status['edge_hr_pause_bpm']),
              'latest_heart_rate': _intFromAny(status['latest_heart_rate']),
            },
          );
          return true;
        } catch (_) {
          // Fall through to failure telemetry below.
        }
      }
      await _trackBehavior(
        'edge_target_peak_shock_failed',
        reason: 'pavlok_zap_dispatch_failed',
        payload: {
          'source': source,
          'reconnect_attempted': true,
          'reconnected': reconnected,
          'pavlok_intensity': _edgePeakZapIntensity,
          'pavlok_duration_ms': _edgePeakZapDurationMs,
          'edge_hr_state': '${status['edge_hr_state'] ?? ''}',
          'edge_hr_last': _intFromAny(status['edge_hr_last']),
          'edge_hr_pause_bpm': _intFromAny(status['edge_hr_pause_bpm']),
          'latest_heart_rate': _intFromAny(status['latest_heart_rate']),
        },
      );
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final ble = context.watch<BleService>();
    final lovenseConnected = _nativeLovenseConnected || ble.lovenseConnected;
    final pavlokConnected = _nativePavlokConnected || ble.pavlokConnected;
    final lovenseBattery = _nativeLovenseBatteryPct ?? ble.lovenseBatteryPct;
    final pavlokBattery = _nativePavlokBatteryPct ?? ble.pavlokBatteryPct;
    final width = MediaQuery.sizeOf(context).width;
    final crossAxisCount = width >= 900
        ? 4
        : width >= 600
            ? 3
            : 2;

    final features = <_DashboardFeature>[
      _DashboardFeature(
        title: 'Chat',
        icon: Icons.chat_bubble_outline,
        onTap: _openNativeChat,
      ),
      _DashboardFeature(
        title: 'Questions',
        icon: Icons.quiz_outlined,
        screenBuilder: () => const QuestionsScreen(),
      ),
      _DashboardFeature(
        title: 'Settings',
        icon: Icons.settings_outlined,
        screenBuilder: () => const SettingsScreen(),
      ),
      _DashboardFeature(
        title: 'NudeNet Blocker',
        icon: Icons.shield_outlined,
        enabled: false,
        screenBuilder: () => const NudeNetBlockerScreen(),
      ),
      _DashboardFeature(
        title: 'Daily Check-In',
        icon: Icons.fact_check_outlined,
        screenBuilder: () => const CheckInScreen(),
      ),
      _DashboardFeature(
        title: 'Tasks',
        icon: Icons.task_outlined,
        screenBuilder: () => const TaskListScreen(),
      ),
      _DashboardFeature(
        title: 'Toy Control',
        icon: Icons.vibration_outlined,
        screenBuilder: () => const IntifaceScreen(),
      ),
      _DashboardFeature(
        title: 'Edge Controls',
        icon: Icons.tune,
        screenBuilder: () => EdgeControlsScreen(
          readState: () => EdgeControlsViewData(
            edgeCount: _edgeCount,
            orgasmCount: _orgasmCount,
            orgasmPermissionTokens: _orgasmPermissionTokens,
            orgasmDeniedCycles: _orgasmDeniedCycles,
            orgasmPleadTier: _orgasmPleadTier,
            edgeTimelineEventCount: _edgeTimelineEventCount,
            edgeTimelineLastEvent: _edgeTimelineLastEvent,
            edgeSafetyProfile: _edgeSafetyProfile,
            edgeBypassCooldownRemainingMs: _remainingBypassCooldownMs(),
            edgePendingCount: _edgePendingCount,
            orgasmPendingCount: _orgasmPendingCount,
            edgeTargetCount: _edgeTargetCount,
            edgeTargetShockAtPeak: _edgeTargetShockAtPeak,
            manualBuzzHoldUntilLowHr: _manualBuzzHoldUntilLowHr,
            counterApproveInFlight: _counterApproveInFlight,
            edgeTargetStepInFlight: _edgeTargetStepInFlight,
            lastEdgeSource: _lastEdgeSource,
            lastEdgeAtMs: _lastEdgeAtMs,
            buzzSessionEdgeCount: _buzzSessionEdgeCount,
            outOfItAutoTriggered: _outOfItAutoTriggered,
          ),
          onQueueEdge: _addEdgeFromControlsWithSafety,
          onQueueOrgasm: _requestOrgasmPermissionOrLog,
          onHoldBuzzUntilLowHr: _activateManualBuzzHoldUntilLowHr,
          onApprovePending: _approvePendingCounters,
          onUndoEdge: () => _undoSessionCounter(orgasm: false),
          onUndoOrgasm: () => _undoSessionCounter(orgasm: true),
          onSetEdgeTargetCount: _setEdgeTargetCount,
          onSetPeakShockEnabled: _setEdgeTargetShockAtPeak,
          onSetSafetyProfile: _setEdgeSafetyProfile,
          onRefreshState: _loadSessionCounters,
          onStopActuationNow: _stopBuzzOutputNow,
        ),
      ),
    ];

    return Scaffold(
      appBar: AppBar(
        title: const Text('Dashboard'),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 6),
            child: Chip(
              avatar: Icon(
                Icons.circle,
                size: 10,
                color: _enrollmentColor(cs),
              ),
              label: Text(_enrollmentLabel()),
            ),
          ),
          IconButton(
            onPressed: _openEnrollmentSetup,
            tooltip: 'Enrollment Setup',
            icon: const Icon(Icons.link_outlined),
          ),
        ],
      ),
      body: CustomScrollView(
        slivers: [
          if (_enrollmentState == 'retrying' && _enrollmentError.isNotEmpty)
            SliverToBoxAdapter(
              child: Container(
                width: double.infinity,
                margin: const EdgeInsets.fromLTRB(16, 8, 16, 4),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                  color: cs.surfaceContainerHighest.withOpacity(0.7),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: cs.outlineVariant.withOpacity(0.45)),
                ),
                child: Text(
                  _enrollmentError,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: cs.onSurfaceVariant,
                      ),
                ),
              ),
            ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 6, 16, 4),
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(Icons.battery_charging_full_outlined),
                          const SizedBox(width: 8),
                          Text(
                            'Toy Battery Levels',
                            style: Theme.of(context).textTheme.titleSmall,
                          ),
                          const Spacer(),
                          TextButton(
                            onPressed: _refreshToyStatusFromCard,
                            child: const Text('Refresh'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        'Lovense: ${lovenseConnected ? 'Paired' : 'Unpaired'}'
                        '${lovenseBattery == null ? ' • Battery: Unknown' : ' • Battery: ${lovenseBattery}%'}',
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Pavlok: ${pavlokConnected ? 'Paired' : 'Unpaired'}'
                        '${pavlokBattery == null ? ' • Battery: Unknown' : ' • Battery: ${pavlokBattery}%'}',
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 6, 16, 4),
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(Icons.favorite_outline),
                          const SizedBox(width: 8),
                          Text(
                            'Session Counters',
                            style: Theme.of(context).textTheme.titleSmall,
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text('Edges: $_edgeCount • Orgasms: $_orgasmCount'),
                      const SizedBox(height: 4),
                      Text(
                        'Pending: $_edgePendingCount edges • $_orgasmPendingCount orgasms',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SliverToBoxAdapter(
            child: Padding(
              padding: EdgeInsets.fromLTRB(16, 14, 16, 8),
              child: Text('Choose a feature'),
            ),
          ),
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 18),
            sliver: SliverGrid(
              delegate: SliverChildBuilderDelegate(
                (context, index) {
                  final item = features[index];
                  return _FeaturePill(
                    feature: item,
                    onTap: () {
                      final action = item.onTap;
                      if (action != null) {
                        return action();
                      }
                      final builder = item.screenBuilder;
                      if (builder != null) {
                        return _navigateAndTrack(builder());
                      }
                      return Future<void>.value();
                    },
                  );
                },
                childCount: features.length,
              ),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: crossAxisCount,
                childAspectRatio: 1.45,
                crossAxisSpacing: 12,
                mainAxisSpacing: 12,
              ),
            ),
          ),
          const SliverToBoxAdapter(
            child: SizedBox(height: 120),
          ),
        ],
      ),
    );
  }
}

class _DashboardFeature {
  const _DashboardFeature({
    required this.title,
    required this.icon,
    this.screenBuilder,
    this.onTap,
    this.enabled = true,
  }) : assert(screenBuilder != null || onTap != null);

  final String title;
  final IconData icon;
  final Widget Function()? screenBuilder;
  final Future<void> Function()? onTap;
  final bool enabled;
}

class _FeaturePill extends StatelessWidget {
  const _FeaturePill({required this.feature, required this.onTap});

  final _DashboardFeature feature;
  final Future<void> Function() onTap;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Material(
      color: feature.enabled
          ? cs.surfaceContainerLow
          : cs.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(28),
      child: InkWell(
        borderRadius: BorderRadius.circular(28),
        onTap: feature.enabled ? () => unawaited(onTap()) : null,
        child: Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(28),
            border: Border.all(color: cs.outlineVariant.withOpacity(0.45)),
          ),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: feature.enabled
                      ? cs.primaryContainer
                      : cs.surfaceContainerHigh,
                ),
                child: Icon(
                  feature.icon,
                  color: feature.enabled
                      ? cs.onPrimaryContainer
                      : cs.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                feature.enabled ? feature.title : '${feature.title} (Disabled)',
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w600,
                      color: feature.enabled
                          ? null
                          : cs.onSurfaceVariant,
                    ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class NudeNetBlockerScreen extends StatefulWidget {
  const NudeNetBlockerScreen({super.key});

  @override
  State<NudeNetBlockerScreen> createState() => _NudeNetBlockerScreenState();
}

class _NudeNetBlockerScreenState extends State<NudeNetBlockerScreen> {
  static const _kNudeNetEnabled = 'nudenet_enabled';
  static const _kFilterThreshold = 'filter_confidence_threshold';
  static const _kFilterStrictMode = 'filter_strict_mode';

  bool _loading = true;
  bool _strictMode = false;
  double _threshold = 0.55;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _strictMode = prefs.getBool(_kFilterStrictMode) ?? false;
      _threshold = prefs.getDouble(_kFilterThreshold) ?? 0.55;
      _loading = false;
    });
  }

  Future<void> _save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_kNudeNetEnabled, false);
    await prefs.setBool(_kFilterStrictMode, _strictMode);
    await prefs.setDouble(_kFilterThreshold, _threshold);
    await FilterServiceChannel.setStrictMode(enabled: _strictMode);
    await FilterServiceChannel.setThreshold(_threshold);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('NudeNet blocker settings saved.')),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('NudeNet Blocker')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            child: const ListTile(
              leading: Icon(Icons.block_outlined),
              title: Text('NudeNet media censoring is disabled'),
              subtitle: Text(
                'This feature is greyed out in the app and handler panel and remains forced off.',
              ),
            ),
          ),
          Opacity(
            opacity: 0.55,
            child: SwitchListTile(
              title: const Text('Enable NudeNet media censoring'),
              subtitle: const Text('Blocks detected explicit content locally.'),
              value: false,
              onChanged: null,
            ),
          ),
          SwitchListTile(
            title: const Text('Strict Mode'),
            subtitle: const Text('Apply stricter content filtering behavior.'),
            value: _strictMode,
            onChanged: (value) => setState(() => _strictMode = value),
          ),
          const SizedBox(height: 10),
          Text(
            'Detection Threshold (${_threshold.toStringAsFixed(2)})',
            style: Theme.of(context).textTheme.titleSmall,
          ),
          Slider(
            value: _threshold,
            min: 0.1,
            max: 1.0,
            divisions: 18,
            onChanged: (value) => setState(() => _threshold = value),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _save,
            icon: const Icon(Icons.save_outlined),
            label: const Text('Save'),
          ),
        ],
      ),
    );
  }
}
