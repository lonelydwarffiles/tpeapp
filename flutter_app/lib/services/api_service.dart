import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/task.dart';

/// Central HTTP client for all partner-backend API calls.
///
/// Dart equivalent of the OkHttp calls scattered across:
///  - [PairingActivity]          → `/api/pair`
///  - [CheckInActivity]          → `/api/tpe/checkin`
///  - [TaskPhotoUploadWorker]    → `/api/tpe/task/status`
///  - [QuestionsActivity]        → `/api/admin/questions`
///  - [AssignTaskActivity]       → `/api/admin/tpe/tasks`
///
/// Reads endpoint, bearer token, and Basic-Auth credentials from the same
/// SharedPreferences keys used by the native Kotlin layer so both sides
/// stay in sync.
class ApiService {
  ApiService(this._prefs);

  final SharedPreferences _prefs;

  static const _timeout = Duration(seconds: 15);

  // ── Preferences keys (must match native constants) ────────────────────

  String get _endpoint =>
      (_prefs.getString('partner_endpoint_url') ?? '').trimRight();

  String? get _bearerToken {
    final t = _prefs.getString('webhook_bearer_token');
    return (t != null && t.isNotEmpty) ? t : null;
  }

  String? get _adminUser {
    final u = _prefs.getString('admin_username');
    return (u != null && u.isNotEmpty) ? u : null;
  }

  String? get _adminPass {
    final p = _prefs.getString('admin_password');
    return (p != null && p.isNotEmpty) ? p : null;
  }

  String? get _deviceId {
    final id = _prefs.getString('device_id');
    return (id != null && id.isNotEmpty) ? id : null;
  }

  String? get _deviceName {
    final name = _prefs.getString('device_name');
    return (name != null && name.isNotEmpty) ? name : null;
  }

  // ── Headers ───────────────────────────────────────────────────────────

  Map<String, String> get _bearerHeaders => {
        'Content-Type': 'application/json',
        if (_bearerToken != null) 'Authorization': 'Bearer $_bearerToken',
        if (_deviceId != null) 'X-Device-ID': _deviceId!,
      };

  Map<String, String> get _basicAuthHeaders {
    final user = _adminUser;
    final pass = _adminPass;
    final Map<String, String> base = {
      'Content-Type': 'application/json',
      if (_deviceId != null) 'X-Device-ID': _deviceId!,
    };
    if (user == null || pass == null) return base;
    final encoded = base64Encode(utf8.encode('$user:$pass'));
    return {
      ...base,
      'Authorization': 'Basic $encoded',
    };
  }

  // ── Pairing ───────────────────────────────────────────────────────────

  /// POSTs pairing payload to `{endpoint}/api/pair`.
  /// Returns true on success; throws on failure.
  Future<bool> pair({
    required String endpoint,
    required String pairingToken,
    required String mqttClientId,
    String? webhookSecret,
    String? mqttTopicPrefix,
  }) async {
    final deviceId = _deviceId;
    final deviceName = _deviceName;
    // Backend still requires the legacy key name `fcm_token`; map it to a
    // stable MQTT/device identifier so Firebase is not required.
    final storedRoutingToken = (_prefs.getString('fcm_token') ?? '').trim();
    final effectiveRoutingToken = storedRoutingToken.isNotEmpty
      ? storedRoutingToken
        : (deviceId != null && deviceId.isNotEmpty ? deviceId : mqttClientId);
    final body = jsonEncode({
      'loc': endpoint,
      'endpoint': endpoint,
      'fcm_token': effectiveRoutingToken,
      'fcmToken': effectiveRoutingToken,
      'mqtt_client_id': mqttClientId,
      'mqttClientId': mqttClientId,
      'pairing_token': pairingToken,
      'pairingToken': pairingToken,
      if (webhookSecret != null && webhookSecret.isNotEmpty)
        'webhook_secret': webhookSecret,
      if (webhookSecret != null && webhookSecret.isNotEmpty)
        'webhookSecret': webhookSecret,
      if (mqttTopicPrefix != null && mqttTopicPrefix.isNotEmpty)
        'mqtt_topic_prefix': mqttTopicPrefix,
      if (mqttTopicPrefix != null && mqttTopicPrefix.isNotEmpty)
        'mqttTopicPrefix': mqttTopicPrefix,
      if (deviceId != null) 'device_id': deviceId,
      if (deviceId != null) 'deviceId': deviceId,
      if (deviceName != null) 'device_name': deviceName,
      if (deviceName != null) 'deviceName': deviceName,
    });
    final response = await http
        .post(
          Uri.parse('$endpoint/api/pair'),
          headers: {
            'Content-Type': 'application/json',
            if (deviceId != null) 'X-Device-ID': deviceId,
          },
          body: body,
        )
        .timeout(_timeout);
    if (!response.isSuccessful) {
      final details = response.body.trim();
      throw Exception(
        details.isEmpty
            ? 'Pairing rejected: HTTP ${response.statusCode}'
            : 'Pairing rejected: HTTP ${response.statusCode} - $details',
      );
    }
    return true;
  }

  /// POSTs manual code pairing payload to `{endpoint}/api/pair/code`.
  ///
  /// Returns decoded JSON response so callers can persist returned transport
  /// settings (webhook secret / mqtt fields) when provided by backend.
  Future<Map<String, dynamic>> pairWithCode({
    required String endpoint,
    required String pairingCode,
    required String mqttClientId,
  }) async {
    final deviceId = _deviceId;
    final deviceName = _deviceName;
    final storedRoutingToken = (_prefs.getString('fcm_token') ?? '').trim();
    final effectiveRoutingToken = storedRoutingToken.isNotEmpty
        ? storedRoutingToken
        : (deviceId != null && deviceId.isNotEmpty ? deviceId : mqttClientId);

    final body = jsonEncode({
      'fcm_token': effectiveRoutingToken,
      'pairing_code': pairingCode.trim().toUpperCase(),
      'mqtt_client_id': mqttClientId,
      if (deviceId != null) 'device_id': deviceId,
      if (deviceName != null) 'device_name': deviceName,
    });

    final response = await http
        .post(
          Uri.parse('$endpoint/api/pair/code'),
          headers: {
            'Content-Type': 'application/json',
            if (deviceId != null) 'X-Device-ID': deviceId,
          },
          body: body,
        )
        .timeout(_timeout);

    if (!response.isSuccessful) {
      final details = response.body.trim();
      throw Exception(
        details.isEmpty
            ? 'Pairing rejected: HTTP ${response.statusCode}'
            : 'Pairing rejected: HTTP ${response.statusCode} - $details',
      );
    }

    final raw = response.body.trim();
    if (raw.isEmpty) return const <String, dynamic>{};
    final decoded = jsonDecode(raw);
    if (decoded is Map<String, dynamic>) return decoded;
    return const <String, dynamic>{};
  }

  // ── Check-in ─────────────────────────────────────────────────────────

  /// POSTs `{ mood_score, note }` to `{endpoint}/api/tpe/checkin`.
  Future<void> submitCheckIn({required int moodScore, required String note}) async {
    final body = jsonEncode({'mood_score': moodScore, 'note': note});
    final response = await http
        .post(
          Uri.parse('$_endpoint/api/tpe/checkin'),
          headers: _bearerHeaders,
          body: body,
        )
        .timeout(_timeout);
    _assertSuccess(response, 'Check-in');
  }

  // ── Task status upload ────────────────────────────────────────────────

  /// Reports task completion to `{endpoint}/api/tpe/task/status`.
  /// Optionally attaches a [photoPath] as multipart form data.
  Future<void> uploadTaskStatus({
    required String taskId,
    required TaskStatus status,
    String? photoPath,
  }) async {
    final uri = Uri.parse('$_endpoint/api/tpe/task/status');

    if (photoPath != null) {
      final request = http.MultipartRequest('POST', uri)
        ..headers.addAll(_bearerHeaders..remove('Content-Type'))
        ..fields['task_id'] = taskId
        ..fields['status'] = status.name.toUpperCase()
        ..files.add(await http.MultipartFile.fromPath('photo', photoPath));
      final streamed = await request.send().timeout(_timeout);
      if (streamed.statusCode < 200 || streamed.statusCode >= 300) {
        throw Exception('Task upload failed: HTTP ${streamed.statusCode}');
      }
    } else {
      final body = jsonEncode({
        'task_id': taskId,
        'status': status.name.toUpperCase(),
      });
      final response = await http
          .post(uri, headers: _bearerHeaders, body: body)
          .timeout(_timeout);
      _assertSuccess(response, 'Task status');
    }
  }

  // ── Questions (admin) ─────────────────────────────────────────────────

  /// Fetches unanswered questions from `GET {endpoint}/api/admin/questions`.
  Future<List<Map<String, dynamic>>> fetchQuestions() async {
    final response = await http
        .get(
          Uri.parse('$_endpoint/api/admin/questions'),
          headers: _basicAuthHeaders,
        )
        .timeout(_timeout);
    _assertSuccess(response, 'Fetch questions');
    final list = jsonDecode(response.body) as List<dynamic>;
    return list.cast<Map<String, dynamic>>();
  }

  /// Posts an answer to `POST {endpoint}/api/admin/questions/{id}/answer`.
  Future<void> answerQuestion(String id, String answer) async {
    final body = jsonEncode({'answer': answer});
    final response = await http
        .post(
          Uri.parse('$_endpoint/api/admin/questions/$id/answer'),
          headers: _basicAuthHeaders,
          body: body,
        )
        .timeout(_timeout);
    _assertSuccess(response, 'Answer question');
  }

  /// Deletes a question via `DELETE {endpoint}/api/admin/questions/{id}`.
  Future<void> deleteQuestion(String id) async {
    final response = await http
        .delete(
          Uri.parse('$_endpoint/api/admin/questions/$id'),
          headers: _basicAuthHeaders,
        )
        .timeout(_timeout);
    _assertSuccess(response, 'Delete question');
  }

  // ── Task assignment (admin) ───────────────────────────────────────────

  /// Posts a new task to `POST {endpoint}/api/admin/tpe/tasks`.
  Future<void> assignTask({
    required String title,
    required String description,
    required int deadlineMs,
  }) async {
    final body = jsonEncode({
      'title': title,
      'description': description,
      'deadline_ms': deadlineMs,
    });
    final response = await http
        .post(
          Uri.parse('$_endpoint/api/admin/tpe/tasks'),
          headers: _basicAuthHeaders,
          body: body,
        )
        .timeout(_timeout);
    _assertSuccess(response, 'Assign task');
  }

  // ── Remote command ack ────────────────────────────────────────────────

  /// POSTs command execution status to
  /// `{endpoint}/api/tpe/commands/{commandId}/ack`.
  Future<void> postCommandAck({
    required String commandId,
    required String status,
    String? errorCode,
    String? errorMessage,
    Map<String, dynamic>? telemetry,
  }) async {
    final body = jsonEncode({
      'status': status,
      if (errorCode != null && errorCode.isNotEmpty) 'error_code': errorCode,
      if (errorMessage != null && errorMessage.isNotEmpty)
        'error_message': errorMessage,
      if (telemetry != null && telemetry.isNotEmpty) 'telemetry': telemetry,
    });

    final response = await http
        .post(
          Uri.parse('$_endpoint/api/tpe/commands/$commandId/ack'),
          headers: _bearerHeaders,
          body: body,
        )
        .timeout(_timeout);

    _assertSuccess(response, 'Command ack');
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  void _assertSuccess(http.Response response, String label) {
    if (!response.isSuccessful) {
      throw Exception('$label failed: HTTP ${response.statusCode}');
    }
  }
}

extension on http.Response {
  bool get isSuccessful => statusCode >= 200 && statusCode < 300;
}
