import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;

import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/services.dart';

import '../channels/ble_channel.dart';
import '../channels/device_admin_channel.dart';
import '../channels/device_command_channel.dart';
import '../channels/filter_service_channel.dart';
import '../channels/password_vault_channel.dart';
import '../channels/remote_control_channel.dart';
import '../channels/screen_share_channel.dart';
import '../models/remote_command.dart';
import 'api_service.dart';
import 'device_file_access_service.dart';
import 'intiface_service.dart';
import 'notification_buzz_service.dart';
import 'screen_share_service.dart';

typedef CheckInRequestHandler = Future<void> Function();
typedef CommandMessageHandler = void Function(String message);

class RemoteCommandService {
  static const _kRemoteControlConsentGranted =
      'screen_share_remote_control_consent_granted';
  static const _orgasmPermissionTokensKey = 'home_orgasm_permission_tokens';
  static const _orgasmDeniedCyclesKey = 'home_orgasm_denied_cycles';
  static const _edgeSafetyProfileKey = 'edge_safety_profile';
  static const _edgeBypassCooldownUntilMsKey = 'edge_bypass_cooldown_until_ms';
  static const _edgeTimelineKey = 'edge_timeline_events_json';
  static const int _edgeTimelineRetentionMs = 7 * 24 * 60 * 60 * 1000;
  static const int _edgeTimelineMaxEvents = 1200;
  static const int _edgeBypassCooldownMs = 2 * 60 * 1000;

  RemoteCommandService({
    required SharedPreferences prefs,
    required CheckInRequestHandler onCheckInRequested,
    CommandMessageHandler? onMessage,
    ScreenShareService? screenShareService,
  })  : _api = ApiService(prefs),
        _prefs = prefs,
        _onCheckInRequested = onCheckInRequested,
        _onMessage = onMessage,
        _screenShare = screenShareService ?? ScreenShareService(),
        _fileAccess = DeviceFileAccessService(prefs);

  final ApiService _api;
  final SharedPreferences _prefs;
  final ScreenShareService _screenShare;
  final DeviceFileAccessService _fileAccess;
  final CheckInRequestHandler _onCheckInRequested;
  final CommandMessageHandler? _onMessage;
  static const MethodChannel _textReplacementChannel =
      MethodChannel('com.hound.controller/text_replacement');
  Map<String, dynamic>? _lastTelemetry;
  final IntifaceService _intiface = IntifaceService();
  Timer? _toyPatternTimer;
  DateTime? _toyPatternEndsAt;
  int _toyPatternTick = 0;

  Future<void> handleEvent(Map<String, String> event) async {
    developer.log('MQTT raw payload: $event', name: 'RemoteCommandService');
    final command = RemoteCommand.fromEvent(event);
    if (command == null) return;
    _lastTelemetry = null;

    await _emitBehavior(
      event: 'remote_command_received',
      reason: command.action,
      payload: {
        if (command.commandId != null) 'command_id': command.commandId,
        if (command.sessionId != null) 'session_id': command.sessionId,
      },
    );

    await _ack(command, status: 'RECEIVED');

    try {
      await _ack(command, status: 'RUNNING');
      switch (command.action) {
        case 'native.handled':
          await _handleNativeFallback(command);
          break;
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
        case 'vault.entries.list':
          _lastTelemetry = await _handleVaultEntriesList();
          break;
        case 'vault.entry.add':
          _lastTelemetry = await _handleVaultEntryAdd(command);
          break;
        case 'vault.entry.update':
          _lastTelemetry = await _handleVaultEntryUpdate(command);
          break;
        case 'vault.entry.delete':
          _lastTelemetry = await _handleVaultEntryDelete(command);
          break;
        case 'vault.entry.lock':
          _lastTelemetry = await _handleVaultEntryLock(command);
          break;
        case 'vault.entries.lock_all':
          _lastTelemetry = await _handleVaultLockAll(command);
          break;
        case 'vault.entries.import':
          _lastTelemetry = await _handleVaultImport(command);
          break;
        case 'vault.password.reveal':
          _lastTelemetry = await _handleVaultReveal(command);
          break;
        case 'device.file.read':
          _lastTelemetry = await _handleDeviceFileRead(command);
          break;
        case 'device.file.write':
          _lastTelemetry = await _handleDeviceFileWrite(command);
          break;
        case 'device.file.delete':
          _lastTelemetry = await _handleDeviceFileDelete(command);
          break;
        case 'toy.live.control':
          _lastTelemetry = await _handleToyLiveControl(command);
          break;
        case 'toy.lovense.command':
          _lastTelemetry = await _handleLovenseCommand(command);
          break;
        case 'toy.pavlok.command':
          _lastTelemetry = await _handlePavlokCommand(command);
          break;
        case 'orgasm.permission.grant':
          _lastTelemetry = await _handleOrgasmPermissionGrant(command);
          break;
        case 'orgasm.permission.revoke':
          _lastTelemetry = await _handleOrgasmPermissionRevoke(command);
          break;
        case 'edge.safety_profile.set':
          _lastTelemetry = await _handleEdgeSafetyProfileSet(command);
          break;
        case 'edge.bypass_cooldown.arm':
          _lastTelemetry = await _handleEdgeBypassCooldownArm(command);
          break;
        case 'edge.bypass_cooldown.clear':
          _lastTelemetry = await _handleEdgeBypassCooldownClear(command);
          break;
        case 'discord.bot.notification_command':
          _lastTelemetry = await _handleDiscordBotNotificationCommand(command);
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
      await _emitBehavior(
        event: 'remote_command_succeeded',
        reason: command.action,
        payload: {
          if (command.commandId != null) 'command_id': command.commandId,
          if (command.sessionId != null) 'session_id': command.sessionId,
          if (_lastTelemetry != null) 'telemetry': _lastTelemetry,
        },
      );
    } catch (e) {
      await _ack(
        command,
        status: 'FAILED',
        errorCode: 'execution_error',
        errorMessage: e.toString(),
      );
      await _emitBehavior(
        event: 'remote_command_failed',
        reason: command.action,
        payload: {
          if (command.commandId != null) 'command_id': command.commandId,
          if (command.sessionId != null) 'session_id': command.sessionId,
          'error': e.toString(),
        },
      );
      _onMessage?.call('Command failed (${command.action}): $e');
    }
  }

  Future<void> _handleNativeFallback(RemoteCommand command) async {
    final legacyAction = (command.raw['action'] ?? '').trim().toUpperCase();
    switch (legacyAction) {
      case 'LOCK_DEVICE':
        var locked = false;
        try {
          await DeviceCommandChannel.lockDevice();
          locked = true;
        } catch (_) {
          locked = await DeviceAdminChannel.lockNow();
        }
        if (!locked) {
          throw StateError(
            'LOCK_DEVICE failed. Ensure device-admin is enabled or root is available.',
          );
        }
        _onMessage?.call('Device lock command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SET_BRIGHTNESS':
        final brightness = _intValue(command.params, const ['value', 'level']);
        if (brightness == null) {
          throw StateError('SET_BRIGHTNESS missing required value parameter.');
        }
        await DeviceCommandChannel.setBrightness(brightness.clamp(0, 255));
        _onMessage?.call('Brightness command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SCREEN_OFF':
        await DeviceCommandChannel.screenOff();
        _onMessage?.call('Screen off command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SCREEN_ON':
        await DeviceCommandChannel.screenOn();
        _onMessage?.call('Screen on command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SET_SCREEN_TIMEOUT':
        final timeoutMs = _intValue(command.params, const ['ms', 'timeout_ms', 'timeoutMs']);
        if (timeoutMs == null) {
          throw StateError('SET_SCREEN_TIMEOUT missing required ms parameter.');
        }
        await DeviceCommandChannel.setScreenTimeout(timeoutMs.clamp(1000, 86400000));
        _onMessage?.call('Screen timeout command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'OPEN_URL':
        final url = _stringValue(command.params, const ['url']);
        if (url == null || url.isEmpty) {
          throw StateError('OPEN_URL missing required url parameter.');
        }
        await DeviceCommandChannel.openUrl(url);
        _onMessage?.call('Open URL command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SET_VOLUME':
        final streamRaw = (_stringValue(command.params, const ['stream']) ?? 'music').toLowerCase();
        final stream = switch (streamRaw) {
          'media' => 'music',
          'call' => 'voice_call',
          _ => streamRaw,
        };
        final max = _boolValue(command.params, const ['max'], defaultValue: false);
        final level = _intValue(command.params, const ['level']) ?? 50;
        await DeviceCommandChannel.setVolume(stream: stream, level: level.clamp(0, 100), max: max);
        _onMessage?.call('Volume command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SET_RINGER_MODE':
        final mode = (_stringValue(command.params, const ['mode']) ?? 'normal').toLowerCase();
        await DeviceCommandChannel.setRingerMode(mode);
        _onMessage?.call('Ringer mode command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SPEAK_TEXT':
        final text = _requiredString(command.params, const ['text']);
        await DeviceCommandChannel.speakText(text);
        _onMessage?.call('Speak text command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'PLAY_AUDIO':
        final url = _requiredString(command.params, const ['url']);
        final loop = _boolValue(command.params, const ['loop'], defaultValue: false);
        await DeviceCommandChannel.playAudio(url, loop: loop);
        _onMessage?.call('Play audio command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'STOP_AUDIO':
        await DeviceCommandChannel.stopAudio();
        _onMessage?.call('Stop audio command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'TAKE_SCREENSHOT':
        await DeviceCommandChannel.takeScreenshot();
        _onMessage?.call('Take screenshot command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SET_FLASHLIGHT':
        final on = _boolValue(command.params, const ['enabled', 'on']);
        await DeviceCommandChannel.setFlashlight(on: on);
        _onMessage?.call('Flashlight command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'GET_LOCATION':
        final location = await DeviceCommandChannel.getLocationData();
        _onMessage?.call('Get location command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          if (location != null) 'location': location,
        };
        break;
      case 'UPDATE_TONE_COMPLIANCE':
        final enabled = _boolValue(
          command.params,
          const ['strict_tone_mode', 'strictToneMode', 'strict', 'enabled'],
          defaultValue: true,
        );
        await FilterServiceChannel.setStrictMode(enabled: enabled);
        _onMessage?.call('Tone compliance updated.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'strict_tone_mode': enabled,
        };
        break;
      case 'SEND_NOTIFICATION':
        final title = _stringValue(command.params, const ['title']) ?? 'Handler Notice';
        final body = _stringValue(command.params, const ['body', 'message']) ?? 'New command received.';
        final channelId = _stringValue(command.params, const ['channel_id', 'channelId']);
        await DeviceCommandChannel.sendNotification(
          title: title,
          body: body,
          channelId: channelId,
        );
        _onMessage?.call('Notification command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SET_DND':
        final rawPolicy = (_stringValue(command.params, const ['policy']) ?? 'all').toLowerCase();
        final policy = switch (rawPolicy) {
          'none' => 'total_silence',
          'alarms' => 'alarms_only',
          _ => rawPolicy,
        };
        await DeviceCommandChannel.setDnd(policy);
        _onMessage?.call('DND command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SET_WALLPAPER':
        final target = (_stringValue(command.params, const ['target']) ?? 'both').toLowerCase();
        final url = _stringValue(command.params, const ['url']);
        final homeUrl = _stringValue(command.params, const ['home_url', 'homeUrl']) ?? url;
        final lockUrl = _stringValue(command.params, const ['lock_url', 'lockUrl']);
        if ((homeUrl == null || homeUrl.isEmpty) && (lockUrl == null || lockUrl.isEmpty)) {
          throw StateError('SET_WALLPAPER missing required url/home_url/lock_url parameter.');
        }
        await DeviceCommandChannel.setWallpaper(
          url: url,
          homeUrl: homeUrl,
          lockUrl: lockUrl,
          target: target,
        );
        _onMessage?.call('Wallpaper command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SHOW_OVERLAY':
        final title = _stringValue(command.params, const ['title']) ?? 'Handler Notice';
        final message = _stringValue(command.params, const ['message', 'body']) ?? '';
        final imageUrl = _stringValue(command.params, const ['image_url', 'imageUrl']);
        await DeviceCommandChannel.showOverlay(
          title: title,
          message: message,
          imageUrl: imageUrl,
        );
        _onMessage?.call('Overlay command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'OPEN_APP':
        final appName = _requiredString(command.params, const ['app_name', 'package_name', 'packageName']);
        await DeviceCommandChannel.openAppByName(appName);
        _onMessage?.call('Open app command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'app_name': appName,
        };
        break;
      case 'FORCE_STOP_APP':
        final appName = _requiredString(command.params, const ['app_name', 'package_name', 'packageName']);
        await DeviceCommandChannel.forceStopAppByName(appName);
        _onMessage?.call('Force stop app command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'app_name': appName,
        };
        break;
      case 'DISABLE_APP':
        final appName = _requiredString(command.params, const ['app_name', 'package_name', 'packageName']);
        await DeviceCommandChannel.disableAppByName(appName);
        _onMessage?.call('Disable app command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'app_name': appName,
        };
        break;
      case 'ENABLE_APP':
        final appName = _requiredString(command.params, const ['app_name', 'package_name', 'packageName']);
        await DeviceCommandChannel.enableAppByName(appName);
        _onMessage?.call('Enable app command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'app_name': appName,
        };
        break;
      case 'CLEAR_APP_CACHE':
        final appName = _requiredString(command.params, const ['app_name', 'package_name', 'packageName']);
        await DeviceCommandChannel.clearAppCacheByName(appName);
        _onMessage?.call('Clear app cache command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'app_name': appName,
        };
        break;
      case 'UNINSTALL_APP':
        final appName = _requiredString(command.params, const ['app_name', 'package_name', 'packageName']);
        await DeviceCommandChannel.uninstallAppByName(appName);
        _onMessage?.call('Uninstall app command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'app_name': appName,
        };
        break;
      case 'SUSPEND_APP':
        final packageName = _stringValue(command.params, const ['package_name', 'packageName']);
        if (packageName == null || packageName.isEmpty) {
          throw StateError('SUSPEND_APP missing required package_name parameter.');
        }
        await DeviceCommandChannel.suspendApp(packageName);
        _onMessage?.call('Suspend app command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'UNSUSPEND_APP':
        final packageName = _stringValue(command.params, const ['package_name', 'packageName']);
        if (packageName == null || packageName.isEmpty) {
          throw StateError('UNSUSPEND_APP missing required package_name parameter.');
        }
        await DeviceCommandChannel.unsuspendApp(packageName);
        _onMessage?.call('Unsuspend app command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'SET_CLIPBOARD':
        final text = _requiredString(command.params, const ['text', 'value']);
        await DeviceCommandChannel.setClipboardText(text);
        _onMessage?.call('Clipboard command executed.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'UPDATE_TEXT_REPLACEMENT_DICT':
        final json = _requiredString(command.params, const [
          'text_replacement_dict',
          'dictionary',
          'dict',
          'json',
        ]);
        await _textReplacementChannel.invokeMethod<void>('setDict', {'json': json});
        _onMessage?.call('Text replacement dictionary updated.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'UPDATE_TEXT_REPLACEMENT_POLICY':
        final policy = _requiredString(command.params, const [
          'policy',
          'text_replacement_policy',
          'json',
        ]);
        await _textReplacementChannel.invokeMethod<void>('setPolicy', {'json': policy});
        _onMessage?.call('Text replacement policy updated.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
        };
        break;
      case 'APP_LIST_POLL':
      case 'APP_LIST_PUSH':
        final includeSystem = _boolValue(command.params, const ['include_system', 'includeSystem'], defaultValue: true);
        final fullSnapshot = _boolValue(command.params, const ['full_snapshot', 'fullSnapshot'], defaultValue: true);
        final pollId = _stringValue(command.params, const ['poll_id', 'pollId']);
        await DeviceCommandChannel.uploadAppInventory(
          pollId: pollId,
          includeSystem: includeSystem,
          fullSnapshot: fullSnapshot,
          source: legacyAction == 'APP_LIST_PUSH' ? 'ws_push' : 'ws_poll',
        );
        _onMessage?.call('App inventory upload queued.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'include_system': includeSystem,
          'full_snapshot': fullSnapshot,
          if (pollId != null) 'poll_id': pollId,
        };
        break;
      case 'SET_VPN_POLICY':
        final vpnPolicyJson = _stringValue(command.params, const ['vpn_policy_json', 'vpnPolicyJson']);
        final providerMode = _stringValue(command.params, const ['provider_mode', 'providerMode']);
        await DeviceCommandChannel.setVpnPolicy(
          vpnPolicyJson: vpnPolicyJson,
          providerMode: providerMode,
        );
        _onMessage?.call('VPN policy stored.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          if (providerMode != null) 'provider_mode': providerMode,
          'policy_configured': (vpnPolicyJson ?? '').trim().isNotEmpty,
        };
        break;
      case 'SET_VPN_PROVIDER_PROFILE':
        final providerMode = _stringValue(command.params, const ['provider_mode', 'providerMode']);
        final vpnProfileId = _stringValue(command.params, const ['vpn_profile_id', 'vpnProfileId']);
        final vpnPolicyJson = _stringValue(command.params, const ['vpn_policy_json', 'vpnPolicyJson']);
        await DeviceCommandChannel.setVpnProviderProfile(
          providerMode: providerMode,
          vpnProfileId: vpnProfileId,
          vpnPolicyJson: vpnPolicyJson,
        );
        _onMessage?.call('VPN provider profile stored.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          if (providerMode != null) 'provider_mode': providerMode,
          if (vpnProfileId != null) 'vpn_profile_id': vpnProfileId,
          'policy_configured': (vpnPolicyJson ?? '').trim().isNotEmpty,
        };
        break;
      case 'VPN_CONNECT':
        await DeviceCommandChannel.vpnConnect();
        final status = await DeviceCommandChannel.getVpnStatus();
        _onMessage?.call('VPN connect flow triggered.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'desired_state': 'connected',
          if (status != null) 'vpn_status': status,
        };
        break;
      case 'VPN_DISCONNECT':
        await DeviceCommandChannel.vpnDisconnect();
        final status = await DeviceCommandChannel.getVpnStatus();
        _onMessage?.call('VPN disconnect flow triggered.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          'desired_state': 'disconnected',
          if (status != null) 'vpn_status': status,
        };
        break;
      case 'VPN_STATUS_POLL':
        final status = await DeviceCommandChannel.getVpnStatus();
        _onMessage?.call('VPN status snapshot captured.');
        _lastTelemetry = {
          'fallback_transport': 'ws_or_mqtt',
          'legacy_action': legacyAction,
          if (status != null) 'vpn_status': status,
        };
        break;
      default:
        throw StateError('Unsupported legacy native action in fallback host: $legacyAction');
    }
  }

  Future<void> _emitBehavior({
    required String event,
    String? reason,
    Map<String, dynamic>? payload,
  }) async {
    try {
      await _api.postBehaviorEvent(
        event: event,
        reason: reason,
        payload: payload,
      );
    } catch (_) {
      // Behavior telemetry is best-effort and must not block command handling.
    }
  }

  Future<Map<String, dynamic>> _handleVaultEntriesList() async {
    final entries = await PasswordVaultChannel.getEntries();
    final mapped = entries
        .map(
          (e) => {
            'id': e.id,
            'site': e.site,
            'username': e.username,
            'notes': e.notes,
            'locked_until': e.lockedUntil,
            'is_locked': e.isLocked,
          },
        )
        .toList();
    _onMessage?.call('Vault sync sent (${mapped.length} entries).');
    return {
      'vault_count': mapped.length,
      'vault_entries': mapped,
    };
  }

  Future<Map<String, dynamic>> _handleVaultEntryAdd(RemoteCommand command) async {
    final site = _stringValue(command.params, const ['site']) ?? '';
    final username = _stringValue(command.params, const ['username']) ?? '';
    final notes = _stringValue(command.params, const ['notes']) ?? '';
    final password = _requiredString(command.params, const ['password']);
    final id = await PasswordVaultChannel.addEntry(
      site: site,
      username: username,
      password: password,
      notes: notes,
    );
    _onMessage?.call('Vault entry added remotely: $id');
    return {'vault_entry_id': id};
  }

  Future<Map<String, dynamic>> _handleVaultEntryUpdate(RemoteCommand command) async {
    final id = _requiredString(command.params, const ['id', 'entry_id', 'entryId']);
    final updated = await PasswordVaultChannel.updateEntry(
      id: id,
      site: _stringValue(command.params, const ['site']),
      username: _stringValue(command.params, const ['username']),
      password: _stringValue(command.params, const ['password']),
      notes: _stringValue(command.params, const ['notes']),
    );
    if (!updated) {
      throw StateError('Vault entry not found: $id');
    }
    _onMessage?.call('Vault entry updated remotely: $id');
    return {'vault_entry_id': id, 'updated': true};
  }

  Future<Map<String, dynamic>> _handleVaultEntryDelete(RemoteCommand command) async {
    final id = _requiredString(command.params, const ['id', 'entry_id', 'entryId']);
    final deleted = await PasswordVaultChannel.deleteEntry(id);
    if (!deleted) {
      throw StateError('Vault entry not found: $id');
    }
    _onMessage?.call('Vault entry deleted remotely: $id');
    return {'vault_entry_id': id, 'deleted': true};
  }

  Future<Map<String, dynamic>> _handleVaultEntryLock(RemoteCommand command) async {
    final id = _requiredString(command.params, const ['id', 'entry_id', 'entryId']);
    final ttlSec = _intValue(command.params, const ['ttl_sec', 'ttlSec']) ?? 900;
    final duration = Duration(seconds: ttlSec);
    await PasswordVaultChannel.lockEntry(id, duration);
    _onMessage?.call('Vault entry locked remotely: $id');
    return {
      'vault_entry_id': id,
      'locked_for_sec': duration.inSeconds,
    };
  }

  Future<Map<String, dynamic>> _handleVaultLockAll(RemoteCommand command) async {
    final ttlSec = _intValue(command.params, const ['ttl_sec', 'ttlSec']) ?? 900;
    final duration = Duration(seconds: ttlSec);
    await PasswordVaultChannel.lockAll(duration);
    _onMessage?.call('All vault entries locked remotely.');
    return {'locked_for_sec': duration.inSeconds};
  }

  Future<Map<String, dynamic>> _handleVaultImport(RemoteCommand command) async {
    final entriesAny = command.params['entries'];
    if (entriesAny is! List) {
      throw ArgumentError('Missing required parameter: entries');
    }
    final entries = entriesAny
        .whereType<Map>()
        .map((e) => {
              'site': (e['site'] ?? '').toString(),
              'username': (e['username'] ?? '').toString(),
              'password': (e['password'] ?? '').toString(),
              'notes': (e['notes'] ?? '').toString(),
            })
        .toList();
    final inserted = await PasswordVaultChannel.importEntries(entries);
    _onMessage?.call('Vault import completed remotely: $inserted inserted.');
    return {'inserted': inserted, 'requested': entries.length};
  }

  Future<Map<String, dynamic>> _handleVaultReveal(RemoteCommand command) async {
    final id = _requiredString(command.params, const ['id', 'entry_id', 'entryId']);
    final reason = _requiredString(command.params, const ['reason']);
    try {
      final password = await PasswordVaultChannel.revealPassword(id, reason: reason);
      if (password == null) {
        throw StateError('Vault entry unavailable or locked: $id');
      }
      _onMessage?.call('Vault password revealed remotely: $id');
      return {
        'vault_entry_id': id,
        'password': password,
      };
    } on PlatformException catch (e) {
      if (e.code == 'RATE_LIMITED') {
        final retryAfterMs = e.details is Map
            ? ((e.details['retryAfterMs'] as num?)?.toInt() ?? 0)
            : 0;
        throw StateError('Reveal rate-limited. retry_after_ms=$retryAfterMs');
      }
      if (e.code == 'REASON_REQUIRED') {
        throw StateError('Reveal reason rejected by policy.');
      }
      rethrow;
    }
  }

  Future<Map<String, dynamic>> _handleDeviceFileRead(RemoteCommand command) async {
    final path = _requiredString(command.params, const ['path', 'relative_path', 'relativePath']);
    final asBase64 = _boolValue(command.params, const ['as_base64', 'asBase64'], defaultValue: false);
    final maxBytes = _intValue(command.params, const ['max_bytes', 'maxBytes']) ?? 65536;

    final result = await _fileAccess.read(
      relativePath: path,
      asBase64: asBase64,
      maxBytes: maxBytes,
    );
    if (!result.ok) {
      throw StateError(result.error ?? 'File read failed.');
    }
    _onMessage?.call('File read: ${result.relativePath}');
    return result.toTelemetry();
  }

  Future<Map<String, dynamic>> _handleDeviceFileWrite(RemoteCommand command) async {
    final path = _requiredString(command.params, const ['path', 'relative_path', 'relativePath']);
    final append = _boolValue(command.params, const ['append'], defaultValue: false);

    final content = _stringValue(command.params, const ['content']);
    final contentBase64 = _stringValue(command.params, const ['content_base64', 'contentBase64']);

    if ((content == null || content.isEmpty) && (contentBase64 == null || contentBase64.isEmpty)) {
      throw ArgumentError('Missing required parameter: content or content_base64');
    }

    final result = (contentBase64 != null && contentBase64.isNotEmpty)
        ? await _fileAccess.writeBase64(
            relativePath: path,
            contentBase64: contentBase64,
            append: append,
            originatedByHandler: true,
          )
        : await _fileAccess.writeText(
            relativePath: path,
            content: content ?? '',
            append: append,
            originatedByHandler: true,
          );

    if (!result.ok) {
      throw StateError(result.error ?? 'File write failed.');
    }
    _onMessage?.call('File written: ${result.relativePath}');
    return result.toTelemetry();
  }

  Future<Map<String, dynamic>> _handleDeviceFileDelete(RemoteCommand command) async {
    final path = _requiredString(command.params, const ['path', 'relative_path', 'relativePath']);
    final result = await _fileAccess.delete(relativePath: path);
    if (!result.ok) {
      throw StateError(result.error ?? 'File delete failed.');
    }
    _onMessage?.call('File deleted: ${result.relativePath}');
    return result.toTelemetry();
  }

  Future<Map<String, dynamic>> _handleToyLiveControl(RemoteCommand command) async {
    final mode = (_stringValue(command.params, const ['toy_mode', 'mode']) ?? 'lovense').toLowerCase();
    final toyCommand = (_stringValue(command.params, const ['toy_command', 'command']) ?? 'vibrate').toLowerCase();
    final pattern = (_stringValue(command.params, const ['toy_pattern', 'pattern']) ?? '').toLowerCase();
    final level = (_intValue(command.params, const ['toy_level', 'level', 'intensity', 'toy_intensity']) ?? 10).clamp(0, 20);
    final durationMs = (_intValue(command.params, const [
      'toy_duration_ms',
      'duration_ms',
      'length_ms',
      'length',
      'duration',
    ]) ?? 0).clamp(0, 1200000);

    if (toyCommand == 'stop' || level == 0) {
      await _stopToyMode(mode);
      return {
        'toy_mode': mode,
        'toy_command': 'stop',
        'pattern_active': false,
      };
    }

    if (pattern.isEmpty) {
      _cancelToyPattern();
      await _applyToyLevel(mode, level.toInt());
    } else {
      _startToyPattern(mode: mode, pattern: pattern, baseLevel: level.toInt(), durationMs: durationMs.toInt());
    }

    if (durationMs > 0 && pattern.isEmpty) {
      Timer(Duration(milliseconds: durationMs.toInt()), () async {
        await _stopToyMode(mode);
      });
    }

    return {
      'toy_mode': mode,
      'toy_command': toyCommand,
      'toy_pattern': pattern,
      'toy_level': level,
      'duration_ms': durationMs,
      'pattern_active': pattern.isNotEmpty,
    };
  }

  Future<Map<String, dynamic>> _handleLovenseCommand(RemoteCommand command) async {
    final toyCommand = (_stringValue(command.params, const ['toy_command', 'command']) ?? 'vibrate').toLowerCase();
    final level = (_intValue(command.params, const ['toy_level', 'level', 'intensity', 'toy_intensity']) ?? 10).clamp(0, 20);
    final durationMs = (_intValue(command.params, const [
      'toy_duration_ms',
      'duration_ms',
      'length_ms',
      'length',
      'duration',
    ]) ?? 0).clamp(0, 1200000);

    switch (toyCommand) {
      case 'scan':
      case 'pair':
        await BleChannel.lovenseScan();
        break;
      case 'stopscan':
      case 'stop_scan':
        await BleChannel.lovenseStopScan();
        break;
      case 'disconnect':
        await BleChannel.lovenseDisconnect();
        break;
      case 'rotate':
        if (durationMs > 0) {
          await BleChannel.lovenseRotateFor(
            level: level.toInt(),
            durationMs: durationMs.toInt(),
          );
        } else {
          await BleChannel.lovenseRotate(level.toInt());
        }
        break;
      case 'pump':
        if (durationMs > 0) {
          await BleChannel.lovensePumpFor(
            level: level.clamp(0, 3).toInt(),
            durationMs: durationMs.toInt(),
          );
        } else {
          await BleChannel.lovensePump(level.clamp(0, 3).toInt());
        }
        break;
      case 'battery':
        await BleChannel.lovenseBattery();
        break;
      case 'stop':
        await BleChannel.lovenseStopAll();
        break;
      case 'vibrate':
      default:
        if (durationMs > 0) {
          await BleChannel.lovenseVibrateFor(
            level: level.toInt(),
            durationMs: durationMs.toInt(),
          );
        } else {
          await BleChannel.lovenseVibrate(level.toInt());
        }
        break;
    }

    return {
      'toy_mode': 'lovense',
      'toy_command': toyCommand,
      'toy_level': level,
      'toy_duration_ms': durationMs,
    };
  }

  Future<Map<String, dynamic>> _handlePavlokCommand(RemoteCommand command) async {
    final pavlokCommand = (_stringValue(command.params, const ['pavlok_cmd', 'toy_command', 'command']) ?? 'zap').toLowerCase();
    final intensity = (_intValue(command.params, const [
      'pavlok_intensity',
      'intensity',
      'toy_level',
      'level',
      'toy_intensity',
    ]) ?? 64).clamp(0, 255);
    final durationMs = (_intValue(command.params, const [
      'pavlok_duration_ms',
      'duration_ms',
      'toy_duration_ms',
      'length_ms',
      'length',
      'duration',
    ]) ?? 500).clamp(0, 25500);

    switch (pavlokCommand) {
      case 'scan':
      case 'pair':
        await BleChannel.pavlokScan();
        break;
      case 'stopscan':
      case 'stop_scan':
        await BleChannel.pavlokStopScan();
        break;
      case 'disconnect':
        await BleChannel.pavlokDisconnect();
        break;
      case 'stop':
        await BleChannel.pavlokStopAll();
        break;
      case 'beep':
        await BleChannel.pavlokBeep(intensity: intensity.toInt(), durationMs: durationMs.toInt());
        break;
      case 'vibrate':
        await BleChannel.pavlokVibrate(intensity: intensity.toInt(), durationMs: durationMs.toInt());
        break;
      case 'shock':
      case 'zap':
      default:
        await BleChannel.pavlokZap(intensity: intensity.toInt(), durationMs: durationMs.toInt());
        break;
    }

    return {
      'toy_mode': 'pavlok',
      'pavlok_cmd': pavlokCommand,
      'pavlok_intensity': intensity,
      'pavlok_duration_ms': durationMs,
    };
  }

  Future<Map<String, dynamic>> _handleOrgasmPermissionGrant(RemoteCommand command) async {
    final requested = _intValue(command.params, const ['tokens', 'count', 'value']) ?? 1;
    final grant = requested.clamp(1, 1000);
    final current = (_prefs.getInt(_orgasmPermissionTokensKey) ?? 0).clamp(0, 1000000);
    final next = (current + grant).clamp(0, 1000000);
    await _prefs.setInt(_orgasmPermissionTokensKey, next);
    await _prefs.setInt(_orgasmDeniedCyclesKey, 0);
    await _appendEdgeTimelineEvent(
      'orgasm_permission_granted',
      payload: {
        'granted_tokens': grant,
        'permission_tokens': next,
      },
    );
    _onMessage?.call('Orgasm permission granted (+$grant).');
    return {
      'granted_tokens': grant,
      'permission_tokens': next,
      'denied_cycles_reset': true,
    };
  }

  Future<Map<String, dynamic>> _handleOrgasmPermissionRevoke(RemoteCommand command) async {
    final hard = _boolValue(command.params, const ['hard', 'reset'], defaultValue: false);
    await _prefs.setInt(_orgasmPermissionTokensKey, 0);
    if (hard) {
      await _prefs.setInt(_orgasmDeniedCyclesKey, 0);
    }
    await _appendEdgeTimelineEvent(
      'orgasm_permission_revoked',
      payload: {
        'hard': hard,
        'permission_tokens': 0,
      },
    );
    _onMessage?.call('Orgasm permission revoked.');
    return {
      'permission_tokens': 0,
      'denied_cycles_reset': hard,
    };
  }

  Future<Map<String, dynamic>> _handleEdgeSafetyProfileSet(RemoteCommand command) async {
    final requested = _stringValue(command.params, const ['profile', 'value', 'name']);
    final profile = _sanitizeSafetyProfile(requested);
    await _prefs.setString(_edgeSafetyProfileKey, profile);
    await _appendEdgeTimelineEvent(
      'edge_safety_profile_changed',
      payload: {
        'profile': profile,
        'source': 'remote_command',
      },
    );
    _onMessage?.call('Edge safety profile set to $profile.');
    return {
      'edge_safety_profile': profile,
    };
  }

  Future<Map<String, dynamic>> _handleEdgeBypassCooldownArm(RemoteCommand command) async {
    final durationMs = (_intValue(command.params, const ['duration_ms', 'durationMs', 'ms']) ??
            _edgeBypassCooldownMs)
        .clamp(5000, 15 * 60 * 1000);
    final untilMs = DateTime.now().millisecondsSinceEpoch + durationMs;
    await _prefs.setInt(_edgeBypassCooldownUntilMsKey, untilMs);
    await _appendEdgeTimelineEvent(
      'edge_bypass_cooldown_armed',
      payload: {
        'source': 'remote_command',
        'duration_ms': durationMs,
        'cooldown_until_ms': untilMs,
      },
    );
    _onMessage?.call('Edge bypass cooldown armed.');
    return {
      'edge_bypass_cooldown_until_ms': untilMs,
      'duration_ms': durationMs,
    };
  }

  Future<Map<String, dynamic>> _handleEdgeBypassCooldownClear(RemoteCommand command) async {
    await _prefs.setInt(_edgeBypassCooldownUntilMsKey, 0);
    await _appendEdgeTimelineEvent(
      'edge_bypass_cooldown_cleared',
      payload: {
        'source': 'remote_command',
      },
    );
    _onMessage?.call('Edge bypass cooldown cleared.');
    return {
      'edge_bypass_cooldown_until_ms': 0,
    };
  }

  void _startToyPattern({
    required String mode,
    required String pattern,
    required int baseLevel,
    required int durationMs,
  }) {
    _cancelToyPattern();
    _toyPatternEndsAt = durationMs > 0
        ? DateTime.now().add(Duration(milliseconds: durationMs))
        : null;
    _toyPatternTick = 0;

    _toyPatternTimer = Timer.periodic(const Duration(milliseconds: 650), (timer) async {
      if (_toyPatternEndsAt != null && DateTime.now().isAfter(_toyPatternEndsAt!)) {
        _cancelToyPattern();
        await _stopToyMode(mode);
        return;
      }

      final step = _toyPatternTick++;
      final normalizedPattern = pattern.trim().toLowerCase();
      int nextLevel;
      switch (normalizedPattern) {
        case 'pulse':
          nextLevel = (step % 2 == 0) ? baseLevel : (baseLevel ~/ 3);
          break;
        case 'wave':
          final phase = step % 6;
          const wave = [0.30, 0.55, 0.85, 1.00, 0.75, 0.45];
          nextLevel = (baseLevel * wave[phase]).round();
          break;
        case 'tease':
          nextLevel = (step % 4 == 0) ? (baseLevel * 0.9).round() : (baseLevel * 0.2).round();
          break;
        default:
          nextLevel = baseLevel;
      }
      await _applyToyLevel(mode, nextLevel.clamp(0, 20));
    });
  }

  void _cancelToyPattern() {
    _toyPatternTimer?.cancel();
    _toyPatternTimer = null;
    _toyPatternEndsAt = null;
    _toyPatternTick = 0;
  }

  Future<void> _stopToyMode(String mode) async {
    _cancelToyPattern();
    if (mode == 'intiface' || mode == 'wevibe') {
      if (!_intiface.isConnected) {
        await _intiface.connect();
      }
      _intiface.setVibration(0);
      return;
    }
    await BleChannel.lovenseStopAll();
  }

  Future<void> _applyToyLevel(String mode, int level) async {
    if (mode == 'intiface' || mode == 'wevibe') {
      if (!_intiface.isConnected) {
        await _intiface.connect();
      }
      _intiface.setVibration((level.clamp(0, 20)) / 20.0);
      return;
    }
    await BleChannel.lovenseVibrate(level.clamp(0, 20));
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
    ], defaultValue: true);

    if (allowRemoteInput && !(_prefs.getBool(_kRemoteControlConsentGranted) ?? false)) {
      // Manual screen-share UI is intentionally hidden; auto-enable the
      // one-time consent gate for handler-driven review sessions.
      await _prefs.setBool(_kRemoteControlConsentGranted, true);
    }

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

  Future<Map<String, dynamic>> _handleDiscordBotNotificationCommand(
    RemoteCommand command,
  ) async {
    final raw = _requiredString(command.params, const [
      'raw',
      'message',
      'text',
      'content',
      'command_text',
    ]);
    final packageName =
        _stringValue(command.params, const ['package_name', 'packageName']) ??
            'com.discord';
    final messageId = _stringValue(command.params, const [
      'discord_message_id',
      'message_id',
      'messageId',
    ]);
    final reactionEmoji = _stringValue(command.params, const [
      'reaction_emoji',
      'reactionEmoji',
      'confirm_reaction',
    ]);

    final accepted = await NotificationBuzzService.instance.ingestExternalCommand(
      raw: raw,
      source: 'discord_bot',
      packageName: packageName,
      messageId: messageId,
    );

    _onMessage?.call(
      accepted
          ? 'Discord bot command accepted for buzz pipeline.'
          : 'Discord bot command ignored by policy/dedupe.',
    );

    return {
      'discord_bot_command_received': true,
      'discord_command_accepted': accepted,
      if (messageId != null) 'discord_message_id': messageId,
      if (reactionEmoji != null) 'discord_reaction_emoji': reactionEmoji,
      'package_name': packageName,
    };
  }

  Future<void> _ack(
    RemoteCommand command, {
    required String status,
    String? errorCode,
    String? errorMessage,
    Map<String, dynamic>? telemetry,
  }) async {
    if (!command.hasCommandId) return;
    try {
      final mergedTelemetry = <String, dynamic>{
        'action': command.action,
        if (command.sessionId != null) 'session_id': command.sessionId,
        'source': 'flutter_app',
        if (_lastTelemetry != null) ..._lastTelemetry!,
        if (telemetry != null) ...telemetry,
      };
      await _api.postCommandAck(
        commandId: command.commandId!,
        status: status,
        errorCode: errorCode,
        errorMessage: errorMessage,
        telemetry: mergedTelemetry,
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

  static String _sanitizeSafetyProfile(String? raw) {
    switch ('${raw ?? ''}'.trim().toLowerCase()) {
      case 'strict_handler':
      case 'recovery_heavy':
      case 'training':
      case 'chaos':
        return '${raw ?? ''}'.trim().toLowerCase();
      default:
        return 'strict_handler';
    }
  }

  Future<void> _appendEdgeTimelineEvent(String event, {Map<String, dynamic>? payload}) async {
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final cutoffMs = nowMs - _edgeTimelineRetentionMs;
    final raw = _prefs.getString(_edgeTimelineKey);
    final events = <Map<String, dynamic>>[];

    if (raw != null && raw.trim().isNotEmpty) {
      try {
        final decoded = jsonDecode(raw);
        if (decoded is List) {
          for (final item in decoded) {
            if (item is Map) {
              final map = <String, dynamic>{};
              for (final entry in item.entries) {
                map['${entry.key}'] = entry.value;
              }
              final at = map['at_ms'];
              final atMs = at is num ? at.toInt() : int.tryParse('${at ?? ''}');
              if (atMs != null && atMs >= cutoffMs) {
                events.add(map);
              }
            }
          }
        }
      } catch (_) {
        // Best-effort timeline parsing.
      }
    }

    events.add({
      'event': event,
      'at_ms': nowMs,
      if (payload != null) 'payload': payload,
    });
    if (events.length > _edgeTimelineMaxEvents) {
      events.removeRange(0, events.length - _edgeTimelineMaxEvents);
    }
    await _prefs.setString(_edgeTimelineKey, jsonEncode(events));
  }
}
