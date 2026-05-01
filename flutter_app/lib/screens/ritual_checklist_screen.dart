import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';

import '../models/ritual_step.dart';
import '../services/ritual_repository.dart';

/// Dart equivalent of [RitualChecklistActivity].
///
/// Displays daily ritual steps; each step can be checked off, and steps that
/// require photo proof launch the camera.
class RitualChecklistScreen extends StatefulWidget {
  const RitualChecklistScreen({super.key});

  @override
  State<RitualChecklistScreen> createState() => _RitualChecklistScreenState();
}

class _RitualChecklistScreenState extends State<RitualChecklistScreen> {
  final Set<String> _completed = {};
  String? _pendingPhotoStepId;

  Future<void> _capturePhoto(RitualStep step) async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(source: ImageSource.camera);
    if (picked != null) {
      setState(() => _completed.add(step.id));
    }
  }

  Future<void> _submitAll(List<RitualStep> steps) async {
    final incomplete = steps.where((s) => !_completed.contains(s.id)).toList();
    if (incomplete.isNotEmpty) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Incomplete Ritual'),
          content: Text(
              '${incomplete.length} step(s) not completed. Submit anyway?'),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('No')),
            TextButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('Yes')),
          ],
        ),
      );
      if (ok != true) return;
    }

    final completedCount = _completed.length;
    final total = steps.length;
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Ritual complete: $completedCount / $total steps done.'),
        ),
      );
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final steps = context.watch<RitualRepository>().steps;

    return Scaffold(
      appBar: AppBar(title: const Text('Daily Ritual')),
      body: steps.isEmpty
          ? const Center(child: Text('No ritual steps configured.'))
          : Column(
              children: [
                Expanded(
                  child: ListView.builder(
                    itemCount: steps.length,
                    itemBuilder: (_, i) {
                      final step = steps[i];
                      final done = _completed.contains(step.id);
                      return CheckboxListTile(
                        title: Text(
                          step.title,
                          style: done
                              ? const TextStyle(
                                  decoration: TextDecoration.lineThrough)
                              : null,
                        ),
                        subtitle: step.description.isNotEmpty
                            ? Text(step.description)
                            : null,
                        value: done,
                        onChanged: step.requiresPhoto
                            ? null // handled via secondary tap
                            : (checked) {
                                setState(() {
                                  if (checked == true) {
                                    _completed.add(step.id);
                                  } else {
                                    _completed.remove(step.id);
                                  }
                                });
                              },
                        secondary: step.requiresPhoto
                            ? IconButton(
                                icon: Icon(
                                  done ? Icons.check_circle : Icons.camera_alt,
                                  color: done ? Colors.green : null,
                                ),
                                tooltip: 'Take photo proof',
                                onPressed: done ? null : () => _capturePhoto(step),
                              )
                            : null,
                      );
                    },
                  ),
                ),
                SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: FilledButton.icon(
                      onPressed: () => _submitAll(steps),
                      icon: const Icon(Icons.done_all),
                      label: const Text('Complete Ritual'),
                    ),
                  ),
                ),
              ],
            ),
    );
  }
}
