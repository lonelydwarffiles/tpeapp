import 'dart:async';
import 'dart:developer' as developer;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/mqtt_channel.dart';
import '../services/chat_repository.dart';
import '../services/api_service.dart';
import '../services/remote_command_service.dart';
import '../services/websocket_service.dart';
import '../models/chat_message.dart';
import 'check_in_screen.dart';
import 'task_list_screen.dart';
import 'screen_share_screen.dart';
import 'password_vault_screen.dart';
import 'intiface_screen.dart';
import 'questions_screen.dart';
import 'settings_screen.dart';

// ── Empty chat placeholder ────────────────────────────────────────────────────

class _EmptyChat extends StatelessWidget {
  const _EmptyChat({required this.cs});
  final ColorScheme cs;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 80,
              height: 80,
              decoration: BoxDecoration(
                color: cs.primaryContainer,
                shape: BoxShape.circle,
              ),
              child: Icon(Icons.smart_toy_outlined,
                  size: 40, color: cs.onPrimaryContainer),
            ),
            const SizedBox(height: 20),
            Text(
              'Handler',
              style: Theme.of(context)
                  .textTheme
                  .headlineSmall
                  ?.copyWith(fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            Text(
              'Start a conversation with your AI accountability partner.',
              textAlign: TextAlign.center,
              style: Theme.of(context)
                  .textTheme
                  .bodyMedium
                  ?.copyWith(color: cs.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }
}

/// Main screen — the "Handler" AI chat interface.
///
/// Dart equivalent of [HandlerChatActivity].  Also listens to [MqttChannel]
/// events so the UI can react to partner pushes (REQUEST_CHECKIN, etc.).
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  static const String _fallbackLiveEndpoint = 'https://mochii.live';
  final _textController = TextEditingController();
  final _scrollController = ScrollController();
  bool _sending = false;
  String _enrollmentState = 'enrolling';
  String _enrollmentError = '';
  StreamSubscription<Map<String, String>>? _mqttSub;
  Timer? _enrollmentStatusTimer;
  RemoteCommandService? _remoteCommands;
  ApiService? _api;
  WebSocketService? _webSocketService;
  bool _homeOpenedTracked = false;
  DateTime? _typingSessionStart;
  int _typingInsertedChars = 0;
  int _typingBackspaceCount = 0;
  String _lastInputValue = '';

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
    _textController.addListener(_onInputChanged);
    _mqttSub = MqttChannel.events.listen(_onMqttEvent);
    _refreshEnrollmentState();
    _enrollmentStatusTimer = Timer.periodic(const Duration(seconds: 5), (_) {
      _refreshEnrollmentState();
    });
  }

  @override
  void dispose() {
    _finalizeTypingSession();
    WidgetsBinding.instance.removeObserver(this);
    _mqttSub?.cancel();
    unawaited(_webSocketService?.disconnect() ?? Future<void>.value());
    _enrollmentStatusTimer?.cancel();
    _textController.removeListener(_onInputChanged);
    _textController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      _finalizeTypingSession();
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

  void _onMqttEvent(Map<String, String> data) {
    developer.log('MQTT raw payload: $data', name: 'HomeScreen');
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

      // Force the startup enrollment loop to re-run using new values.
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

  Future<void> _send() async {
    final text = _textController.text.trim();
    if (text.isEmpty || _sending) return;

    _finalizeTypingSession();
    _textController.clear();
    _lastInputValue = '';

    await _trackBehavior(
      'chat_message_sent',
      payload: {'length': text.length},
    );

    final repo = context.read<ChatRepository>();
    setState(() => _sending = true);

    await repo.addUserMessage(text);
    _scrollToBottom();

    try {
      final reply = await repo.sendMessage(text);
      await repo.addAssistantMessage(reply);
    } catch (e) {
      await repo.addAssistantMessage('⚠️ ${e.toString()}');
    } finally {
      if (mounted) setState(() => _sending = false);
      _scrollToBottom();
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut,
        );
      }
    });
  }

  void _navigate(Widget screen) =>
      unawaited(_navigateAndTrack(screen));

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

  void _onInputChanged() {
    final current = _textController.text;

    if (_typingSessionStart == null && current.isNotEmpty) {
      _typingSessionStart = DateTime.now().toUtc();
      _typingInsertedChars = 0;
      _typingBackspaceCount = 0;
      _lastInputValue = '';
    }

    if (_typingSessionStart != null) {
      final delta = current.length - _lastInputValue.length;
      if (delta > 0) {
        _typingInsertedChars += delta;
      } else if (delta < 0) {
        _typingBackspaceCount += -delta;
      }
    }

    _lastInputValue = current;
  }

  void _finalizeTypingSession() {
    final startedAt = _typingSessionStart;
    if (startedAt == null) return;

    final endedAt = DateTime.now().toUtc();
    final durationMs = endedAt.difference(startedAt).inMilliseconds;
    final inserted = _typingInsertedChars;
    final backspaces = _typingBackspaceCount;
    final correctionRate = inserted > 0 ? (backspaces / inserted) : 0.0;

    _typingSessionStart = null;
    _typingInsertedChars = 0;
    _typingBackspaceCount = 0;

    unawaited(
      _trackBehavior(
        'typing_session_metrics',
        payload: {
          'start_time': startedAt.toIso8601String(),
          'stop_time': endedAt.toIso8601String(),
          'duration_ms': durationMs,
          'total_characters': inserted,
          'backspace_count': backspaces,
          'correction_rate': double.parse(correctionRate.toStringAsFixed(4)),
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final history = context.watch<ChatRepository>().history;
    final cs = Theme.of(context).colorScheme;
    final bg = Theme.of(context).scaffoldBackgroundColor;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Handler'),
        actions: [
          Container(
            margin: const EdgeInsets.only(right: 6),
            child: Chip(
              avatar: Icon(
                Icons.circle,
                size: 10,
                color: _enrollmentColor(cs),
              ),
              label: Text(_enrollmentLabel()),
            ),
          ),
          PopupMenuButton<String>(
            onSelected: (item) {
              switch (item) {
                case 'checkin':
                  _navigate(const CheckInScreen());
                  return;
                case 'tasks':
                  _navigate(const TaskListScreen());
                  return;
                case 'questions':
                  _navigate(const QuestionsScreen());
                  return;
                case 'settings':
                  _navigate(const SettingsScreen());
                  return;
                case 'screen_share':
                  _navigate(const ScreenShareScreen());
                  return;
                case 'vault':
                  _navigate(const PasswordVaultScreen());
                  return;
                case 'enrollment':
                  _openEnrollmentSetup();
                  return;
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'enrollment', child: Text('Enrollment Setup')),
              PopupMenuItem(value: 'checkin', child: Text('Daily Check-In')),
              PopupMenuItem(value: 'tasks', child: Text('My Tasks')),
              PopupMenuItem(value: 'questions', child: Text('Questions')),
              PopupMenuItem(value: 'settings', child: Text('Settings')),
              PopupMenuItem(value: 'screen_share', child: Text('Screen Share')),
              PopupMenuItem(value: 'vault', child: Text('Password Vault')),
            ],
          ),
        ],
      ),
      body: Stack(
        children: [
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: bg,
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    cs.surface.withOpacity(0.92),
                    bg,
                  ],
                ),
              ),
            ),
          ),
          Positioned(
            top: -60,
            right: -40,
            child: _GlowOrb(color: cs.primary.withOpacity(0.22), size: 220),
          ),
          Positioned(
            bottom: 90,
            left: -50,
            child: _GlowOrb(color: cs.tertiary.withOpacity(0.12), size: 180),
          ),
          Column(
            children: [
              if (_enrollmentState == 'retrying' && _enrollmentError.isNotEmpty)
                Container(
                  width: double.infinity,
                  margin: const EdgeInsets.fromLTRB(12, 8, 12, 0),
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
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
              Expanded(
                child: history.isEmpty
                    ? _EmptyChat(cs: cs)
                    : ListView.builder(
                        controller: _scrollController,
                        padding: const EdgeInsets.symmetric(
                            horizontal: 12, vertical: 8),
                        itemCount: history.length,
                        itemBuilder: (_, i) =>
                            _MessageBubble(message: history[i]),
                      ),
              ),
              if (_sending)
                LinearProgressIndicator(
                  backgroundColor: cs.surfaceContainerHighest,
                  color: cs.primary,
                ),
              _InputRow(
                controller: _textController,
                onSend: _send,
                enabled: !_sending,
              ),
            ],
          ),
        ],
      ),
    );
  }
}


class _GlowOrb extends StatelessWidget {
  const _GlowOrb({required this.color, required this.size});

  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: RadialGradient(
            colors: [color, color.withOpacity(0)],
          ),
        ),
      ),
    );
  }
}

// ── Chat bubble ───────────────────────────────────────────────────────────────

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message});
  final ChatMessage message;

  @override
  Widget build(BuildContext context) {
    final isUser = message.isUser;
    final cs = Theme.of(context).colorScheme;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment:
            isUser ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!isUser) ...[
            CircleAvatar(
              radius: 14,
              backgroundColor: cs.primaryContainer,
              child: Icon(Icons.smart_toy_outlined,
                  size: 14, color: cs.onPrimaryContainer),
            ),
            const SizedBox(width: 6),
          ],
          Flexible(
            child: Container(
              constraints: BoxConstraints(
                maxWidth: MediaQuery.of(context).size.width * 0.72,
              ),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(
                color: isUser
                    ? cs.primary.withOpacity(0.92)
                    : cs.surfaceContainerHigh,
                borderRadius: BorderRadius.only(
                  topLeft: const Radius.circular(18),
                  topRight: const Radius.circular(18),
                  bottomLeft: Radius.circular(isUser ? 18 : 4),
                  bottomRight: Radius.circular(isUser ? 4 : 18),
                ),
                border: Border.all(
                  color: isUser
                      ? cs.primary.withOpacity(0.55)
                      : cs.outlineVariant.withOpacity(0.4),
                ),
                boxShadow: [
                  BoxShadow(
                    blurRadius: 12,
                    offset: const Offset(0, 4),
                    color: Colors.black.withOpacity(0.18),
                  ),
                ],
              ),
              child: Text(
                message.content,
                style: TextStyle(
                  color: isUser ? cs.onPrimary : cs.onSurface,
                  height: 1.4,
                ),
              ),
            ),
          ),
          if (isUser) const SizedBox(width: 6),
        ],
      ),
    );
  }
}

// ── Input row ─────────────────────────────────────────────────────────────────

class _InputRow extends StatelessWidget {
  const _InputRow({
    required this.controller,
    required this.onSend,
    required this.enabled,
  });
  final TextEditingController controller;
  final VoidCallback onSend;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 6, 12, 10),
        child: Card(
          elevation: 4,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(10, 8, 8, 8),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: controller,
                    enabled: enabled,
                    decoration: const InputDecoration(
                      hintText: 'Message Handler…',
                    ),
                    onSubmitted: (_) => onSend(),
                    textInputAction: TextInputAction.send,
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: enabled ? onSend : null,
                  icon: const Icon(Icons.arrow_upward_rounded),
                  style: IconButton.styleFrom(
                    shape: const CircleBorder(),
                    backgroundColor: cs.primary,
                    foregroundColor: cs.onPrimary,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
