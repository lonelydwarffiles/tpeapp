import 'dart:convert';

class RemoteCommand {
  const RemoteCommand({
    required this.action,
    required this.raw,
    required this.params,
    this.commandId,
    this.sessionId,
  });

  final String action;
  final String? commandId;
  final String? sessionId;
  final Map<String, String> raw;
  final Map<String, dynamic> params;

  bool get hasCommandId => commandId != null && commandId!.isNotEmpty;

  static RemoteCommand? fromEvent(Map<String, String> event) {
    final legacyAction = (event['action'] ?? '').trim();
    final explicitAction = (event['command'] ?? '').trim();
    final action = explicitAction.isNotEmpty
        ? explicitAction
        : _legacyToCanonical(legacyAction);

    if (action.isEmpty) return null;

    final parsedParams = <String, dynamic>{};
    final paramsRaw = event['params'];
    if (paramsRaw != null && paramsRaw.trim().isNotEmpty) {
      try {
        final decoded = jsonDecode(paramsRaw);
        if (decoded is Map<String, dynamic>) {
          parsedParams.addAll(decoded);
        }
      } catch (_) {
        // Ignore invalid params payload; fall back to top-level values.
      }
    }

    for (final entry in event.entries) {
      if (entry.key == 'params') continue;
      if (!parsedParams.containsKey(entry.key)) {
        parsedParams[entry.key] = entry.value;
      }
    }

    final commandId = (event['command_id'] ?? event['id'] ?? '').trim();
    final sessionId = _stringValue(parsedParams, [
      'session_id',
      'sessionId',
    ]);

    return RemoteCommand(
      action: action,
      commandId: commandId.isEmpty ? null : commandId,
      sessionId: sessionId,
      raw: Map<String, String>.from(event),
      params: parsedParams,
    );
  }

  static String _legacyToCanonical(String action) {
    switch (action) {
      // Actions that also require Flutter-side handling.
      case 'REQUEST_CHECKIN':
        return 'puppy.checkin.request';
      case 'START_REVIEW':
        return 'screen.share.start';
      case 'STOP_REVIEW':
        return 'screen.share.stop';
      case 'LOVENSE_COMMAND':
        return 'toy.lovense.command';
      case 'PAVLOK_COMMAND':
        return 'toy.pavlok.command';
      case 'ALLOW_ORGASM':
      case 'GRANT_ORGASM_PERMISSION':
        return 'orgasm.permission.grant';
      case 'REVOKE_ORGASM_PERMISSION':
        return 'orgasm.permission.revoke';
      case 'SET_EDGE_SAFETY_PROFILE':
        return 'edge.safety_profile.set';
      case 'ARM_EDGE_BYPASS_COOLDOWN':
        return 'edge.bypass_cooldown.arm';
      case 'CLEAR_EDGE_BYPASS_COOLDOWN':
        return 'edge.bypass_cooldown.clear';
      case 'DISCORD_BOT_NOTIFICATION_COMMAND':
      case 'DISCORD_NOTIFICATION_COMMAND':
      case 'BOT_NOTIFICATION_COMMAND':
        return 'discord.bot.notification_command';

      // All other MQTT UPPERCASE actions are fully handled by
      // PartnerMqttService (Kotlin-native). Flutter maps them to
      // 'native.handled' so that any command_id present in the payload
      // receives a SUCCEEDED ack instead of a spurious REJECTED.
      case 'ble_trigger':
      case 'ban_word':
      case 'UPDATE_SETTINGS':
      case 'UPDATE_NOTIFICATION_BLOCKLIST':
      case 'UPDATE_RESTRICTED_VOCABULARY':
      case 'UPDATE_TONE_COMPLIANCE':
      case 'UPDATE_TEXT_REPLACEMENT_POLICY':
      case 'TASK_ASSIGNED':
      case 'TASK_CLEARED':
      case 'CLEAR_TASK_ASSIGNED':
      case 'TASK_CLEAR':
      case 'NEW_QUESTION':
      case 'RULE_REMINDER':
      case 'OPEN_APP':
      case 'FORCE_STOP_APP':
      case 'DISABLE_APP':
      case 'ENABLE_APP':
      case 'CLEAR_APP_CACHE':
      case 'UNINSTALL_APP':
      case 'OPEN_URL':
      case 'SET_BRIGHTNESS':
      case 'SCREEN_ON':
      case 'SCREEN_OFF':
      case 'SET_SCREEN_TIMEOUT':
      case 'SHOW_OVERLAY':
      case 'SET_ORIENTATION':
      case 'SET_ROTATION':
      case 'SET_VOLUME':
      case 'SET_RINGER_MODE':
      case 'PLAY_AUDIO':
      case 'STOP_AUDIO':
      case 'SPEAK_TEXT':
      case 'LOCK_DEVICE':
      case 'DISMISS_KEYGUARD':
      case 'SET_WIFI':
      case 'SET_MOBILE_DATA':
      case 'SET_AIRPLANE_MODE':
      case 'SET_BLUETOOTH':
      case 'CONNECT_WIFI':
      case 'TAKE_SCREENSHOT':
      case 'RECORD_SCREEN':
      case 'SET_FLASHLIGHT':
      case 'GET_LOCATION':
      case 'SEND_NOTIFICATION':
      case 'CLEAR_NOTIFICATIONS':
      case 'INCOMING_PROXY_SMS':
      case 'SET_PROXY_SMS_CAN_REPLY':
      case 'SET_SMS_THREAD_CAN_REPLY':
      case 'TOGGLE_THREAD_CAN_REPLY':
      case 'SET_DND':
      case 'SET_ALARM':
      case 'SET_WALLPAPER':
      case 'SET_AUTO_ROTATE':
      case 'SET_CLIPBOARD':
      case 'SET_NFC':
      case 'SET_FONT_SIZE':
      case 'SUSPEND_APP':
      case 'UNSUSPEND_APP':
      case 'SET_VPN_POLICY':
      case 'SET_VPN_PROVIDER_PROFILE':
      case 'VPN_CONNECT':
      case 'VPN_DISCONNECT':
      case 'VPN_STATUS_POLL':
      case 'APP_LIST_POLL':
      case 'APP_LIST_PUSH':
      case 'SET_RITUALS':
      case 'SET_RITUAL_TIMES':
      case 'SET_HONORIFIC':
      case 'SET_HONORIFIC_ENABLED':
      case 'SET_PTS_ENABLED':
      case 'SET_PTS_APPROVED':
      case 'APP_PERMISSION_RESPONSE':
      case 'START_CORNER_TIME':
      case 'CANCEL_ESCALATION':
      case 'SET_AFFIRMATIONS':
      case 'SHOW_AFFIRMATION':
      case 'SET_MANTRA_ENABLED':
      case 'SET_MANTRA_INTERVAL':
      case 'SET_GATING_ENABLED':
      case 'SET_GATING_APPROVED':
      case 'SET_GEOFENCES':
      case 'SET_GEOFENCE_ENABLED':
      case 'SET_LOVENSE_SCHEDULES':
      case 'SET_SUB_STATUS':
      case 'SET_HANDLER_SYSTEM_PROMPT':
      case 'SET_HANDLER_API_KEY':
      case 'SET_HANDLER_ENDPOINT':
      case 'SET_HANDLER_MODEL':
      case 'VAULT_ADD_ENTRY':
      case 'VAULT_UPDATE_ENTRY':
      case 'VAULT_DELETE_ENTRY':
      case 'VAULT_LOCK_ENTRY':
      case 'VAULT_LOCK_ALL':
      case 'VAULT_SET_CHANGE_BLOCK':
        return 'native.handled';

      default:
        return action;
    }
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
}