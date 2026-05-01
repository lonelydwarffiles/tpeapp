import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:record/record.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Manages a persistent WebSocket connection to the partner backend.
///
/// Handles two command payloads for the Live Hot Mic feature:
///
/// - `{"command": "START_HOT_MIC"}` — begins streaming live PCM audio chunks
///   as binary WebSocket frames.
/// - `{"command": "STOP_HOT_MIC"}` — stops the audio stream immediately.
///
/// Audio is captured at 16 kHz, mono, 16-bit PCM (lowest-latency format
/// available via the `record` package) so each binary frame is raw PCM data
/// ready for the server to decode without any container overhead.
class WebSocketService {
  WebSocketService(this._prefs);

  final SharedPreferences _prefs;

  WebSocket? _socket;
  StreamSubscription<dynamic>? _socketSub;
  AudioRecorder? _recorder;
  StreamSubscription<List<int>>? _audioSub;
  // Guards against concurrent _startHotMic() calls before _recorder is assigned.
  bool _startingHotMic = false;

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
    final url = '$_wsBaseUrl/ws';
    _socket = await WebSocket.connect(url);
    _socketSub = _socket!.listen(
      _onMessage,
      onError: _onError,
      onDone: _onDone,
    );
  }

  /// Stops any active recording and closes the WebSocket.
  Future<void> disconnect() async {
    await _stopHotMic();
    await _socketSub?.cancel();
    _socketSub = null;
    await _socket?.close();
    _socket = null;
  }

  // ── Incoming message handling ────────────────────────────────────────

  void _onMessage(dynamic data) {
    if (data is! String) return;
    final Map<String, dynamic> payload;
    try {
      payload = jsonDecode(data) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    final command = payload['command'] as String?;
    switch (command) {
      case 'START_HOT_MIC':
        _startHotMic();
      case 'STOP_HOT_MIC':
        _stopHotMic();
    }
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
