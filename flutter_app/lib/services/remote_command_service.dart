import 'package:shared_preferences/shared_preferences.dart';

import '../channels/remote_control_channel.dart';
import '../channels/screen_share_channel.dart';
import '../models/remote_command.dart';
import 'api_service.dart';
import 'screen_share_service.dart';

typedef CheckInRequestHandler = Future<void> Function();
typedef CommandMessageHandler = void Function(String message);

class RemoteCommandService {
  RemoteCommandService({
    required SharedPreferences prefs,
    required CheckInRequestHandler onCheckInRequested,
    CommandMessageHandler? onMessage,
    ScreenShareService? screenShareService,
  })  : _api = ApiService(prefs),
        _onCheckInRequested = onCheckInRequested,
        _onMessage = onMessage,
        _screenShare = screenShareService ?? ScreenShareService();

  final ApiService _api;
  final ScreenShareService _screenShare;
  final CheckInRequestHandler _onCheckInRequested;
  final CommandMessageHandler? _onMessage;

  Future<void> handleEvent(Map<String, String> event) async {
    final command = RemoteCommand.fromEvent(event);
    if (command == null) return;

    await _ack(command, status: 'RECEIVED');

    try {
      await _ack(command, status: 'RUNNING');
      switch (command.action) {
        case 'puppy.checkin.request':
          await _onCheckInRequested();
          break;
        case 'screen.share.start':
          await _handleScreenShareStart(command);
          break;
        case 'screen.share.stop':
          await _handleScreenShareStop(command);
          break;
        case 'screen.input.lock.set':
          await _handleTouchLock(command);
          break;
        case 'screen.input.mode.set':
          await _handleInjectionMode(command);
          break;
        default:
          await _ack(
            command,
            status: 'REJECTED',
            errorCode: 'unsupported_action',
            errorMessage: 'Unsupported action: ${command.action}',
          );
          return;
      }
      await _ack(command, status: 'SUCCEEDED');
    } catch (e) {
      await _ack(
        command,
        status: 'FAILED',
        errorCode: 'execution_error',
        errorMessage: e.toString(),
      );
      _onMessage?.call('Command failed (${command.action}): $e');
    }
  }

  Future<void> _handleScreenShareStart(RemoteCommand command) async {
    final params = command.params;
    final signalUrl = _requiredString(params, const [
      'signal_url',
      'signaling_url',
      'signalingUrl',
    ]);
    final sessionId = _requiredString(params, const [
      'session_id',
      'sessionId',
    ]);
    final allowRemoteInput = _boolValue(params, const [
      'allow_remote_input',
      'allowRemoteInput',
    ], defaultValue: false);

    final resolvedSignalUrl = signalUrl.endsWith('/')
        ? '$signalUrl$sessionId'
        : '$signalUrl/$sessionId';

    await _screenShare.start(
      signalingUrl: resolvedSignalUrl,
      remoteControlEnabled: allowRemoteInput,
    );
    _onMessage?.call('Screen sharing started for session $sessionId.');
  }

  Future<void> _handleScreenShareStop(RemoteCommand command) async {
    await _screenShare.stop();
    await ScreenShareChannel.setTouchLock(
      enabled: false,
      mode: 'strict',
      allowRemoteInput: false,
      sessionId: command.sessionId,
      ttlSec: 0,
      reason: 'session_stopped',
    );
    _onMessage?.call('Screen sharing stopped.');
  }

  Future<void> _handleTouchLock(RemoteCommand command) async {
    final params = command.params;
    final enabled = _boolValue(params, const ['enabled']);
    final mode = _stringValue(params, const ['mode']) ?? 'strict';
    final allowRemoteInput = _boolValue(params, const [
      'allow_remote_input',
      'allowRemoteInput',
    ], defaultValue: true);
    final ttlSec = _intValue(params, const ['ttl_sec', 'ttlSec']) ?? 0;
    final reason = _stringValue(params, const ['reason']);

    await ScreenShareChannel.setTouchLock(
      enabled: enabled,
      mode: mode,
      allowRemoteInput: allowRemoteInput,
      sessionId: command.sessionId,
      ttlSec: ttlSec,
      reason: reason,
    );

    final state = await ScreenShareChannel.getTouchLockState();
    _onMessage?.call(
      'Touch lock ${state['enabled'] == true ? 'enabled' : 'disabled'} '
      '(${state['mode'] ?? mode}).',
    );
  }

  Future<void> _handleInjectionMode(RemoteCommand command) async {
    final requested = _requiredString(command.params, const ['mode']);
    final mode = switch (requested) {
      'root' => RemoteControlChannel.modeRoot,
      'accessibility' => RemoteControlChannel.modeAccessibility,
      _ => RemoteControlChannel.modeAuto,
    };
    await RemoteControlChannel.setInjectionMode(mode);
    _onMessage?.call('Remote input mode set to $mode.');
  }

  Future<void> _ack(
    RemoteCommand command, {
    required String status,
    String? errorCode,
    String? errorMessage,
  }) async {
    if (!command.hasCommandId) return;
    try {
      await _api.postCommandAck(
        commandId: command.commandId!,
        status: status,
        errorCode: errorCode,
        errorMessage: errorMessage,
        telemetry: {
          'action': command.action,
          if (command.sessionId != null) 'session_id': command.sessionId,
          'source': 'flutter_app',
        },
      );
    } catch (_) {
      // Ack delivery is best-effort; command execution should not crash UI.
    }
  }

  static String _requiredString(Map<String, dynamic> map, List<String> keys) {
    final value = _stringValue(map, keys);
    if (value == null || value.isEmpty) {
      throw ArgumentError('Missing required parameter: ${keys.first}');
    }
    return value;
  }

  static String? _stringValue(Map<String, dynamic> map, List<String> keys) {
    for (final key in keys) {
      final value = map[key];
      if (value is String && value.trim().isNotEmpty) {
        return value.trim();
      }
    }
    return null;
  }

  static bool _boolValue(
    Map<String, dynamic> map,
    List<String> keys, {
    bool defaultValue = false,
  }) {
    for (final key in keys) {
      final value = map[key];
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

  static int? _intValue(Map<String, dynamic> map, List<String> keys) {
    for (final key in keys) {
      final value = map[key];
      if (value is int) return value;
      if (value is num) return value.toInt();
      if (value is String) {
        final parsed = int.tryParse(value.trim());
        if (parsed != null) return parsed;
      }
    }
    return null;
  }
}