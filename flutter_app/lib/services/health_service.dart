import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:health/health.dart';

/// Wraps the Health Connect SDK for reading biometric vitals.
///
/// Resolves and requests all supported vitals from a preferred set
/// (heart, activity, respiratory, oxygen, sleep, and body metrics).
class HealthService {
  HealthService._();

  static final HealthService instance = HealthService._();

  static const Set<String> _preferredTypeNames = <String>{
    'HEART_RATE',
    'RESTING_HEART_RATE',
    'HEART_RATE_VARIABILITY_SDNN',
    'RESPIRATORY_RATE',
    'BLOOD_OXYGEN',
    'OXYGEN_SATURATION',
    'STEPS',
    'DISTANCE_DELTA',
    'FLIGHTS_CLIMBED',
    'TOTAL_CALORIES_BURNED',
    'ACTIVE_ENERGY_BURNED',
    'BASAL_ENERGY_BURNED',
    'SLEEP_ASLEEP',
    'SLEEP_AWAKE',
    'SLEEP_IN_BED',
    'SLEEP_SESSION',
    'WEIGHT',
    'HEIGHT',
    'BODY_FAT_PERCENTAGE',
    'BODY_TEMPERATURE',
  };

  static const Set<String> _typeNameKeywords = <String>{
    'HEART',
    'RESPIRATORY',
    'OXYGEN',
    'SLEEP',
    'STEP',
    'DISTANCE',
    'FLIGHT',
    'FLOOR',
    'CALORIE',
    'ENERGY',
    'WEIGHT',
    'HEIGHT',
    'BODY_FAT',
    'TEMPERATURE',
    'EXERCISE',
    'VO2',
  };

  static final List<HealthDataType> _types = _resolveSupportedTypes();

  static final List<HealthDataAccess> _permissions =
      List<HealthDataAccess>.filled(_types.length, HealthDataAccess.READ);

  final _health = Health();
  static const MethodChannel _nativeHealth =
      MethodChannel('com.hound.controller/health');

  /// Requests read access for all configured Health Connect vitals.
  ///
  /// Returns true if all requested permissions were granted.
  Future<bool> requestPermissions() async {
    try {
      final native = await _nativeHealth.invokeMethod<bool>('requestPermissions');
      if (native != null) return native;
    } on MissingPluginException {
      // Fallback to package implementation when native bridge is unavailable.
    } on PlatformException {
      // Fallback to package implementation when native bridge fails.
    }

    await _health.configure();

    try {
      return await _health.requestAuthorization(_types, permissions: _permissions);
    } catch (e) {
      debugPrint('$runtimeType - Exception in requestPermissions(): $e');
      return false;
    }
  }

  /// Returns true if the app currently holds all required Health Connect
  /// Read permissions.
  Future<bool> hasPermissions() async {
    try {
      final native = await _nativeHealth.invokeMethod<bool>('hasPermissions');
      if (native != null) return native;
    } on MissingPluginException {
      // Fallback to package implementation when native bridge is unavailable.
    } on PlatformException {
      // Fallback to package implementation when native bridge fails.
    }

    await _health.configure();
    try {
      final result = await _health.hasPermissions(_types, permissions: _permissions);
      return result ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// Queries configured vitals data for the [window] ending at [endTime].
  ///
  /// Returns a JSON-serialisable list shaped as:
  /// ```json
  /// [
  ///   { "type": "heart_rate", "value": 72.0, "unit": "bpm",
  ///     "start_ms": 1700000000000, "end_ms": 1700000060000 },
  ///   { "type": "steps",      "value": 120.0, "unit": "count",
  ///     "start_ms": 1700000000000, "end_ms": 1700000060000 }
  /// ]
  /// ```
  Future<List<Map<String, dynamic>>> queryVitals({
    required DateTime endTime,
    Duration window = const Duration(minutes: 15),
  }) async {
    await _health.configure();
    final startTime = endTime.subtract(window);

    List<HealthDataPoint> dataPoints;
    try {
      dataPoints = await _health.getHealthDataFromTypes(
        startTime: startTime,
        endTime: endTime,
        types: _types,
      );
    } on MissingPluginException {
      return const <Map<String, dynamic>>[];
    } on PlatformException {
      return const <Map<String, dynamic>>[];
    }

    final deduplicated = <HealthDataPoint>[];
    final seen = <String>{};
    for (final point in dataPoints) {
      final key = [
        point.type.name,
        point.unit.name,
        point.dateFrom.millisecondsSinceEpoch,
        point.dateTo.millisecondsSinceEpoch,
        point.sourceName,
      ].join('|');
      if (seen.add(key)) {
        deduplicated.add(point);
      }
    }

    return deduplicated.map((point) {
      final typeName = point.type.name;
      final typeKey = _canonicalTypeKey(typeName);
      var unit = _canonicalUnit(typeName, point.unit.name);
      final value = _numericValueFromPoint(point.value);
      final payload = <String, dynamic>{
        'type': typeKey,
        'unit': unit,
        'start_ms': point.dateFrom.millisecondsSinceEpoch,
        'end_ms': point.dateTo.millisecondsSinceEpoch,
        'source': point.sourceName,
      };

      if (value != null) {
        payload['value'] = value;
      } else if (typeKey == 'sleep_session') {
        final minutes = point.dateTo.difference(point.dateFrom).inMinutes;
        payload['value'] = minutes.toDouble();
        payload['unit'] = 'minutes';
      } else {
        payload['raw_value'] = '${point.value}';
      }

      return payload;
    }).whereType<Map<String, dynamic>>().toList();
  }

  /// Convenience method — returns [queryVitals] as a JSON string.
  Future<String> queryVitalsJson({
    required DateTime endTime,
    Duration window = const Duration(minutes: 15),
  }) async {
    final records = await queryVitals(endTime: endTime, window: window);
    return jsonEncode(records);
  }

  static List<HealthDataType> _resolveSupportedTypes() {
    final byName = <String, HealthDataType>{
      for (final type in HealthDataType.values) type.name: type,
    };

    final resolved = <HealthDataType>[];
    final seen = <String>{};

    void addIfPresent(String name) {
      final type = byName[name];
      if (type == null) return;
      if (!seen.add(name)) return;
      resolved.add(type);
    }

    for (final name in _preferredTypeNames) {
      addIfPresent(name);
    }

    for (final entry in byName.entries) {
      if (seen.contains(entry.key)) continue;
      final upper = entry.key.toUpperCase();
      final isRelevant = _typeNameKeywords.any((keyword) => upper.contains(keyword));
      if (isRelevant) {
        seen.add(entry.key);
        resolved.add(entry.value);
      }
    }

    if (resolved.isEmpty) {
      addIfPresent('HEART_RATE');
      addIfPresent('STEPS');
    }

    return resolved;
  }

  static String _canonicalTypeKey(String healthTypeName) {
    switch (healthTypeName) {
      case 'HEART_RATE':
        return 'heart_rate';
      case 'RESTING_HEART_RATE':
        return 'resting_heart_rate';
      case 'HEART_RATE_VARIABILITY_SDNN':
        return 'heart_rate_variability_sdnn';
      case 'RESPIRATORY_RATE':
        return 'respiratory_rate';
      case 'BLOOD_OXYGEN':
      case 'OXYGEN_SATURATION':
        return 'oxygen_saturation';
      case 'DISTANCE_DELTA':
        return 'distance';
      case 'TOTAL_CALORIES_BURNED':
        return 'total_calories_burned';
      case 'ACTIVE_ENERGY_BURNED':
        return 'active_energy_burned';
      case 'BASAL_ENERGY_BURNED':
        return 'basal_energy_burned';
      case 'FLIGHTS_CLIMBED':
        return 'floors_climbed';
      case 'SLEEP_ASLEEP':
      case 'SLEEP_AWAKE':
      case 'SLEEP_IN_BED':
      case 'SLEEP_SESSION':
        return 'sleep_session';
      case 'BODY_FAT_PERCENTAGE':
        return 'body_fat_percentage';
      default:
        return healthTypeName.toLowerCase();
    }
  }

  static String _canonicalUnit(String healthTypeName, String unitName) {
    switch (healthTypeName) {
      case 'HEART_RATE':
      case 'RESTING_HEART_RATE':
      case 'RESPIRATORY_RATE':
        return 'bpm';
      case 'HEART_RATE_VARIABILITY_SDNN':
        return 'ms';
      case 'BLOOD_OXYGEN':
      case 'OXYGEN_SATURATION':
      case 'BODY_FAT_PERCENTAGE':
        return 'percent';
      case 'STEPS':
      case 'FLIGHTS_CLIMBED':
        return 'count';
      case 'DISTANCE_DELTA':
        return 'meters';
      case 'TOTAL_CALORIES_BURNED':
      case 'ACTIVE_ENERGY_BURNED':
      case 'BASAL_ENERGY_BURNED':
        return 'kcal';
      case 'SLEEP_ASLEEP':
      case 'SLEEP_AWAKE':
      case 'SLEEP_IN_BED':
      case 'SLEEP_SESSION':
        return 'minutes';
      default:
        return unitName.toLowerCase();
    }
  }

  static double? _numericValueFromPoint(HealthValue value) {
    if (value is NumericHealthValue) {
      return value.numericValue.toDouble();
    }
    final raw = '$value';
    final match = RegExp(r'-?\d+(?:\.\d+)?').firstMatch(raw);
    if (match == null) {
      return null;
    }
    return double.tryParse(match.group(0)!);
  }
}
