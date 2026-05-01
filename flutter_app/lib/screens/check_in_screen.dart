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
      setState(() => _result = '✅ Check-in submitted successfully!');
    } catch (e) {
      setState(() => _result = '⚠️ ${e.toString()}');
    } finally {
      setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Daily Check-In')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Mood Score: ${_moodScore.round()} / 10',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            Slider(
              value: _moodScore,
              min: 1,
              max: 10,
              divisions: 9,
              label: _moodScore.round().toString(),
              onChanged: (v) => setState(() => _moodScore = v),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _noteController,
              maxLines: 4,
              decoration: const InputDecoration(
                labelText: 'Note (optional)',
                border: OutlineInputBorder(),
                hintText: 'How are you feeling today?',
              ),
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: _submitting ? null : _submit,
              child: _submitting
                  ? const SizedBox(
                      height: 20,
                      width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Submit Check-In'),
            ),
            if (_result != null) ...[
              const SizedBox(height: 16),
              Text(_result!, textAlign: TextAlign.center),
            ],
          ],
        ),
      ),
    );
  }
}
