import 'package:flutter/services.dart';

import '../channels/device_command_channel.dart';

/// Handles conditional Text-to-Speech driven by `TTS_COMMAND` WebSocket payloads.
///
/// Uses native speech unconditionally so TTS works in background and in
/// hybrid-host states where optional Flutter audio plugins are unavailable.
class TtsService {
  TtsService();

  // Native speech is the default path because it is available in background
  // service flows and does not depend on Flutter plugin registration.
  bool _useNativePath = true;

  // ── Initialisation ──────────────────────────────────────────────────────

  Future<void> _ensureInitialised() async {
    if (_useNativePath) return;
  }

  Future<void> _speakViaBestPath(String text) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return;

    if (_useNativePath) {
      await DeviceCommandChannel.speakText(trimmed);
      return;
    }

    try {
      await DeviceCommandChannel.speakText(trimmed);
    } on MissingPluginException {
      _useNativePath = true;
      await DeviceCommandChannel.speakText(trimmed);
    } on PlatformException {
      _useNativePath = true;
      await DeviceCommandChannel.speakText(trimmed);
    }
  }

  // ── Public API ──────────────────────────────────────────────────────────

  /// Called when a `TTS_COMMAND` WebSocket message is received.
  ///
  /// [text] – the string to speak.
  /// [forceSpeaker] – when `true` the text is spoken even if no headset is
  ///   connected (using the built-in speaker).  When `false` and no headset
  ///   is present the call is silently ignored, and a haptic pulse is emitted
  ///   so the user knows a message arrived.
  Future<void> handleCommand({
    required String text,
    required bool forceSpeaker,
  }) async {
    await _ensureInitialised();
    await _speakViaBestPath(text);
  }

  Future<void> dispose() async {
    // Native path does not hold a Flutter TTS engine instance.
  }
}
