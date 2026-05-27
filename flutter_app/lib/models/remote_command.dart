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
      case 'REQUEST_CHECKIN':
        return 'puppy.checkin.request';
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