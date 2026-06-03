import 'dart:convert';

import 'package:flutter/services.dart';

/// Dart client for the `com.hound.controller/text_replacement` MethodChannel.
///
/// Manages the system-wide text-replacement dictionary that the LSPosed module
/// reads to perform stealth regex substitutions inside every hooked app's
/// [android.widget.TextView].
///
/// The dictionary is a [Map<String, dynamic>] where each key is a Regex pattern
/// string and each value is either:
/// - A single replacement string: `"pattern": "replacement"`
/// - A list of replacement strings: `"pattern": ["option1", "option2", "option3"]`
///
/// When multiple options are provided, one is randomly selected on each match.
///
/// Example:
/// ```dart
/// await TextReplacementChannel.setDict({
///   r'(?i)\b(good)\s+(boy|girl)\b': r'$1 pup',
///   r'(?i)\bI\b': ['this mutt', 'puppy', 'it'],  // randomly picks one
/// });
/// ```
class TextReplacementChannel {
  TextReplacementChannel._();

  static const _channel = MethodChannel('com.hound.controller/text_replacement');
  static const List<String> _defaultBlockedPackages = <String>[
    // Messages
    'com.google.android.apps.messaging',
    'com.android.messaging',
    'com.samsung.android.messaging',
    // Snapchat
    'com.snapchat.android',
    // Facebook Messenger
    'com.facebook.orca',
    // Google Search (Google app)
    'com.google.android.googlequicksearchbox',
  ];
  static const Map<String, dynamic> defaultPolicy = {
    'default_mode': 'auto',
    'blocked_packages': _defaultBlockedPackages,
    'blocked_package_prefixes': <String>[],
  };

  /// Built-in defaults for URL rewrite + self-reference normalization.
  ///
  /// Rules are regex pattern -> replacement (string or list of strings).
  /// When a list is provided, one option is randomly selected on each match.
  static const Map<String, dynamic> defaultDict = {
    "(?i)\\bhttps?://(?:www\\.|mobile\\.)?(?:twitter\\.com|x\\.com)(/[^\\s<>'\\\"]*)?":
        r'https://fxtwitter.com$1',
    r'(?i)\bI am\b': 'this mutt is',
    r"(?i)\bI'm\b": 'this mutt is',
    r'(?i)\bI\b': ['this mutt', 'puppy', 'it'],  // variable self-reference
    r'(?i)\bme\b': 'it',
    r'(?i)\bmyself\b': 'itself',
    r'(?i)\bmy\b': 'its',
    r'(?i)\bmine\b': 'its',
  };

  // Old playful-diction defaults that should no longer be auto-enforced.
  static const Set<String> _deprecatedBarkRuleKeys = {
    r'(?i)\byes\b',
    r'(?i)\byep\b',
    r'(?i)\bno\b',
    r'(?i)\bokay\b',
    r'(?i)\bok\b',
    r'(?i)\bmaybe\b',
    r'(?i)\bplease\b',
    r'(?i)\bhello\b',
    r'(?i)\bhi\b',
    r'(?i)\bgood\b',
    r'(?i)\bgreat\b',
    r'(?i)\bthanks\b',
    r'(?i)\bsorry\b',
  };

  static const Map<String, dynamic> _preferredSelfReferenceRules = {
    r'(?i)\bI am\b': 'this mutt is',
    r"(?i)\bI'm\b": 'this mutt is',
    r'(?i)\bI\b': ['this mutt', 'puppy', 'it'],  // variable self-reference
    r'(?i)\bme\b': 'it',
    r'(?i)\bmyself\b': 'itself',
    r'(?i)\bmy\b': 'its',
    r'(?i)\bmine\b': 'its',
  };

  /// Fetches the currently stored dictionary from SharedPreferences.
  ///
  /// Returns an empty map when no dictionary has been set or when the stored
  /// value is malformed JSON.
  ///
  /// Returned map may contain both string and list values for each pattern.
  static Future<Map<String, dynamic>> getDict() async {
    final json = await _channel.invokeMethod<String>('getDict') ?? '';
    if (json.isEmpty) return {};
    try {
      final decoded = jsonDecode(json) as Map<String, dynamic>;
      return decoded;
    } on FormatException catch (_) {
      // Stored value is corrupt; return empty so the UI stays usable.
      return {};
    }
  }

  /// Persists [dict] to SharedPreferences.
  ///
  /// Passing an empty map clears the dictionary (no replacements will be made).
  ///
  /// Dictionary values can be either strings or lists of strings for random selection.
  static Future<void> setDict(Map<String, dynamic> dict) =>
      _channel.invokeMethod('setDict', {'json': jsonEncode(dict)});

  static Future<Map<String, dynamic>> getPolicy() async {
    final json = await _channel.invokeMethod<String>('getPolicy') ?? '';
    if (json.isEmpty) return {};
    try {
      final decoded = jsonDecode(json);
      if (decoded is Map<String, dynamic>) return decoded;
      return {};
    } on FormatException catch (_) {
      return {};
    }
  }

  static Future<void> setPolicy(Map<String, dynamic> policy) =>
      _channel.invokeMethod('setPolicy', {'json': jsonEncode(policy)});

  /// Returns the explicit mode override for a package, or null when it inherits default behavior.
  static Future<String?> getPackagePolicy(String packageName) async {
    final pkg = packageName.trim().toLowerCase();
    if (pkg.isEmpty) return null;
    return _channel.invokeMethod<String>('getPackagePolicy', {
      'packageName': pkg,
    });
  }

  /// Sets a per-package policy mode.
  ///
  /// Accepted values: auto, full, identity, off. Use inherit/default to clear the override.
  static Future<void> setPackagePolicy(String packageName, String mode) async {
    final pkg = packageName.trim().toLowerCase();
    final normalizedMode = mode.trim().toLowerCase();
    if (pkg.isEmpty) {
      throw ArgumentError.value(packageName, 'packageName', 'packageName must not be empty');
    }
    if (normalizedMode.isEmpty) {
      throw ArgumentError.value(mode, 'mode', 'mode must not be empty');
    }
    await _channel.invokeMethod('setPackagePolicy', {
      'packageName': pkg,
      'mode': normalizedMode,
    });
  }

  /// Seeds [defaultDict] only when no dictionary has been configured yet.
  static Future<void> ensureDefaults() async {
    final current = await getDict();
    final currentPolicy = await getPolicy();
    if (current.isEmpty) {
      await setDict(defaultDict);
    } else {
      // Keep custom rules, backfill missing defaults, and enforce preferred
      // first-person-to-it/mutt self-reference mappings.
      final merged = <String, dynamic>{...defaultDict, ...current};
      for (final key in _deprecatedBarkRuleKeys) {
        merged.remove(key);
      }
      merged.addAll(_preferredSelfReferenceRules);
      await setDict(merged);
    }

    if (currentPolicy.isEmpty) {
      await setPolicy(defaultPolicy);
      return;
    }

    final mergedPolicy = <String, dynamic>{...currentPolicy};
    mergedPolicy.putIfAbsent('default_mode', () => defaultPolicy['default_mode']);
    mergedPolicy.putIfAbsent('packages', () => <String, dynamic>{});
    mergedPolicy.putIfAbsent('package_prefixes', () => <String, dynamic>{});
    // Keep the block scope intentionally narrow to these specific apps.
    mergedPolicy['blocked_packages'] = List<String>.from(_defaultBlockedPackages);
    mergedPolicy['blocked_package_prefixes'] = <String>[];
    await setPolicy(mergedPolicy);
  }
}

