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

  final SharedPreferences _prefs;

  WebSocket? _socket;
  StreamSubscription<dynamic>? _socketSub;
  AudioRecorder? _recorder;
  StreamSubscription<List<int>>? _audioSub;
  // Guards against concurrent _startHotMic() calls before _recorder is assigned.
  bool _startingHotMic = false;

  final TtsService _tts = TtsService();

  // ── Connection management ────────────────────────────────────────────

  String get _wsBaseUrl {
    final endpoint =
        (_prefs.getString('partner_endpoint_url') ?? '').trim();
    // Convert http(s):// to ws(s)://
    return endpoint
        .replaceFirst(RegExp(r'^https://'), 'wss://')
        .replaceFirst(RegExp(r'^http://'), 'ws://');
  }

  /// Opens the WebSocket connection to `{endpoint}/ws`.
  /// Closes any pre-existing connection first.
  Future<void> connect() async {
    await disconnect();
    if (_wsBaseUrl.isEmpty) {
      developer.log(
        'Skipping websocket connect: partner_endpoint_url is empty',
        name: 'WebSocketService',
      );
      return;
    }
    final token = (_prefs.getString('webhook_bearer_token') ?? '').trim();
    final deviceId = (_prefs.getString('device_id') ?? '').trim();
    final query = <String>[];
    if (token.isNotEmpty) query.add('secret=${Uri.encodeQueryComponent(token)}');
    if (deviceId.isNotEmpty) query.add('device_id=${Uri.encodeQueryComponent(deviceId)}');
    final suffix = query.isEmpty ? '' : '?${query.join('&')}';
    final url = '$_wsBaseUrl/ws$suffix';
    _socket = await WebSocket.connect(url);
    _socketSub = _socket!.listen(
      _onMessage,
      onError: _onError,
      onDone: _onDone,
    );
  }

  /// Stops any active recording, disposes TTS, and closes the WebSocket.
  Future<void> disconnect() async {
    await _stopHotMic();
    await _tts.dispose();
    await _socketSub?.cancel();
    _socketSub = null;
    await _socket?.close();
    _socket = null;
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
          _tts.handleCommand(text: text, forceSpeaker: forceSpeaker);
        }
      default:
        developer.log(
          'Ignoring unsupported WS command: $command',
          name: 'WebSocketService',
        );
    }
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
    _stopHotMic();
  }

  void _onDone() {
    _stopHotMic();
    _socket = null;
    _socketSub = null;
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
