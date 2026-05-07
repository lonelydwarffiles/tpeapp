import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

import '../models/sub_profile.dart';

class SubProfileRepository extends ChangeNotifier {
  SubProfileRepository(this._prefs) {
    _load();
  }

  static const _subsKey = 'sub_profiles_json';
  static const _activeSubIdKey = 'active_sub_id';
  static const _domPrefsKey = 'dom_preferences_json';

  static const metrics = [
    'structure',
    'communication',
    'intensity',
  ];

  static const Map<String, int> _defaultPrefs = {
    'structure': 50,
    'communication': 50,
    'intensity': 50,
  };

  final SharedPreferences _prefs;
  final _uuid = const Uuid();

  List<SubProfile> _subs = [];
  Map<String, int> _domPreferences = Map.of(_defaultPrefs);

  List<SubProfile> get subs => List.unmodifiable(_subs);
  Map<String, int> get domPreferences => Map.unmodifiable(_domPreferences);

  String? get activeSubId => _prefs.getString(_activeSubIdKey);

  SubProfile? get activeSub {
    final id = activeSubId;
    if (id == null) return null;
    try {
      return _subs.firstWhere((s) => s.id == id);
    } catch (_) {
      return null;
    }
  }

  void _load() {
    final subsJson = _prefs.getString(_subsKey);
    if (subsJson != null) {
      try {
        final decoded = jsonDecode(subsJson) as List<dynamic>;
        _subs = decoded
            .map((e) => SubProfile.fromJson(e as Map<String, dynamic>))
            .toList();
      } catch (_) {
        _subs = [];
      }
    }

    if (_subs.isEmpty) {
      _subs = [
        SubProfile(
          id: _uuid.v4(),
          name: 'Primary Sub',
          preferences: Map.of(_defaultPrefs),
        ),
      ];
    }

    final domPrefsJson = _prefs.getString(_domPrefsKey);
    if (domPrefsJson != null) {
      try {
        final decoded = jsonDecode(domPrefsJson) as Map<String, dynamic>;
        _domPreferences = {
          for (final m in metrics)
            m: ((decoded[m] as num?)?.round() ?? _defaultPrefs[m]!),
        };
      } catch (_) {
        _domPreferences = Map.of(_defaultPrefs);
      }
    }

    final active = _prefs.getString(_activeSubIdKey);
    if (active == null || !_subs.any((s) => s.id == active)) {
      _prefs.setString(_activeSubIdKey, _subs.first.id);
    }

    _persist();
  }

  Future<void> _persist() async {
    await _prefs.setString(
      _subsKey,
      jsonEncode(_subs.map((s) => s.toJson()).toList()),
    );
    await _prefs.setString(_domPrefsKey, jsonEncode(_domPreferences));
  }

  Future<void> addSub(String name) async {
    final normalized = name.trim();
    if (normalized.isEmpty) return;
    _subs.add(
      SubProfile(
        id: _uuid.v4(),
        name: normalized,
        preferences: Map.of(_defaultPrefs),
      ),
    );
    await _persist();
    notifyListeners();
  }

  Future<void> removeSub(String subId) async {
    if (_subs.length <= 1) return;
    _subs.removeWhere((s) => s.id == subId);

    final active = activeSubId;
    if (active == subId && _subs.isNotEmpty) {
      await _prefs.setString(_activeSubIdKey, _subs.first.id);
    }

    await _persist();
    notifyListeners();
  }

  Future<void> setActiveSub(String subId) async {
    if (!_subs.any((s) => s.id == subId)) return;
    await _prefs.setString(_activeSubIdKey, subId);
    notifyListeners();
  }

  Future<void> updateDomPreference(String key, int value) async {
    if (!metrics.contains(key)) return;
    _domPreferences[key] = value.clamp(0, 100);
    await _persist();
    notifyListeners();
  }

  Future<void> updateSubPreference(String subId, String key, int value) async {
    if (!metrics.contains(key)) return;
    final idx = _subs.indexWhere((s) => s.id == subId);
    if (idx < 0) return;

    final current = _subs[idx];
    final updatedPrefs = Map<String, int>.from(current.preferences);
    updatedPrefs[key] = value.clamp(0, 100);

    _subs[idx] = current.copyWith(preferences: updatedPrefs);
    await _persist();
    notifyListeners();
  }

  double compatibilityFor(SubProfile sub) {
    var totalAbsoluteDifference = 0.0;
    for (final metric in metrics) {
      final dom = _domPreferences[metric] ?? 50;
      final target = sub.preferences[metric] ?? 50;
      totalAbsoluteDifference += (dom - target).abs();
    }
    final averageDelta = totalAbsoluteDifference / metrics.length;
    final score = 100 - averageDelta;
    return score.clamp(0, 100);
  }

  String compatibilitySummary(SubProfile sub) {
    final score = compatibilityFor(sub);
    if (score >= 85) return 'Excellent alignment';
    if (score >= 70) return 'Strong alignment';
    if (score >= 55) return 'Moderate alignment';
    return 'Needs negotiation';
  }
}
