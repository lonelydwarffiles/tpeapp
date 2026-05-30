import 'dart:async';
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
  static const _offlineQueueKey = 'offline_http_queue_v1';
  static const _offlineQueueMax = 200;

  bool _isFlushingQueue = false;

  // ── Preferences keys (must match native constants) ────────────────────

  String get _endpoint =>
      (_prefs.getString('partner_endpoint_url') ?? '')
        .trim()
        .replaceFirst(RegExp(r'/+$'), '');

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

  /// POSTs auto-pair payload to `{endpoint}/api/pair/auto`.
  ///
  /// Used for zero-step enrollment flows where the backend has auto-pair
  /// enabled. Returns decoded JSON response on success.
  Future<Map<String, dynamic>> pairAuto({
    required String endpoint,
    required String mqttClientId,
    String? autoPairKey,
  }) async {
    final deviceId = _deviceId;
    final deviceName = _deviceName;
    final storedRoutingToken = (_prefs.getString('fcm_token') ?? '').trim();
    final effectiveRoutingToken = storedRoutingToken.isNotEmpty
        ? storedRoutingToken
        : (deviceId != null && deviceId.isNotEmpty ? deviceId : mqttClientId);

    final body = jsonEncode({
      'fcm_token': effectiveRoutingToken,
      'mqtt_client_id': mqttClientId,
      if (deviceId != null) 'device_id': deviceId,
      if (deviceName != null) 'device_name': deviceName,
      if (autoPairKey != null && autoPairKey.isNotEmpty)
        'auto_pair_key': autoPairKey,
    });

    final response = await http
        .post(
          Uri.parse('$endpoint/api/pair/auto'),
          headers: {
            'Content-Type': 'application/json',
            if (deviceId != null) 'X-Device-ID': deviceId,
            if (autoPairKey != null && autoPairKey.isNotEmpty)
              'X-Auto-Pair-Key': autoPairKey,
          },
          body: body,
        )
        .timeout(_timeout);

    if (!response.isSuccessful) {
      final details = response.body.trim();
      throw Exception(
        details.isEmpty
            ? 'Auto pairing rejected: HTTP ${response.statusCode}'
            : 'Auto pairing rejected: HTTP ${response.statusCode} - $details',
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
  Future<void> submitCheckIn(
      {required int moodScore, required String note}) async {
    final payload = {'mood_score': moodScore, 'note': note};
    await _flushOfflineQueue();
    try {
      final response = await http
          .post(
            Uri.parse('$_endpoint/api/tpe/checkin'),
            headers: _bearerHeaders,
            body: jsonEncode(payload),
          )
          .timeout(_timeout);
      _assertSuccess(response, 'Check-in');
    } on SocketException catch (_) {
      await _enqueueOfflinePost(path: '/api/tpe/checkin', payload: payload);
    } on TimeoutException catch (_) {
      await _enqueueOfflinePost(path: '/api/tpe/checkin', payload: payload);
    }
  }

  // ── Device status heartbeat ──────────────────────────────────────────

  /// POSTs device presence/status (including optional location) to
  /// `{endpoint}/api/handler/device-status`.
  Future<void> postDeviceStatus({
    int? batteryPct,
    double? lat,
    double? lon,
    bool? aiAlert,
    String? aiLabel,
    double? aiScore,
    Map<String, dynamic>? toyInfo,
    Map<String, dynamic>? capabilities,
  }) async {
    final payload = {
      if (_deviceId != null) 'device_id': _deviceId,
      if (_deviceName != null) 'device_name': _deviceName,
      if (batteryPct != null) 'battery_pct': batteryPct,
      if (lat != null) 'lat': lat,
      if (lon != null) 'lon': lon,
      if (aiAlert != null) 'ai_alert': aiAlert,
      if (aiLabel != null && aiLabel.trim().isNotEmpty) 'ai_label': aiLabel.trim(),
      if (aiScore != null) 'ai_score': aiScore,
      if (toyInfo != null && toyInfo.isNotEmpty) 'toy_info': toyInfo,
      if (capabilities != null && capabilities.isNotEmpty) 'capabilities': capabilities,
    };

    await _flushOfflineQueue();
    try {
      final response = await http
          .post(
            Uri.parse('$_endpoint/api/handler/device-status'),
            headers: _bearerHeaders,
            body: jsonEncode(payload),
          )
          .timeout(_timeout);
      _assertSuccess(response, 'Device status');
    } on SocketException catch (_) {
      await _enqueueOfflinePost(path: '/api/handler/device-status', payload: payload);
    } on TimeoutException catch (_) {
      await _enqueueOfflinePost(path: '/api/handler/device-status', payload: payload);
    }
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

  /// Fetches questions using the best available auth/route combination.
  ///
  /// Fallback order:
  /// 1) GET /api/tpe/questions (device bearer)
  /// 2) GET /api/handler/questions (JWT bearer)
  /// 3) GET /api/admin/questions (Basic auth)
  /// 4) GET /api/questions/public (unauthenticated, read-only)
  Future<List<Map<String, dynamic>>> fetchQuestions() async {
    final response = await _requestWithFallback(
      method: 'GET',
      paths: const [
        '/api/tpe/questions',
        '/api/handler/questions',
        '/api/admin/questions',
        '/api/questions/public',
      ],
      headersByPath: [
        _bearerHeaders,
        _bearerHeaders,
        _basicAuthHeaders,
        const {'Content-Type': 'application/json'},
      ],
    );
    _assertSuccess(response, 'Fetch questions');
    final list = (jsonDecode(response.body) as List<dynamic>)
        .whereType<Map>()
        .map((e) => Map<String, dynamic>.from(e))
        .toList();

    final isPublicFeed = response.request?.url.path.endsWith('/api/questions/public') ?? false;
    if (isPublicFeed) {
      return list
          .map(
            (q) => {
              ...q,
              'question': (q['question'] ?? q['text'] ?? '').toString(),
              'can_moderate': false,
            },
          )
          .toList();
    }

    return list
        .map(
          (q) => {
            ...q,
            'question': (q['question'] ?? q['text'] ?? '').toString(),
            'can_moderate': true,
          },
        )
        .toList();
  }

  /// Posts an answer via device route first, then handler/admin fallbacks.
  Future<void> answerQuestion(String id, String answer) async {
    final body = jsonEncode({'answer': answer});
    final response = await _requestWithFallback(
      method: 'POST',
      paths: [
        '/api/tpe/questions/$id/answer',
        '/api/handler/questions/$id/answer',
        '/api/admin/questions/$id/answer',
      ],
      headersByPath: [
        _bearerHeaders,
        _bearerHeaders,
        _basicAuthHeaders,
      ],
      body: body,
    );
    _assertSuccess(response, 'Answer question');
  }

  /// Deletes a question via handler JWT route first, then admin Basic route.
  Future<void> deleteQuestion(String id) async {
    final response = await _requestWithFallback(
      method: 'DELETE',
      paths: [
        '/api/handler/questions/$id',
        '/api/admin/questions/$id',
      ],
      headersByPath: [
        _bearerHeaders,
        _basicAuthHeaders,
      ],
    );
    _assertSuccess(response, 'Delete question');
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
    final payload = {
      'status': status,
      if (errorCode != null && errorCode.isNotEmpty) 'error_code': errorCode,
      if (errorMessage != null && errorMessage.isNotEmpty)
        'error_message': errorMessage,
      if (telemetry != null && telemetry.isNotEmpty) 'telemetry': telemetry,
    };
    final path = '/api/tpe/commands/$commandId/ack';
    await _flushOfflineQueue();
    try {
      final response = await http
          .post(
            Uri.parse('$_endpoint$path'),
            headers: _bearerHeaders,
            body: jsonEncode(payload),
          )
          .timeout(_timeout);
      _assertSuccess(response, 'Command ack');
    } on SocketException catch (_) {
      await _enqueueOfflinePost(path: path, payload: payload);
    } on TimeoutException catch (_) {
      await _enqueueOfflinePost(path: path, payload: payload);
    }
  }

  /// Pushes a text-replacement dictionary to the device runtime through the
  /// partner backend's admin MQTT bridge.
  Future<void> pushTextReplacementDict({
    required Map<String, String> dict,
    String? deviceId,
  }) async {
    final payload = {
      'action': 'UPDATE_TEXT_REPLACEMENT_DICT',
      'text_replacement_dict': jsonEncode(dict),
      if ((deviceId ?? _deviceId) != null) 'device_id': deviceId ?? _deviceId,
    };

    final response = await http
        .post(
          Uri.parse('$_endpoint/api/admin/tpe/push'),
          headers: _basicAuthHeaders,
          body: jsonEncode(payload),
        )
        .timeout(_timeout);
    _assertSuccess(response, 'Push text replacement dictionary');
  }

  Future<void> pushTextReplacementPolicy({
    required Map<String, dynamic> policy,
    String? deviceId,
  }) async {
    final payload = {
      'action': 'UPDATE_TEXT_REPLACEMENT_POLICY',
      'policy': jsonEncode(policy),
      if ((deviceId ?? _deviceId) != null) 'device_id': deviceId ?? _deviceId,
    };

    final response = await http
        .post(
          Uri.parse('$_endpoint/api/admin/tpe/push'),
          headers: _basicAuthHeaders,
          body: jsonEncode(payload),
        )
        .timeout(_timeout);
    _assertSuccess(response, 'Push text replacement policy');
  }

  // ── Behavioral telemetry ───────────────────────────────────────────────

  /// Sends a high-signal app behavior event to `{endpoint}/api/tpe/webhook`.
  Future<void> postBehaviorEvent({
    required String event,
    String? reason,
    Map<String, dynamic>? payload,
  }) async {
    final payloadBody = {
      'event': event,
      if (reason != null && reason.trim().isNotEmpty) 'reason': reason.trim(),
      'timestamp': DateTime.now().millisecondsSinceEpoch,
      if (_deviceId != null) 'device_id': _deviceId,
      'source': 'flutter_app',
      if (payload != null && payload.isNotEmpty) ...payload,
    };
    await _flushOfflineQueue();
    try {
      final response = await http
          .post(
            Uri.parse('$_endpoint/api/tpe/webhook'),
            headers: _bearerHeaders,
            body: jsonEncode(payloadBody),
          )
          .timeout(_timeout);
      _assertSuccess(response, 'Behavior event');
    } on SocketException catch (_) {
      await _enqueueOfflinePost(path: '/api/tpe/webhook', payload: payloadBody);
    } on TimeoutException catch (_) {
      await _enqueueOfflinePost(path: '/api/tpe/webhook', payload: payloadBody);
    }
  }

  /// Convenience helper for social actions like likes/saves/comments.
  Future<void> postSocialInteraction({
    required String platform,
    required String action,
    String? targetType,
    String? targetId,
    String? reason,
    Map<String, dynamic>? extra,
  }) {
    return postBehaviorEvent(
      event: 'social_interaction',
      reason: reason ?? '$platform:$action',
      payload: {
        'platform': platform,
        'action': action,
        if (targetType != null && targetType.trim().isNotEmpty)
          'target_type': targetType.trim(),
        if (targetId != null && targetId.trim().isNotEmpty)
          'target_id': targetId.trim(),
        if (extra != null && extra.isNotEmpty) ...extra,
      },
    );
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  Future<List<Map<String, dynamic>>> _readOfflineQueue() async {
    final raw = _prefs.getString(_offlineQueueKey);
    if (raw == null || raw.trim().isEmpty) return const [];
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return const [];
      return decoded
          .whereType<Map>()
          .map((e) => Map<String, dynamic>.from(e))
          .toList();
    } catch (_) {
      return const [];
    }
  }

  Future<void> _writeOfflineQueue(List<Map<String, dynamic>> queue) async {
    await _prefs.setString(_offlineQueueKey, jsonEncode(queue));
  }

  Future<void> _enqueueOfflinePost({
    required String path,
    required Map<String, dynamic> payload,
  }) async {
    final queue = await _readOfflineQueue();
    queue.add({
      'path': path,
      'payload': payload,
      'queued_at': DateTime.now().toUtc().toIso8601String(),
    });
    if (queue.length > _offlineQueueMax) {
      queue.removeRange(0, queue.length - _offlineQueueMax);
    }
    await _writeOfflineQueue(queue);
  }

  Future<void> _flushOfflineQueue() async {
    if (_isFlushingQueue) return;
    if (_endpoint.isEmpty) return;
    _isFlushingQueue = true;
    try {
      var queue = await _readOfflineQueue();
      if (queue.isEmpty) return;

      final remaining = <Map<String, dynamic>>[];
      var stop = false;
      for (final item in queue) {
        if (stop) {
          remaining.add(item);
          continue;
        }
        final path = (item['path'] ?? '').toString();
        final payload = item['payload'];
        if (path.isEmpty || payload is! Map) continue;
        try {
          final response = await http
              .post(
                Uri.parse('$_endpoint$path'),
                headers: _bearerHeaders,
                body: jsonEncode(payload),
              )
              .timeout(_timeout);
          if (response.statusCode >= 200 && response.statusCode < 300) {
            continue;
          }
          // Retry later for transient backend/network edge statuses.
          if (response.statusCode == 408 ||
              response.statusCode == 429 ||
              response.statusCode >= 500) {
            remaining.add(item);
            stop = true;
            continue;
          }
          // Non-retryable errors (4xx except 408/429) are dropped.
        } on SocketException catch (_) {
          remaining.add(item);
          stop = true;
        } on TimeoutException catch (_) {
          remaining.add(item);
          stop = true;
        } catch (_) {
          remaining.add(item);
          stop = true;
        }
      }
      await _writeOfflineQueue(remaining);
    } finally {
      _isFlushingQueue = false;
    }
  }

  void _assertSuccess(http.Response response, String label) {
    if (!response.isSuccessful) {
      throw Exception('$label failed: HTTP ${response.statusCode}');
    }
  }

  Future<http.Response> _requestWithFallback({
    required String method,
    required List<String> paths,
    required List<Map<String, String>> headersByPath,
    String? body,
  }) async {
    if (_endpoint.isEmpty) {
      throw Exception('Endpoint is not configured.');
    }
    if (paths.isEmpty || paths.length != headersByPath.length) {
      throw Exception('Invalid request fallback configuration.');
    }

    http.Response? lastResponse;
    for (var i = 0; i < paths.length; i++) {
      final path = paths[i];
      final headers = headersByPath[i];
      final uri = Uri.parse('$_endpoint$path');
      final response = await _send(method, uri, headers: headers, body: body)
          .timeout(_timeout);
      lastResponse = response;
      if (response.isSuccessful) return response;

      // Try the next route/auth combo for common auth/route misses.
      if (response.statusCode == 401 ||
          response.statusCode == 403 ||
          response.statusCode == 404) {
        continue;
      }
      return response;
    }

    return lastResponse ??
        http.Response('No fallback attempt executed', 500);
  }

  Future<http.Response> _send(
    String method,
    Uri uri, {
    required Map<String, String> headers,
    String? body,
  }) {
    switch (method.toUpperCase()) {
      case 'GET':
        return http.get(uri, headers: headers);
      case 'POST':
        return http.post(uri, headers: headers, body: body);
      case 'DELETE':
        return http.delete(uri, headers: headers);
      default:
        throw Exception('Unsupported HTTP method: $method');
    }
  }
}

extension on http.Response {
  bool get isSuccessful => statusCode >= 200 && statusCode < 300;
}
