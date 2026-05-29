import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

class KioskTaskController extends ChangeNotifier {
  KioskTaskController(this._prefs) {
    _hydrate();
  }

  static const _keyTaskId = 'kiosk_task_id';
  static const _keyTaskDescription = 'kiosk_task_desc';
  static const _keyDeadlineMs = 'kiosk_task_deadline_ms';
  static const _clearActions = {'TASK_CLEARED', 'CLEAR_TASK_ASSIGNED', 'TASK_CLEAR'};

  final SharedPreferences _prefs;
  Timer? _ticker;
  String? _taskId;
  String? _taskDescription;
  int? _deadlineMs;

  bool get isActive => _taskDescription != null && (_deadlineMs ?? 0) > DateTime.now().millisecondsSinceEpoch;
  String get taskDescription => _taskDescription ?? '';
  int get deadlineMs => _deadlineMs ?? 0;
  Duration get remaining {
    final ms = deadlineMs - DateTime.now().millisecondsSinceEpoch;
    return Duration(milliseconds: ms > 0 ? ms : 0);
  }

  void handleMqttEvent(Map<String, String> event) {
    final action = (event['action'] ?? event['command'] ?? '').trim();
    if (action == 'TASK_ASSIGNED') {
      final deadline = int.tryParse((event['deadline_ms'] ?? '').trim());
      if (deadline == null) return;
      unawaited(_assign(
        taskId: (event['task_id'] ?? '').trim(),
        description: (event['task_desc'] ?? '').trim(),
        deadlineMs: deadline,
      ));
      return;
    }
    if (_clearActions.contains(action)) {
      final incomingId = (event['task_id'] ?? '').trim();
      if (_taskId == null || incomingId.isEmpty || incomingId == _taskId) {
        unawaited(clear());
      }
    }
  }

  Future<void> clear() async {
    _ticker?.cancel();
    _ticker = null;
    _taskId = null;
    _taskDescription = null;
    _deadlineMs = null;
    await _prefs.remove(_keyTaskId);
    await _prefs.remove(_keyTaskDescription);
    await _prefs.remove(_keyDeadlineMs);
    notifyListeners();
  }

  Future<void> _assign({
    required String taskId,
    required String description,
    required int deadlineMs,
  }) async {
    _taskId = taskId.isEmpty ? null : taskId;
    _taskDescription = description.isEmpty ? 'Complete assigned task.' : description;
    _deadlineMs = deadlineMs;
    await _prefs.setString(_keyTaskDescription, _taskDescription!);
    await _prefs.setInt(_keyDeadlineMs, deadlineMs);
    if (_taskId != null) {
      await _prefs.setString(_keyTaskId, _taskId!);
    } else {
      await _prefs.remove(_keyTaskId);
    }
    _startTicker();
    notifyListeners();
  }

  void _hydrate() {
    _taskId = _prefs.getString(_keyTaskId)?.trim();
    _taskDescription = _prefs.getString(_keyTaskDescription)?.trim();
    _deadlineMs = _prefs.getInt(_keyDeadlineMs);
    if (_taskDescription != null && _deadlineMs != null) {
      _startTicker();
    } else {
      _taskId = null;
      _taskDescription = null;
      _deadlineMs = null;
    }
  }

  void _startTicker() {
    _ticker?.cancel();
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!isActive) {
        unawaited(clear());
        return;
      }
      notifyListeners();
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }
}
