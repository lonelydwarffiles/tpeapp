import 'dart:async';

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
  static const String _edgePendingCountKey = 'home_edge_pending_count';
  static const String _orgasmPendingCountKey = 'home_orgasm_pending_count';
  static const String _edgeTargetShockAtPeakKey = 'edge_target_shock_at_peak';
  static const int _edgeHrSafetyMarginBpm = 2;
  static const int _edgeGuardSliceMs = 1200;
  static const int _edgeHrFreshMaxAgeMs = 12000;
  static const int _edgeResumeStableMs = 6000;
  static const int _edgeResumeWaitMaxMs = 20000;

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
  int _edgePendingCount = 0;
  int _orgasmPendingCount = 0;
  int _edgeTargetCount = 0;
  bool _edgeTargetShockAtPeak = true;
  bool _manualBuzzHoldUntilLowHr = false;

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
    });
    _toyStatusTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      unawaited(_refreshToyStatus());
    });
    _edgeTargetTimer = Timer.periodic(const Duration(seconds: 20), (_) {
      unawaited(_runEdgeTargetStepIfNeeded());
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_refreshToyStatus());
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
      }
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
    final prefs = context.read<SharedPreferences>();
    var edgeCount = prefs.getInt(_edgeCountKey) ?? 0;
    var orgasmCount = prefs.getInt(_orgasmCountKey) ?? 0;
    var edgeTargetCount = prefs.getInt('edge_target_count') ?? 0;
    var edgeTargetShockAtPeak = prefs.getBool(_edgeTargetShockAtPeakKey) ?? true;
    final edgePendingCount =
      (prefs.getInt(_edgePendingCountKey) ?? 0).clamp(0, 1000000);
    final orgasmPendingCount =
      (prefs.getInt(_orgasmPendingCountKey) ?? 0).clamp(0, 1000000);

    final api = _api;
    if (api != null) {
      final remote = await api.fetchPublicStatusCounters();
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
          edgeCount = parsedEdge > edgeCount ? parsedEdge : edgeCount;
          await prefs.setInt(_edgeCountKey, edgeCount);
        }
        if (parsedOrgasm != null && parsedOrgasm >= 0) {
          orgasmCount = parsedOrgasm > orgasmCount ? parsedOrgasm : orgasmCount;
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

    if (!mounted) return;
    setState(() {
      _edgeCount = edgeCount;
      _orgasmCount = orgasmCount;
      _edgePendingCount = edgePendingCount;
      _orgasmPendingCount = orgasmPendingCount;
      _edgeTargetCount = edgeTargetCount;
      _edgeTargetShockAtPeak = edgeTargetShockAtPeak;
    });

    unawaited(_runEdgeTargetStepIfNeeded());
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

  Future<void> _approvePendingCounters() async {
    if (_counterApproveInFlight) return;

    final edgeDelta = _edgePendingCount;
    final orgasmDelta = _orgasmPendingCount;
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
      await prefs.setInt(_edgePendingCountKey, 0);
      await prefs.setInt(_orgasmPendingCountKey, 0);

      if (!mounted) return;
      setState(() {
        _edgeCount = nextEdge;
        _orgasmCount = nextOrgasm;
        _edgePendingCount = 0;
        _orgasmPendingCount = 0;
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

  Future<void> _activateManualBuzzHoldUntilLowHr() async {
    if (_manualBuzzHoldUntilLowHr) {
      await _stopBuzzOutputNow();
      return;
    }
    if (mounted) {
      setState(() => _manualBuzzHoldUntilLowHr = true);
    }
    await _stopBuzzOutputNow();
    await _trackBehavior(
      'manual_buzz_hold_enabled',
      reason: 'home_button',
    );
  }

  Future<bool> _manualBuzzHoldReadyToRelease() async {
    if (!_manualBuzzHoldUntilLowHr) {
      return true;
    }
    final status = await _fetchRealtimeEdgeStatus();
    final ready = _isEdgeHrFresh(status) && _isEdgeHrBelowResume(status);
    if (!ready) {
      return false;
    }
    if (mounted) {
      setState(() => _manualBuzzHoldUntilLowHr = false);
    }
    await _trackBehavior(
      'manual_buzz_hold_released',
      reason: 'hr_low_end_reached',
      payload: {
        'edge_hr_last': _intFromAny(status['edge_hr_last']),
        'latest_heart_rate': _intFromAny(status['latest_heart_rate']),
        'edge_hr_resume_bpm': _intFromAny(status['edge_hr_resume_bpm']),
      },
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
      await ble.lovenseVibrate(12);
      try {
        while (remainingMs > 0) {
          if (_manualBuzzHoldUntilLowHr) {
            break;
          }
          final sliceMs = remainingMs < _edgeGuardSliceMs ? remainingMs : _edgeGuardSliceMs;
          await Future<void>.delayed(Duration(milliseconds: sliceMs));
          remainingMs -= sliceMs;
          status = await _fetchRealtimeEdgeStatus();
          if (_hrAtOrAboveSoftStopForEdge(status) || !_isEdgeHrFresh(status)) {
            break;
          }
        }
      } finally {
        await ble.lovenseVibrate(0);
      }

      if (_edgeTargetShockAtPeak) {
        final peakStatus = await _fetchRealtimeEdgeStatus();
        if (_hrLimitReachedForPeakShock(peakStatus)) {
          await BleChannel.pavlokZap(intensity: 100, durationMs: 500);
          await _trackBehavior(
            'edge_target_peak_shock_fired',
            reason: 'hr_limit_hit_while_buzzing',
            payload: {
              'edge_hr_state': '${peakStatus['edge_hr_state'] ?? ''}',
              'edge_hr_last': _intFromAny(peakStatus['edge_hr_last']),
              'edge_hr_pause_bpm': _intFromAny(peakStatus['edge_hr_pause_bpm']),
              'latest_heart_rate': _intFromAny(peakStatus['latest_heart_rate']),
            },
          );
        }
      }

      await _queueSessionCounter(orgasm: false);
      await _approvePendingCounters();
    } catch (_) {
      // Keep automation best-effort and retry on next tick.
    } finally {
      _edgeTargetStepInFlight = false;
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
    final buzzControlsVisible =
        _edgeTargetCount > 0 || _manualBuzzHoldUntilLowHr || _edgeTargetStepInFlight;
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
      floatingActionButtonLocation: FloatingActionButtonLocation.centerFloat,
      floatingActionButton: SafeArea(
        minimum: const EdgeInsets.fromLTRB(12, 0, 12, 10),
        child: Card(
          margin: EdgeInsets.zero,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Backend counters: $_edgeCount edges • $_orgasmCount orgasms',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
                const SizedBox(height: 4),
                Text(
                  'Pending in app: $_edgePendingCount edges • $_orgasmPendingCount orgasms',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 4),
                Text(
                  _edgeTargetCount > 0
                      ? (_edgeCount >= _edgeTargetCount
                          ? 'Edge target reached: $_edgeCount/$_edgeTargetCount'
                          : lovenseConnected
                              ? 'Edge target: $_edgeCount/$_edgeTargetCount (auto-running)'
                              : 'Edge target: $_edgeCount/$_edgeTargetCount (waiting for toy)')
                      : 'Edge target: off',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 2),
                Text(
                  'Peak shock: ${_edgeTargetShockAtPeak ? 'on' : 'off'}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 2),
                Text(
                  _manualBuzzHoldUntilLowHr
                      ? 'Buzz hold: active until HR is at low-end threshold'
                      : 'Buzz hold: off',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                if (buzzControlsVisible) ...[
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: FilledButton.icon(
                          onPressed: () => unawaited(
                            _queueSessionCounter(orgasm: false),
                          ),
                          icon: const Icon(Icons.trending_up),
                          label: const Text('Queue Edge'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: FilledButton.tonalIcon(
                          onPressed: () => unawaited(
                            _queueSessionCounter(orgasm: true),
                          ),
                          icon: const Icon(Icons.favorite),
                          label: const Text('Queue Orgasm'),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: () => unawaited(_activateManualBuzzHoldUntilLowHr()),
                      icon: Icon(
                        _manualBuzzHoldUntilLowHr
                            ? Icons.pause_circle_filled
                            : Icons.pause_circle_outline,
                      ),
                      label: Text(
                        _manualBuzzHoldUntilLowHr
                            ? 'Buzz Hold Active (Tap to Re-stop)'
                            : 'Hold Buzz Until HR Low',
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: (_counterApproveInFlight ||
                              (_edgePendingCount <= 0 && _orgasmPendingCount <= 0))
                          ? null
                          : () => unawaited(_approvePendingCounters()),
                      icon: _counterApproveInFlight
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.cloud_upload_outlined),
                      label: Text(
                        _counterApproveInFlight
                            ? 'Approving...'
                            : 'Approve Pending Counters',
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
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
