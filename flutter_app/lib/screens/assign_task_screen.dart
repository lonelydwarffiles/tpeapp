import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/api_service.dart';

/// Dart equivalent of [AssignTaskActivity] (partner-facing screen).
///
/// PIN-protected in [HomeScreen] before navigation.
class AssignTaskScreen extends StatefulWidget {
  const AssignTaskScreen({super.key});

  @override
  State<AssignTaskScreen> createState() => _AssignTaskScreenState();
}

class _AssignTaskScreenState extends State<AssignTaskScreen> {
  final _titleController       = TextEditingController();
  final _descriptionController = TextEditingController();
  DateTime? _deadline;
  bool _submitting = false;
  String? _result;

  @override
  void dispose() {
    _titleController.dispose();
    _descriptionController.dispose();
    super.dispose();
  }

  Future<void> _pickDeadline() async {
    final now = DateTime.now();
    final date = await showDatePicker(
      context: context,
      initialDate: now.add(const Duration(days: 1)),
      firstDate: now,
      lastDate: now.add(const Duration(days: 365)),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.now(),
    );
    if (time == null) return;
    setState(() {
      _deadline = DateTime(date.year, date.month, date.day, time.hour, time.minute);
    });
  }

  Future<void> _send() async {
    final title       = _titleController.text.trim();
    final description = _descriptionController.text.trim();
    if (title.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Title is required.')));
      return;
    }
    if (_deadline == null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Please set a deadline.')));
      return;
    }

    setState(() {
      _submitting = true;
      _result = null;
    });

    try {
      final prefs = await SharedPreferences.getInstance();
      final api = ApiService(prefs);
      await api.assignTask(
        title: title,
        description: description,
        deadlineMs: _deadline!.millisecondsSinceEpoch,
      );
      setState(() => _result = '✅ Task assigned successfully!');
      _titleController.clear();
      _descriptionController.clear();
      setState(() => _deadline = null);
    } catch (e) {
      setState(() => _result = '⚠️ ${e.toString()}');
    } finally {
      setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Assign Task')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _titleController,
              decoration: const InputDecoration(
                labelText: 'Task Title',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _descriptionController,
              maxLines: 4,
              decoration: const InputDecoration(
                labelText: 'Description',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _pickDeadline,
              icon: const Icon(Icons.calendar_today),
              label: Text(_deadline == null
                  ? 'Set Deadline'
                  : 'Deadline: ${_deadline!.toLocal()}'),
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: _submitting ? null : _send,
              child: _submitting
                  ? const SizedBox(
                      height: 20, width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Send Task'),
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
