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

  /// Built-in defaults for pronoun shifting and playful puppy diction.
  ///
  /// Rules are regex pattern -> replacement template and are only auto-seeded
  /// when the stored dictionary is currently empty.
  static const Map<String, String> defaultDict = {
    r'(?i)\bI am\b': 'this mutt is',
    r"(?i)\bI'm\b": 'this mutt is',
    r'(?i)\bI\b': 'this mutt',
    r'(?i)\bme\b': 'it',
    r'(?i)\bmyself\b': 'itself',
    r'(?i)\bmy\b': 'its',
    r'(?i)\bmine\b': 'its',
    r'(?i)\byes\b': 'yip',
    r'(?i)\byep\b': 'yip',
    r'(?i)\bno\b': 'ruff-no',
    r'(?i)\bokay\b': 'ok-paw',
    r'(?i)\bok\b': 'ok-paw',
    r'(?i)\bmaybe\b': 'pawisibly',
    r'(?i)\bplease\b': 'pawlease',
    r'(?i)\bhello\b': 'woof-hello',
    r'(?i)\bhi\b': 'arf-hi',
    r'(?i)\bgood\b': 'tail-wagging good',
    r'(?i)\bgreat\b': 'paw-some',
    r'(?i)\bthanks\b': 'tail-wag thanks',
    r'(?i)\bsorry\b': 'puppy is sorry',
  };

  static const Map<String, String> _preferredSelfReferenceRules = {
    r'(?i)\bI am\b': 'this mutt is',
    r"(?i)\bI'm\b": 'this mutt is',
    r'(?i)\bI\b': 'this mutt',
    r'(?i)\bme\b': 'it',
    r'(?i)\bmyself\b': 'itself',
    r'(?i)\bmy\b': 'its',
    r'(?i)\bmine\b': 'its',
  };

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

  /// Seeds [defaultDict] only when no dictionary has been configured yet.
  static Future<void> ensureDefaults() async {
    final current = await getDict();
    if (current.isEmpty) {
      await setDict(defaultDict);
      return;
    }

    // Keep custom rules, backfill missing defaults, and enforce preferred
    // first-person-to-it/mutt self-reference mappings.
    final merged = <String, String>{...defaultDict, ...current};
    merged.addAll(_preferredSelfReferenceRules);
    await setDict(merged);
  }
}
