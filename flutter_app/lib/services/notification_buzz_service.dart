import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:developer' as developer;

import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/ble_channel.dart';
import 'api_service.dart';

/// Listens for native accessibility notification events and executes only
/// notification commands detected in notification text.
class NotificationBuzzService {
  NotificationBuzzService._();

  static final NotificationBuzzService instance = NotificationBuzzService._();

  static const EventChannel _events =
      EventChannel('com.hound.controller/notification_buzz');
  static const _policyPrefsKey = 'notification_command_policy_json';
  static const _policySyncInterval = Duration(minutes: 5);

  StreamSubscription<dynamic>? _sub;
  Timer? _policySyncTimer;
  var _running = false;
  final Queue<int> _buzzDurationsMs = Queue<int>();
  final Queue<int> _actuationMs = Queue<int>();
  final Map<String, int> _recentSignatures = <String, int>{};

  SharedPreferences? _prefs;
  ApiService? _api;
  NotificationCommandPolicy _policy = NotificationCommandPolicy.defaults();
  int _cooldownUntilMs = 0;

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

    if (!_policy.enabled) {
      return;
    }
    if (!_policy.allowSources.contains(parsed.source)) {
      return;
    }
    if (_policy.packageAllowlist.isNotEmpty &&
        !_policy.packageAllowlist.contains(parsed.packageName)) {
      return;
    }
    if (_policy.packageBlocklist.contains(parsed.packageName)) {
      return;
    }

    final appRule = _policy.ruleForPackage(parsed.packageName);
    final minConfidence = appRule?.minConfidence ?? _policy.minConfidence;
    if (parsed.confidence < minConfidence) {
      return;
    }
    if (appRule != null &&
        appRule.allowedCommands.isNotEmpty &&
        !appRule.allowedCommands.contains(parsed.command)) {
      return;
    }

    final nowMs = DateTime.now().millisecondsSinceEpoch;
    if (_isInCooldown(nowMs)) {
      return;
    }
    if (_isDeduped(parsed, nowMs)) {
      return;
    }

    if (_policy.dryRun) {
      developer.log(
        'notification-command dry-run: ${parsed.command} from ${parsed.packageName} '
        'source=${parsed.source} confidence=${parsed.confidence.toStringAsFixed(2)} raw="${parsed.raw}"',
        name: 'NotificationBuzzService',
      );
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

    if (parsed.command == 'zap') {
      final scaledStrength =
          (parsed.strength * (appRule?.strengthScale ?? 1.0)).round();
      final strength = scaledStrength.clamp(1, _policy.zapMaxStrength);
      final intensity = ((strength / 100) * 255).round().clamp(1, 255);
      final durationMs =
          parsed.durationMs.clamp(_policy.minDurationMs, _policy.maxDurationMs);
      unawaited(
          BleChannel.pavlokZap(intensity: intensity, durationMs: durationMs));
      return;
    }

    final loopMultiplier = parsed.loop ? _policy.buzzLoopMultiplier : 1;
    final effectiveCount =
        (parsed.count * loopMultiplier).clamp(1, _policy.buzzMaxQueueAdd);
    final durationMs =
        parsed.durationMs.clamp(_policy.minDurationMs, _policy.maxDurationMs);
    for (var i = 0; i < effectiveCount; i++) {
      _buzzDurationsMs.add(durationMs);
    }
    if (!_running) {
      unawaited(_drainQueue());
    }
  }

  Future<void> _drainQueue() async {
    if (_running) return;
    _running = true;
    try {
      while (_buzzDurationsMs.isNotEmpty) {
        final durationMs = _buzzDurationsMs.removeFirst();
        await BleChannel.lovenseVibrateFor(
          level: _policy.buzzLevel,
          durationMs: durationMs,
        );
        await Future<void>.delayed(Duration(milliseconds: _policy.buzzGapMs));
      }
    } finally {
      _running = false;
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
    final parsedFromRaw = _parseRawCommand(raw);

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

    final count = parsedFromRaw?.count ??
        (event['count'] is num
            ? (event['count'] as num).toInt().clamp(1, 20)
            : 1);
    final loop = parsedFromRaw?.loop ?? (event['loop'] == true);
    final strength = parsedFromRaw?.strength ??
        (event['strength'] is num
            ? (event['strength'] as num).toInt().clamp(1, 100)
            : _policy.zapDefaultStrength);
    final durationMs = (parsedFromRaw?.durationMs ?? fallbackDuration)
        .clamp(_policy.minDurationMs, _policy.maxDurationMs);

    final baseConfidence = event['confidence'] is num
        ? (event['confidence'] as num).toDouble()
        : (source == 'notification' ? 0.9 : 0.72);
    final confidenceBoost =
        (parsedFromRaw?.hadStructuredArgs ?? false) ? 0.05 : 0.0;
    final confidence = (baseConfidence + confidenceBoost).clamp(0.0, 1.0);

    return NotificationCommand(
      source: source,
      packageName: packageName,
      command: command,
      count: count,
      loop: loop,
      strength: strength,
      durationMs: durationMs,
      confidence: confidence,
      raw: raw,
    );
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
      final count = _extractCount(tokens, buzzIndex) ?? 1;
      final loop = tokens.contains('loop');
      final durationMs = _extractDurationMs(tokens, buzzIndex);
      return _ParsedRawCommand(
        command: 'buzz',
        count: count,
        loop: loop,
        durationMs: durationMs,
        hadStructuredArgs: loop || count > 1 || durationMs != null,
      );
    }

    final zapIndex = tokens.indexOf('zap');
    if (zapIndex >= 0) {
      final strength =
          _extractStrength(tokens, zapIndex) ?? _policy.zapDefaultStrength;
      final durationMs = _extractDurationMs(tokens, zapIndex);
      return _ParsedRawCommand(
        command: 'zap',
        strength: strength,
        durationMs: durationMs,
        hadStructuredArgs:
            strength != _policy.zapDefaultStrength || durationMs != null,
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
    final signature =
        '${command.source}|${command.packageName}|${command.command}|${command.count}|${command.loop}|${command.strength}|${command.durationMs}';
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
  final Map<String, NotificationCommandAppRule> appRules;

  static NotificationCommandPolicy defaults() => NotificationCommandPolicy(
        enabled: true,
        dryRun: false,
        minConfidence: 0.82,
        allowSources: {'notification', 'screen'},
        packageAllowlist: <String>{},
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
        buzzMaxQueueAdd: 24,
        buzzGapMs: 700,
        defaultDurationMs: 500,
        minDurationMs: 100,
        maxDurationMs: 4 * 1000,
        zapDefaultStrength: 40,
        zapMaxStrength: 80,
        appRules: <String, NotificationCommandAppRule>{
          'com.discord': NotificationCommandAppRule(
            allowedCommands: {'buzz', 'zap'},
            minConfidence: 0.72,
            strengthScale: 1.0,
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

    Set<String> _stringSet(dynamic value) {
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
      allowSources: _stringSet(raw['allow_sources']).isEmpty
          ? defaults.allowSources
          : _stringSet(raw['allow_sources']),
      packageAllowlist: _stringSet(raw['package_allowlist']),
      packageBlocklist: _stringSet(raw['package_blocklist']),
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
          ? (raw['max_duration_ms'] as num).toInt().clamp(100, 30000)
          : defaults.maxDurationMs,
      zapDefaultStrength: raw['zap_default_strength'] is num
          ? (raw['zap_default_strength'] as num).toInt().clamp(1, 100)
          : defaults.zapDefaultStrength,
      zapMaxStrength: raw['zap_max_strength'] is num
          ? (raw['zap_max_strength'] as num).toInt().clamp(1, 100)
          : defaults.zapMaxStrength,
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
  });

  final Set<String> allowedCommands;
  final double? minConfidence;
  final double strengthScale;

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
    required this.strength,
    required this.durationMs,
    required this.confidence,
    required this.raw,
  });

  final String source;
  final String packageName;
  final String command;
  final int count;
  final bool loop;
  final int strength;
  final int durationMs;
  final double confidence;
  final String raw;
}

class _ParsedRawCommand {
  _ParsedRawCommand({
    required this.command,
    this.count = 1,
    this.loop = false,
    this.strength = 64,
    this.durationMs,
    this.hadStructuredArgs = false,
  });

  final String command;
  final int count;
  final bool loop;
  final int strength;
  final int? durationMs;
  final bool hadStructuredArgs;
}
