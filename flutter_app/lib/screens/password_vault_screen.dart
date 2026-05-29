import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/partner_pin_channel.dart';
import '../channels/password_vault_channel.dart';
import '../utils/password_generator.dart';

/// Partner-controlled password vault screen.
///
/// The sub can see which accounts are managed (site + username) and can reveal
/// a password for a configurable number of seconds.  Adding, editing, and
/// deleting entries all require the partner PIN.
///
/// Time-locked entries show a live countdown; the "Reveal" button is disabled
/// while the lock is active.
class PasswordVaultScreen extends StatefulWidget {
  const PasswordVaultScreen({super.key, this.revealTimeoutSeconds = 10});

  /// How many seconds to display a revealed password before hiding it again.
  final int revealTimeoutSeconds;

  @override
  State<PasswordVaultScreen> createState() => _PasswordVaultScreenState();
}

class _PasswordVaultScreenState extends State<PasswordVaultScreen> {
  static const _kVaultRevealConsentReason =
      'vault_reveal_one_time_consent_reason';

  List<VaultEntry> _entries = [];
  bool _loading = true;

  /// Maps entry id → revealed plaintext password (removed after timeout).
  final Map<String, String> _revealed = {};

  /// Maps entry id → active hide timer.
  final Map<String, Timer> _hideTimers = {};

  /// Maps entry id → countdown seconds remaining.
  final Map<String, int> _countdowns = {};

  /// Maps entry id → countdown ticker.
  final Map<String, Timer> _countdownTimers = {};

  /// Maps entry id → lock-remaining countdown ticker.
  final Map<String, Timer> _lockTimers = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final t in _hideTimers.values) {
      t.cancel();
    }
    for (final t in _countdownTimers.values) {
      t.cancel();
    }
    for (final t in _lockTimers.values) {
      t.cancel();
    }
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final entries = await PasswordVaultChannel.getEntries();
    if (mounted) {
      setState(() {
        _entries = entries;
        _loading = false;
      });
      _startLockCountdowns();
    }
  }

  // Start per-second tickers for any currently-locked entries so the UI
  // updates the remaining-lock duration live.
  void _startLockCountdowns() {
    for (final t in _lockTimers.values) {
      t.cancel();
    }
    _lockTimers.clear();

    for (final e in _entries) {
      if (e.isLocked) {
        _lockTimers[e.id]?.cancel();
        _lockTimers[e.id] = Timer.periodic(const Duration(seconds: 1), (_) {
          if (!mounted) return;
          setState(() {});
          // Stop ticking once unlocked.
          if (!e.isLocked) {
            _lockTimers[e.id]?.cancel();
            _lockTimers.remove(e.id);
          }
        });
      }
    }
  }

  // ------------------------------------------------------------------
  //  Reveal / hide
  // ------------------------------------------------------------------

  Future<void> _reveal(VaultEntry entry) async {
    final reason = await _getRevealReason();
    if (reason == null) return;

    String? password;
    try {
      password = await PasswordVaultChannel.revealPassword(entry.id, reason: reason);
    } on PlatformException catch (e) {
      if (!mounted) return;
      if (e.code == 'RATE_LIMITED') {
        final details = e.details;
        final retryAfterMs = details is Map
            ? ((details['retryAfterMs'] as num?)?.toInt() ?? 0)
            : 0;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              retryAfterMs > 0
                  ? 'Reveal blocked. Try again in ${_formatDuration(Duration(milliseconds: retryAfterMs))}.'
                  : 'Reveal blocked by handler policy.',
            ),
          ),
        );
        return;
      }
      if (e.code == 'REASON_REQUIRED') {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Please provide a more specific reveal reason.')),
        );
        return;
      }
      rethrow;
    }

    if (password == null) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Password is locked or unavailable.')),
        );
      }
      return;
    }

    // Cancel any existing timers for this entry.
    _hideTimers[entry.id]?.cancel();
    _countdownTimers[entry.id]?.cancel();

    final timeout = widget.revealTimeoutSeconds;
    setState(() {
      _revealed[entry.id]   = password;
      _countdowns[entry.id] = timeout;
    });

    // Countdown ticker — refreshes every second.
    _countdownTimers[entry.id] = Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted) { t.cancel(); return; }
      setState(() {
        final remaining = (_countdowns[entry.id] ?? 0) - 1;
        if (remaining <= 0) {
          _countdowns.remove(entry.id);
          t.cancel();
        } else {
          _countdowns[entry.id] = remaining;
        }
      });
    });

    // Auto-hide after timeout.
    _hideTimers[entry.id] = Timer(Duration(seconds: timeout), () {
      if (!mounted) return;
      setState(() => _revealed.remove(entry.id));
    });
  }

  Future<String?> _getRevealReason() async {
    final prefs = await SharedPreferences.getInstance();
    final stored = prefs.getString(_kVaultRevealConsentReason)?.trim() ?? '';
    if (stored.isNotEmpty) {
      return stored;
    }

    final reason = await _showRevealReasonDialog();
    if (reason == null) return null;
    final normalized = reason.trim();
    if (normalized.isEmpty) return null;
    if (normalized.length < 6) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Please provide a slightly more specific reason.')),
        );
      }
      return null;
    }

    await prefs.setString(_kVaultRevealConsentReason, normalized);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('File-access consent saved. You will not be asked again.')),
      );
    }
    return normalized;
  }

  void _hide(String id) {
    _hideTimers[id]?.cancel();
    _countdownTimers[id]?.cancel();
    setState(() {
      _revealed.remove(id);
      _countdowns.remove(id);
    });
  }

  // ------------------------------------------------------------------
  //  Add / edit / delete (all PIN-protected)
  // ------------------------------------------------------------------

  Future<void> _addEntry() async {
    final pin = await _showPinDialog('Partner PIN required to add an entry');
    if (pin == null) return;
    final ok = await PartnerPinChannel.verifyPin(pin);
    if (!ok) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Incorrect PIN.')));
      }
      return;
    }
    final result = await _showEntryDialog(title: 'Add Credential');
    if (result == null) return;
    await PasswordVaultChannel.addEntry(
      site:     result['site']!,
      username: result['username']!,
      password: result['password']!,
      notes:    result['notes']!,
    );
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Credential added.')));
    }
    await _load();
  }

  Future<void> _lockAll() async {
    final pin = await _showPinDialog('Partner PIN required to lock all entries');
    if (pin == null) return;
    final ok = await PartnerPinChannel.verifyPin(pin);
    if (!ok) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Incorrect PIN.')));
      }
      return;
    }

    final selected = await _showLockDurationDialog();
    if (selected == null) return;

    await PasswordVaultChannel.lockAll(selected);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content:
                Text('All entries locked for ${_formatDuration(selected)}.')),
      );
    }
    await _load();
  }

  Future<void> _lockEntry(VaultEntry entry) async {
    final pin = await _showPinDialog('Partner PIN required to lock entry');
    if (pin == null) return;
    final ok = await PartnerPinChannel.verifyPin(pin);
    if (!ok) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Incorrect PIN.')));
      }
      return;
    }

    final selected = await _showLockDurationDialog();
    if (selected == null) return;

    await PasswordVaultChannel.lockEntry(entry.id, selected);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Entry locked for ${_formatDuration(selected)}.')),
      );
    }
    await _load();
  }

  String _formatDuration(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60);
    if (h > 0 && m > 0) return '${h}h ${m}m';
    if (h > 0) return '${h}h';
    if (m > 0) return '${m}m';
    return '${d.inSeconds}s';
  }

  Future<void> _editEntry(VaultEntry entry) async {
    final pin = await _showPinDialog('Partner PIN required to edit');
    if (pin == null) return;
    final ok = await PartnerPinChannel.verifyPin(pin);
    if (!ok) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Incorrect PIN.')));
      }
      return;
    }
    final result = await _showEntryDialog(
      title: 'Edit Credential',
      site:     entry.site,
      username: entry.username,
      notes:    entry.notes,
    );
    if (result == null) return;
    await PasswordVaultChannel.updateEntry(
      id:       entry.id,
      site:     result['site'],
      username: result['username'],
      password: result['password']!.isEmpty ? null : result['password'],
      notes:    result['notes'],
    );
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Credential updated.')));
    }
    await _load();
  }

  Future<void> _deleteEntry(VaultEntry entry) async {
    final pin = await _showPinDialog('Partner PIN required to delete');
    if (pin == null) return;
    final ok = await PartnerPinChannel.verifyPin(pin);
    if (!ok) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Incorrect PIN.')));
      }
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete credential?'),
        content: Text(
          'This will permanently remove "${entry.site}" from the vault.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Delete',
                  style: TextStyle(color: Colors.red))),
        ],
      ),
    );
    if (confirmed != true) return;
    await PasswordVaultChannel.deleteEntry(entry.id);
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Credential deleted.')));
    }
    await _load();
  }

  // ------------------------------------------------------------------
  //  Import credentials (CSV / JSON)
  // ------------------------------------------------------------------

  /// PIN-gated credential import.
  ///
  /// Supports two text-based formats pasted into a text field:
  ///  • **JSON array**: `[{"site":"…","username":"…","password":"…","notes":"…"},…]`
  ///  • **CSV** (header row required): `site,username,password,notes`
  ///
  /// Duplicate site+username pairs are silently skipped on the Kotlin side.
  Future<void> _importEntries() async {
    final pin = await _showPinDialog('Partner PIN required to import');
    if (pin == null) return;
    final ok = await PartnerPinChannel.verifyPin(pin);
    if (!ok) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Incorrect PIN.')));
      }
      return;
    }

    final rawText = await _showImportDialog();
    if (rawText == null || rawText.trim().isEmpty) return;

    List<Map<String, String>>? entries;
    String? parseError;
    try {
      entries = _parseImportText(rawText.trim());
    } catch (e) {
      parseError = e.toString();
    }

    if (parseError != null || entries == null || entries.isEmpty) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(parseError != null
                ? 'Parse error: $parseError'
                : 'No valid entries found in the pasted text.'),
          ),
        );
      }
      return;
    }

    // Show a preview and confirm before inserting.
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Confirm import'),
        content: Text(
          'Found ${entries!.length} credential(s) to import.\n'
          'Duplicates (same site + username) will be skipped.\n\n'
          'Proceed?'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Import')),
        ],
      ),
    );
    if (confirmed != true) return;

    final count = await PasswordVaultChannel.importEntries(entries!);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Imported $count new credential(s).')),
      );
    }
    await _load();
  }

  Future<String?> _showImportDialog() async {
    final ctrl = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Paste credentials'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Paste a JSON array or CSV (with header: site,username,password,notes):',
                style: TextStyle(fontSize: 12),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: ctrl,
                maxLines: 10,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  hintText: '[{"site":"example.com","username":"user","password":"pass"}]',
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, ctrl.text),
              child: const Text('Parse')),
        ],
      ),
    );
  }

  /// Parses a JSON array or CSV string into a list of entry maps.
  List<Map<String, String>> _parseImportText(String text) {
    if (text.startsWith('[')) {
      // JSON array
      final List<dynamic> arr = jsonDecode(text) as List<dynamic>;
      return arr.map((e) {
        final m = Map<String, dynamic>.from(e as Map);
        return {
          'site':     m['site']?.toString()     ?? '',
          'username': m['username']?.toString() ?? m['login']?.toString() ?? '',
          'password': m['password']?.toString() ?? '',
          'notes':    m['notes']?.toString()    ?? m['note']?.toString() ?? '',
        };
      }).toList();
    } else {
      // CSV — first line is header
      final lines = text.split('\n').map((l) => l.trim()).where((l) => l.isNotEmpty).toList();
      if (lines.length < 2) return [];
      final headers = _splitCsv(lines[0]).map((h) => h.toLowerCase()).toList();

      /// Returns the index of [name] or [fallback] in [headers], or -1.
      int col(String name, [String? fallback]) {
        final i = headers.indexOf(name);
        if (i >= 0) return i;
        if (fallback != null) return headers.indexOf(fallback);
        return -1;
      }

      final siteCol     = col('site', 'url');
      final usernameCol = col('username', 'login');
      final passwordCol = col('password');
      final notesCol    = col('notes', 'note');

      if (passwordCol < 0) {
        throw FormatException(
            'CSV missing required "password" column. '
            'Available columns: ${headers.join(", ")}');
      }

      return lines.skip(1).map((line) {
        final cols = _splitCsv(line);
        String colVal(int idx) => idx >= 0 && idx < cols.length ? cols[idx] : '';
        return {
          'site':     colVal(siteCol),
          'username': colVal(usernameCol),
          'password': colVal(passwordCol),
          'notes':    colVal(notesCol),
        };
      }).toList();
    }
  }

  /// Splits a single CSV line respecting double-quoted fields.
  List<String> _splitCsv(String line) {
    final result  = <String>[];
    final current = StringBuffer();
    bool inQuotes = false;
    for (var i = 0; i < line.length; i++) {
      final ch = line[i];
      if (ch == '"') {
        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
          current.write('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (ch == ',' && !inQuotes) {
        result.add(current.toString());
        current.clear();
      } else {
        current.write(ch);
      }
    }
    result.add(current.toString());
    return result;
  }

  // ------------------------------------------------------------------
  //  Autofill settings
  // ------------------------------------------------------------------

  /// Opens the system autofill-provider settings page so the user can select
  /// TpeApp as the device autofill service.
  Future<void> _openAutofillSettings() async {
    try {
      await PasswordVaultChannel.openAutofillSettings();
    } on MissingPluginException {
      // Fallback if Kotlin side doesn't handle it yet.
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Set "TpeApp" as your autofill service in the system screen that just opened.'),
        ),
      );
    }
  }

  // ------------------------------------------------------------------
  //  Dialogs
  // ------------------------------------------------------------------

  Future<String?> _showPinDialog(String title) async {
    final controller = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          obscureText: true,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(hintText: 'Enter partner PIN'),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, controller.text),
              child: const Text('OK')),
        ],
      ),
    );
  }

  Future<String?> _showRevealReasonDialog() async {
    final ctrl = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('One-time file access consent'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Explain why file access is needed right now. This reason is logged for your handler and saved for future reveals.',
              style: TextStyle(fontSize: 12),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: ctrl,
              maxLines: 3,
              minLines: 2,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                hintText: 'Example: Logging in for scheduled homework session',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
              child: const Text('Continue')),
        ],
      ),
    );
  }

  Future<Map<String, String>?> _showEntryDialog({
    required String title,
    String site     = '',
    String username = '',
    String notes    = '',
  }) async {
    final siteCtrl     = TextEditingController(text: site);
    final usernameCtrl = TextEditingController(text: username);
    final passwordCtrl = TextEditingController();
    final notesCtrl    = TextEditingController(text: notes);

    return showDialog<Map<String, String>>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDlgState) => AlertDialog(
        title: Text(title),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: siteCtrl,
                decoration: const InputDecoration(
                    labelText: 'Site / App', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: usernameCtrl,
                decoration: const InputDecoration(
                    labelText: 'Username / Email',
                    border: OutlineInputBorder()),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: passwordCtrl,
                      obscureText: true,
                      decoration: InputDecoration(
                        labelText: site.isEmpty
                            ? 'Password'
                            : 'New Password (leave blank to keep)',
                        border: const OutlineInputBorder(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Tooltip(
                    message: 'Generate strong password',
                    child: IconButton.filled(
                      icon: const Icon(Icons.auto_awesome, size: 18),
                      onPressed: () {
                        final pwd = PasswordGenerator.generate();
                        setDlgState(() => passwordCtrl.text = pwd);
                      },
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(
                controller: notesCtrl,
                maxLines: 3,
                decoration: const InputDecoration(
                    labelText: 'Notes (optional)',
                    border: OutlineInputBorder()),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, {
                    'site':     siteCtrl.text.trim(),
                    'username': usernameCtrl.text.trim(),
                    'password': passwordCtrl.text,
                    'notes':    notesCtrl.text.trim(),
                  }),
              child: const Text('Save')),
        ],
      ),
      ),
    );
  }

  Future<Duration?> _showLockDurationDialog() async {
    return showDialog<Duration>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Lock duration'),
        content: const Text('Choose how long this entry should stay locked.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, const Duration(minutes: 15)),
            child: const Text('15m'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, const Duration(hours: 1)),
            child: const Text('1h'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, const Duration(hours: 12)),
            child: const Text('12h'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, const Duration(days: 1)),
            child: const Text('24h'),
          ),
        ],
      ),
    );
  }

  // ------------------------------------------------------------------
  //  Build
  // ------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Password Vault'),
        actions: [
          IconButton(
            icon: const Icon(Icons.lock),
            tooltip: 'Lock all entries',
            onPressed: _lockAll,
          ),
          IconButton(
            icon: const Icon(Icons.upload_file),
            tooltip: 'Import credentials',
            onPressed: _importEntries,
          ),
          IconButton(
            icon: const Icon(Icons.add),
            tooltip: 'Add credential',
            onPressed: _addEntry,
          ),
          IconButton(
            icon: const Icon(Icons.password),
            tooltip: 'Autofill settings',
            onPressed: _openAutofillSettings,
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: _load,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _entries.isEmpty
              ? const Center(
                  child: Padding(
                    padding: EdgeInsets.all(24),
                    child: Text(
                      'No credentials in the vault yet.\n'
                      'Your partner can add them remotely through the website.',
                      textAlign: TextAlign.center,
                    ),
                  ),
                )
              : ListView.separated(
                  padding: const EdgeInsets.fromLTRB(12, 12, 12, 80),
                  itemCount: _entries.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, i) => _EntryTile(
                    entry:             _entries[i],
                    revealedPassword:  _revealed[_entries[i].id],
                    countdown:         _countdowns[_entries[i].id],
                    onReveal:          () => _reveal(_entries[i]),
                    onHide:            () => _hide(_entries[i].id),
                    onEdit:            () => _editEntry(_entries[i]),
                    onDelete:          () => _deleteEntry(_entries[i]),
                    onLock:            () => _lockEntry(_entries[i]),
                  ),
                ),
    );
  }
}

// ---------------------------------------------------------------------------
//  Entry tile widget
// ---------------------------------------------------------------------------

class _EntryTile extends StatelessWidget {
  const _EntryTile({
    required this.entry,
    required this.onReveal,
    required this.onHide,
    required this.onEdit,
    required this.onDelete,
    required this.onLock,
    this.revealedPassword,
    this.countdown,
  });

  final VaultEntry entry;
  final String? revealedPassword;
  final int? countdown;
  final VoidCallback onReveal;
  final VoidCallback onHide;
  final VoidCallback onEdit;
  final VoidCallback onDelete;
  final VoidCallback onLock;

  String _formatDuration(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60);
    final s = d.inSeconds.remainder(60);
    if (h > 0) return '${h}h ${m}m';
    if (m > 0) return '${m}m ${s}s';
    return '${s}s';
  }

  @override
  Widget build(BuildContext context) {
    final locked   = entry.isLocked;
    final revealed = revealedPassword != null;

    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      leading: CircleAvatar(
        backgroundColor: Theme.of(context).colorScheme.primaryContainer,
        child: Text(
          entry.site.isNotEmpty ? entry.site[0].toUpperCase() : '?',
          style: TextStyle(
            color: Theme.of(context).colorScheme.onPrimaryContainer,
            fontWeight: FontWeight.bold,
          ),
        ),
      ),
      title: Text(
        entry.site.isNotEmpty ? entry.site : '(no site)',
        style: const TextStyle(fontWeight: FontWeight.w600),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(entry.username.isNotEmpty ? entry.username : '—'),
          if (locked)
            Text(
              '🔒 Locked for ${_formatDuration(entry.remainingLock)}',
              style: TextStyle(
                  color: Theme.of(context).colorScheme.error,
                  fontSize: 12),
            ),
          if (revealed) ...[
            const SizedBox(height: 4),
            Row(
              children: [
                Expanded(
                  child: SelectableText(
                    revealedPassword!,
                    style: const TextStyle(fontFamily: 'monospace'),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.copy, size: 18),
                  tooltip: 'Copy',
                  onPressed: () {
                    Clipboard.setData(
                        ClipboardData(text: revealedPassword!));
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Password copied.')),
                    );
                  },
                ),
              ],
            ),
            if (countdown != null)
              Text(
                'Hidden in ${countdown}s',
                style: const TextStyle(fontSize: 11, color: Colors.grey),
              ),
          ] else if (!locked)
            const Text(
              '••••••••',
              style: TextStyle(letterSpacing: 2),
            ),
        ],
      ),
      isThreeLine: true,
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (!locked)
            IconButton(
              icon: Icon(revealed ? Icons.visibility_off : Icons.visibility),
              tooltip: revealed ? 'Hide' : 'Reveal password',
              onPressed: revealed ? onHide : onReveal,
            ),
          PopupMenuButton<String>(
            tooltip: 'More actions',
            onSelected: (value) {
              switch (value) {
                case 'edit':
                  onEdit();
                  return;
                case 'lock':
                  onLock();
                  return;
                case 'delete':
                  onDelete();
                  return;
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'edit', child: Text('Edit')),
              PopupMenuItem(value: 'lock', child: Text('Lock')),
              PopupMenuItem(value: 'delete', child: Text('Delete')),
            ],
          ),
        ],
      ),
    );
  }
}
