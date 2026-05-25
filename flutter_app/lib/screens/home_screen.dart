import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../channels/mqtt_channel.dart';
import '../services/chat_repository.dart';
import '../models/chat_message.dart';
import 'check_in_screen.dart';
import 'task_list_screen.dart';
import 'screen_share_screen.dart';
import 'password_vault_screen.dart';
import 'intiface_screen.dart';

// ── Navigation drawer ─────────────────────────────────────────────────────────

class _AppDrawer extends StatelessWidget {
  const _AppDrawer({required this.onNavigate, required this.onNavigateWithPin});

  final void Function(Widget) onNavigate;
  final void Function(Widget) onNavigateWithPin;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Drawer(
      child: Column(
        children: [
          DrawerHeader(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [cs.primaryContainer, cs.secondaryContainer],
              ),
            ),
            child: Align(
              alignment: Alignment.bottomLeft,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  CircleAvatar(
                    radius: 26,
                    backgroundColor: cs.primary,
                    child: Icon(Icons.smart_toy_outlined, color: cs.onPrimary, size: 28),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    'TPE',
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                          color: cs.onPrimaryContainer,
                          fontWeight: FontWeight.bold,
                        ),
                  ),
                ],
              ),
            ),
          ),
          Expanded(
            child: ListView(
              padding: EdgeInsets.zero,
              children: [
                _DrawerItem(
                  icon: Icons.checklist_rounded,
                  label: 'Daily Check-In',
                  onTap: () => _open(context, const CheckInScreen()),
                ),
                _DrawerItem(
                  icon: Icons.task_alt_rounded,
                  label: 'My Tasks',
                  onTap: () => _open(context, const TaskListScreen()),
                ),
                const Divider(),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
                  child: Text(
                    'PARTNER',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                          color: cs.onSurfaceVariant,
                          letterSpacing: 1.2,
                        ),
                  ),
                ),
                _DrawerItem(
                  icon: Icons.assignment_rounded,
                  label: 'Assign Task',
                  locked: true,
                  onTap: () => _openWithPin(context, const AssignTaskScreen()),
                ),
                _DrawerItem(
                  icon: Icons.quiz_rounded,
                  label: 'Questions',
                  locked: true,
                  onTap: () => _openWithPin(context, const QuestionsScreen()),
                ),
                _DrawerItem(
                  icon: Icons.favorite_rounded,
                  label: 'Relationship Center',
                  locked: true,
                  onTap: () => _openWithPin(context, const RelationshipCenterScreen()),
                ),
                _DrawerItem(
                  icon: Icons.settings_rounded,
                  label: 'Settings',
                  locked: true,
                  onTap: () => _openWithPin(context, const SettingsScreen()),
                ),
                const Divider(),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
                  child: Text(
                    'FEATURES',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                          color: cs.onSurfaceVariant,
                          letterSpacing: 1.2,
                        ),
                  ),
                ),
                _DrawerItem(
                  icon: Icons.screen_share_rounded,
                  label: 'Screen Share',
                  onTap: () => _open(context, const ScreenShareScreen()),
                ),
                _DrawerItem(
                  icon: Icons.lock_rounded,
                  label: 'Password Vault',
                  onTap: () => _open(context, const PasswordVaultScreen()),
                ),
                _DrawerItem(
                  icon: Icons.vibration_rounded,
                  label: 'Toy Control',
                  onTap: () => _open(context, const IntifaceScreen()),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _open(BuildContext context, Widget screen) {
    Navigator.pop(context);
    onNavigate(screen);
  }

  void _openWithPin(BuildContext context, Widget screen) {
    Navigator.pop(context);
    onNavigateWithPin(screen);
  }
}

class _DrawerItem extends StatelessWidget {
  const _DrawerItem({
    required this.icon,
    required this.label,
    required this.onTap,
    this.locked = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final bool locked;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ListTile(
      leading: Icon(icon, color: cs.onSurfaceVariant),
      title: Text(label),
      trailing: locked
          ? Icon(Icons.lock_outline_rounded, size: 16, color: cs.outlineVariant)
          : null,
      onTap: onTap,
    );
  }
}

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
              child: Icon(Icons.smart_toy_outlined, size: 40, color: cs.onPrimaryContainer),
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

class _HomeScreenState extends State<HomeScreen> {
  final _textController = TextEditingController();
  final _scrollController = ScrollController();
  bool _sending = false;
  StreamSubscription<Map<String, String>>? _mqttSub;

  @override
  void initState() {
    super.initState();
    _mqttSub = MqttChannel.events.listen(_onMqttEvent);
  }

  @override
  void dispose() {
    _mqttSub?.cancel();
    _textController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _onMqttEvent(Map<String, String> data) {
    switch (data['action']) {
      case 'REQUEST_CHECKIN':
        if (mounted) {
          Navigator.push(
              context, MaterialPageRoute(builder: (_) => const CheckInScreen()));
        }
        return;
      default:
        break;
    }
  }

  Future<void> _send() async {
    final text = _textController.text.trim();
    if (text.isEmpty || _sending) return;
    _textController.clear();

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
      Navigator.push(context, MaterialPageRoute(builder: (_) => screen));

  void _navigateWithPin(Widget screen) =>
      _requirePin(() => _navigate(screen));

  @override
  Widget build(BuildContext context) {
    final history = context.watch<ChatRepository>().history;
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Handler'),
        actions: [
          PopupMenuButton<String>(
            onSelected: (item) {
              switch (item) {
                case 'checkin':
                  Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const CheckInScreen()));
                  return;
                case 'tasks':
                  Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const TaskListScreen()));
                  return;
                case 'assign':
                  _requirePin(() => Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const AssignTaskScreen())));
                  return;
                case 'questions':
                  _requirePin(() => Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const QuestionsScreen())));
                  return;
                case 'settings':
                  _requirePin(() => Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const SettingsScreen())));
                  return;
                case 'screen_share':
                  Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const ScreenShareScreen()));
                  return;
                case 'vault':
                  Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const PasswordVaultScreen()));
                  return;
                case 'relationships':
                  _requirePin(() => Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const RelationshipCenterScreen())));
                  return;
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'checkin',   child: Text('Daily Check-In')),
              PopupMenuItem(value: 'tasks',     child: Text('My Tasks')),
              PopupMenuItem(value: 'assign',    child: Text('Assign Task (Partner)')),
              PopupMenuItem(value: 'questions', child: Text('Questions (Partner)')),
              PopupMenuItem(value: 'settings',  child: Text('Settings (Partner)')),
              PopupMenuItem(value: 'screen_share', child: Text('Screen Share')),
              PopupMenuItem(value: 'vault',     child: Text('Password Vault')),
              PopupMenuItem(value: 'relationships', child: Text('Relationship Center (Partner)')),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: history.isEmpty
                ? _EmptyChat(cs: cs)
                : ListView.builder(
                    controller: _scrollController,
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    itemCount: history.length,
                    itemBuilder: (_, i) => _MessageBubble(message: history[i]),
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
              child: Icon(Icons.smart_toy_outlined, size: 14, color: cs.onPrimaryContainer),
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
                color: isUser ? cs.primary : cs.surfaceContainerHighest,
                borderRadius: BorderRadius.only(
                  topLeft: const Radius.circular(18),
                  topRight: const Radius.circular(18),
                  bottomLeft: Radius.circular(isUser ? 18 : 4),
                  bottomRight: Radius.circular(isUser ? 4 : 18),
                ),
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
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 6, 12, 10),
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
              ),
            ),
          ],
        ),
      ),
    );
  }
}
