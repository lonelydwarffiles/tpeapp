import 'package:flutter/services.dart';

/// Dart client for the `com.tpeapp/password_vault` MethodChannel.
///
/// The partner-controlled password vault stores credentials encrypted on the
/// device (AES-256-GCM, Android Keystore).  Passwords are never returned by
/// [getEntries]; only [revealPassword] returns the plaintext value, and only
/// when the entry is not currently time-locked by the partner.
///
/// All mutating operations ([addEntry], [updateEntry], [deleteEntry]) should
/// be guarded by a PIN check on the Dart side before calling.
class PasswordVaultChannel {
  PasswordVaultChannel._();

  static const _channel = MethodChannel('com.tpeapp/password_vault');

  /// Returns all vault entries with **passwords redacted** (empty string).
  ///
  /// Each map contains: `id`, `site`, `username`, `notes`, `lockedUntil`
  /// (epoch millis; 0 = not locked).
  static Future<List<VaultEntry>> getEntries() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('getEntries') ?? [];
    return raw
        .cast<Map<dynamic, dynamic>>()
        .map((m) => VaultEntry.fromMap(Map<String, dynamic>.from(m)))
        .toList();
  }

  /// Returns the plaintext password for [id], or `null` when the entry is
  /// time-locked or not found.  A `password_viewed` webhook is fired on the
  /// Kotlin side whenever this returns a non-null value.
  static Future<String?> revealPassword(String id, {String? reason}) =>
      _channel.invokeMethod<String?>('revealPassword', {
        'id': id,
        if (reason != null) 'reason': reason,
      });

  /// Adds a new vault entry and returns the generated UUID.
  static Future<String> addEntry({
    required String site,
    required String username,
    required String password,
    String notes = '',
  }) async {
    final id = await _channel.invokeMethod<String>('addEntry', {
      'site':     site,
      'username': username,
      'password': password,
      'notes':    notes,
    });
    return id!;
  }

  /// Updates fields on an existing entry.  Pass `null` for fields that should
  /// remain unchanged.  Returns `true` when the entry was found.
  static Future<bool> updateEntry({
    required String id,
    String? site,
    String? username,
    String? password,
    String? notes,
  }) async {
    final ok = await _channel.invokeMethod<bool>('updateEntry', {
      'id':       id,
      if (site     != null) 'site':     site,
      if (username != null) 'username': username,
      if (password != null) 'password': password,
      if (notes    != null) 'notes':    notes,
    });
    return ok ?? false;
  }

  /// Deletes the entry with [id].  Returns `true` when the entry was found.
  static Future<bool> deleteEntry(String id) async {
    final ok = await _channel.invokeMethod<bool>('deleteEntry', {'id': id});
    return ok ?? false;
  }

  /// Time-locks the entry with [id] for [duration].
  static Future<void> lockEntry(String id, Duration duration) =>
      _channel.invokeMethod('lockEntry', {
        'id':         id,
        'durationMs': duration.inMilliseconds,
      });

  /// Time-locks every vault entry for [duration].
  static Future<void> lockAll(Duration duration) =>
      _channel.invokeMethod('lockAll', {
        'durationMs': duration.inMilliseconds,
      });

  /// Bulk-imports [entries] into the vault.
  ///
  /// Each map must contain `site`, `username`, `password`; `notes` is optional.
  /// Duplicate site+username pairs are silently skipped.  Returns the number
  /// of new entries actually inserted.
  static Future<int> importEntries(List<Map<String, String>> entries) async {
    final count = await _channel.invokeMethod<int>('importEntries', {
      'entries': entries,
    });
    return count ?? 0;
  }

  /// Opens Android autofill provider settings for selecting TpeApp.
  static Future<void> openAutofillSettings() =>
      _channel.invokeMethod('openAutofillSettings');
}

/// Lightweight model for a vault entry returned by [PasswordVaultChannel.getEntries].
///
/// The [password] field is always empty in this model — call
/// [PasswordVaultChannel.revealPassword] to obtain the real value.
class VaultEntry {
  const VaultEntry({
    required this.id,
    required this.site,
    required this.username,
    required this.notes,
    required this.lockedUntil,
  });

  final String id;
  final String site;
  final String username;

  /// Optional free-text notes associated with this credential.
  final String notes;

  /// Epoch milliseconds until which this entry is locked; 0 means unlocked.
  final int lockedUntil;

  /// Whether the entry is currently time-locked by the partner.
  bool get isLocked =>
      lockedUntil > DateTime.now().millisecondsSinceEpoch;

  /// How long until the lock expires, or [Duration.zero] when unlocked.
  Duration get remainingLock {
    if (!isLocked) return Duration.zero;
    return Duration(
        milliseconds: lockedUntil - DateTime.now().millisecondsSinceEpoch);
  }

  factory VaultEntry.fromMap(Map<String, dynamic> m) => VaultEntry(
        id:          m['id'] as String,
        site:        m['site'] as String? ?? '',
        username:    m['username'] as String? ?? '',
        notes:       m['notes'] as String? ?? '',
        lockedUntil: (m['lockedUntil'] as num?)?.toInt() ?? 0,
      );
}
