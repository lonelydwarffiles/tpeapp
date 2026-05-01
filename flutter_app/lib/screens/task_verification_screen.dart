import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/task.dart';
import '../services/api_service.dart';
import '../services/task_repository.dart';

/// Dart equivalent of [TaskVerificationActivity].
///
/// Lets the device owner capture a proof photo and upload task completion
/// to the partner backend via [ApiService].
class TaskVerificationScreen extends StatefulWidget {
  const TaskVerificationScreen({super.key, required this.taskId});

  final String taskId;

  @override
  State<TaskVerificationScreen> createState() => _TaskVerificationScreenState();
}

class _TaskVerificationScreenState extends State<TaskVerificationScreen> {
  String? _photoPath;
  bool _uploading = false;
  String? _result;

  Future<void> _takePhoto() async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(source: ImageSource.camera);
    if (picked != null) setState(() => _photoPath = picked.path);
  }

  Future<void> _submit() async {
    final repo = context.read<TaskRepository>();
    final task = repo.findById(widget.taskId);
    if (task == null) return;

    setState(() {
      _uploading = true;
      _result = null;
    });

    try {
      final prefs = await SharedPreferences.getInstance();
      final api = ApiService(prefs);
      await api.uploadTaskStatus(
        taskId: task.id,
        status: TaskStatus.completed,
        photoPath: _photoPath,
      );
      await repo.markCompleted(task.id, photoPath: _photoPath);
      setState(() => _result = '✅ Task submitted successfully!');
    } catch (e) {
      setState(() => _result = '⚠️ ${e.toString()}');
    } finally {
      setState(() => _uploading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final task = context.read<TaskRepository>().findById(widget.taskId);
    if (task == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Task')),
        body: const Center(child: Text('Task not found.')),
      );
    }

    return Scaffold(
      appBar: AppBar(title: Text(task.title)),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(task.description, style: Theme.of(context).textTheme.bodyLarge),
            const SizedBox(height: 24),
            if (_photoPath != null)
              ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Image.network(
                  _photoPath!,
                  height: 200,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) =>
                      const Icon(Icons.broken_image, size: 80),
                ),
              )
            else
              OutlinedButton.icon(
                onPressed: _takePhoto,
                icon: const Icon(Icons.camera_alt),
                label: const Text('Take Proof Photo'),
              ),
            const SizedBox(height: 16),
            if (_photoPath != null)
              TextButton.icon(
                onPressed: _takePhoto,
                icon: const Icon(Icons.refresh),
                label: const Text('Retake Photo'),
              ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: (_uploading || task.status != TaskStatus.pending)
                  ? null
                  : _submit,
              child: _uploading
                  ? const SizedBox(
                      height: 20,
                      width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Submit Task'),
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
