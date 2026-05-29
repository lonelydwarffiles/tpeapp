import 'dart:async';
import 'dart:developer' as developer;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/filter_service_channel.dart';
import '../channels/mqtt_channel.dart';
import '../services/api_service.dart';
import '../services/kiosk_task_controller.dart';
import '../services/remote_command_service.dart';
import '../services/websocket_service.dart';
import 'check_in_screen.dart';
import 'intiface_screen.dart';
import 'questions_screen.dart';
import 'screen_share_screen.dart';
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

  String _enrollmentState = 'enrolling';
  String _enrollmentError = '';

  StreamSubscription<Map<String, String>>? _mqttSub;
  Timer? _enrollmentStatusTimer;
  RemoteCommandService? _remoteCommands;
  ApiService? _api;
  WebSocketService? _webSocketService;
  bool _homeOpenedTracked = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _api ??= ApiService(context.read<SharedPreferences>());
    _webSocketService ??= context.read<WebSocketService>();
    _remoteCommands ??= RemoteCommandService(
      prefs: context.read<SharedPreferences>(),
      onCheckInRequested: _openCheckIn,
      onMessage: _showCommandMessage,
    );
    unawaited(_webSocketService?.connect() ?? Future<void>.value());
    if (!_homeOpenedTracked) {
      _homeOpenedTracked = true;
      unawaited(_trackBehavior('app_home_opened', reason: _enrollmentState));
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _mqttSub = MqttChannel.events.listen(_onMqttEvent);
    _refreshEnrollmentState();
    _enrollmentStatusTimer = Timer.periodic(const Duration(seconds: 5), (_) {
      _refreshEnrollmentState();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _mqttSub?.cancel();
    unawaited(_webSocketService?.disconnect() ?? Future<void>.value());
    _enrollmentStatusTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
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

  void _onMqttEvent(Map<String, String> data) {
    developer.log('MQTT raw payload: $data', name: 'HomeScreen');
    context.read<KioskTaskController>().handleMqttEvent(data);
    final action = (data['action'] ?? data['command'] ?? '').trim();
    if (action.isNotEmpty) {
      unawaited(
        _trackBehavior(
          'mqtt_event_received',
          reason: action,
          payload: {
            if (data['session_id'] != null && data['session_id']!.trim().isNotEmpty)
              'session_id': data['session_id']!.trim(),
          },
        ),
      );
    }
    final commands = _remoteCommands;
    if (commands != null) {
      unawaited(commands.handleEvent(data));
    }
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

      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Enrollment updated. Reconnecting...')),
      );
    } finally {
      endpointCtrl.dispose();
      keyCtrl.dispose();
    }
  }

  void _showCommandMessage(String message) {
    if (!mounted || message.trim().isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  Future<void> _navigateAndTrack(Widget screen) async {
    final route = screen.runtimeType.toString();
    await _trackBehavior('screen_opened', reason: route);
    if (!mounted) return;
    await Navigator.push(context, MaterialPageRoute(builder: (_) => screen));
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

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final width = MediaQuery.sizeOf(context).width;
    final crossAxisCount = width >= 900
        ? 4
        : width >= 600
            ? 3
            : 2;

    final features = <_DashboardFeature>[
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
        screenBuilder: () => const NudeNetBlockerScreen(),
      ),
      _DashboardFeature(
        title: 'WebRTC Video',
        icon: Icons.videocam_outlined,
        screenBuilder: () => const ScreenShareScreen(),
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
                    onTap: () => _navigateAndTrack(item.screenBuilder()),
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
        ],
      ),
    );
  }
}

class _DashboardFeature {
  const _DashboardFeature({
    required this.title,
    required this.icon,
    required this.screenBuilder,
  });

  final String title;
  final IconData icon;
  final Widget Function() screenBuilder;
}

class _FeaturePill extends StatelessWidget {
  const _FeaturePill({required this.feature, required this.onTap});

  final _DashboardFeature feature;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Material(
      color: cs.surfaceContainerLow,
      borderRadius: BorderRadius.circular(28),
      child: InkWell(
        borderRadius: BorderRadius.circular(28),
        onTap: onTap,
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
                  color: cs.primaryContainer,
                ),
                child: Icon(feature.icon, color: cs.onPrimaryContainer),
              ),
              const SizedBox(height: 10),
              Text(
                feature.title,
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w600,
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
  bool _nudeNetEnabled = false;
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
      _nudeNetEnabled = prefs.getBool(_kNudeNetEnabled) ?? false;
      _strictMode = prefs.getBool(_kFilterStrictMode) ?? false;
      _threshold = prefs.getDouble(_kFilterThreshold) ?? 0.55;
      _loading = false;
    });
  }

  Future<void> _save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_kNudeNetEnabled, _nudeNetEnabled);
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
          SwitchListTile(
            title: const Text('Enable NudeNet media censoring'),
            subtitle: const Text('Blocks detected explicit content locally.'),
            value: _nudeNetEnabled,
            onChanged: (value) => setState(() => _nudeNetEnabled = value),
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
