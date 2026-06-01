import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/accessibility_setup_channel.dart';
import '../channels/device_admin_channel.dart';
import '../channels/remote_control_channel.dart';
import '../channels/text_replacement_channel.dart';
import '../services/api_service.dart';
import '../services/health_service.dart';
import '../services/vitals_sync_service.dart';
import 'password_vault_screen.dart';

// SharedPreferences keys for the password vault (mirror of Kotlin constants)
const _kBlockPasswordChanges = 'vault_block_password_changes';
const _kRevealTimeoutSeconds = 'vault_reveal_timeout_seconds';

/// Settings / admin screen.
///
/// Covers the features exposed by [com.tpeapp.ui.MainActivity] in the old
/// native UI:
///  - Device Admin status + activate / deactivate
///  - Core runtime controls and health-sync permissions
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  static const Duration _channelTimeout = Duration(seconds: 4);

  bool _adminActive = false;
  bool _loadingAdmin = true;
  bool _batteryOptimizationsIgnored = false;
  bool _loadingBatteryOptimization = true;
  bool _accessibilityEnabled = false;
  bool _loadingAccessibility = true;

  // Health Connect
  bool _healthConnectEnabled = false;
  bool _healthConnectPermitted = false;
  bool _loadingHealth = true;

  // Remote Control injection mode
  String _injectionMode = RemoteControlChannel.modeAuto;
  bool? _rootAvailable;
  bool _loadingRemoteControl = true;

  // Text replacement dictionary
  Map<String, dynamic> _textReplacementDict = {};
  bool _loadingDict = true;

  // Password vault
  bool _blockPasswordChanges = false;
  int _revealTimeoutSeconds  = 10;

  late SharedPreferences _prefs;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    _prefs = await SharedPreferences.getInstance();
    final active = await _safeBool(DeviceAdminChannel.isAdminActive);
    final batteryIgnored =
      await _safeBool(DeviceAdminChannel.isIgnoringBatteryOptimizations);
    final accessibilityEnabled =
      await _safeBool(AccessibilitySetupChannel.isEnabled);
    final healthEnabled =
      await _safeBool(VitalsSyncService.instance.isEnabled);
    final healthPermitted =
      await _safeBool(HealthService.instance.hasPermissions);
    final injectionMode = await _safeString(
      RemoteControlChannel.getInjectionMode,
      fallback: RemoteControlChannel.modeAuto,
    );
    final rootAvailable =
      await _safeNullableBool(RemoteControlChannel.isRootAvailable);
    final textReplacementDict = await _safeDict(TextReplacementChannel.getDict);
    setState(() {
      _adminActive = active;
      _loadingAdmin = false;
      _batteryOptimizationsIgnored = batteryIgnored;
      _loadingBatteryOptimization = false;
      _accessibilityEnabled = accessibilityEnabled;
      _loadingAccessibility = false;
      _healthConnectEnabled = healthEnabled;
      _healthConnectPermitted = healthPermitted;
      _loadingHealth = false;
      _injectionMode = injectionMode;
      _rootAvailable = rootAvailable;
      _loadingRemoteControl = false;
      _textReplacementDict = textReplacementDict;
      _loadingDict = false;
      _blockPasswordChanges = _prefs.getBool(_kBlockPasswordChanges) ?? false;
      _revealTimeoutSeconds =
          _prefs.getInt(_kRevealTimeoutSeconds) ?? 10;
    });
  }

  Future<bool> _safeBool(Future<bool> Function() fn) async {
    try {
      return await fn().timeout(_channelTimeout);
    } catch (_) {
      return false;
    }
  }

  Future<bool?> _safeNullableBool(Future<bool> Function() fn) async {
    try {
      return await fn().timeout(_channelTimeout);
    } catch (_) {
      return null;
    }
  }

  Future<String> _safeString(
    Future<String> Function() fn, {
    required String fallback,
  }) async {
    try {
      final value = await fn().timeout(_channelTimeout);
      return value.trim().isEmpty ? fallback : value;
    } catch (_) {
      return fallback;
    }
  }

  Future<Map<String, dynamic>> _safeDict(
    Future<Map<String, dynamic>> Function() fn,
  ) async {
    try {
      return await fn().timeout(_channelTimeout);
    } catch (_) {
      return const {};
    }
  }

  Future<void> _refreshAccessibility() async {
    try {
      setState(() => _loadingAccessibility = true);
      final enabled = await AccessibilitySetupChannel.isEnabled();
      if (!mounted) return;
      setState(() {
        _accessibilityEnabled = enabled;
        _loadingAccessibility = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _loadingAccessibility = false);
      _showActionError('refresh accessibility status', error);
    }
  }

  Future<void> _openAccessibilitySettings() async {
    try {
      await AccessibilitySetupChannel.openSettings();
    } catch (error) {
      _showActionError('open accessibility settings', error);
    }
  }

  Future<void> _ensureAccessibilityPersistent() async {
    try {
      final status = await AccessibilitySetupChannel.ensurePersistent();
      final enabled = status['all_required_enabled'] == true;
      if (!mounted) return;
      setState(() => _accessibilityEnabled = enabled);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            enabled
                ? 'Accessibility services are now enabled.'
                : 'Auto-fix could not fully enable accessibility. Open system settings to finish.',
          ),
        ),
      );
    } catch (error) {
      _showActionError('auto-fix accessibility', error);
    } finally {
      await _refreshAccessibility();
    }
  }

  Future<void> _activateAdmin() async {
    try {
      final ready = await _ensureAdminPinSet();
      if (!ready) return;

      if (!mounted) return;
      final proceed = await showDialog<bool>(
            context: context,
            builder: (ctx) => AlertDialog(
              title: const Text('Enable Device Admin'),
              content: const Text(
                'You are about to open Android Device Admin settings. '
                'Enable TPE App there, then return here.',
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('Cancel'),
                ),
                FilledButton(
                  onPressed: () => Navigator.pop(ctx, true),
                  child: const Text('Open Settings'),
                ),
              ],
            ),
          ) ??
          false;
      if (!proceed) return;

      await DeviceAdminChannel.requestActivation();
      await Future.delayed(const Duration(seconds: 1));
      final active = await DeviceAdminChannel.isAdminActive();
      setState(() => _adminActive = active);
      if (!active) {
        await DeviceAdminChannel.openAdminSettings();
      }
      await _emitBehavior(
        event: active ? 'device_admin_activated' : 'device_admin_activation_pending',
        reason: 'settings',
      );

      if (mounted && !active) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Device Admin is still inactive. Enable it in system settings, then return here.',
            ),
          ),
        );
      }
    } catch (error) {
      _showActionError('activate Device Admin', error);
    }
  }

  Future<bool> _ensureAdminPinSet() async {
    final hasPin = await DeviceAdminChannel.isPinSet();
    if (hasPin) return true;

    if (!mounted) return false;
    final pin = await _showPinDialog('Create Device Admin PIN');
    if (pin == null) return false;
    final trimmed = pin.trim();
    if (trimmed.length < 4) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('PIN must be at least 4 digits.')),
        );
      }
      return false;
    }

    await DeviceAdminChannel.setPin(trimmed);
    await _emitBehavior(
      event: 'device_admin_pin_set',
      reason: 'settings',
    );
    return true;
  }

  Future<void> _requestBatteryOptimizationExemption() async {
    try {
      await DeviceAdminChannel.requestIgnoreBatteryOptimizations();
      await Future.delayed(const Duration(milliseconds: 800));
      final ignored = await DeviceAdminChannel.isIgnoringBatteryOptimizations();
      if (!mounted) return;
      setState(() => _batteryOptimizationsIgnored = ignored);

      await _emitBehavior(
        event: ignored
            ? 'battery_optimization_disabled'
            : 'battery_optimization_still_enabled',
        reason: 'settings',
      );

      if (!mounted) return;
      if (!ignored) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Battery optimization is still enabled. Disable it for reliable background control.',
            ),
          ),
        );
      }
    } catch (error) {
      _showActionError('change battery optimization setting', error);
    }
  }

  Future<void> _openBatteryOptimizationSettings() async {
    try {
      await DeviceAdminChannel.openBatteryOptimizationSettings();
    } catch (error) {
      _showActionError('open battery optimization settings', error);
    }
  }

  Future<void> _deactivateAdmin() async {
    try {
      final pin = await _showPinDialog('Deactivate Admin');
      if (pin == null) return;
      final ok = await DeviceAdminChannel.deactivate(pin);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(ok ? 'Admin deactivated.' : 'Incorrect PIN.'),
        ));
      }
      if (ok) setState(() => _adminActive = false);
      await _emitBehavior(
        event: ok ? 'device_admin_deactivated' : 'device_admin_deactivate_failed',
        reason: ok ? 'settings' : 'pin_incorrect',
      );
    } catch (error) {
      _showActionError('deactivate Device Admin', error);
    }
  }

  // ── Health Connect ────────────────────────────────────────────────────

  /// Requests Health Connect permissions, then enables or disables the
  /// periodic background vitals-sync task based on the toggle value.
  Future<void> _toggleHealthConnect(bool enable) async {
    try {
      if (enable) {
        final granted = await HealthService.instance.requestPermissions();
        if (!granted) {
          unawaited(_emitBehavior(
            event: 'health_connect_toggle_failed',
            reason: 'permissions_not_granted',
            payload: {'requested_enable': true},
          ));
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
              content: Text(
                  'Health Connect permissions were not granted. '
                  'Please enable them in the Health Connect app.'),
            ));
          }
          return;
        }
        await VitalsSyncService.instance.enable();
        unawaited(_emitBehavior(
          event: 'health_connect_toggle',
          reason: 'enabled',
          payload: {'requested_enable': true},
        ));
      } else {
        await VitalsSyncService.instance.disable();
        unawaited(_emitBehavior(
          event: 'health_connect_toggle',
          reason: 'disabled',
          payload: {'requested_enable': false},
        ));
      }
      final permitted = await HealthService.instance.hasPermissions();
      if (!mounted) return;
      setState(() {
        _healthConnectEnabled = enable ? permitted : false;
        _healthConnectPermitted = permitted;
      });
    } catch (error) {
      _showActionError('update Health Connect', error);
    }
  }

  Future<void> _setInjectionMode(String? mode) async {
    if (mode == null) return;
    try {
      await RemoteControlChannel.setInjectionMode(mode);
      setState(() => _injectionMode = mode);
      await _emitBehavior(
        event: 'remote_injection_mode_set',
        reason: mode,
        payload: {'root_available': _rootAvailable == true},
      );
    } catch (error) {
      _showActionError('set injection mode', error);
    }
  }

  // ── Text Replacement Dictionary ──────────────────────────────────────

  Future<void> _addDictEntry() async {
    final patternCtrl     = TextEditingController();
    final replacementCtrl = TextEditingController();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Add Replacement Rule'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: patternCtrl,
              decoration: const InputDecoration(
                labelText: 'Regex pattern',
                hintText: r'(?i)\b(good)\s+(boy|girl)\b',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: replacementCtrl,
              decoration: const InputDecoration(
                labelText: 'Replacement (use \$1, \$2 … for groups)',
                hintText: r'$1 pup',
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Add')),
        ],
      ),
    );
    if (confirmed != true) return;
    final pattern     = patternCtrl.text.trim();
    final replacement = replacementCtrl.text;
    if (pattern.isEmpty) return;
    // Validate the regex before saving to avoid silent failures in the hook.
    try {
      RegExp(pattern);
    } on FormatException {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Invalid regex pattern — rule not saved.')));
      }
      return;
    }
    final updated = Map<String, dynamic>.from(_textReplacementDict)..[pattern] = replacement;
    await TextReplacementChannel.setDict(updated);
    setState(() => _textReplacementDict = updated);
    await _syncTextReplacementDict(updated);
    await _emitBehavior(
      event: 'text_replacement_rule_added',
      reason: 'text_correction',
      payload: {
        'pattern': pattern,
        'replacement_length': replacement.length,
        'rules_count': updated.length,
      },
    );
  }

  Future<void> _removeDictEntry(String pattern) async {
    final updated = Map<String, dynamic>.from(_textReplacementDict)..remove(pattern);
    await TextReplacementChannel.setDict(updated);
    setState(() => _textReplacementDict = updated);
    await _syncTextReplacementDict(updated);
    await _emitBehavior(
      event: 'text_replacement_rule_removed',
      reason: 'text_correction',
      payload: {
        'pattern': pattern,
        'rules_count': updated.length,
      },
    );
  }

  Future<void> _saveVaultSettings() async {
    try {
      await _prefs.setBool(_kBlockPasswordChanges, _blockPasswordChanges);
      await _prefs.setInt(_kRevealTimeoutSeconds, _revealTimeoutSeconds);
      await _emitBehavior(
        event: 'vault_settings_saved',
        reason: _blockPasswordChanges ? 'blocking_enabled' : 'blocking_disabled',
        payload: {'reveal_timeout_seconds': _revealTimeoutSeconds},
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Vault settings saved.')));
      }
    } catch (error) {
      _showActionError('save vault settings', error);
    }
  }

  void _showActionError(String action, Object error) {
    if (!mounted) return;
    final message = error
        .toString()
        .replaceAll(RegExp(r'^Exception:\s*'), '')
        .replaceAll(RegExp(r'^PlatformException\([^,]+,\s*'), '')
        .replaceAll(RegExp(r',\s*null\)$'), '')
        .trim();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          message.isEmpty
              ? 'Failed to $action.'
              : 'Failed to $action: $message',
        ),
      ),
    );
  }

  Future<void> _emitBehavior({
    required String event,
    String? reason,
    Map<String, dynamic>? payload,
  }) async {
    try {
      await ApiService(_prefs).postBehaviorEvent(
        event: event,
        reason: reason,
        payload: payload,
      );
    } catch (_) {
      // Best-effort telemetry only.
    }
  }

  Future<void> _syncTextReplacementDict(Map<String, dynamic> dict) async {
    final endpoint = (_prefs.getString('partner_endpoint_url') ?? '').trim();
    if (endpoint.isEmpty) {
      return;
    }

    try {
      await ApiService(_prefs).pushTextReplacementDict(dict: dict);
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Text replacement saved locally, but could not sync to the device runtime.',
          ),
        ),
      );
    }
  }

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
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, controller.text),
              child: const Text('OK')),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // ── Device Admin ────────────────────────────────────────────
          Text('Device Admin', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _loadingAdmin
              ? const LinearProgressIndicator()
              : Row(children: [
                  Expanded(
                    child: Text(_adminActive ? '✅ Active' : '❌ Inactive'),
                  ),
                  if (!_adminActive)
                    FilledButton(
                        onPressed: _activateAdmin,
                        child: const Text('Activate'))
                  else
                    OutlinedButton(
                        onPressed: _deactivateAdmin,
                        child: const Text('Deactivate')),
                ]),

          const SizedBox(height: 14),
          Text('Battery Optimization',
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          _loadingBatteryOptimization
              ? const LinearProgressIndicator()
              : Row(children: [
                  Expanded(
                    child: Text(
                      _batteryOptimizationsIgnored
                          ? '✅ Exempted (recommended)'
                          : '❌ Optimization enabled',
                    ),
                  ),
                  if (_batteryOptimizationsIgnored)
                    OutlinedButton(
                      onPressed: _openBatteryOptimizationSettings,
                      child: const Text('Open Settings'),
                    )
                  else
                    FilledButton(
                      onPressed: _requestBatteryOptimizationExemption,
                      child: const Text('Disable'),
                    ),
                ]),

          const SizedBox(height: 14),
          Text('Accessibility Service',
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          _loadingAccessibility
              ? const LinearProgressIndicator()
              : Row(children: [
                  Expanded(
                    child: Text(
                      _accessibilityEnabled
                          ? '✅ TPE Accessibility Companion enabled'
                          : '❌ TPE Accessibility Companion disabled',
                    ),
                  ),
                  OutlinedButton(
                    onPressed: _refreshAccessibility,
                    child: const Text('Refresh'),
                  ),
                  const SizedBox(width: 8),
                  if (_accessibilityEnabled)
                    OutlinedButton(
                      onPressed: _openAccessibilitySettings,
                      child: const Text('Open Settings'),
                    )
                  else ...[
                    OutlinedButton(
                      onPressed: _ensureAccessibilityPersistent,
                      child: const Text('Auto-Fix'),
                    ),
                    const SizedBox(width: 8),
                    FilledButton(
                      onPressed: _openAccessibilitySettings,
                      child: const Text('Enable'),
                    ),
                  ],
                ]),

          const Divider(height: 32),

          // ── Health Connect ──────────────────────────────────────────
          Text('Health Connect', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _loadingHealth
              ? const LinearProgressIndicator()
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _healthConnectPermitted
                          ? '✅ Permissions granted'
                          : '❌ Permissions not granted',
                    ),
                    const SizedBox(height: 4),
                    SwitchListTile(
                      title: const Text('Enable Vitals Sync'),
                      subtitle: const Text(
                          'Syncs all available Health Connect vitals '
                          '(heart, steps, sleep, oxygen, respiratory, and more) '
                          'to your partner server '
                          'every 15 minutes.'),
                      value: _healthConnectEnabled,
                      onChanged: _toggleHealthConnect,
                      contentPadding: EdgeInsets.zero,
                    ),
                  ],
                ),

          const Divider(height: 32),

          // ── Remote Control injection mode ───────────────────────────
          Text('Remote Control', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _loadingRemoteControl
              ? const LinearProgressIndicator()
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Semantics(
                      label: _rootAvailable == true
                          ? 'Root available'
                          : 'Root unavailable',
                      child: Text(
                        _rootAvailable == true
                            ? '✅ Root available'
                            : '❌ Root unavailable',
                      ),
                    ),
                    const SizedBox(height: 8),
                    const Text('Gesture injection method:'),
                    RadioListTile<String>(
                      title: const Text('Auto-detect'),
                      subtitle: const Text(
                          'Use root when available, otherwise fall back to '
                          'Accessibility Service.'),
                      value: RemoteControlChannel.modeAuto,
                      groupValue: _injectionMode,
                      onChanged: _setInjectionMode,
                      contentPadding: EdgeInsets.zero,
                    ),
                    RadioListTile<String>(
                      title: const Text('Force Root'),
                      subtitle: const Text(
                          'Always use su -c input tap (rooted devices only).'),
                      value: RemoteControlChannel.modeRoot,
                      groupValue: _injectionMode,
                      onChanged: _setInjectionMode,
                      contentPadding: EdgeInsets.zero,
                    ),
                    RadioListTile<String>(
                      title: const Text('Force Accessibility'),
                      subtitle: const Text(
                          'Always use the TPE Accessibility Companion '
                          '(no root required).'),
                      value: RemoteControlChannel.modeAccessibility,
                      groupValue: _injectionMode,
                      onChanged: _setInjectionMode,
                      contentPadding: EdgeInsets.zero,
                    ),
                  ],
                ),

          const Divider(height: 32),

          // ── Text Replacement Dictionary ─────────────────────────────
          Row(
            children: [
              Expanded(
                child: Text('Text Replacement',
                    style: Theme.of(context).textTheme.titleMedium),
              ),
              IconButton(
                icon: const Icon(Icons.add),
                tooltip: 'Add rule',
                onPressed: _addDictEntry,
              ),
            ],
          ),
          _loadingDict
              ? const LinearProgressIndicator()
              : _textReplacementDict.isEmpty
                  ? const Padding(
                      padding: EdgeInsets.symmetric(vertical: 8),
                      child: Text('No replacement rules configured.'),
                    )
                  : Column(
                      children: _textReplacementDict.entries.map((e) {
                        return ListTile(
                          contentPadding: EdgeInsets.zero,
                          title: Text(
                            e.key,
                            style: const TextStyle(fontFamily: 'monospace'),
                          ),
                          subtitle: Text('→ ${e.value}'),
                          trailing: IconButton(
                            icon: const Icon(Icons.delete_outline),
                            tooltip: 'Remove rule',
                            onPressed: () => _removeDictEntry(e.key),
                          ),
                        );
                      }).toList(),
                    ),

          const Divider(height: 32),

          // ── Password Vault ──────────────────────────────────────────
          Text('Password Vault', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          SwitchListTile(
            title: const Text('Block Password Changes'),
            subtitle: const Text(
                'Press BACK when any app shows a "change password" '
                'screen, and notify your partner.'),
            value: _blockPasswordChanges,
            onChanged: (v) => setState(() => _blockPasswordChanges = v),
            contentPadding: EdgeInsets.zero,
          ),
          const SizedBox(height: 4),
          Row(children: [
            const Text('Reveal timeout:'),
            Expanded(
              child: Slider(
                value: _revealTimeoutSeconds.toDouble(),
                min: 5,
                max: 60,
                divisions: 11,
                label: '${_revealTimeoutSeconds}s',
                onChanged: (v) =>
                    setState(() => _revealTimeoutSeconds = v.round()),
              ),
            ),
            Text('${_revealTimeoutSeconds}s'),
          ]),
          const SizedBox(height: 8),
          FilledButton(
              onPressed: _saveVaultSettings,
              child: const Text('Save Vault Settings')),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            icon: const Icon(Icons.lock_outline),
            label: const Text('Open Password Vault'),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(
                builder: (_) => PasswordVaultScreen(
                  revealTimeoutSeconds: _revealTimeoutSeconds,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SettingsTextField extends StatelessWidget {
  const _SettingsTextField({
    required this.label,
    required this.value,
    required this.onChanged,
    this.obscure = false,
    this.maxLines = 1,
  });
  final String label;
  final String value;
  final ValueChanged<String> onChanged;
  final bool obscure;
  final int maxLines;

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      initialValue: value,
      obscureText: obscure,
      maxLines: obscure ? 1 : maxLines,
      decoration: InputDecoration(
        labelText: label,
        border: const OutlineInputBorder(),
        isDense: true,
      ),
      onChanged: onChanged,
    );
  }
}

// (no stale extension needed — keys are top-level constants above)
