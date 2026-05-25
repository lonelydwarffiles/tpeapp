import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import '../models/task.dart';
import '../services/task_repository.dart';
import 'task_verification_screen.dart';

/// Dart equivalent of [TaskListActivity].
///
/// Displays pending and completed/missed tasks grouped into two sections.
/// Tapping a pending task opens [TaskVerificationScreen].
class TaskListScreen extends StatelessWidget {
  const TaskListScreen({super.key});

  static final _dateFmt = DateFormat("MMM d 'at' h:mm a");

  @override
  Widget build(BuildContext context) {
    final repo = context.watch<TaskRepository>();
    final pending = repo.pending;
    final done = repo.done;
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(title: const Text('My Tasks')),
      body: pending.isEmpty && done.isEmpty
          ? Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.task_alt_rounded, size: 56, color: cs.outlineVariant),
                  const SizedBox(height: 16),
                  Text(
                    'No tasks yet',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Your accountability partner hasn\'t assigned any tasks.',
                    textAlign: TextAlign.center,
                    style: Theme.of(context)
                        .textTheme
                        .bodyMedium
                        ?.copyWith(color: cs.onSurfaceVariant),
                  ),
                ],
              ),
            )
          : ListView(
              children: [
                if (pending.isNotEmpty) ...[
                  _SectionHeader(title: 'Pending', count: pending.length),
                  ...pending.map((t) => _TaskTile(
                        task: t,
                        dateFmt: _dateFmt,
                        onTap: () => Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) => TaskVerificationScreen(taskId: t.id),
                          ),
                        ),
                      )),
                ],
                if (done.isNotEmpty) ...[
                  _SectionHeader(title: 'Completed / Missed', count: done.length),
                  ...done.map((t) => _TaskTile(task: t, dateFmt: _dateFmt)),
                ],
                const SizedBox(height: 16),
              ],
            ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title, required this.count});
  final String title;
  final int count;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 6),
      child: Row(
        children: [
          Text(
            title.toUpperCase(),
            style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: cs.primary,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 1.1,
                ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
            decoration: BoxDecoration(
              color: cs.primaryContainer,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text(
              '$count',
              style: Theme.of(context)
                  .textTheme
                  .labelSmall
                  ?.copyWith(color: cs.onPrimaryContainer, fontWeight: FontWeight.bold),
            ),
          ),
        ],
      ),
    );
  }
}

class _TaskTile extends StatelessWidget {
  const _TaskTile({required this.task, required this.dateFmt, this.onTap});
  final Task task;
  final DateFormat dateFmt;
  final VoidCallback? onTap;

  Color _statusColor(BuildContext context, TaskStatus status) => switch (status) {
        TaskStatus.pending   => Colors.orange,
        TaskStatus.completed => Colors.green,
        TaskStatus.missed    => Theme.of(context).colorScheme.error,
      };

  String _statusLabel(TaskStatus status) => switch (status) {
        TaskStatus.pending   => 'Pending',
        TaskStatus.completed => 'Done',
        TaskStatus.missed    => 'Missed',
      };

  IconData _statusIcon(TaskStatus status) => switch (status) {
        TaskStatus.pending   => Icons.hourglass_top_rounded,
        TaskStatus.completed => Icons.check_circle_rounded,
        TaskStatus.missed    => Icons.cancel_rounded,
      };

  @override
  Widget build(BuildContext context) {
    final color = _statusColor(context, task.status);
    return ListTile(
      title: Text(task.title, style: const TextStyle(fontWeight: FontWeight.w500)),
      subtitle: Text(
        'Due: ${dateFmt.format(DateTime.fromMillisecondsSinceEpoch(task.deadlineMs))}',
        style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
      ),
      leading: CircleAvatar(
        radius: 18,
        backgroundColor: color.withOpacity(0.15),
        child: Icon(_statusIcon(task.status), color: color, size: 18),
      ),
      trailing: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: color.withOpacity(0.12),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color.withOpacity(0.4)),
        ),
        child: Text(
          _statusLabel(task.status),
          style: TextStyle(fontSize: 11, color: color, fontWeight: FontWeight.w600),
        ),
      ),
      onTap: onTap,
    );
  }
}
