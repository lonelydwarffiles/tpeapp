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

    return Scaffold(
      appBar: AppBar(title: const Text('My Tasks')),
      body: pending.isEmpty && done.isEmpty
          ? const Center(child: Text('No tasks assigned yet.'))
          : ListView(
              children: [
                if (pending.isNotEmpty) ...[
                  _SectionHeader(title: 'Pending (${pending.length})'),
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
                  _SectionHeader(title: 'Completed / Missed'),
                  ...done.map((t) => _TaskTile(task: t, dateFmt: _dateFmt)),
                ],
              ],
            ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title});
  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        title,
        style: Theme.of(context)
            .textTheme
            .titleSmall
            ?.copyWith(color: Theme.of(context).colorScheme.primary),
      ),
    );
  }
}

class _TaskTile extends StatelessWidget {
  const _TaskTile({required this.task, required this.dateFmt, this.onTap});
  final Task task;
  final DateFormat dateFmt;
  final VoidCallback? onTap;

  Color _statusColor(TaskStatus status) => switch (status) {
        TaskStatus.pending   => Colors.orange,
        TaskStatus.completed => Colors.green,
        TaskStatus.missed    => Colors.red,
      };

  String _statusLabel(TaskStatus status) => switch (status) {
        TaskStatus.pending   => 'PENDING',
        TaskStatus.completed => 'COMPLETED',
        TaskStatus.missed    => 'MISSED',
      };

  @override
  Widget build(BuildContext context) {
    return ListTile(
      title: Text(task.title),
      subtitle: Text('Due: ${dateFmt.format(
        DateTime.fromMillisecondsSinceEpoch(task.deadlineMs),
      )}'),
      trailing: Chip(
        label: Text(
          _statusLabel(task.status),
          style: const TextStyle(fontSize: 11),
        ),
        backgroundColor: _statusColor(task.status).withOpacity(0.2),
        side: BorderSide(color: _statusColor(task.status)),
      ),
      onTap: onTap,
    );
  }
}
