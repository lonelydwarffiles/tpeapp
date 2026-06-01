import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;
import 'dart:io';

import 'package:record/record.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'tts_service.dart';

/// Manages a persistent WebSocket connection to the partner backend.
///
/// Handles three command payloads:
///
/// - `{"command": "START_HOT_MIC"}` — begins streaming live PCM audio chunks
///   as binary WebSocket frames.
/// - `{"command": "STOP_HOT_MIC"}` — stops the audio stream immediately.
/// - `{"command": "TTS_COMMAND", "text": "<string>", "force_speaker": <bool>}`
///   — conditionally speaks [text] via [TtsService]:
///   - Always speaks when a Bluetooth or wired headset is connected.
///   - Speaks through the device speaker only when `force_speaker` is `true`.
///   - Emits a silent haptic pulse when no headset is connected and
///     `force_speaker` is `false`.
class WebSocketService {
  WebSocketService(this._prefs);

  static const Duration _initialReconnectDelay = Duration(seconds: 2);
  static const Duration _maxReconnectDelay = Duration(seconds: 30);
  static const Duration _pingInterval = Duration(seconds: 20);
  static const Duration _pongTimeout = Duration(seconds: 60);
  static const int _wsCloseAuthFailed = 4001;
  static const int _wsClosePolicyViolation = 1008;

  final SharedPreferences _prefs;

  WebSocket? _socket;
  StreamSubscription<dynamic>? _socketSub;
  AudioRecorder? _recorder;
  StreamSubscription<List<int>>? _audioSub;
  Timer? _reconnectTimer;
  Timer? _pingTimer;
  Duration _reconnectDelay = _initialReconnectDelay;
  bool _manualDisconnect = false;
  bool _connecting = false;
  bool _closingSocket = false;
  DateTime _lastPongAt = DateTime.fromMillisecondsSinceEpoch(0);
  bool _suspendAutoReconnect = false;
  String _lastCredentialFingerprint = '';
  // Guards against concurrent _startHotMic() calls before _recorder is assigned.
  bool _startingHotMic = false;

  final TtsService _tts = TtsService();
  void Function(Map<String, String> event)? onCommandEvent;

  // ── Connection management ────────────────────────────────────────────

  String get _wsBaseUrl {
    final endpoint =
        (_prefs.getString('partner_endpoint_url') ?? '').trim();
    // Convert http(s):// to ws(s)://
    return endpoint
        .replaceFirst(RegExp(r'^https://'), 'wss://')
        .replaceFirst(RegExp(r'^http://'), 'ws://');
  }

  bool get isConnected => _socket?.readyState == WebSocket.open;

  Future<void> ensureConnected() async {
    if (isConnected || _connecting) return;
    await connect();
  }

  /// Opens the WebSocket connection to `{endpoint}/ws`.
  /// Closes any pre-existing connection first.
  Future<void> connect() async {
    if (_connecting || isConnected) return;
    _manualDisconnect = false;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    if (_wsBaseUrl.isEmpty) {
      developer.log(
        'Skipping websocket connect: partner_endpoint_url is empty',
        name: 'WebSocketService',
      );
      return;
    }
    _connecting = true;
    try {
      await _closeSocket();
      final token = (_prefs.getString('webhook_bearer_token') ?? '').trim();
      final deviceId = (_prefs.getString('device_id') ?? '').trim();
      final credentialFingerprint = '$token|$deviceId';
      if (_suspendAutoReconnect && credentialFingerprint == _lastCredentialFingerprint) {
        developer.log(
          'Skipping websocket connect after auth/policy close until credentials change',
          name: 'WebSocketService',
        );
        return;
      }
      _lastCredentialFingerprint = credentialFingerprint;
      _suspendAutoReconnect = false;
      final query = <String>[];
      if (token.isNotEmpty) query.add('secret=${Uri.encodeQueryComponent(token)}');
      if (deviceId.isNotEmpty) query.add('device_id=${Uri.encodeQueryComponent(deviceId)}');
      final suffix = query.isEmpty ? '' : '?${query.join('&')}';
      final url = '$_wsBaseUrl/ws$suffix';
      developer.log('Connecting websocket: $url', name: 'WebSocketService');
      _socket = await WebSocket.connect(url);
      _socketSub = _socket!.listen(
        _onMessage,
        onError: _onError,
        onDone: _onDone,
      );
      developer.log('Websocket connected', name: 'WebSocketService');
      unawaited(_prefs.setBool('ws_auth_failed', false));
      _lastPongAt = DateTime.now();
      _startPingLoop();
      _reconnectDelay = _initialReconnectDelay;
    } catch (error) {
      developer.log(
        'WebSocket connect failed: $error',
        name: 'WebSocketService',
      );
      _scheduleReconnect();
    } finally {
      _connecting = false;
    }
  }

  /// Stops any active recording, disposes TTS, and closes the WebSocket.
  Future<void> disconnect({bool disposeTts = true}) async {
    _manualDisconnect = true;
    _suspendAutoReconnect = false;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    _pingTimer?.cancel();
    _pingTimer = null;
    await _stopHotMic();
    if (disposeTts) {
      await _tts.dispose();
    }
    await _closeSocket();
  }

  // ── Incoming message handling ────────────────────────────────────────

  void _onMessage(dynamic data) {
    developer.log(
      'Raw incoming WS payload (${data.runtimeType}): $data',
      name: 'WebSocketService',
    );
    if (data is! String) return;
    final Map<String, dynamic> payload;
    try {
      payload = jsonDecode(data) as Map<String, dynamic>;
    } catch (err) {
      developer.log(
        'Failed to parse incoming WS payload: $err',
        name: 'WebSocketService',
      );
      return;
    }
    final command = _commandFromPayload(payload);
    if (command == 'PONG') {
      _lastPongAt = DateTime.now();
      return;
    }
    switch (command) {
      case 'START_HOT_MIC':
        _startHotMic();
      case 'STOP_HOT_MIC':
        _stopHotMic();
      case 'TTS_COMMAND':
        final text = _stringValue(payload, const ['text', 'message', 'content']);
        if (text != null && text.isNotEmpty) {
          final forceSpeaker = _boolValue(
            payload,
            const ['force_speaker', 'forceSpeaker'],
            defaultValue: false,
          );
          unawaited(
            _tts
                .handleCommand(text: text, forceSpeaker: forceSpeaker)
                .catchError((error, stack) {
              developer.log(
                'TTS command failed: $error',
                name: 'WebSocketService',
                stackTrace: stack,
              );
            }),
          );
        }
      default:
        final event = _toCommandEvent(payload);
        if (event != null && onCommandEvent != null) {
          onCommandEvent!.call(event);
          developer.log(
            'Forwarded WS command payload to command pipeline: $event',
            name: 'WebSocketService',
          );
        } else {
          developer.log(
            'Ignoring unsupported WS command: $command',
            name: 'WebSocketService',
          );
        }
    }
  }

  Map<String, String>? _toCommandEvent(Map<String, dynamic> payload) {
    final hasCommandEnvelope =
        payload.containsKey('action') || payload.containsKey('command');
    if (!hasCommandEnvelope) return null;

    final mapped = <String, String>{};
    payload.forEach((key, value) {
      if (value == null) return;
      if (value is String) {
        mapped[key] = value;
      } else if (value is num || value is bool) {
        mapped[key] = value.toString();
      } else {
        mapped[key] = jsonEncode(value);
      }
    });
    return mapped;
  }

  String _commandFromPayload(Map<String, dynamic> payload) {
    final raw = _stringValue(payload, const ['command', 'action', 'event', 'type']) ?? '';
    final normalized = raw.trim().toUpperCase().replaceAll(RegExp(r'[\s\-.]+'), '_');
    switch (normalized) {
      case 'HOT_MIC_START':
        return 'START_HOT_MIC';
      case 'HOT_MIC_STOP':
        return 'STOP_HOT_MIC';
      case 'SPEAK_TEXT':
      case 'TTS':
        return 'TTS_COMMAND';
      default:
        return normalized;
    }
  }

  String? _stringValue(Map<String, dynamic> payload, List<String> keys) {
    for (final key in keys) {
      final value = payload[key];
      if (value is String && value.trim().isNotEmpty) {
        return value.trim();
      }
    }
    return null;
  }

  bool _boolValue(
    Map<String, dynamic> payload,
    List<String> keys, {
    required bool defaultValue,
  }) {
    for (final key in keys) {
      final value = payload[key];
      if (value is bool) return value;
      if (value is String) {
        final normalized = value.trim().toLowerCase();
        if (normalized == 'true' || normalized == '1') return true;
        if (normalized == 'false' || normalized == '0') return false;
      }
      if (value is num) return value != 0;
    }
    return defaultValue;
  }

  void _onError(Object error) {
    developer.log(
      'WebSocket stream error: $error',
      name: 'WebSocketService',
    );
    _stopHotMic();
    if (!_manualDisconnect && !_closingSocket) {
      _scheduleReconnect();
    }
  }

  void _onDone() {
    final closeCode = _socket?.closeCode;
    final closeReason = _socket?.closeReason;
    _stopHotMic();
    _pingTimer?.cancel();
    _pingTimer = null;
    _socket = null;
    _socketSub = null;
    developer.log(
      'WebSocket closed (code=$closeCode reason=$closeReason)',
      name: 'WebSocketService',
    );
    if (closeCode == _wsCloseAuthFailed || closeCode == _wsClosePolicyViolation) {
      unawaited(_prefs.setBool('ws_auth_failed', true));
      _suspendAutoReconnect = true;
      developer.log(
        'Suspending auto-reconnect due to auth/policy close. Update pairing/credentials, then reconnect.',
        name: 'WebSocketService',
      );
      return;
    }
    if (!_manualDisconnect && !_closingSocket) {
      _scheduleReconnect();
    }
  }

  void _scheduleReconnect() {
    if (_manualDisconnect || _connecting) return;
    if (_reconnectTimer != null) return;
    developer.log(
      'Scheduling websocket reconnect in ${_reconnectDelay.inSeconds}s',
      name: 'WebSocketService',
    );
    _reconnectTimer = Timer(_reconnectDelay, () async {
      _reconnectTimer = null;
      await connect();
    });
    final doubled = _reconnectDelay.inMilliseconds * 2;
    _reconnectDelay = Duration(
      milliseconds: doubled > _maxReconnectDelay.inMilliseconds
          ? _maxReconnectDelay.inMilliseconds
          : doubled,
    );
  }

  Future<void> _closeSocket() async {
    _closingSocket = true;
    try {
      _pingTimer?.cancel();
      _pingTimer = null;
      await _socketSub?.cancel();
      _socketSub = null;
      await _socket?.close();
      _socket = null;
    } finally {
      _closingSocket = false;
    }
  }

  void _startPingLoop() {
    _pingTimer?.cancel();
    _pingTimer = Timer.periodic(_pingInterval, (_) {
      final socket = _socket;
      if (socket == null || socket.readyState != WebSocket.open) {
        return;
      }

      final now = DateTime.now();
      if (now.difference(_lastPongAt) > _pongTimeout) {
        developer.log(
          'No pong received within timeout; recycling websocket connection',
          name: 'WebSocketService',
        );
        unawaited(_closeSocket().then((_) {
          if (!_manualDisconnect && !_closingSocket) {
            _scheduleReconnect();
          }
        }));
        return;
      }

      try {
        socket.add(jsonEncode({'type': 'ping', 'ts': now.millisecondsSinceEpoch}));
      } catch (error) {
        developer.log('Failed to send websocket ping: $error', name: 'WebSocketService');
      }
    });
  }

  // ── Live Hot Mic ─────────────────────────────────────────────────────

  Future<void> _startHotMic() async {
    // Guard: do not start a second recorder if one is already running or starting.
    if (_recorder != null || _startingHotMic) return;
    // Guard: require an open socket to send data into.
    if (_socket == null || _socket!.readyState != WebSocket.open) return;

    _startingHotMic = true;
    _recorder = AudioRecorder();

    final hasPermission = await _recorder!.hasPermission();
    if (!hasPermission) {
      await _recorder!.dispose();
      _recorder = null;
      _startingHotMic = false;
      return;
    }

    // Low-latency raw PCM: 16 kHz, mono, 16-bit.  Each chunk is sent as a
    // binary WebSocket frame so the server receives a continuous PCM stream.
    final audioStream = await _recorder!.startStream(
      const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: 16000,
        numChannels: 1,
      ),
    );

    _audioSub = audioStream.listen(
      (chunk) {
        if (_socket != null && _socket!.readyState == WebSocket.open) {
          _socket!.add(chunk);
        }
      },
      onDone: () => _cleanupRecorder(),
      onError: (_) => _cleanupRecorder(),
      cancelOnError: true,
    );
    _startingHotMic = false;
  }

  Future<void> _stopHotMic() async {
    _startingHotMic = false;
    await _audioSub?.cancel();
    _audioSub = null;
    await _recorder?.stop();
    await _recorder?.dispose();
    _recorder = null;
  }

  Future<void> _cleanupRecorder() async {
    _startingHotMic = false;
    await _audioSub?.cancel();
    _audioSub = null;
    await _recorder?.dispose();
    _recorder = null;
  }
}
