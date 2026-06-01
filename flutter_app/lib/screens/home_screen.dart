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

  String _enrollmentState = 'enrolling';
  String _enrollmentError = '';

  StreamSubscription<dynamic>? _nativeBleSub;
  Timer? _enrollmentStatusTimer;
  Timer? _toyStatusTimer;
  ApiService? _api;
  WebSocketService? _webSocketService;
  bool _homeOpenedTracked = false;
  String _lastConnectedEndpoint = '';
  bool _nativeLovenseConnected = false;
  bool _nativePavlokConnected = false;
  int? _nativeLovenseBatteryPct;
  int? _nativePavlokBatteryPct;
  bool _countersLoaded = false;
  int _edgeCount = 0;
  int _orgasmCount = 0;

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
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_refreshToyStatus());
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _nativeBleSub?.cancel();
    _enrollmentStatusTimer?.cancel();
    _toyStatusTimer?.cancel();
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

    final api = _api;
    if (api != null) {
      final remote = await api.fetchPublicStatusCounters();
      if (remote.isNotEmpty) {
        final remoteEdge = remote['tasks_completed'];
        final remoteOrgasm = remote['confessions_posted'];
        final parsedEdge = remoteEdge is num ? remoteEdge.toInt() : int.tryParse('${remoteEdge ?? ''}');
        final parsedOrgasm = remoteOrgasm is num ? remoteOrgasm.toInt() : int.tryParse('${remoteOrgasm ?? ''}');
        if (parsedEdge != null && parsedEdge >= 0) {
          edgeCount = parsedEdge > edgeCount ? parsedEdge : edgeCount;
          await prefs.setInt(_edgeCountKey, edgeCount);
        }
        if (parsedOrgasm != null && parsedOrgasm >= 0) {
          orgasmCount = parsedOrgasm > orgasmCount ? parsedOrgasm : orgasmCount;
          await prefs.setInt(_orgasmCountKey, orgasmCount);
        }
      }
    }

    if (!mounted) return;
    setState(() {
      _edgeCount = edgeCount;
      _orgasmCount = orgasmCount;
    });
  }

  Future<void> _incrementSessionCounter({required bool orgasm}) async {
    final prefs = context.read<SharedPreferences>();
    final edgeCount = orgasm ? _edgeCount : _edgeCount + 1;
    final orgasmCount = orgasm ? _orgasmCount + 1 : _orgasmCount;

    await prefs.setInt(_edgeCountKey, edgeCount);
    await prefs.setInt(_orgasmCountKey, orgasmCount);

    if (!mounted) return;
    setState(() {
      _edgeCount = edgeCount;
      _orgasmCount = orgasmCount;
    });

    await _trackBehavior(
      orgasm ? 'orgasm_recorded' : 'edge_recorded',
      reason: 'home_counter_button',
      payload: {
        'edge_count': edgeCount,
        'orgasm_count': orgasmCount,
      },
    );

    // The webhook-backed counter update is asynchronous on the backend, so an
    // immediate reload can race against stale public-status values and snap the
    // UI back down. Refresh later in the background instead.
    unawaited(
      Future<void>.delayed(const Duration(seconds: 3), () async {
        if (!mounted) return;
        await _loadSessionCounters();
      }),
    );
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
                  'Counters: $_edgeCount edges • $_orgasmCount orgasms',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton.icon(
                        onPressed: () => unawaited(
                          _incrementSessionCounter(orgasm: false),
                        ),
                        icon: const Icon(Icons.trending_up),
                        label: const Text('Add Edge'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: FilledButton.tonalIcon(
                        onPressed: () => unawaited(
                          _incrementSessionCounter(orgasm: true),
                        ),
                        icon: const Icon(Icons.favorite),
                        label: const Text('Add Orgasm'),
                      ),
                    ),
                  ],
                ),
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
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
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
