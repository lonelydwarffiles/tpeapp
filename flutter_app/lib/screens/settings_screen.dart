import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/device_admin_channel.dart';
import '../channels/filter_service_channel.dart';
import '../channels/remote_control_channel.dart';
import '../channels/text_replacement_channel.dart';
import '../services/api_service.dart';
import '../services/health_service.dart';
import '../services/secure_storage_service.dart';
import '../services/vitals_sync_service.dart';
import 'password_vault_screen.dart';

// SharedPreferences keys shared with ChatRepository
const _kHandlerEndpoint     = 'handler_endpoint';
const _kHandlerApiKey       = 'handler_api_key';
const _kHandlerModel        = 'handler_model';
const _kHandlerSystemPrompt = 'handler_system_prompt';
const _kNudeNetEnabled      = 'nudenet_enabled';
const _kMediaFilterMode     = 'media_filter_mode';
const _kMediaCensorStyle    = 'media_censor_style';
const _kMediaStrictPackages = 'media_filter_strict_packages';
const _kMediaMaxInFlight    = 'media_filter_max_in_flight';

// SharedPreferences keys for the password vault (mirror of Kotlin constants)
const _kBlockPasswordChanges = 'vault_block_password_changes';
const _kRevealTimeoutSeconds = 'vault_reveal_timeout_seconds';

/// Settings / admin screen — partner-facing; PIN-protected in [HomeScreen].
///
/// Covers the features exposed by [com.tpeapp.ui.MainActivity] in the old
/// native UI:
///  - Device Admin status + activate / deactivate
///  - Filter threshold and strict mode
///  - Webhook URL and bearer token
///  - Handler (AI chat) endpoint, model, API key, system prompt
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _adminActive = false;
  bool _loadingAdmin = true;

  // Filter
  double _threshold = 0.55;
  bool _strictMode = false;
  bool _nudeNetEnabled = false;
  String _mediaFilterMode = 'speed';
  String _mediaCensorStyle = 'pixelate';
  String _mediaStrictPackagesRaw = '';
  int _mediaMaxInFlight = 4;
  String _webhookUrl = '';
  String _webhookToken = '';

  // Handler chat
  String _handlerEndpoint = 'https://api.openai.com';
  String _handlerApiKey = '';
  String _handlerModel = 'gpt-4o';
  String _handlerPrompt = '';

  // Health Connect
  bool _healthConnectEnabled = false;
  bool _healthConnectPermitted = false;
  bool _loadingHealth = true;

  // Remote Control injection mode
  String _injectionMode = RemoteControlChannel.modeAuto;
  bool? _rootAvailable;
  bool _loadingRemoteControl = true;

  // Text replacement dictionary
  Map<String, String> _textReplacementDict = {};
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
    final secureApiKey = await SecureStorageService.instance.readHandlerApiKey();
    final legacyApiKey = _prefs.getString(_kHandlerApiKey);
    final handlerApiKey = (secureApiKey != null && secureApiKey.isNotEmpty)
        ? secureApiKey
        : (legacyApiKey ?? '');
    if ((secureApiKey == null || secureApiKey.isEmpty) &&
        legacyApiKey != null &&
        legacyApiKey.isNotEmpty) {
      await SecureStorageService.instance.writeHandlerApiKey(legacyApiKey);
      await _prefs.remove(_kHandlerApiKey);
    }
    final active = await DeviceAdminChannel.isAdminActive();
    final webhookUrl = await FilterServiceChannel.getWebhookUrl();
    final webhookToken = await FilterServiceChannel.getWebhookToken();
    final healthEnabled = await VitalsSyncService.instance.isEnabled();
    final healthPermitted = await HealthService.instance.hasPermissions();
    final injectionMode = await RemoteControlChannel.getInjectionMode();
    final rootAvailable = await RemoteControlChannel.isRootAvailable();
    final textReplacementDict = await TextReplacementChannel.getDict();
    final mediaConfig = await FilterServiceChannel.getMediaFilterConfig();
    final strictPackages = (mediaConfig['strict_packages'] is List)
      ? (mediaConfig['strict_packages'] as List)
        .whereType<String>()
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .toList()
      : <String>[];
    setState(() {
      _adminActive = active;
      _loadingAdmin = false;
      _threshold = (_prefs.getDouble('filter_confidence_threshold') ?? 0.55);
      _strictMode = _prefs.getBool('filter_strict_mode') ?? false;
        _nudeNetEnabled = _prefs.getBool(_kNudeNetEnabled) ?? false;
        _mediaFilterMode = (mediaConfig['mode'] as String?)?.trim().toLowerCase() == 'strict'
          ? 'strict'
          : (_prefs.getString(_kMediaFilterMode) ?? 'speed');
        _mediaCensorStyle = (mediaConfig['censor_style'] as String?)?.trim().toLowerCase() == 'blur'
          ? 'blur'
          : (_prefs.getString(_kMediaCensorStyle) ?? 'pixelate');
        _mediaStrictPackagesRaw = strictPackages.join(', ');
        _mediaMaxInFlight = (mediaConfig['max_in_flight'] is int)
          ? (mediaConfig['max_in_flight'] as int).clamp(1, 12)
          : (_prefs.getInt(_kMediaMaxInFlight) ?? 4).clamp(1, 12);
      _webhookUrl = webhookUrl ?? '';
      _webhookToken = webhookToken ?? '';
      _handlerEndpoint =
          _prefs.getString(_kHandlerEndpoint) ?? 'https://api.openai.com';
      _handlerApiKey = handlerApiKey;
      _handlerModel = _prefs.getString(_kHandlerModel) ?? 'gpt-4o';
      _handlerPrompt = _prefs.getString(_kHandlerSystemPrompt) ?? '';
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

  Future<void> _activateAdmin() async {
    await DeviceAdminChannel.requestActivation();
    await Future.delayed(const Duration(seconds: 1));
    final active = await DeviceAdminChannel.isAdminActive();
    setState(() => _adminActive = active);
    await _emitBehavior(
      event: active ? 'device_admin_activated' : 'device_admin_activation_pending',
      reason: 'settings',
    );
  }

  Future<void> _deactivateAdmin() async {
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
  }

  Future<void> _applyFilterSettings() async {
    final strictPackages = _mediaStrictPackagesRaw
        .split(RegExp(r'[\n,]'))
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .toSet()
        .toList();

    await _prefs.setBool(_kNudeNetEnabled, _nudeNetEnabled);
    await _prefs.setString(_kMediaFilterMode, _mediaFilterMode);
    await _prefs.setString(_kMediaCensorStyle, _mediaCensorStyle);
    await _prefs.setString(_kMediaStrictPackages, strictPackages.join(','));
    await _prefs.setInt(_kMediaMaxInFlight, _mediaMaxInFlight);

    await FilterServiceChannel.setThreshold(_threshold);
    await FilterServiceChannel.setStrictMode(enabled: _strictMode);
    await FilterServiceChannel.setMediaFilterMode(_mediaFilterMode);
    await FilterServiceChannel.setMediaCensorStyle(_mediaCensorStyle);
    await FilterServiceChannel.setMediaStrictPackages(strictPackages);
    await FilterServiceChannel.setMediaMaxInFlight(_mediaMaxInFlight);
    await FilterServiceChannel.setWebhookUrl(_webhookUrl);
    await FilterServiceChannel.setWebhookToken(_webhookToken);
    await _emitBehavior(
      event: 'filter_settings_saved',
      reason: _strictMode ? 'strict_mode' : 'normal_mode',
      payload: {
        'threshold': double.parse(_threshold.toStringAsFixed(2)),
        'media_mode': _mediaFilterMode,
        'censor_style': _mediaCensorStyle,
        'strict_packages_count': strictPackages.length,
        'max_in_flight': _mediaMaxInFlight,
        'nudenet_enabled': _nudeNetEnabled,
      },
    );
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Filter settings saved.')));
    }
  }

  Future<void> _applyHandlerSettings() async {
    await _prefs.setString(_kHandlerEndpoint, _handlerEndpoint);
    await SecureStorageService.instance.writeHandlerApiKey(_handlerApiKey);
    await _prefs.remove(_kHandlerApiKey);
    await _prefs.setString(_kHandlerModel, _handlerModel);
    await _prefs.setString(_kHandlerSystemPrompt, _handlerPrompt);
    await _emitBehavior(
      event: 'handler_settings_saved',
      reason: _handlerModel,
      payload: {
        'endpoint': _handlerEndpoint,
        'prompt_length': _handlerPrompt.length,
        'api_key_set': _handlerApiKey.trim().isNotEmpty,
      },
    );
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Handler settings saved.')));
    }
  }

  // ── Health Connect ────────────────────────────────────────────────────

  /// Requests Health Connect permissions, then enables or disables the
  /// periodic background vitals-sync task based on the toggle value.
  Future<void> _toggleHealthConnect(bool enable) async {
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
    setState(() {
      _healthConnectEnabled = enable ? permitted : false;
      _healthConnectPermitted = permitted;
    });
  }

  Future<void> _setInjectionMode(String? mode) async {
    if (mode == null) return;
    await RemoteControlChannel.setInjectionMode(mode);
    setState(() => _injectionMode = mode);
    await _emitBehavior(
      event: 'remote_injection_mode_set',
      reason: mode,
      payload: {'root_available': _rootAvailable == true},
    );
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
    final updated = Map<String, String>.from(_textReplacementDict)..[pattern] = replacement;
    await TextReplacementChannel.setDict(updated);
    setState(() => _textReplacementDict = updated);
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
    final updated = Map<String, String>.from(_textReplacementDict)..remove(pattern);
    await TextReplacementChannel.setDict(updated);
    setState(() => _textReplacementDict = updated);
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

          const Divider(height: 32),

          // ── Filter settings ─────────────────────────────────────────
          Text('Content Filter', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Text('Media Censoring (NudeNet)',
              style: Theme.of(context).textTheme.titleSmall),
          SwitchListTile(
            title: const Text('Enable NudeNet media censoring'),
            subtitle: const Text('Disable only when troubleshooting performance.'),
            value: _nudeNetEnabled,
            onChanged: (v) => setState(() => _nudeNetEnabled = v),
            contentPadding: EdgeInsets.zero,
          ),
          DropdownButtonFormField<String>(
            initialValue: _mediaFilterMode,
            decoration: const InputDecoration(
              labelText: 'Media Filter Mode',
              border: OutlineInputBorder(),
            ),
            items: const [
              DropdownMenuItem(value: 'speed', child: Text('Speed (non-blocking)')),
              DropdownMenuItem(value: 'strict', child: Text('Strict (blocking for protected apps)')),
            ],
            onChanged: (v) => setState(() => _mediaFilterMode = v ?? 'speed'),
          ),
          const SizedBox(height: 8),
          DropdownButtonFormField<String>(
            initialValue: _mediaCensorStyle,
            decoration: const InputDecoration(
              labelText: 'Censor Style',
              border: OutlineInputBorder(),
            ),
            items: const [
              DropdownMenuItem(value: 'pixelate', child: Text('Pixelate (faster)')),
              DropdownMenuItem(value: 'blur', child: Text('Blur (slower, softer look)')),
            ],
            onChanged: (v) => setState(() => _mediaCensorStyle = v ?? 'pixelate'),
          ),
          const SizedBox(height: 8),
          _SettingsTextField(
            label: 'Strict Package List (comma separated)',
            value: _mediaStrictPackagesRaw,
            maxLines: 2,
            onChanged: (v) => _mediaStrictPackagesRaw = v,
          ),
          const SizedBox(height: 8),
          Row(children: [
            const Text('In-flight scan budget:'),
            Expanded(
              child: Slider(
                value: _mediaMaxInFlight.toDouble(),
                min: 1,
                max: 12,
                divisions: 11,
                label: '$_mediaMaxInFlight',
                onChanged: (v) => setState(() => _mediaMaxInFlight = v.round()),
              ),
            ),
            Text('$_mediaMaxInFlight'),
          ]),
          Row(children: [
            const Text('Threshold:'),
            Expanded(
              child: Slider(
                value: _threshold,
                min: 0.1,
                max: 1.0,
                divisions: 18,
                label: _threshold.toStringAsFixed(2),
                onChanged: (v) => setState(() => _threshold = v),
              ),
            ),
            Text(_threshold.toStringAsFixed(2)),
          ]),
          SwitchListTile(
            title: const Text('Strict Mode'),
            value: _strictMode,
            onChanged: (v) => setState(() => _strictMode = v),
            contentPadding: EdgeInsets.zero,
          ),
          _SettingsTextField(
            label: 'Webhook URL',
            value: _webhookUrl,
            onChanged: (v) => _webhookUrl = v,
          ),
          const SizedBox(height: 8),
          _SettingsTextField(
            label: 'Webhook Bearer Token',
            value: _webhookToken,
            obscure: true,
            onChanged: (v) => _webhookToken = v,
          ),
          const SizedBox(height: 12),
          FilledButton(
              onPressed: _applyFilterSettings,
              child: const Text('Save Filter Settings')),

          const Divider(height: 32),

          // ── Handler chat settings ───────────────────────────────────
          Text('Handler AI', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _SettingsTextField(
            label: 'API Endpoint',
            value: _handlerEndpoint,
            onChanged: (v) => _handlerEndpoint = v,
          ),
          const SizedBox(height: 8),
          _SettingsTextField(
            label: 'API Key',
            value: _handlerApiKey,
            obscure: true,
            onChanged: (v) => _handlerApiKey = v,
          ),
          const SizedBox(height: 8),
          _SettingsTextField(
            label: 'Model (e.g. gpt-4o)',
            value: _handlerModel,
            onChanged: (v) => _handlerModel = v,
          ),
          const SizedBox(height: 8),
          _SettingsTextField(
            label: 'System Prompt',
            value: _handlerPrompt,
            maxLines: 4,
            onChanged: (v) => _handlerPrompt = v,
          ),
          const SizedBox(height: 12),
          FilledButton(
              onPressed: _applyHandlerSettings,
              child: const Text('Save Handler Settings')),

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
                          'Syncs HeartRate & Steps to your partner server '
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
                          'Always use the RemoteControlService Accessibility '
                          'Service (no root required).'),
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
