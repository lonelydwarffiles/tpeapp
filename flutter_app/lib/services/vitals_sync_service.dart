import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:workmanager/workmanager.dart';

import 'health_service.dart';

/// WorkManager task name for periodic vitals sync.
const _kVitalsTaskName = 'vitals_sync';

/// Unique task tag used to identify the periodic work in the queue.
const _kVitalsTaskTag = 'com.tpeapp.vitals_sync';

/// Preferred sync interval (every 15 minutes).
const _kSyncInterval = Duration(minutes: 15);

/// SharedPreferences key that gates the background sync.
const kHealthConnectEnabled = 'health_connect_enabled';

// ── Background entry point ────────────────────────────────────────────────────

/// Top-level callback required by WorkManager.
///
/// Must be annotated [@pragma('vm:entry-point')] so the Dart tree-shaker
/// preserves it in release builds.  It is called in a fresh Flutter isolate,
/// so every service must be re-initialised here.
@pragma('vm:entry-point')
void vitalsCallbackDispatcher() {
  Workmanager().executeTask((taskName, inputData) async {
    if (taskName != _kVitalsTaskName) return Future.value(true);

    try {
      final prefs = await SharedPreferences.getInstance();

      // Bail out if the user has disabled Health Connect sync.
      if (!(prefs.getBool(kHealthConnectEnabled) ?? true)) {
        return Future.value(true);
      }

      final endpoint =
          (prefs.getString('partner_endpoint_url') ?? '').trimRight();
      if (endpoint.isEmpty) return Future.value(true);

      // Query Health Connect for the last 15 minutes.
      final healthService = HealthService.instance;
      final hasHealthPermissions = await healthService.hasPermissions();

      final now = DateTime.now();
      final records = await healthService.queryVitals(endTime: now);

      final token = prefs.getString('webhook_bearer_token');
      final deviceId = prefs.getString('device_id');
      final headers = <String, String>{
        'Content-Type': 'application/json',
        if (token != null && token.isNotEmpty) 'Authorization': 'Bearer $token',
        if (deviceId != null && deviceId.isNotEmpty) 'X-Device-ID': deviceId,
      };

      if (records.isEmpty) {
        await _postDeviceEvent(
          endpoint: endpoint,
          headers: headers,
          event: 'device_health_sync_empty',
          reason: 'no_records',
          payload: {
            'health_permissions': hasHealthPermissions,
            'window_minutes': 15,
          },
        );
        return Future.value(true);
      }

      var hrCount = 0;
      var hrTotal = 0.0;
      var stepCount = 0;
      var stepTotal = 0.0;
      final typeSamples = <String, int>{};
      final typeTotals = <String, double>{};
      final typeNumericCounts = <String, int>{};
      for (final record in records) {
        final type = (record['type'] ?? '').toString();
        if (type.isNotEmpty) {
          typeSamples[type] = (typeSamples[type] ?? 0) + 1;
        }
        final raw = record['value'];
        if (raw is! num) continue;
        if (type.isNotEmpty) {
          typeTotals[type] = (typeTotals[type] ?? 0.0) + raw.toDouble();
          typeNumericCounts[type] = (typeNumericCounts[type] ?? 0) + 1;
        }
        if (type == 'heart_rate') {
          hrCount += 1;
          hrTotal += raw.toDouble();
        } else if (type == 'steps') {
          stepCount += 1;
          stepTotal += raw.toDouble();
        }
      }

      final typeAverages = <String, double>{};
      typeTotals.forEach((type, total) {
        final count = typeNumericCounts[type] ?? 0;
        if (count > 0) {
          typeAverages[type] = double.parse((total / count).toStringAsFixed(2));
        }
      });

      final body = jsonEncode({'vitals': records});

      final response = await http
          .post(
            Uri.parse('$endpoint/api/vitals/sync'),
            headers: headers,
            body: body,
          )
          .timeout(const Duration(seconds: 20));

      final syncOk = response.statusCode >= 200 && response.statusCode < 300;
      await _postDeviceEvent(
        endpoint: endpoint,
        headers: headers,
        event: syncOk ? 'device_health_sync_success' : 'device_health_sync_failed',
        reason: syncOk ? 'vitals_uploaded' : 'http_${response.statusCode}',
        payload: {
          'health_permissions': hasHealthPermissions,
          'records_count': records.length,
          'heart_rate_samples': hrCount,
          'heart_rate_avg': hrCount > 0 ? double.parse((hrTotal / hrCount).toStringAsFixed(2)) : null,
          'steps_samples': stepCount,
          'steps_total': stepTotal.round(),
          'type_samples': typeSamples,
          'type_numeric_avg': typeAverages,
          'window_minutes': 15,
        },
      );

      if (!syncOk) return Future.value(false);

      return Future.value(true);
    } on SocketException {
      // Device is offline.  For a periodic task, returning false marks this
      // execution as failed but does not affect the next scheduled interval —
      // WorkManager will run the task again at the normal 15-minute cadence.
      return Future.value(false);
    } catch (_) {
      // Any other error — return success so WorkManager doesn't back off
      // exponentially on non-transient failures.
      return Future.value(true);
    }
  });
}

Future<void> _postDeviceEvent({
  required String endpoint,
  required Map<String, String> headers,
  required String event,
  String? reason,
  Map<String, dynamic>? payload,
}) async {
  final body = jsonEncode({
    'event': event,
    if (reason != null && reason.trim().isNotEmpty) 'reason': reason.trim(),
    'timestamp': DateTime.now().millisecondsSinceEpoch,
    'source': 'flutter_vitals_worker',
    if (payload != null && payload.isNotEmpty) ...payload,
  });
  await http
      .post(
        Uri.parse('$endpoint/api/tpe/webhook'),
        headers: headers,
        body: body,
      )
      .timeout(const Duration(seconds: 10));
}

// ── VitalsSyncService (foreground / scheduler API) ───────────────────────────

/// Manages registration and cancellation of the periodic vitals-sync
/// WorkManager task from the Flutter foreground.
class VitalsSyncService {
  VitalsSyncService._();

  static final VitalsSyncService instance = VitalsSyncService._();

  /// Initialises WorkManager with [vitalsCallbackDispatcher].
  ///
  /// Must be called once from [main] before [runApp].
  Future<void> initialize() async {
    try {
      await Workmanager().initialize(
        vitalsCallbackDispatcher,
        isInDebugMode: false,
      );
    } on MissingPluginException {
      // Optional in host builds where the plugin channel is not registered.
    } on PlatformException {
      // Optional in host builds where the plugin channel is not registered.
    }
  }

  /// Registers (or replaces) the periodic vitals-sync task.
  ///
  /// Runs approximately every [_kSyncInterval] (15 min).
  /// [ExistingWorkPolicy.replace] ensures only one copy is ever queued.
  Future<void> enable() async {
    try {
      await Workmanager().registerPeriodicTask(
        _kVitalsTaskTag,
        _kVitalsTaskName,
        frequency: _kSyncInterval,
        existingWorkPolicy: ExistingPeriodicWorkPolicy.replace,
        constraints: Constraints(
          networkType: NetworkType.connected,
        ),
      );
    } on MissingPluginException {
      // Keep preference state even if periodic scheduler is unavailable.
    } on PlatformException {
      // Keep preference state even if periodic scheduler is unavailable.
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kHealthConnectEnabled, true);
  }

  /// Cancels the periodic vitals-sync task and marks the feature disabled.
  Future<void> disable() async {
    try {
      await Workmanager().cancelByUniqueName(_kVitalsTaskTag);
    } on MissingPluginException {
      // Optional in host builds where the plugin channel is not registered.
    } on PlatformException {
      // Optional in host builds where the plugin channel is not registered.
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kHealthConnectEnabled, false);
  }

  /// Returns true if the periodic task is currently enabled in preferences.
  Future<bool> isEnabled() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(kHealthConnectEnabled) ?? true;
  }
}
