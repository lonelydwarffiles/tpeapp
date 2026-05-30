import 'dart:async';

import 'package:flutter/services.dart';

import '../channels/ble_channel.dart';

/// Listens for native accessibility notification events and executes only
/// "buzz" commands detected in notification text.
class NotificationBuzzService {
  NotificationBuzzService._();

  static final NotificationBuzzService instance = NotificationBuzzService._();

  static const EventChannel _events =
      EventChannel('com.example.tpe_app/notification_buzz');

  StreamSubscription<dynamic>? _sub;
  var _running = false;
  var _buzzQueue = 0;

  Future<void> start() async {
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
  }

  void _onNativeEvent(dynamic event) {
    if (event is! Map) {
      return;
    }
    final countRaw = event['count'];
    final count = (countRaw is num ? countRaw.toInt() : 1).clamp(1, 20);
    _buzzQueue += count;
    if (!_running) {
      unawaited(_drainQueue());
    }
  }

  Future<void> _drainQueue() async {
    if (_running) return;
    _running = true;
    try {
      while (_buzzQueue > 0) {
        _buzzQueue--;
        await BleChannel.lovenseVibrateFor(level: 10, durationMs: 500);
        await Future<void>.delayed(const Duration(milliseconds: 600));
      }
    } finally {
      _running = false;
    }
  }
}
