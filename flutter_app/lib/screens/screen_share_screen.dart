import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/screen_share_service.dart';

/// Screen-sharing screen — lets the device owner start or stop a live
/// WebRTC screen-share session with their accountability partner.
///
/// Features:
///  • Reads the partner endpoint URL from SharedPreferences to pre-fill
///    the signaling URL field.
///  • Consent toggle that enables the remote-control DataChannel so the
///    partner can inject tap gestures via [ScreenShareService].
///  • A "Start" button that calls [ScreenShareService.start] (which
///    triggers the Android MediaProjection consent dialog internally
///    through flutter_webrtc).
///  • A "Stop" button that tears down the peer connection cleanly.
class ScreenShareScreen extends StatefulWidget {
  const ScreenShareScreen({super.key});

  @override
  State<ScreenShareScreen> createState() => _ScreenShareScreenState();
}

class _ScreenShareScreenState extends State<ScreenShareScreen> {
  final _sessionController = TextEditingController();
  final _signalingController = TextEditingController();
  final _service = ScreenShareService();

  bool _remoteControlEnabled = false;
  bool _isRunning = false;
  bool _isBusy = false;
  String? _statusMessage;

  @override
  void initState() {
    super.initState();
    _service.onStateChanged = _onServiceStateChanged;
    _loadSavedEndpoint();
  }

  Future<void> _loadSavedEndpoint() async {
    final prefs = await SharedPreferences.getInstance();
    final endpoint = prefs.getString('partner_endpoint_url') ?? '';
    if (endpoint.isNotEmpty && mounted) {
      // Derive default signaling URL from partner endpoint.
      // Session ID is left blank for the user to fill in.
      setState(() {
        _signalingController.text =
            '${endpoint.replaceFirst(RegExp(r'^http'), 'ws')}'
            '/api/tpe/signal/';
      });
    }
  }

  @override
  void dispose() {
    _sessionController.dispose();
    _signalingController.dispose();
    _service.onStateChanged = null;
    super.dispose();
  }

  void _onServiceStateChanged() {
    if (mounted) {
      setState(() {
        _isRunning = _service.isRunning;
        _isBusy = false;
        _statusMessage =
            _isRunning ? '🔴 Streaming to partner…' : 'Ready';
      });
    }
  }

  Future<void> _startSharing() async {
    final sessionId = _sessionController.text.trim();
    final signalingBase = _signalingController.text.trim();

    if (signalingBase.isEmpty) {
      _showSnack('Enter a signaling URL.');
      return;
    }
    if (sessionId.isEmpty) {
      _showSnack('Enter a partner session ID.');
      return;
    }

    if (_remoteControlEnabled) {
      final confirmed = await _showRemoteControlConsent();
      if (!confirmed) return;
    }

    // Build full signaling URL: base already ends with '/api/tpe/signal/'
    final signalingUrl = signalingBase.endsWith('/')
        ? '$signalingBase$sessionId'
        : '$signalingBase/$sessionId';

    setState(() {
      _isBusy = true;
      _statusMessage = 'Connecting…';
    });

    try {
      await _service.start(
        signalingUrl: signalingUrl,
        remoteControlEnabled: _remoteControlEnabled,
      );
    } catch (e) {
      if (mounted) {
        setState(() {
          _isBusy = false;
          _statusMessage = '⚠️ Failed to start: $e';
        });
      }
    }
  }

  Future<void> _stopSharing() async {
    setState(() => _isBusy = true);
    await _service.stop();
  }

  Future<bool> _showRemoteControlConsent() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Allow remote control?'),
        content: const Text(
          'Your partner will be able to tap and interact with your screen '
          'while the session is active.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Allow'),
          ),
        ],
      ),
    );
    return ok ?? false;
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Screen Share')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Share your screen with your accountability partner for a '
              'live review session over WebRTC.',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 20),

            // -- Signaling URL field --
            TextField(
              controller: _signalingController,
              enabled: !_isRunning && !_isBusy,
              decoration: const InputDecoration(
                labelText: 'Signaling URL (base)',
                hintText: 'wss://example.com/api/tpe/signal/',
                border: OutlineInputBorder(),
              ),
              keyboardType: TextInputType.url,
            ),
            const SizedBox(height: 12),

            // -- Session ID field --
            TextField(
              controller: _sessionController,
              enabled: !_isRunning && !_isBusy,
              decoration: const InputDecoration(
                labelText: 'Partner Session ID',
                hintText: 'abc123',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),

            // -- Remote control consent toggle --
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Allow partner to control my screen'),
              subtitle: const Text(
                'Enables the remote-control DataChannel so your partner '
                'can inject taps and other gestures.',
              ),
              value: _remoteControlEnabled,
              onChanged: (_isRunning || _isBusy)
                  ? null
                  : (v) => setState(() => _remoteControlEnabled = v),
            ),
            const SizedBox(height: 20),

            // -- Status text --
            if (_statusMessage != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(
                  _statusMessage!,
                  style: Theme.of(context).textTheme.bodySmall,
                  textAlign: TextAlign.center,
                ),
              ),

            // -- Start / Stop button --
            if (_isBusy)
              const Center(child: CircularProgressIndicator())
            else if (_isRunning)
              FilledButton.tonal(
                onPressed: _stopSharing,
                child: const Text('Stop Sharing'),
              )
            else
              FilledButton(
                onPressed: _startSharing,
                child: const Text('Start Screen Share'),
              ),
          ],
        ),
      ),
    );
  }
}
