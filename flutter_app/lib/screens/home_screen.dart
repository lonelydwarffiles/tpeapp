import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../channels/fcm_channel.dart';
import '../channels/partner_pin_channel.dart';
import '../services/chat_repository.dart';
import '../models/chat_message.dart';
import 'check_in_screen.dart';
import 'task_list_screen.dart';
import 'assign_task_screen.dart';
import 'questions_screen.dart';
import 'settings_screen.dart';

/// Main screen — the "Handler" AI chat interface.
///
/// Dart equivalent of [HandlerChatActivity].  Also listens to [FcmChannel]
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

  @override
  void initState() {
    super.initState();
    FcmChannel.events.listen(_onFcmEvent);
  }

  @override
  void dispose() {
    _textController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _onFcmEvent(Map<String, String> data) {
    switch (data['action']) {
      case 'REQUEST_CHECKIN':
        if (mounted) {
          Navigator.push(
              context, MaterialPageRoute(builder: (_) => const CheckInScreen()));
        }
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

  Future<void> _requirePin(VoidCallback onSuccess) async {
    final pin = await _showPinDialog();
    if (pin == null) return;
    final ok = await PartnerPinChannel.verifyPin(pin);
    if (ok) {
      onSuccess();
    } else {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Incorrect PIN.')),
        );
      }
    }
  }

  Future<String?> _showPinDialog() async {
    final controller = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Partner PIN Required'),
        content: TextField(
          controller: controller,
          obscureText: true,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(hintText: 'Enter partner PIN'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.pop(ctx, controller.text),
            child: const Text('OK'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final history = context.watch<ChatRepository>().history;

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
                case 'tasks':
                  Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const TaskListScreen()));
                case 'assign':
                  _requirePin(() => Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const AssignTaskScreen())));
                case 'questions':
                  _requirePin(() => Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const QuestionsScreen())));
                case 'settings':
                  _requirePin(() => Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const SettingsScreen())));
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'checkin',   child: Text('Daily Check-In')),
              PopupMenuItem(value: 'tasks',     child: Text('My Tasks')),
              PopupMenuItem(value: 'assign',    child: Text('Assign Task (Partner)')),
              PopupMenuItem(value: 'questions', child: Text('Questions (Partner)')),
              PopupMenuItem(value: 'settings',  child: Text('Settings (Partner)')),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: ListView.builder(
              controller: _scrollController,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              itemCount: history.length,
              itemBuilder: (_, i) => _MessageBubble(message: history[i]),
            ),
          ),
          if (_sending) const LinearProgressIndicator(),
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

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message});
  final ChatMessage message;

  @override
  Widget build(BuildContext context) {
    final isUser = message.isUser;
    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.75,
        ),
        decoration: BoxDecoration(
          color: isUser
              ? Theme.of(context).colorScheme.primary
              : Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(
          message.content,
          style: TextStyle(
            color: isUser
                ? Theme.of(context).colorScheme.onPrimary
                : Theme.of(context).colorScheme.onSurface,
          ),
        ),
      ),
    );
  }
}

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
        padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
        child: Row(
          children: [
            Expanded(
              child: TextField(
                controller: controller,
                enabled: enabled,
                decoration: const InputDecoration(
                  hintText: 'Message Handler…',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
                onSubmitted: (_) => onSend(),
                textInputAction: TextInputAction.send,
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filled(
              onPressed: enabled ? onSend : null,
              icon: const Icon(Icons.send),
            ),
          ],
        ),
      ),
    );
  }
}
