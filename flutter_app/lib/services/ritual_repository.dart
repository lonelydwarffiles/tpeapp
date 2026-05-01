import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/ritual_step.dart';

/// Dart equivalent of [com.tpeapp.ritual.RitualRepository].
class RitualRepository extends ChangeNotifier {
  RitualRepository(this._prefs) {
    _load();
  }

  static const _stepsKey   = 'ritual_steps';
  static const _morningKey = 'ritual_morning_time_minutes';
  static const _eveningKey = 'ritual_evening_time_minutes';

  static const _defaultMorning = 480;   // 8:00 AM
  static const _defaultEvening = 1260;  // 9:00 PM

  final SharedPreferences _prefs;
  List<RitualStep> _steps = [];

  List<RitualStep> get steps => List.unmodifiable(_steps);

  /// Minutes since midnight for the morning ritual alarm.
  int get morningTimeMinutes => _prefs.getInt(_morningKey) ?? _defaultMorning;

  /// Minutes since midnight for the evening ritual alarm.
  int get eveningTimeMinutes => _prefs.getInt(_eveningKey) ?? _defaultEvening;

  void _load() {
    final json = _prefs.getString(_stepsKey);
    if (json == null) return;
    try {
      final list = jsonDecode(json) as List<dynamic>;
      _steps = list
          .map((e) => RitualStep.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      _steps = [];
    }
  }

  Future<void> setSteps(List<RitualStep> steps) async {
    _steps = List.of(steps);
    final encoded = jsonEncode(_steps.map((s) => s.toJson()).toList());
    await _prefs.setString(_stepsKey, encoded);
    notifyListeners();
  }

  Future<void> setMorningTime(int minutes) async {
    await _prefs.setInt(_morningKey, minutes);
    notifyListeners();
  }

  Future<void> setEveningTime(int minutes) async {
    await _prefs.setInt(_eveningKey, minutes);
    notifyListeners();
  }
}
