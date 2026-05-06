import 'package:audio_session/audio_session.dart';
import 'package:flutter/services.dart';
import 'package:flutter_tts/flutter_tts.dart';

/// Handles conditional Text-to-Speech driven by `TTS_COMMAND` WebSocket payloads.
///
/// Behaviour:
/// - If a Bluetooth or wired headset is connected, always speaks [text].
/// - If no headset is connected and [forceSpeaker] is `true`, speaks through
///   the device speaker.
/// - If no headset is connected and [forceSpeaker] is `false`, skips speaking
///   and fires a light haptic vibration to silently alert the user.
class TtsService {
  TtsService();

  final FlutterTts _tts = FlutterTts();
  bool _ttsInitialised = false;

  // ── Initialisation ──────────────────────────────────────────────────────

  Future<void> _ensureInitialised() async {
    if (_ttsInitialised) return;
    await _tts.setLanguage('en-US');
    await _tts.setSpeechRate(0.5);
    await _tts.setVolume(1.0);
    _ttsInitialised = true;
  }

  // ── Audio-route helpers ─────────────────────────────────────────────────

  /// Returns `true` when a Bluetooth or wired headset is currently routed as
  /// the active audio output.
  Future<bool> _headsetConnected() async {
    final session = await AudioSession.instance;
    final devices = await session.getDevices(includeInputs: false);
    for (final device in devices) {
      final t = device.type;
      if (t == AudioDeviceType.bluetoothA2dp ||
          t == AudioDeviceType.bluetoothLe ||
          t == AudioDeviceType.bluetoothSco ||
          t == AudioDeviceType.wiredHeadset ||
          t == AudioDeviceType.wiredHeadphones ||
          // USB audio covers USB-C headphones and DAC adapters.  A USB
          // speaker would also match, but routing to an external device is
          // intentional in either case.
          t == AudioDeviceType.usbAudio) {
        return true;
      }
    }
    return false;
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

    final headset = await _headsetConnected();

    if (headset || forceSpeaker) {
      await _tts.speak(text);
    } else {
      // No headset and force_speaker is false — emit a silent haptic pulse.
      await HapticFeedback.lightImpact();
    }
  }

  Future<void> dispose() async {
    await _tts.stop();
  }
}
