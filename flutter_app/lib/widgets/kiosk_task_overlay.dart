import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:convert';

import '../models/task.dart';
import '../services/kiosk_task_controller.dart';
import '../services/task_repository.dart';
import '../services/api_service.dart';

class KioskTaskOverlay extends StatefulWidget {
  const KioskTaskOverlay({super.key});

  @override
  State<KioskTaskOverlay> createState() => _KioskTaskOverlayState();
}

class _KioskTaskOverlayState extends State<KioskTaskOverlay> {
  bool _markingComplete = false;

  int _pendingTaskCountFromStorage(SharedPreferences prefs) {
    final raw = prefs.getString('tasks_json');
    if (raw == null || raw.trim().isEmpty) return 0;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return 0;
      var count = 0;
      for (final item in decoded) {
        if (item is! Map) continue;
        final status = (item['status'] ?? '').toString().trim().toLowerCase();
        if (status == 'pending') count += 1;
      }
      return count;
    } catch (_) {
      return 0;
    }
  }

  String? _firstPendingTaskIdFromStorage(SharedPreferences prefs) {
    final raw = prefs.getString('tasks_json');
    if (raw == null || raw.trim().isEmpty) return null;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return null;
      for (final item in decoded) {
        if (item is! Map) continue;
        final status = (item['status'] ?? '').toString().trim().toLowerCase();
        if (status != 'pending') continue;
        final id = (item['id'] ?? '').toString().trim();
        if (id.isNotEmpty) return id;
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  Future<void> _markTaskComplete(Task task) async {
    if (_markingComplete) return;
    setState(() => _markingComplete = true);

    final repo = context.read<TaskRepository>();
    final prefs = context.read<SharedPreferences>();

    try {
      await repo.markCompleted(task.id);

      final endpoint = (prefs.getString('partner_endpoint_url') ?? '').trim();
      if (endpoint.isNotEmpty) {
        try {
          await ApiService(prefs).uploadTaskStatus(
            taskId: task.id,
            status: TaskStatus.completed,
          );
        } catch (_) {
          // Local completion still unlocks; remote sync is best-effort.
        }
      }
    } finally {
      if (mounted) {
        setState(() => _markingComplete = false);
      }
    }
  }

  Future<void> _markTaskCompleteById(String taskId) async {
    if (_markingComplete || taskId.trim().isEmpty) return;
    setState(() => _markingComplete = true);

    final repo = context.read<TaskRepository>();
    final prefs = context.read<SharedPreferences>();

    try {
      await repo.markCompleted(taskId);

      final endpoint = (prefs.getString('partner_endpoint_url') ?? '').trim();
      if (endpoint.isNotEmpty) {
        try {
          await ApiService(prefs).uploadTaskStatus(
            taskId: taskId,
            status: TaskStatus.completed,
          );
        } catch (_) {
          // Local completion still unlocks; remote sync is best-effort.
        }
      }
    } finally {
      if (mounted) {
        setState(() => _markingComplete = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Consumer2<KioskTaskController, TaskRepository>(
      builder: (context, controller, tasks, _) {
        final prefs = context.read<SharedPreferences>();
        final taskGateEnabled = prefs.getBool('task_gate_enabled') ?? true;
        final pendingTask = tasks.pending.isNotEmpty ? tasks.pending.first : null;
        final pendingCount = _pendingTaskCountFromStorage(prefs);
        final pendingTaskId = _firstPendingTaskIdFromStorage(prefs);
        final taskGateActive = taskGateEnabled && pendingCount > 0;

        if (!controller.isActive && !taskGateActive) {
          return const SizedBox.shrink();
        }
        final remaining = controller.remaining;
        final minutes = remaining.inMinutes.remainder(60).toString().padLeft(2, '0');
        final seconds = remaining.inSeconds.remainder(60).toString().padLeft(2, '0');
        final hours = remaining.inHours.toString().padLeft(2, '0');
        final cs = Theme.of(context).colorScheme;
        final title = controller.isActive ? 'Assigned Task' : 'Task Lock Active';
        final body = controller.isActive
            ? controller.taskDescription
            : 'Phone-use lock is active until tasks are completed. Pending tasks: $pendingCount';

        return PopScope(
          canPop: false,
          child: Material(
            color: Colors.black.withValues(alpha: 0.92),
            child: SafeArea(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Container(
                    width: 560,
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: cs.surfaceContainerHigh,
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(color: cs.primary.withValues(alpha: 0.45)),
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.assignment_late_rounded, size: 52),
                        const SizedBox(height: 14),
                        Text(
                          title,
                          style: Theme.of(context).textTheme.headlineSmall,
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: 12),
                        Text(
                          body,
                          style: Theme.of(context).textTheme.titleMedium,
                          textAlign: TextAlign.center,
                        ),
                        if (controller.isActive) ...[
                          const SizedBox(height: 20),
                          Text(
                            '$hours:$minutes:$seconds',
                            style: Theme.of(context).textTheme.displaySmall?.copyWith(
                                  fontWeight: FontWeight.w700,
                                  color: cs.primary,
                                ),
                          ),
                        ],
                        if (taskGateActive) ...[
                          const SizedBox(height: 14),
                          FilledButton.icon(
                            onPressed: (_markingComplete ||
                                    (pendingTask == null && pendingTaskId == null))
                                ? null
                                : () {
                                    if (pendingTask != null) {
                                      _markTaskComplete(pendingTask);
                                      return;
                                    }
                                    _markTaskCompleteById(pendingTaskId!);
                                  },
                            icon: _markingComplete
                                ? const SizedBox(
                                    width: 18,
                                    height: 18,
                                    child: CircularProgressIndicator(strokeWidth: 2),
                                  )
                                : const Icon(Icons.task_alt_outlined),
                            label: Text(_markingComplete
                                ? 'Marking Complete...'
                                : 'Mark as Complete'),
                          ),
                        ],
                        const SizedBox(height: 8),
                        Text(
                          controller.isActive
                              ? 'Kiosk lock is active until timer ends or admin clears this task.'
                              : 'Task gate stays locked until this task is marked complete.',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                color: cs.onSurfaceVariant,
                              ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}
