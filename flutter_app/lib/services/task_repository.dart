import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/task.dart';

/// Dart equivalent of [com.tpeapp.tasks.TaskRepository].
///
/// Tasks are persisted as a JSON array in SharedPreferences under `tasks_json`.
class TaskRepository extends ChangeNotifier {
  TaskRepository(this._prefs) {
    _load();
  }

  static const _key = 'tasks_json';
  final SharedPreferences _prefs;
  List<Task> _tasks = [];

  List<Task> get tasks => List.unmodifiable(_tasks);
  List<Task> get pending =>
      _tasks.where((t) => t.status == TaskStatus.pending).toList();
  List<Task> get done =>
      _tasks.where((t) => t.status != TaskStatus.pending).toList();

  void _load() {
    final json = _prefs.getString(_key);
    if (json == null) return;
    try {
      final list = jsonDecode(json) as List<dynamic>;
      _tasks = list
          .map((e) => Task.fromJson(e as Map<String, dynamic>))
          .toList()
        ..sort((a, b) => a.deadlineMs.compareTo(b.deadlineMs));
    } catch (_) {
      _tasks = [];
    }
  }

  void reload() {
    _load();
    notifyListeners();
  }

  Future<void> _save() async {
    final encoded = jsonEncode(_tasks.map((t) => t.toJson()).toList());
    await _prefs.setString(_key, encoded);
  }

  Future<void> upsert(Task task) async {
    final idx = _tasks.indexWhere((t) => t.id == task.id);
    if (idx >= 0) {
      _tasks[idx] = task;
    } else {
      _tasks.add(task);
    }
    _tasks.sort((a, b) => a.deadlineMs.compareTo(b.deadlineMs));
    await _save();
    notifyListeners();
  }

  Task? findById(String id) {
    try {
      return _tasks.firstWhere((t) => t.id == id);
    } catch (_) {
      return null;
    }
  }

  Future<void> markCompleted(String taskId, {String? photoPath}) async {
    final task = findById(taskId);
    if (task != null) {
      await upsert(
        task.copyWith(
          status: TaskStatus.completed,
          photoPath: photoPath ?? task.photoPath,
        ),
      );
      return;
    }

    final raw = _prefs.getString(_key);
    if (raw == null || raw.trim().isEmpty) {
      return;
    }

    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) {
        return;
      }

      var updated = false;
      for (final item in decoded) {
        if (item is! Map) continue;
        final id = (item['id'] ?? '').toString().trim();
        if (id != taskId) continue;
        item['status'] = TaskStatus.completed.name.toUpperCase();
        if (photoPath != null && photoPath.trim().isNotEmpty) {
          item['photoUri'] = photoPath;
        }
        updated = true;
        break;
      }

      if (!updated) {
        return;
      }

      await _prefs.setString(_key, jsonEncode(decoded));
      _load();
      notifyListeners();
    } catch (_) {
      // Keep caller resilient if local JSON is malformed.
    }
  }

  Future<void> markMissed(String taskId) async {
    final task = findById(taskId);
    if (task == null) return;
    await upsert(task.copyWith(status: TaskStatus.missed));
  }

  Future<void> ensureDaily1000mlTaskForToday() async {
    final enabled = _prefs.getBool('task_gate_enabled') ?? true;
    if (!enabled) {
      return;
    }

    final now = DateTime.now();
    final taskId = _dailyTaskIdFor(now);
    if (_tasks.any((t) => t.id == taskId)) {
      return;
    }

    final endOfDay = DateTime(now.year, now.month, now.day, 23, 59, 59, 999);
    final task = Task(
      id: taskId,
      title: 'Daily 1000ml task',
      description: 'Complete 1000ml daily target and verify completion.',
      deadlineMs: endOfDay.millisecondsSinceEpoch,
      status: TaskStatus.pending,
    );
    await upsert(task);
  }

  String _dailyTaskIdFor(DateTime date) {
    final y = date.year.toString().padLeft(4, '0');
    final m = date.month.toString().padLeft(2, '0');
    final d = date.day.toString().padLeft(2, '0');
    return 'daily_1000ml_$y$m$d';
  }
}
