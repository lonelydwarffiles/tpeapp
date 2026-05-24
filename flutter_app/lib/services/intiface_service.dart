import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

/// Manages a WebSocket connection to a local Intiface Central server and
/// implements the Buttplug.io Message Spec v3 protocol over JSON.
///
/// Lifecycle:
/// 1. Call [connect] to open the socket, perform the server handshake, and
///    begin scanning for Bluetooth toys.
/// 2. Once a toy is detected (DeviceAdded), the [deviceIndex] is stored and
///    [setVibration] becomes functional.
/// 3. Call [disconnect] to stop everything cleanly.
///
/// Reconnection: if the socket closes unexpectedly, the service waits for an
/// exponentially increasing delay (up to [_maxReconnectDelay]) before trying
/// again, unless [disconnect] was called intentionally.
class IntifaceService {
  static const String _host = '127.0.0.1';
  static const int _port = 12345;
  static const String _url = 'ws://$_host:$_port';

  static const String _clientName = 'tpeapp';

  /// Buttplug spec version declared to the server.
  static const int _messageVersion = 3;

  static const Duration _initialReconnectDelay = Duration(seconds: 2);
  static const Duration _maxReconnectDelay = Duration(seconds: 30);

  // ── State ────────────────────────────────────────────────────────────

  WebSocket? _socket;
  StreamSubscription<dynamic>? _socketSub;

  int _msgId = 1;
  int? _deviceIndex;

  bool _intentionalDisconnect = false;
  Duration _reconnectDelay = _initialReconnectDelay;
  Timer? _reconnectTimer;

  // ── Public API ───────────────────────────────────────────────────────

  /// The index of the first toy detected via DeviceAdded, or `null` if none.
  int? get deviceIndex => _deviceIndex;

  /// `true` while a WebSocket connection is active and the handshake has
  /// completed successfully.
  bool get isConnected =>
      _socket != null && _socket!.readyState == WebSocket.open;

  /// Opens the WebSocket connection, performs the Buttplug handshake, and
  /// starts scanning for devices.
  ///
  /// Safe to call multiple times: any pre-existing connection is closed first.
  Future<void> connect() async {
    _intentionalDisconnect = false;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;

    await _closeSocket();
    await _openSocket();
  }

  /// Stops vibration (if a device is connected), closes the socket, and
  /// cancels any pending reconnection attempt.
  Future<void> disconnect() async {
    _intentionalDisconnect = true;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;

    // Best-effort: stop vibration before closing.
    if (isConnected && _deviceIndex != null) {
      _sendVibrateCmd(_deviceIndex!, 0.0);
    }

    await _closeSocket();
  }

  /// Sends a VibrateCmd to the stored device with [intensity] clamped to
  /// [0.0, 1.0].  Does nothing if no device has been discovered yet or the
  /// socket is not connected.
  void setVibration(double intensity) {
    final idx = _deviceIndex;
    if (idx == null || !isConnected) return;
    _sendVibrateCmd(idx, intensity.clamp(0.0, 1.0));
  }

  // ── Internal: socket lifecycle ───────────────────────────────────────

  Future<void> _openSocket() async {
    try {
      _socket = await WebSocket.connect(_url);
      _reconnectDelay = _initialReconnectDelay; // reset backoff on success
      _socketSub = _socket!.listen(
        _onMessage,
        onError: _onError,
        onDone: _onDone,
        cancelOnError: false,
      );
      await _handshake();
    } catch (e) {
      _scheduleReconnect();
    }
  }

  Future<void> _closeSocket() async {
    await _socketSub?.cancel();
    _socketSub = null;
    await _socket?.close();
    _socket = null;
  }

  void _scheduleReconnect() {
    if (_intentionalDisconnect) return;

    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(_reconnectDelay, () async {
      if (_intentionalDisconnect) return;
      await _openSocket();
    });

    // Exponential backoff capped at _maxReconnectDelay.
    _reconnectDelay = Duration(
      milliseconds: math.min(
        _reconnectDelay.inMilliseconds * 2,
        _maxReconnectDelay.inMilliseconds,
      ),
    );
  }

  // ── Internal: protocol ───────────────────────────────────────────────

  /// Sends RequestServerInfo and waits for ServerInfo, then triggers scanning.
  Future<void> _handshake() async {
    _send([
      {
        'RequestServerInfo': {
          'Id': _nextId(),
          'ClientName': _clientName,
          'MessageVersion': _messageVersion,
        },
      },
    ]);
    // StartScanning is sent when ServerInfo is received in _onMessage.
  }

  void _startScanning() {
    _send([
      {
        'StartScanning': {'Id': _nextId()},
      },
    ]);
  }

  void _sendVibrateCmd(int deviceIndex, double speed) {
    _send([
      {
        'VibrateCmd': {
          'Id': _nextId(),
          'DeviceIndex': deviceIndex,
          'Speeds': [
            {'Index': 0, 'Speed': speed},
          ],
        },
      },
    ]);
  }

  void _send(List<Map<String, dynamic>> messages) {
    final socket = _socket;
    if (socket == null || socket.readyState != WebSocket.open) return;
    socket.add(jsonEncode(messages));
  }

  int _nextId() => _msgId++;

  // ── Internal: message handling ───────────────────────────────────────

  void _onMessage(dynamic data) {
    if (data is! String) return;

    final List<dynamic> messages;
    try {
      messages = jsonDecode(data) as List<dynamic>;
    } catch (_) {
      return;
    }

    for (final raw in messages) {
      if (raw is! Map<String, dynamic>) continue;
      _handleMessage(raw);
    }
  }

  void _handleMessage(Map<String, dynamic> envelope) {
    final type = envelope.keys.firstOrNull;
    if (type == null) return;

    final body = envelope[type];
    if (body is! Map<String, dynamic>) return;

    switch (type) {
      case 'ServerInfo':
        // Handshake complete — begin scanning for BLE toys.
        _startScanning();

      case 'DeviceAdded':
        // Store the index of the first toy found; ignore subsequent devices.
        _deviceIndex ??= body['DeviceIndex'] as int?;

      case 'DeviceRemoved':
        final removedIndex = body['DeviceIndex'] as int?;
        if (removedIndex != null && removedIndex == _deviceIndex) {
          _deviceIndex = null;
        }

      case 'Error':
        // Non-fatal server errors are silently ignored; the connection stays
        // open.  Fatal transport errors are handled in _onError / _onDone.
        break;
    }
  }

  void _onError(Object error) {
    _closeSocket().then((_) => _scheduleReconnect());
  }

  void _onDone() {
    _socket = null;
    _socketSub = null;
    _scheduleReconnect();
  }
}
