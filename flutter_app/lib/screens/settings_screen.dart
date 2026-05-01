import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/device_admin_channel.dart';
import '../channels/filter_service_channel.dart';

// SharedPreferences keys shared with ChatRepository
const _kHandlerEndpoint     = 'handler_endpoint';
const _kHandlerApiKey       = 'handler_api_key';
const _kHandlerModel        = 'handler_model';
const _kHandlerSystemPrompt = 'handler_system_prompt';

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
  String _webhookUrl = '';
  String _webhookToken = '';

  // Handler chat
  String _handlerEndpoint = 'https://api.openai.com';
  String _handlerApiKey = '';
  String _handlerModel = 'gpt-4o';
  String _handlerPrompt = '';

  late SharedPreferences _prefs;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    _prefs = await SharedPreferences.getInstance();
    final active = await DeviceAdminChannel.isAdminActive();
    final webhookUrl = await FilterServiceChannel.getWebhookUrl();
    final webhookToken = await FilterServiceChannel.getWebhookToken();
    setState(() {
      _adminActive = active;
      _loadingAdmin = false;
      _threshold = (_prefs.getDouble('filter_confidence_threshold') ?? 0.55);
      _strictMode = _prefs.getBool('filter_strict_mode') ?? false;
      _webhookUrl = webhookUrl ?? '';
      _webhookToken = webhookToken ?? '';
      _handlerEndpoint =
          _prefs.getString(_kHandlerEndpoint) ?? 'https://api.openai.com';
      _handlerApiKey = _prefs.getString(_kHandlerApiKey) ?? '';
      _handlerModel = _prefs.getString(_kHandlerModel) ?? 'gpt-4o';
      _handlerPrompt = _prefs.getString(_kHandlerSystemPrompt) ?? '';
    });
  }

  Future<void> _activateAdmin() async {
    await DeviceAdminChannel.requestActivation();
    await Future.delayed(const Duration(seconds: 1));
    final active = await DeviceAdminChannel.isAdminActive();
    setState(() => _adminActive = active);
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
  }

  Future<void> _applyFilterSettings() async {
    await FilterServiceChannel.setThreshold(_threshold);
    await FilterServiceChannel.setStrictMode(enabled: _strictMode);
    await FilterServiceChannel.setWebhookUrl(_webhookUrl);
    await FilterServiceChannel.setWebhookToken(_webhookToken);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Filter settings saved.')));
    }
  }

  Future<void> _applyHandlerSettings() async {
    await _prefs.setString(_kHandlerEndpoint, _handlerEndpoint);
    await _prefs.setString(_kHandlerApiKey, _handlerApiKey);
    await _prefs.setString(_kHandlerModel, _handlerModel);
    await _prefs.setString(_kHandlerSystemPrompt, _handlerPrompt);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Handler settings saved.')));
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
