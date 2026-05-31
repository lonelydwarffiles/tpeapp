import 'package:flutter/services.dart';

/// Dart client for the `com.hound.controller/device_commands` MethodChannel.
///
/// Maps 1-to-1 to the methods in [DeviceCommandChannel] (Kotlin side).
/// All calls are fire-and-forget â€” they return null on success.
class DeviceCommandChannel {
  DeviceCommandChannel._();

  static const _channel = MethodChannel('com.hound.controller/device_commands');

  static Future<void> openUrl(String url) =>
      _channel.invokeMethod('openUrl', {'url': url});

  /// [level] 0â€“255 display brightness.
  static Future<void> setBrightness(int level) =>
      _channel.invokeMethod('setBrightness', {'level': level});

  static Future<void> screenOn() => _channel.invokeMethod('screenOn');
  static Future<void> screenOff() => _channel.invokeMethod('screenOff');

  /// [ms] screen-off timeout in milliseconds.
  static Future<void> setScreenTimeout(int ms) =>
      _channel.invokeMethod('setScreenTimeout', {'ms': ms});

  /// [stream] one of: music | ring | alarm | notification | system | voice_call
  static Future<void> setVolume(
          {required String stream, required int level, bool max = false}) =>
      _channel.invokeMethod(
          'setVolume', {'stream': stream, 'level': level, 'max': max});

  /// [mode] one of: silent | vibrate | normal
  static Future<void> setRingerMode(String mode) =>
      _channel.invokeMethod('setRingerMode', {'mode': mode});

  static Future<void> speakText(String text) =>
      _channel.invokeMethod('speakText', {'text': text});

  /// Streams and plays an audio clip from [url].
  ///
  /// When [loop] is `true` the clip plays continuously until [stopAudio] is
  /// called. Looping audio uses the alarm audio stream so it mixes alongside
  /// any other concurrently playing media instead of pausing or ducking it.
  static Future<void> playAudio(String url, {bool loop = false}) =>
      _channel.invokeMethod('playAudio', {'url': url, 'loop': loop});

  /// Stops any audio clip currently playing via [playAudio].
  static Future<void> stopAudio() => _channel.invokeMethod('stopAudio');

  static Future<void> lockDevice() => _channel.invokeMethod('lockDevice');
  static Future<void> takeScreenshot() =>
      _channel.invokeMethod('takeScreenshot');

  static Future<void> setFlashlight({required bool on}) =>
      _channel.invokeMethod('setFlashlight', {'on': on});

    static Future<void> getLocation() => _channel.invokeMethod('getLocation');

    /// Requests a one-shot location snapshot from the native host.
    ///
    /// Returns a map with fields like `lat`, `lon`, `accuracy_m`,
    /// `provider`, and `timestamp_ms` when available.
    static Future<Map<String, dynamic>?> getLocationData() async {
        final raw = await _channel.invokeMethod<dynamic>('getLocation');
        if (raw is Map) {
            return raw.map((key, value) => MapEntry(key.toString(), value));
    }
        return null;
    }

    /// Returns a richer automatic status snapshot for heartbeat reporting.
    ///
    /// Includes at least `battery_pct` and may include location fields when
    /// permissions and a recent fix are available.
    static Future<Map<String, dynamic>?> getDeviceSnapshot() async {
        final raw = await _channel.invokeMethod<dynamic>('getDeviceSnapshot');
        if (raw is Map) {
            return raw.map((key, value) => MapEntry(key.toString(), value));
        }
        return null;
    }

        static Future<String?> getStableDeviceId() async {
            final raw = await _channel.invokeMethod<dynamic>('getStableDeviceId');
            final value = raw?.toString().trim() ?? '';
            return value.isEmpty ? null : value;
        }

        static Future<Map<String, dynamic>?> consumePendingSharePayload() async {
            final raw = await _channel.invokeMethod<dynamic>('consumePendingSharePayload');
            if (raw is Map) {
                return raw.map((key, value) => MapEntry(key.toString(), value));
            }
            return null;
        }

  static Future<void> sendNotification({
    required String title,
    required String body,
    String? channelId,
  }) =>
      _channel.invokeMethod(
          'sendNotification', {'title': title, 'body': body, 'channelId': channelId});

  /// [policy] one of: total_silence | priority | alarms_only | all
  static Future<void> setDnd(String policy) =>
      _channel.invokeMethod('setDnd', {'policy': policy});

  /// Sets the device wallpaper.
  ///
  /// Supports a single [url] applied to both home and lock screens (legacy
  /// behaviour) as well as per-surface targeting via [homeUrl] / [lockUrl]
  /// and the [target] selector (`"home"`, `"lock"`, or `"both"` â€” default).
  ///
  /// [homeUrl] defaults to [url] when not supplied; [lockUrl] falls back to
  /// [homeUrl] when [target] is `"both"` or `"lock"` and [lockUrl] is null.
  static Future<void> setWallpaper({
    String? url,
    String? homeUrl,
    String? lockUrl,
    String target = 'both',
  }) =>
      _channel.invokeMethod('setWallpaper', {
        'url': url,
        'homeUrl': homeUrl,
        'lockUrl': lockUrl,
        'target': target,
      });

  static Future<void> showOverlay({
    required String title,
    required String message,
    String? imageUrl,
  }) =>
      _channel.invokeMethod('showOverlay',
          {'title': title, 'message': message, 'imageUrl': imageUrl});

  static Future<void> suspendApp(String packageName) =>
      _channel.invokeMethod('suspendApp', {'packageName': packageName});

  static Future<void> unsuspendApp(String packageName) =>
      _channel.invokeMethod('unsuspendApp', {'packageName': packageName});

  static Future<void> openAppByName(String appName) =>
      _channel.invokeMethod('openAppByName', {'appName': appName});

  static Future<void> forceStopAppByName(String appName) =>
      _channel.invokeMethod('forceStopAppByName', {'appName': appName});

  static Future<void> disableAppByName(String appName) =>
      _channel.invokeMethod('disableAppByName', {'appName': appName});

  static Future<void> enableAppByName(String appName) =>
      _channel.invokeMethod('enableAppByName', {'appName': appName});

  static Future<void> clearAppCacheByName(String appName) =>
      _channel.invokeMethod('clearAppCacheByName', {'appName': appName});

  static Future<void> uninstallAppByName(String appName) =>
      _channel.invokeMethod('uninstallAppByName', {'appName': appName});

  static Future<void> setClipboardText(String text) =>
      _channel.invokeMethod('setClipboard', {'text': text});

  static Future<void> openHandlerChat({String threadId = 'default'}) =>
      _channel.invokeMethod('openHandlerChat', {'threadId': threadId});

    static Future<void> uploadAppInventory({
        String? pollId,
        bool includeSystem = true,
        bool fullSnapshot = true,
        String source = 'ws_fallback',
    }) =>
            _channel.invokeMethod('uploadAppInventory', {
                'pollId': pollId,
                'includeSystem': includeSystem,
                'fullSnapshot': fullSnapshot,
                'source': source,
            });

    static Future<void> setVpnPolicy({
        String? vpnPolicyJson,
        String? providerMode,
    }) =>
            _channel.invokeMethod('setVpnPolicy', {
                'vpnPolicyJson': vpnPolicyJson,
                'providerMode': providerMode,
            });

    static Future<void> setVpnProviderProfile({
        String? providerMode,
        String? vpnProfileId,
        String? vpnPolicyJson,
    }) =>
            _channel.invokeMethod('setVpnProviderProfile', {
                'providerMode': providerMode,
                'vpnProfileId': vpnProfileId,
                'vpnPolicyJson': vpnPolicyJson,
            });

    static Future<void> vpnConnect() => _channel.invokeMethod('vpnConnect');

    static Future<void> vpnDisconnect() => _channel.invokeMethod('vpnDisconnect');

    static Future<Map<String, dynamic>?> getVpnStatus() async {
        final raw = await _channel.invokeMethod<dynamic>('getVpnStatus');
        if (raw is Map) {
            return raw.map((key, value) => MapEntry(key.toString(), value));
        }
        return null;
    }
}

