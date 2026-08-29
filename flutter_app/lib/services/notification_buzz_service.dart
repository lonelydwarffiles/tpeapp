import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:developer' as developer;
import 'dart:math';

import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/ble_channel.dart';
import '../channels/device_command_channel.dart';
import 'api_service.dart';

/// Listens for native accessibility notification events and executes only
/// notification commands detected in notification text.
class NotificationBuzzService {
  NotificationBuzzService._();

  static final NotificationBuzzService instance = NotificationBuzzService._();

  static const EventChannel _events =
      EventChannel('com.hound.controller/notification_buzz');
  static const int _pulseGapMinMs = 400;
  static const int _pulseGapMaxMs = 5000;
  static const _policyPrefsKey = 'notification_command_policy_json';
  static const _policySyncInterval = Duration(minutes: 5);
  static const int _realtimeVitalsMinGapMs = 8000;
  static const int _hrSafetyMarginBpm = 2;
  static const int _guardedBuzzSliceMs = 1200;
  static const int _hrFreshMaxAgeMs = 12000;
  static const int _resumeStableMs = 6000;
  static const int _resumeWaitMaxMs = 20000;

  StreamSubscription<dynamic>? _sub;
  Timer? _policySyncTimer;
  var _running = false;
  final Queue<int> _buzzDurationsMs = Queue<int>();
  final Queue<int> _actuationMs = Queue<int>();
  final Map<String, int> _recentSignatures = <String, int>{};
  final Random _rng = Random();
  int _buzzExecutedCount = 0;

  SharedPreferences? _prefs;
  ApiService? _api;
  NotificationCommandPolicy _policy = NotificationCommandPolicy.defaults();
  int _cooldownUntilMs = 0;
  int _lastRealtimeVitalsSyncMs = 0;

  Future<void> start() async {
    await _initPolicyPipeline();
    await _sub?.cancel();
    _sub = _events.receiveBroadcastStream().listen(
      _onNativeEvent,
      onError: (_) {
        // Keep service resilient; stream can reconnect later.
      },
    );
  }

  Future<void> stop() async {
    await _sub?.cancel();
    _sub = null;
    _policySyncTimer?.cancel();
    _policySyncTimer = null;
  }

  void _onNativeEvent(dynamic event) {
    if (event is! Map) {
      return;
    }

    final parsed = _normalizeIncomingEvent(Map<String, dynamic>.from(event));
    if (parsed == null) {
      return;
    }

    // Contract: only explicit command carriers trigger BLE actuation.
    if (parsed.source != 'notification' && parsed.source != 'discord_bot') {
      return;
    }
    if (!_policy.enabled) {
      return;
    }
    if (_policy.allowSources.isNotEmpty && !_policy.allowSources.contains(parsed.source)) {
      return;
    }
    if (_policy.packageAllowlist.isNotEmpty && !_policy.packageAllowlist.contains(parsed.packageName)) {
      return;
    }
    if (_policy.packageBlocklist.contains(parsed.packageName)) {
      return;
    }

    final appRule = _policy.ruleForPackage(parsed.packageName);
    if (appRule != null) {
      if (appRule.allowedCommands.isNotEmpty && !appRule.allowedCommands.contains(parsed.command)) {
        return;
      }
      final minConfidence = appRule.minConfidence ?? _policy.minConfidence;
      if (parsed.confidence < minConfidence) {
        return;
      }
    } else if (parsed.confidence < _policy.minConfidence) {
      return;
    }

    if (_policy.dryRun) {
      developer.log(
        'notification-command dry_run: ${parsed.packageName} ${parsed.command}',
        name: 'NotificationBuzzService',
      );
      return;
    }

    final nowMs = DateTime.now().millisecondsSinceEpoch;
    if (_isInCooldown(nowMs)) {
      return;
    }
    if (_isDeduped(parsed, nowMs)) {
      return;
    }

    if (_breachesRateLimit(nowMs)) {
      _cooldownUntilMs =
          nowMs + (_policy.emergencyCooldownSeconds.clamp(5, 600) * 1000);
      developer.log(
        'notification-command safety cooldown started (${_policy.emergencyCooldownSeconds}s)',
        name: 'NotificationBuzzService',
      );
      return;
    }

    _markActuation(nowMs);

    unawaited(_applyNotificationCommand(parsed));
  }

  Future<bool> ingestExternalCommand({
    required String raw,
    String source = 'discord_bot',
    String packageName = 'com.discord',
    String? messageId,
    double confidence = 0.95,
  }) async {
    final parsed = _normalizeIncomingEvent({
      'source': source,
      'package': packageName,
      'raw': raw,
      'content': raw,
      'text': raw,
      'message_id': messageId,
      'confidence': confidence,
    });
    if (parsed == null) {
      return false;
    }
    if (parsed.source != 'notification' && parsed.source != 'discord_bot') {
      return false;
    }
    if (!_policy.enabled) {
      return false;
    }
    if (_policy.allowSources.isNotEmpty && !_policy.allowSources.contains(parsed.source)) {
      return false;
    }
    if (_policy.packageAllowlist.isNotEmpty && !_policy.packageAllowlist.contains(parsed.packageName)) {
      return false;
    }
    if (_policy.packageBlocklist.contains(parsed.packageName)) {
      return false;
    }

    final appRule = _policy.ruleForPackage(parsed.packageName);
    if (appRule != null) {
      if (appRule.allowedCommands.isNotEmpty && !appRule.allowedCommands.contains(parsed.command)) {
        return false;
      }
      final minConfidence = appRule.minConfidence ?? _policy.minConfidence;
      if (parsed.confidence < minConfidence) {
        return false;
      }
    } else if (parsed.confidence < _policy.minConfidence) {
      return false;
    }

    if (_policy.dryRun) {
      developer.log(
        'external-command dry_run: ${parsed.packageName} ${parsed.command}',
        name: 'NotificationBuzzService',
      );
      return true;
    }

    final nowMs = DateTime.now().millisecondsSinceEpoch;
    if (_isInCooldown(nowMs)) {
      return false;
    }
    if (_isDeduped(parsed, nowMs)) {
      return false;
    }
    if (_breachesRateLimit(nowMs)) {
      _cooldownUntilMs =
          nowMs + (_policy.emergencyCooldownSeconds.clamp(5, 600) * 1000);
      return false;
    }

    _markActuation(nowMs);
    unawaited(_applyNotificationCommand(parsed));
    return true;
  }

  Future<void> _applyNotificationCommand(NotificationCommand parsed) async {
    final appRule = _policy.ruleForPackage(parsed.packageName);
    final loopCap = appRule?.maxLoopCount ?? _policy.buzzMaxQueueAdd;
    final maxDurationMs = appRule?.maxDurationMs ?? _policy.maxDurationMs;

    if (parsed.command == 'zap') {
      final hasPavlok = await BleChannel.pavlokIsConnectedNative();
      if (!hasPavlok) {
        developer.log(
          'notification-command zap ignored (no Pavlok connected): ${parsed.raw}',
          name: 'NotificationBuzzService',
        );
        return;
      }
      final scaledStrength =
          (parsed.strength * (appRule?.strengthScale ?? 1.0)).round();
      final zapStrengthCap = appRule?.maxZapStrength ?? _policy.zapMaxStrength;
      final strength = scaledStrength.clamp(1, zapStrengthCap);
      final intensity = ((strength / 100) * 255).round().clamp(1, 255);
      final durationMs =
          parsed.durationMs.clamp(_policy.minDurationMs, maxDurationMs);
      final repeats = parsed.repeatCount.clamp(1, loopCap);
      for (var i = 0; i < repeats; i++) {
        await BleChannel.pavlokZap(intensity: intensity, durationMs: durationMs);
        if (i < repeats - 1) {
          await Future<void>.delayed(
            Duration(milliseconds: _randomPulseGapMs(durationMs: durationMs)),
          );
        }
      }
      return;
    }

    final hasLovense = await BleChannel.lovenseIsConnectedNative();
    if (!hasLovense) {
      developer.log(
        'notification-command buzz ignored (no Lovense connected): ${parsed.raw}',
        name: 'NotificationBuzzService',
      );
      return;
    }

    final effectiveCount = parsed.loop
        ? parsed.count.clamp(1, loopCap)
        : 1;
    final durationMs =
        parsed.durationMs.clamp(_policy.minDurationMs, maxDurationMs);
    for (var i = 0; i < effectiveCount; i++) {
      _buzzDurationsMs.add(_randomizedPulseDurationMs(durationMs));
    }
    await _maybeSpeakQlTts(parsed.raw);
    if (!_running) {
      unawaited(_drainQueue());
    }
  }

  Future<void> _drainQueue() async {
    if (_running) return;
    _running = true;
    try {
      unawaited(_postStopControlNotification());
      while (_buzzDurationsMs.isNotEmpty) {
        if (await _consumeStopActuationRequest()) {
          _buzzDurationsMs.clear();
          await _stopAllActuationNow();
          continue;
        }
        final durationMs = _buzzDurationsMs.removeFirst();
        await _runGuardedBuzzWithHrSafety(durationMs: durationMs);
        _buzzExecutedCount += 1;
        unawaited(_publishBuzzCountNotification());
        await Future<void>.delayed(
          Duration(milliseconds: _randomPulseGapMs(durationMs: durationMs)),
        );
      }
    } finally {
      _running = false;
    }
  }

  Future<void> _runGuardedBuzzWithHrSafety({required int durationMs}) async {
    if (await _consumeStopActuationRequest()) {
      await _stopAllActuationNow();
      return;
    }

    final api = _api;
    if (api == null) {
      return;
    }

    final ready = await _waitForResumeWindow(api);
    var status = await _fetchRealtimeStatus(api, forceSync: true);
    final hrFresh = _isHrDataFresh(status);
    final hrSafe = hrFresh && !_hrAtOrAboveSoftStop(status);
    final fallbackNoHrMode = !ready || !hrSafe;

    if (fallbackNoHrMode && !_policy.allowBuzzWithoutHrData) {
      return;
    }

    var remainingMs = durationMs.clamp(_policy.minDurationMs, _policy.maxDurationMs);
    var buzzLevel = _policy.buzzLevel;
    if (fallbackNoHrMode) {
      remainingMs = remainingMs.clamp(_policy.minDurationMs, _policy.noHrMaxDurationMs);
      buzzLevel = _policy.noHrBuzzLevel.clamp(1, _policy.buzzLevel);
    }
    await BleChannel.lovenseVibrate(buzzLevel);

    try {
      while (remainingMs > 0) {
        if (await _consumeStopActuationRequest()) {
          break;
        }
        final sliceMs = remainingMs < _guardedBuzzSliceMs ? remainingMs : _guardedBuzzSliceMs;
        await Future<void>.delayed(Duration(milliseconds: sliceMs));
        remainingMs -= sliceMs;

        if (!fallbackNoHrMode) {
          status = await _fetchRealtimeStatus(api, forceSync: true);
          if (_hrAtOrAboveSoftStop(status) || !_isHrDataFresh(status)) {
            break;
          }
        }
      }
    } finally {
      await BleChannel.lovenseStopAll();
    }

    if (!fallbackNoHrMode && _hrLimitReachedForBuzzZap(status)) {
      await _maybeApplyHrConditioningZapDuringBuzz(statusOverride: status);
    }
  }

  Future<void> _postStopControlNotification() async {
    try {
      await DeviceCommandChannel.showStopActuationNotification();
    } catch (_) {
      // Keep stop-control notification best-effort.
    }
  }

  Future<bool> _consumeStopActuationRequest() async {
    try {
      return await DeviceCommandChannel.consumeStopActuationRequest();
    } catch (_) {
      return false;
    }
  }

  Future<void> _stopAllActuationNow() async {
    try {
      await BleChannel.lovenseStopAll();
    } catch (_) {
      // Best-effort stop.
    }
    try {
      await BleChannel.pavlokStopAll();
    } catch (_) {
      // Best-effort stop.
    }
  }

  Future<void> _maybeApplyHrConditioningZapDuringBuzz({
    Map<String, dynamic>? statusOverride,
  }) async {
    final api = _api;
    if (api == null) {
      return;
    }

    final status = statusOverride ?? await _fetchRealtimeStatus(api);
    if (!_hrLimitReachedForBuzzZap(status)) {
      return;
    }

    final hasPavlok = await BleChannel.pavlokIsConnectedNative();
    if (!hasPavlok) {
      return;
    }

    final strength = _policy.zapDefaultStrength.clamp(1, _policy.zapMaxStrength);
    final intensity = ((strength / 100) * 255).round().clamp(1, 255);
    final durationMs =
        _policy.defaultDurationMs.clamp(_policy.minDurationMs, _policy.maxDurationMs);
    await BleChannel.pavlokZap(intensity: intensity, durationMs: durationMs);
  }

  Future<Map<String, dynamic>> _fetchRealtimeStatus(
    ApiService api, {
    bool forceSync = false,
  }) async {
    if (forceSync) {
      final nowMs = DateTime.now().millisecondsSinceEpoch;
      final synced = await api.syncRealtimeVitals(window: const Duration(seconds: 20));
      if (synced) {
        _lastRealtimeVitalsSyncMs = nowMs;
      }
    } else {
      await _refreshRealtimeVitalsIfDue(api);
    }
    return api.fetchPublicStatusCounters();
  }

  Future<void> _refreshRealtimeVitalsIfDue(ApiService api) async {
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    if ((nowMs - _lastRealtimeVitalsSyncMs) < _realtimeVitalsMinGapMs) {
      return;
    }
    final synced = await api.syncRealtimeVitals(window: const Duration(seconds: 20));
    if (synced) {
      _lastRealtimeVitalsSyncMs = nowMs;
    }
  }

  bool _hrLimitReachedForBuzzZap(Map<String, dynamic> status) {
    if (status.isEmpty) {
      return false;
    }

    if (_boolFromAny(status['edge_hr_zap_allowed'])) {
      return true;
    }

    final state = '${status['edge_hr_state'] ?? ''}'.trim().toLowerCase();
    final lastHr = _intFromAny(status['edge_hr_last']) ??
        _intFromAny(status['latest_heart_rate']);
    final pauseBpm = _intFromAny(status['edge_hr_pause_bpm']);
    return state == 'holding_edge' &&
        lastHr != null &&
        pauseBpm != null &&
        lastHr >= pauseBpm;
  }

  bool _hrAtOrAboveSoftStop(Map<String, dynamic> status) {
    if (status.isEmpty) {
      return true;
    }
    final pauseBpm = _intFromAny(status['edge_hr_pause_bpm']);
    final lastHr = _intFromAny(status['edge_hr_last']) ??
        _intFromAny(status['latest_heart_rate']);
    if (pauseBpm == null || lastHr == null) {
      return true;
    }
    final softStop = pauseBpm - _hrSafetyMarginBpm;
    return lastHr >= softStop;
  }

  bool _isHrDataFresh(Map<String, dynamic> status) {
    final updatedAt = _parseIsoToMs(status['edge_hr_updated_at']) ??
        _parseIsoToMs(status['latest_vitals_at']);
    if (updatedAt == null) {
      return false;
    }
    final ageMs = DateTime.now().millisecondsSinceEpoch - updatedAt;
    return ageMs >= 0 && ageMs <= _hrFreshMaxAgeMs;
  }

  bool _hrBelowResume(Map<String, dynamic> status) {
    final lastHr = _intFromAny(status['edge_hr_last']) ??
        _intFromAny(status['latest_heart_rate']);
    final resumeBpm = _intFromAny(status['edge_hr_resume_bpm']);
    if (lastHr == null || resumeBpm == null) {
      return false;
    }
    return lastHr <= resumeBpm;
  }

  Future<bool> _waitForResumeWindow(ApiService api) async {
    var stableMs = 0;
    var waitedMs = 0;
    while (waitedMs < _resumeWaitMaxMs) {
      final status = await _fetchRealtimeStatus(api, forceSync: true);
      final safe = _isHrDataFresh(status) && _hrBelowResume(status);
      if (safe) {
        stableMs += _guardedBuzzSliceMs;
        if (stableMs >= _resumeStableMs) {
          return true;
        }
      } else {
        stableMs = 0;
      }
      await Future<void>.delayed(const Duration(milliseconds: _guardedBuzzSliceMs));
      waitedMs += _guardedBuzzSliceMs;
    }
    return false;
  }

  int? _parseIsoToMs(dynamic value) {
    final raw = '${value ?? ''}'.trim();
    if (raw.isEmpty) {
      return null;
    }
    final parsed = DateTime.tryParse(raw);
    return parsed?.toUtc().millisecondsSinceEpoch;
  }

  int? _intFromAny(dynamic value) {
    if (value is num) return value.toInt();
    return int.tryParse('${value ?? ''}'.trim());
  }

  bool _boolFromAny(dynamic value) {
    if (value is bool) return value;
    final normalized = '${value ?? ''}'.trim().toLowerCase();
    return normalized == '1' || normalized == 'true' || normalized == 'yes' || normalized == 'on';
  }

  int _randomizedPulseDurationMs(int maxDurationMs) {
    final boundedMax = maxDurationMs.clamp(800, 600000);
    if (boundedMax <= 800) {
      return boundedMax;
    }
    final span = (boundedMax - 800) + 1;
    return 800 + _rng.nextInt(span);
  }

  int _randomPulseGapMs({required int durationMs}) {
    final dynamicMax = durationMs.clamp(_pulseGapMinMs, _pulseGapMaxMs);
    final span = (dynamicMax - _pulseGapMinMs) + 1;
    return _pulseGapMinMs + _rng.nextInt(span);
  }

  Future<void> _publishBuzzCountNotification() async {
    try {
      await DeviceCommandChannel.sendNotification(
        title: 'Buzz Activity',
        body: 'Buzz count this session: $_buzzExecutedCount',
        channelId: 'tpe_edge_status',
      );
    } catch (_) {
      // Best-effort status notification.
    }
  }

  Future<void> _maybeSpeakQlTts(String raw) async {
    final lower = raw.toLowerCase();
    if (!RegExp(r'\bql\b').hasMatch(lower)) {
      return;
    }
    final hasHeadphones = await DeviceCommandChannel.isHeadphonesConnected();
    if (!hasHeadphones) {
      return;
    }
    try {
      await DeviceCommandChannel.speakText('QL notification command received.');
    } catch (_) {
      // Best-effort TTS.
    }
  }

  Future<void> _initPolicyPipeline() async {
    _prefs ??= await SharedPreferences.getInstance();
    _api ??= ApiService(_prefs!);

    final raw = _prefs!.getString(_policyPrefsKey);
    if (raw != null && raw.trim().isNotEmpty) {
      try {
        final decoded = jsonDecode(raw);
        if (decoded is Map<String, dynamic>) {
          _policy = NotificationCommandPolicy.fromMap(decoded);
        }
      } catch (_) {
        // Keep defaults on malformed local policy.
      }
    }

    await _syncPolicyFromBackend();
    _policySyncTimer?.cancel();
    _policySyncTimer = Timer.periodic(_policySyncInterval, (_) {
      unawaited(_syncPolicyFromBackend());
    });
  }

  Future<void> _syncPolicyFromBackend() async {
    final api = _api;
    final prefs = _prefs;
    if (api == null || prefs == null) return;

    final remote = await api.fetchNotificationCommandPolicy();
    if (remote.isEmpty) return;

    _policy = NotificationCommandPolicy.fromMap(remote);
    await prefs.setString(_policyPrefsKey, jsonEncode(remote));
  }

  NotificationCommand? _normalizeIncomingEvent(Map<String, dynamic> event) {
    final source =
        (event['source']?.toString().trim().toLowerCase() ?? 'notification')
            .trim();
    final packageName =
        (event['package']?.toString().trim().toLowerCase() ?? '').trim();
    if (packageName.isEmpty) return null;

    final raw = (event['raw']?.toString() ?? '').trim();
    final commandText = _composeCommandText(event);
    if (packageName.contains('discord') && !RegExp(r'\bql\b').hasMatch(commandText.toLowerCase())) {
      return null;
    }
    final parsedFromRaw = _parseRawCommand(commandText);

    final command = (parsedFromRaw?.command ??
            event['command']?.toString().trim().toLowerCase() ??
            'buzz')
        .trim();
    if (command != 'buzz' && command != 'zap') {
      return null;
    }

    final fallbackDuration = event['duration_ms'] is num
        ? (event['duration_ms'] as num).toInt()
        : _policy.defaultDurationMs;

    final count = command == 'buzz'
      ? (parsedFromRaw?.count ??
        (event['count'] is num
          ? (event['count'] as num).toInt().clamp(1, 20)
          : 1))
      : 1;
    final loop = command == 'buzz'
      ? (parsedFromRaw?.loop ?? (event['loop'] == true))
      : false;
    final repeatCount = command == 'zap'
      ? (parsedFromRaw?.repeatCount ??
          (event['repeat_count'] is num
            ? (event['repeat_count'] as num).toInt().clamp(1, 50)
            : 1))
      : 1;
    final strength = parsedFromRaw?.strength ??
        (event['strength'] is num
            ? (event['strength'] as num).toInt().clamp(1, 100)
            : _policy.zapDefaultStrength);
    final durationMs = (command == 'zap'
        ? _policy.defaultDurationMs
        : (parsedFromRaw?.durationMs ?? fallbackDuration))
      .clamp(_policy.minDurationMs, _policy.maxDurationMs);

    final baseConfidence = event['confidence'] is num
        ? (event['confidence'] as num).toDouble()
        : (source == 'notification' ? 0.9 : 0.72);
    final confidenceBoost =
        (parsedFromRaw?.hadStructuredArgs ?? false) ? 0.05 : 0.0;
    final confidence = (baseConfidence + confidenceBoost).clamp(0.0, 1.0);
    final messageId = (event['discord_message_id']?.toString().trim().isNotEmpty == true)
      ? event['discord_message_id'].toString().trim()
      : (event['message_id']?.toString().trim().isNotEmpty == true)
        ? event['message_id'].toString().trim()
        : null;

    return NotificationCommand(
      source: source,
      packageName: packageName,
      command: command,
      count: count,
      loop: loop,
      repeatCount: repeatCount,
      strength: strength,
      durationMs: durationMs,
      confidence: confidence,
      raw: raw.isNotEmpty ? raw : commandText,
      messageId: messageId,
    );
  }

  String _composeCommandText(Map<String, dynamic> event) {
    final pieces = <String>[];

    void addAny(dynamic value) {
      if (value == null) return;
      if (value is Iterable) {
        for (final item in value) {
          addAny(item);
        }
        return;
      }
      final text = value.toString().trim();
      if (text.isNotEmpty) {
        pieces.add(text);
      }
    }

    addAny(event['raw']);
    addAny(event['title']);
    addAny(event['text']);
    addAny(event['big']);
    addAny(event['sub']);
    addAny(event['summary']);
    addAny(event['content']);
    addAny(event['message']);
    addAny(event['content_description']);
    addAny(event['actions']);
    addAny(event['action_labels']);
    addAny(event['buttons']);
    addAny(event['button_labels']);

    return pieces.join(' ').trim();
  }

  _ParsedRawCommand? _parseRawCommand(String raw) {
    if (raw.isEmpty) return null;
    final tokens = RegExp(r'[a-z0-9%]+')
        .allMatches(raw.toLowerCase())
        .map((m) => m.group(0)!)
        .toList(growable: false);
    if (tokens.isEmpty) return null;

    final buzzIndex = tokens.indexOf('buzz');
    if (buzzIndex >= 0) {
      final seconds = _extractBuzzSeconds(tokens, buzzIndex);
      final loop = tokens.contains('loop');
      final loopCount = loop
          ? (_extractLoopCount(tokens, buzzIndex) ?? (seconds ?? 1))
          : 1;
      final parsedDurationMs = _extractDurationMs(tokens, buzzIndex);
      final durationMs = seconds != null
          ? seconds * 1000
          : parsedDurationMs;
      return _ParsedRawCommand(
        command: 'buzz',
        count: loopCount,
        loop: loop,
        durationMs: durationMs,
        hadStructuredArgs: loop || seconds != null || durationMs != null,
      );
    }

    final zapIndex = tokens.indexOf('zap');
    if (zapIndex >= 0) {
      final strength =
          _extractStrength(tokens, zapIndex) ?? _policy.zapDefaultStrength;
      final loop = tokens.contains('loop');
      final repeatCount = loop
          ? (_extractLoopCount(tokens, zapIndex) ?? 1)
          : 1;
      return _ParsedRawCommand(
        command: 'zap',
        strength: strength,
        repeatCount: repeatCount,
        hadStructuredArgs: strength != _policy.zapDefaultStrength || loop,
      );
    }

    return null;
  }

  int? _extractCount(List<String> tokens, int commandIndex) {
    final candidates = <String?>[
      if (commandIndex + 1 < tokens.length) tokens[commandIndex + 1],
      if (commandIndex + 2 < tokens.length) tokens[commandIndex + 2],
      if (commandIndex - 1 >= 0) tokens[commandIndex - 1],
    ];
    for (final token in candidates) {
      if (token == null) continue;
      if (RegExp(r'^\d{1,2}$').hasMatch(token)) {
        return int.parse(token).clamp(1, 20);
      }
      if (RegExp(r'^x\d{1,2}$').hasMatch(token)) {
        return int.parse(token.substring(1)).clamp(1, 20);
      }
      if (RegExp(r'^\d{1,2}x$').hasMatch(token)) {
        return int.parse(token.substring(0, token.length - 1)).clamp(1, 20);
      }
    }
    return null;
  }

  int? _extractBuzzSeconds(List<String> tokens, int commandIndex) {
    final candidates = <String?>[
      if (commandIndex + 1 < tokens.length) tokens[commandIndex + 1],
      if (commandIndex + 2 < tokens.length) tokens[commandIndex + 2],
      if (commandIndex - 1 >= 0) tokens[commandIndex - 1],
    ];
    for (final token in candidates) {
      if (token == null) continue;
      if (RegExp(r'^\d{1,3}$').hasMatch(token)) {
        return int.parse(token).clamp(1, 300);
      }
      if (RegExp(r'^\d{1,3}s$').hasMatch(token)) {
        return int.parse(token.substring(0, token.length - 1)).clamp(1, 300);
      }
    }
    return null;
  }

  int? _extractLoopCount(List<String> tokens, int commandIndex) {
    final loopIndex = tokens.indexOf('loop');
    if (loopIndex >= 0 && loopIndex + 1 < tokens.length) {
      final n = int.tryParse(tokens[loopIndex + 1]);
      if (n != null) return n.clamp(1, 300);
    }
    return _extractCount(tokens, commandIndex);
  }

  int? _extractStrength(List<String> tokens, int commandIndex) {
    final candidates = <String?>[
      if (commandIndex + 1 < tokens.length) tokens[commandIndex + 1],
      if (commandIndex + 2 < tokens.length) tokens[commandIndex + 2],
      if (commandIndex - 1 >= 0) tokens[commandIndex - 1],
    ];
    for (final token in candidates) {
      if (token == null) continue;
      final normalized =
          token.endsWith('%') ? token.substring(0, token.length - 1) : token;
      final value = int.tryParse(normalized);
      if (value != null) {
        return value.clamp(1, 100);
      }
    }
    return null;
  }

  int? _extractDurationMs(List<String> tokens, int commandIndex) {
    final start = (commandIndex - 2).clamp(0, tokens.length - 1);
    final end = (commandIndex + 4).clamp(0, tokens.length - 1);
    for (var i = start; i <= end; i++) {
      final token = tokens[i];
      if (token.endsWith('ms')) {
        final value = int.tryParse(token.substring(0, token.length - 2));
        if (value != null) return value;
      }
      if (token.endsWith('s')) {
        final value = int.tryParse(token.substring(0, token.length - 1));
        if (value != null) return value * 1000;
      }

      final number = int.tryParse(token);
      if (number == null) continue;
      final next = (i + 1 < tokens.length) ? tokens[i + 1] : null;
      if (next == 'ms' ||
          next == 'millis' ||
          next == 'millisecond' ||
          next == 'milliseconds') {
        return number;
      }
      if (next == 's' ||
          next == 'sec' ||
          next == 'secs' ||
          next == 'second' ||
          next == 'seconds') {
        return number * 1000;
      }
    }
    return null;
  }

  bool _isInCooldown(int nowMs) => nowMs < _cooldownUntilMs;

  bool _isDeduped(NotificationCommand command, int nowMs) {
    final compactRaw = command.raw.trim().toLowerCase().replaceAll(RegExp(r'\s+'), ' ');
    final signature =
        '${command.messageId ?? ''}|${command.packageName}|${command.command}|${command.count}|${command.loop}|${command.repeatCount}|${command.strength}|${command.durationMs}|$compactRaw';
    _recentSignatures.removeWhere(
      (_, seenAt) => nowMs - seenAt > _policy.dedupeWindowMs,
    );
    final last = _recentSignatures[signature];
    if (last != null && nowMs - last < _policy.dedupeWindowMs) {
      return true;
    }
    _recentSignatures[signature] = nowMs;
    if (_recentSignatures.length > _policy.maxDedupSignatures) {
      final firstKey = _recentSignatures.keys.first;
      _recentSignatures.remove(firstKey);
    }
    return false;
  }

  bool _breachesRateLimit(int nowMs) {
    final windowStart = nowMs - 60 * 1000;
    while (_actuationMs.isNotEmpty && _actuationMs.first < windowStart) {
      _actuationMs.removeFirst();
    }
    return _actuationMs.length >= _policy.maxActuationsPerMinute;
  }

  void _markActuation(int nowMs) {
    _actuationMs.addLast(nowMs);
  }
}

class NotificationCommandPolicy {
  NotificationCommandPolicy({
    required this.enabled,
    required this.dryRun,
    required this.minConfidence,
    required this.allowSources,
    required this.packageAllowlist,
    required this.packageBlocklist,
    required this.maxActuationsPerMinute,
    required this.emergencyCooldownSeconds,
    required this.dedupeWindowMs,
    required this.maxDedupSignatures,
    required this.buzzLevel,
    required this.buzzLoopMultiplier,
    required this.buzzMaxQueueAdd,
    required this.buzzGapMs,
    required this.defaultDurationMs,
    required this.minDurationMs,
    required this.maxDurationMs,
    required this.zapDefaultStrength,
    required this.zapMaxStrength,
    required this.allowBuzzWithoutHrData,
    required this.noHrBuzzLevel,
    required this.noHrMaxDurationMs,
    required this.appRules,
  });

  final bool enabled;
  final bool dryRun;
  final double minConfidence;
  final Set<String> allowSources;
  final Set<String> packageAllowlist;
  final Set<String> packageBlocklist;
  final int maxActuationsPerMinute;
  final int emergencyCooldownSeconds;
  final int dedupeWindowMs;
  final int maxDedupSignatures;
  final int buzzLevel;
  final int buzzLoopMultiplier;
  final int buzzMaxQueueAdd;
  final int buzzGapMs;
  final int defaultDurationMs;
  final int minDurationMs;
  final int maxDurationMs;
  final int zapDefaultStrength;
  final int zapMaxStrength;
  final bool allowBuzzWithoutHrData;
  final int noHrBuzzLevel;
  final int noHrMaxDurationMs;
  final Map<String, NotificationCommandAppRule> appRules;

  static NotificationCommandPolicy defaults() => NotificationCommandPolicy(
        enabled: true,
        dryRun: false,
        minConfidence: 0.82,
        allowSources: {'notification', 'screen', 'discord_bot'},
        packageAllowlist: {'com.discord'},
        packageBlocklist: {
          'com.hound.controller',
          'com.google.android.inputmethod.latin',
          'com.samsung.android.honeyboard',
        },
        maxActuationsPerMinute: 12,
        emergencyCooldownSeconds: 60,
        dedupeWindowMs: 20 * 1000,
        maxDedupSignatures: 256,
        buzzLevel: 10,
        buzzLoopMultiplier: 2,
        buzzMaxQueueAdd: 10,
        buzzGapMs: 700,
        defaultDurationMs: 500,
        minDurationMs: 100,
        maxDurationMs: 20 * 1000,
        zapDefaultStrength: 20,
        zapMaxStrength: 20,
        allowBuzzWithoutHrData: true,
        noHrBuzzLevel: 7,
        noHrMaxDurationMs: 1200,
        appRules: <String, NotificationCommandAppRule>{
          'com.discord': NotificationCommandAppRule(
            allowedCommands: {'buzz', 'zap'},
            minConfidence: 0.72,
            strengthScale: 1.0,
            maxDurationMs: 600 * 1000,
            maxLoopCount: 25,
            maxZapStrength: 20,
          ),
          'com.google.android.apps.messaging': NotificationCommandAppRule(
            allowedCommands: {'buzz'},
            minConfidence: 0.9,
            strengthScale: 1.0,
          ),
          'com.whatsapp': NotificationCommandAppRule(
            allowedCommands: {'buzz'},
            minConfidence: 0.9,
            strengthScale: 1.0,
          ),
          'org.telegram.messenger': NotificationCommandAppRule(
            allowedCommands: {'buzz'},
            minConfidence: 0.9,
            strengthScale: 1.0,
          ),
        },
      );

  factory NotificationCommandPolicy.fromMap(Map<String, dynamic> raw) {
    final defaults = NotificationCommandPolicy.defaults();

    Set<String> stringSet(dynamic value) {
      if (value is! List) return <String>{};
      return value
          .map((e) => e.toString().trim().toLowerCase())
          .where((e) => e.isNotEmpty)
          .toSet();
    }

    final appRules = <String, NotificationCommandAppRule>{};
    final rawRules = raw['app_rules'];
    if (rawRules is Map) {
      for (final entry in rawRules.entries) {
        final key = entry.key.toString().trim().toLowerCase();
        final value = entry.value;
        if (key.isEmpty || value is! Map) continue;
        appRules[key] = NotificationCommandAppRule.fromMap(
          Map<String, dynamic>.from(value),
        );
      }
    }

    return NotificationCommandPolicy(
      enabled:
          raw['enabled'] is bool ? raw['enabled'] as bool : defaults.enabled,
      dryRun: raw['dry_run'] is bool ? raw['dry_run'] as bool : defaults.dryRun,
      minConfidence: raw['min_confidence'] is num
          ? (raw['min_confidence'] as num).toDouble().clamp(0.0, 1.0)
          : defaults.minConfidence,
      allowSources: stringSet(raw['allow_sources']).isEmpty
          ? defaults.allowSources
          : stringSet(raw['allow_sources']),
      packageAllowlist: stringSet(raw['package_allowlist']),
      packageBlocklist: stringSet(raw['package_blocklist']),
      maxActuationsPerMinute: raw['max_actuations_per_minute'] is num
          ? (raw['max_actuations_per_minute'] as num).toInt().clamp(1, 240)
          : defaults.maxActuationsPerMinute,
      emergencyCooldownSeconds: raw['emergency_cooldown_seconds'] is num
          ? (raw['emergency_cooldown_seconds'] as num).toInt().clamp(5, 600)
          : defaults.emergencyCooldownSeconds,
      dedupeWindowMs: raw['dedupe_window_ms'] is num
          ? (raw['dedupe_window_ms'] as num).toInt().clamp(500, 120000)
          : defaults.dedupeWindowMs,
      maxDedupSignatures: raw['max_dedup_signatures'] is num
          ? (raw['max_dedup_signatures'] as num).toInt().clamp(16, 1024)
          : defaults.maxDedupSignatures,
      buzzLevel: raw['buzz_level'] is num
          ? (raw['buzz_level'] as num).toInt().clamp(1, 20)
          : defaults.buzzLevel,
      buzzLoopMultiplier: raw['buzz_loop_multiplier'] is num
          ? (raw['buzz_loop_multiplier'] as num).toInt().clamp(1, 20)
          : defaults.buzzLoopMultiplier,
      buzzMaxQueueAdd: raw['buzz_max_queue_add'] is num
          ? (raw['buzz_max_queue_add'] as num).toInt().clamp(1, 200)
          : defaults.buzzMaxQueueAdd,
      buzzGapMs: raw['buzz_gap_ms'] is num
          ? (raw['buzz_gap_ms'] as num).toInt().clamp(50, 10000)
          : defaults.buzzGapMs,
      defaultDurationMs: raw['default_duration_ms'] is num
          ? (raw['default_duration_ms'] as num).toInt().clamp(50, 10000)
          : defaults.defaultDurationMs,
      minDurationMs: raw['min_duration_ms'] is num
          ? (raw['min_duration_ms'] as num).toInt().clamp(10, 2000)
          : defaults.minDurationMs,
      maxDurationMs: raw['max_duration_ms'] is num
          ? (raw['max_duration_ms'] as num).toInt().clamp(100, 600000)
          : defaults.maxDurationMs,
      zapDefaultStrength: raw['zap_default_strength'] is num
          ? (raw['zap_default_strength'] as num).toInt().clamp(1, 100)
          : defaults.zapDefaultStrength,
      zapMaxStrength: raw['zap_max_strength'] is num
          ? (raw['zap_max_strength'] as num).toInt().clamp(1, 100)
          : defaults.zapMaxStrength,
        allowBuzzWithoutHrData: raw['allow_buzz_without_hr_data'] is bool
          ? raw['allow_buzz_without_hr_data'] as bool
          : defaults.allowBuzzWithoutHrData,
        noHrBuzzLevel: raw['no_hr_buzz_level'] is num
          ? (raw['no_hr_buzz_level'] as num).toInt().clamp(1, 20)
          : defaults.noHrBuzzLevel,
        noHrMaxDurationMs: raw['no_hr_max_duration_ms'] is num
          ? (raw['no_hr_max_duration_ms'] as num).toInt().clamp(100, 3000)
          : defaults.noHrMaxDurationMs,
      appRules: appRules,
    );
  }

  NotificationCommandAppRule? ruleForPackage(String packageName) {
    final p = packageName.trim().toLowerCase();
    if (p.isEmpty) return null;
    if (appRules.containsKey(p)) {
      return appRules[p];
    }
    for (final entry in appRules.entries) {
      if (p.contains(entry.key)) {
        return entry.value;
      }
    }
    return null;
  }
}

class NotificationCommandAppRule {
  NotificationCommandAppRule({
    required this.allowedCommands,
    this.minConfidence,
    this.strengthScale = 1.0,
    this.maxDurationMs,
    this.maxLoopCount,
    this.maxZapStrength,
  });

  final Set<String> allowedCommands;
  final double? minConfidence;
  final double strengthScale;
  final int? maxDurationMs;
  final int? maxLoopCount;
  final int? maxZapStrength;

  factory NotificationCommandAppRule.fromMap(Map<String, dynamic> raw) {
    Set<String> commands = <String>{};
    final rawCommands = raw['allowed_commands'];
    if (rawCommands is List) {
      commands = rawCommands
          .map((e) => e.toString().trim().toLowerCase())
          .where((e) => e == 'buzz' || e == 'zap')
          .toSet();
    }
    return NotificationCommandAppRule(
      allowedCommands: commands,
      minConfidence: raw['min_confidence'] is num
          ? (raw['min_confidence'] as num).toDouble().clamp(0.0, 1.0)
          : null,
      strengthScale: raw['strength_scale'] is num
          ? (raw['strength_scale'] as num).toDouble().clamp(0.1, 3.0)
          : 1.0,
        maxDurationMs: raw['max_duration_ms'] is num
          ? (raw['max_duration_ms'] as num).toInt().clamp(100, 600000)
          : null,
        maxLoopCount: raw['max_loop_count'] is num
          ? (raw['max_loop_count'] as num).toInt().clamp(1, 300)
          : null,
        maxZapStrength: raw['max_zap_strength'] is num
          ? (raw['max_zap_strength'] as num).toInt().clamp(1, 100)
          : null,
    );
  }
}

class NotificationCommand {
  NotificationCommand({
    required this.source,
    required this.packageName,
    required this.command,
    required this.count,
    required this.loop,
    required this.repeatCount,
    required this.strength,
    required this.durationMs,
    required this.confidence,
    required this.raw,
    this.messageId,
  });

  final String source;
  final String packageName;
  final String command;
  final int count;
  final bool loop;
  final int repeatCount;
  final int strength;
  final int durationMs;
  final double confidence;
  final String raw;
  final String? messageId;
}

class _ParsedRawCommand {
  _ParsedRawCommand({
    required this.command,
    this.count = 1,
    this.loop = false,
    this.repeatCount = 1,
    this.strength = 64,
    this.durationMs,
    this.hadStructuredArgs = false,
  });

  final String command;
  final int count;
  final bool loop;
  final int repeatCount;
  final int strength;
  final int? durationMs;
  final bool hadStructuredArgs;
}
