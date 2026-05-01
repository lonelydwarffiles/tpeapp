import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

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

  Task? findById(String id) =>
      _tasks.cast<Task?>().firstWhere((t) => t?.id == id, orElse: () => null);

  Future<void> markCompleted(String taskId, {String? photoPath}) async {
    final task = findById(taskId);
    if (task == null) return;
    await upsert(
      task.copyWith(
        status: TaskStatus.completed,
        photoPath: photoPath ?? task.photoPath,
      ),
    );
  }

  Future<void> markMissed(String taskId) async {
    final task = findById(taskId);
    if (task == null) return;
    await upsert(task.copyWith(status: TaskStatus.missed));
  }
}
