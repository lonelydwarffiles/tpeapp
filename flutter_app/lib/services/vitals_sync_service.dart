import 'dart:convert';
import 'dart:io';

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
      if (!(prefs.getBool(kHealthConnectEnabled) ?? false)) {
        return Future.value(true);
      }

      final endpoint =
          (prefs.getString('partner_endpoint_url') ?? '').trimRight();
      if (endpoint.isEmpty) return Future.value(true);

      // Query Health Connect for the last 15 minutes.
      final healthService = HealthService.instance;

      final now = DateTime.now();
      final records = await healthService.queryVitals(endTime: now);

      if (records.isEmpty) return Future.value(true);

      // Build request headers matching the rest of the API calls.
      final token = prefs.getString('webhook_bearer_token');
      final deviceId = prefs.getString('device_id');
      final headers = <String, String>{
        'Content-Type': 'application/json',
        if (token != null && token.isNotEmpty) 'Authorization': 'Bearer $token',
        if (deviceId != null && deviceId.isNotEmpty) 'X-Device-ID': deviceId,
      };

      final body = jsonEncode({'vitals': records});

      await http
          .post(
            Uri.parse('$endpoint/api/vitals/sync'),
            headers: headers,
            body: body,
          )
          .timeout(const Duration(seconds: 20));

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
    await Workmanager().initialize(
      vitalsCallbackDispatcher,
      isInDebugMode: false,
    );
  }

  /// Registers (or replaces) the periodic vitals-sync task.
  ///
  /// Runs approximately every [_kSyncInterval] (15 min).
  /// [ExistingWorkPolicy.replace] ensures only one copy is ever queued.
  Future<void> enable() async {
    await Workmanager().registerPeriodicTask(
      _kVitalsTaskTag,
      _kVitalsTaskName,
      frequency: _kSyncInterval,
      existingWorkPolicy: ExistingWorkPolicy.replace,
      constraints: Constraints(
        networkType: NetworkType.connected,
      ),
    );
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kHealthConnectEnabled, true);
  }

  /// Cancels the periodic vitals-sync task and marks the feature disabled.
  Future<void> disable() async {
    await Workmanager().cancelByUniqueName(_kVitalsTaskTag);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kHealthConnectEnabled, false);
  }

  /// Returns true if the periodic task is currently enabled in preferences.
  Future<bool> isEnabled() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(kHealthConnectEnabled) ?? false;
  }
}
