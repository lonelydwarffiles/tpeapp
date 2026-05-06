import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../channels/partner_pin_channel.dart';
import '../channels/password_vault_channel.dart';

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
    final password = await PasswordVaultChannel.revealPassword(entry.id);
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
      builder: (ctx) => AlertDialog(
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
              TextField(
                controller: passwordCtrl,
                obscureText: true,
                decoration: InputDecoration(
                  labelText: site.isEmpty
                      ? 'Password'
                      : 'New Password (leave blank to keep)',
                  border: const OutlineInputBorder(),
                ),
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
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: _load,
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _addEntry,
        tooltip: 'Add credential (Partner PIN required)',
        child: const Icon(Icons.add),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _entries.isEmpty
              ? const Center(
                  child: Padding(
                    padding: EdgeInsets.all(24),
                    child: Text(
                      'No credentials in the vault yet.\n'
                      'Your partner can add them remotely, or tap + to add one now.',
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
                'Hides in ${countdown}s',
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
            onSelected: (v) {
              if (v == 'edit') onEdit();
              if (v == 'delete') onDelete();
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'edit',   child: Text('Edit')),
              PopupMenuItem(value: 'delete', child: Text('Delete',
                  style: TextStyle(color: Colors.red))),
            ],
          ),
        ],
      ),
    );
  }
}
