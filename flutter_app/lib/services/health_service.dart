import 'dart:convert';

import 'package:health/health.dart';

/// Wraps the Health Connect SDK for reading biometric vitals.
///
/// Supports [HealthDataType.HEART_RATE] and [HealthDataType.STEPS].
/// Requires the Health Connect permissions declared in AndroidManifest.xml:
///   android.permission.health.READ_HEART_RATE
///   android.permission.health.READ_STEPS
class HealthService {
  HealthService._();

  static final HealthService instance = HealthService._();

  static const _types = [
    HealthDataType.HEART_RATE,
    HealthDataType.STEPS,
  ];

  static const _permissions = [
    HealthDataAccess.READ,
    HealthDataAccess.READ,
  ];

  final _health = Health();

  /// Requests Read access for HeartRate and Steps from Health Connect.
  ///
  /// Returns true if all requested permissions were granted.
  Future<bool> requestPermissions() async {
    await _health.configure();
    return _health.requestAuthorization(_types, permissions: _permissions);
  }

  /// Returns true if the app currently holds all required Health Connect
  /// Read permissions.
  Future<bool> hasPermissions() async {
    await _health.configure();
    final result = await _health.hasPermissions(_types, permissions: _permissions);
    return result ?? false;
  }

  /// Queries HeartRate and Steps data for the [window] ending at [endTime].
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

    final dataPoints = await _health.getHealthDataFromTypes(
      startTime: startTime,
      endTime: endTime,
      types: _types,
    );

    final deduplicated = Health.removeDuplicates(dataPoints);
    // removeDuplicates eliminates records with identical source, type, and
    // time range that may be reported more than once by overlapping data
    // sources (e.g. wearable + phone sensors).

    return deduplicated.map((point) {
      final typeKey = switch (point.type) {
        HealthDataType.HEART_RATE => 'heart_rate',
        HealthDataType.STEPS => 'steps',
        _ => point.type.name.toLowerCase(),
      };
      final unit = switch (point.type) {
        HealthDataType.HEART_RATE => 'bpm',
        HealthDataType.STEPS => 'count',
        _ => point.unit.name.toLowerCase(),
      };
      // Both HEART_RATE and STEPS always produce NumericHealthValue records;
      // guard against unexpected types from future health package versions.
      if (point.value is! NumericHealthValue) return null;
      final value = (point.value as NumericHealthValue).numericValue.toDouble();

      return <String, dynamic>{
        'type': typeKey,
        'value': value,
        'unit': unit,
        'start_ms': point.dateFrom.millisecondsSinceEpoch,
        'end_ms': point.dateTo.millisecondsSinceEpoch,
      };
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
}
