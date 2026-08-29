import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/api_service.dart';

/// Dart equivalent of [CheckInActivity].
///
/// Lets the device owner submit a daily mood/compliance check-in to the
/// partner backend via [ApiService].
class CheckInScreen extends StatefulWidget {
  const CheckInScreen({super.key});

  @override
  State<CheckInScreen> createState() => _CheckInScreenState();
}

class _CheckInScreenState extends State<CheckInScreen> {
  double _moodScore = 5;
  final _noteController = TextEditingController();
  bool _submitting = false;
  String? _result;

  static const _moodEmoji = ['😞', '😟', '😕', '😐', '🙂', '😊', '😄', '😁', '🤩', '🥰'];

  String get _emoji => _moodEmoji[(_moodScore.round() - 1).clamp(0, 9)];

  @override
  void dispose() {
    _noteController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _submitting = true;
      _result = null;
    });
    try {
      final prefs = await SharedPreferences.getInstance();
      final api = ApiService(prefs);
      await api.submitCheckIn(
        moodScore: _moodScore.round(),
        note: _noteController.text.trim(),
      );
      setState(() => _result = 'success');
    } catch (e) {
      setState(() => _result = '⚠️ ${e.toString()}');
    } finally {
      setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final moodInt = _moodScore.round();

    return Scaffold(
      appBar: AppBar(title: const Text('Daily Check-In')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // ── Mood card ─────────────────────────────────────────────────────
          Card(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'How are you feeling?',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 20),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(_emoji, style: const TextStyle(fontSize: 48)),
                      const SizedBox(width: 16),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '$moodInt / 10',
                            style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                  color: cs.primary,
                                ),
                          ),
                          Text(
                            _moodLabel(moodInt),
                            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                  color: cs.onSurfaceVariant,
                                ),
                          ),
                        ],
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Slider(
                    value: _moodScore,
                    min: 1,
                    max: 10,
                    divisions: 9,
                    label: moodInt.toString(),
                    onChanged: (v) => setState(() => _moodScore = v),
                  ),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text('1', style: Theme.of(context).textTheme.labelSmall?.copyWith(
                            color: cs.onSurfaceVariant,
                          )),
                      Text('10', style: Theme.of(context).textTheme.labelSmall?.copyWith(
                            color: cs.onSurfaceVariant,
                          )),
                    ],
                  ),
                ],
              ),
            ),
          ),

          const SizedBox(height: 12),

          // ── Note card ─────────────────────────────────────────────────────
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Note',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _noteController,
                    maxLines: 4,
                    decoration: const InputDecoration(
                      hintText: 'Anything you want to share with your partner…',
                    ),
                  ),
                ],
              ),
            ),
          ),

          const SizedBox(height: 20),

          // ── Submit button ─────────────────────────────────────────────────
          FilledButton.icon(
            onPressed: _submitting ? null : _submit,
            icon: _submitting
                ? SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: cs.onPrimary),
                  )
                : const Icon(Icons.send_rounded),
            label: Text(_submitting ? 'Submitting…' : 'Submit Check-In'),
          ),

          // ── Result ────────────────────────────────────────────────────────
          if (_result != null) ...[
            const SizedBox(height: 16),
            if (_result == 'success')
              _ResultBanner(
                icon: Icons.check_circle_rounded,
                message: 'Check-in submitted successfully!',
                color: Colors.green,
                cs: cs,
              )
            else
              _ResultBanner(
                icon: Icons.warning_amber_rounded,
                message: _result!,
                color: cs.error,
                cs: cs,
              ),
          ],

          const SizedBox(height: 16),
        ],
      ),
    );
  }

  String _moodLabel(int score) => switch (score) {
        1 || 2 => 'Really struggling',
        3 || 4 => 'Not great',
        5       => 'Okay',
        6 || 7 => 'Doing well',
        8 || 9 => 'Great',
        _       => 'Amazing!',
      };
}

class _ResultBanner extends StatelessWidget {
  const _ResultBanner({
    required this.icon,
    required this.message,
    required this.color,
    required this.cs,
  });

  final IconData icon;
  final String message;
  final Color color;
  final ColorScheme cs;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.4)),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: TextStyle(color: color),
            ),
          ),
        ],
      ),
    );
  }
}
