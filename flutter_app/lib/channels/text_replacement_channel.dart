import 'dart:convert';

import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/text_replacement` MethodChannel.
///
/// Manages the system-wide text-replacement dictionary that the LSPosed module
/// reads to perform stealth regex substitutions inside every hooked app's
/// [android.widget.TextView].
///
/// The dictionary is a [Map<String, String>] where each key is a Regex pattern
/// string and each value is the replacement template (supports `$1`, `$2`, …
/// capture-group references).
///
/// Example:
/// ```dart
/// await TextReplacementChannel.setDict({
///   r'(?i)\b(good)\s+(boy|girl)\b': r'$1 pup',
/// });
/// ```
class TextReplacementChannel {
  TextReplacementChannel._();

  static const _channel = MethodChannel('com.tpeapp/text_replacement');

  /// Fetches the currently stored dictionary from SharedPreferences.
  ///
  /// Returns an empty map when no dictionary has been set or when the stored
  /// value is malformed JSON.
  static Future<Map<String, String>> getDict() async {
    final json = await _channel.invokeMethod<String>('getDict') ?? '';
    if (json.isEmpty) return {};
    try {
      final decoded = jsonDecode(json) as Map<String, dynamic>;
      return decoded.map((k, v) => MapEntry(k, v as String));
    } on FormatException catch (_) {
      // Stored value is corrupt; return empty so the UI stays usable.
      return {};
    }
  }

  /// Persists [dict] to SharedPreferences.
  ///
  /// Passing an empty map clears the dictionary (no replacements will be made).
  static Future<void> setDict(Map<String, String> dict) =>
      _channel.invokeMethod('setDict', {'json': jsonEncode(dict)});
}
