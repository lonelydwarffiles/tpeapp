import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

import '../channels/filter_service_channel.dart';
import '../services/api_service.dart';
import 'home_screen.dart';

/// Dart equivalent of [PairingActivity].
///
/// Scans the partner's QR code, generates a stable MQTT client identity,
/// POSTs the pairing request to the partner backend via [ApiService], and
/// writes `is_paired = true` to SharedPreferences on success.
class PairingScreen extends StatefulWidget {
  const PairingScreen({super.key});

  @override
  State<PairingScreen> createState() => _PairingScreenState();
}

class _PairingScreenState extends State<PairingScreen> {
  final MobileScannerController _scanner = MobileScannerController();
  final TextEditingController _endpointController = TextEditingController();
  final TextEditingController _pairingCodeController = TextEditingController();
  bool _pairing = false;
  String _status =
      'Point your camera at the QR code provided by your accountability partner.';

  @override
  void initState() {
    super.initState();
    _seedEndpointField();
  }

  Future<void> _seedEndpointField() async {
    final prefs = await SharedPreferences.getInstance();
    final endpoint = (prefs.getString('partner_endpoint_url') ?? '').trim();
    if (!mounted || endpoint.isEmpty) return;
    _endpointController.text = endpoint;
  }

  @override
  void dispose() {
    _scanner.dispose();
    _endpointController.dispose();
    _pairingCodeController.dispose();
    super.dispose();
  }

  Future<void> _persistPairingState({
    required String endpoint,
    required String deviceId,
    String webhookSecret = '',
    String mqttBrokerUri = '',
    String mqttUsername = '',
    String mqttPassword = '',
    String mqttTopicPrefix = '',
  }) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('is_paired', true);
    await prefs.setString('partner_endpoint_url', endpoint);
    await prefs.setString('device_id', deviceId);
    await prefs.setString('mqtt_client_id', deviceId);
    await prefs.setString('webhook_url', '$endpoint/api/tpe/webhook');

    if (mqttBrokerUri.isNotEmpty) {
      await prefs.setString('mqtt_broker_uri', mqttBrokerUri);
    }
    if (mqttUsername.isNotEmpty) {
      await prefs.setString('mqtt_username', mqttUsername);
    }
    if (mqttPassword.isNotEmpty) {
      await prefs.setString('mqtt_password', mqttPassword);
    }
    if (mqttTopicPrefix.isNotEmpty) {
      await prefs.setString('mqtt_topic_prefix', mqttTopicPrefix);
    }
    if (webhookSecret.isNotEmpty) {
      await prefs.setString('webhook_bearer_token', webhookSecret);
    }
  }

  Future<void> _pairWithManualCode() async {
    if (_pairing) return;

    final endpoint =
        _endpointController.text.trim().replaceAll(RegExp(r'/$'), '');
    final pairingCode = _pairingCodeController.text.trim().toUpperCase();
    if (endpoint.isEmpty || pairingCode.isEmpty) {
      _setStatus('⚠️ Enter both endpoint and pairing code.');
      return;
    }
    if (!endpoint.startsWith('https://')) {
      _setStatus('⚠️ Partner endpoint must use HTTPS.');
      return;
    }

    setState(() {
      _pairing = true;
      _status = 'Pairing with one-time code…';
    });

    try {
      final prefs = await SharedPreferences.getInstance();
      final deviceId = prefs.getString('device_id')?.trim().isNotEmpty == true
          ? prefs.getString('device_id')!
          : const Uuid().v4();
      await prefs.setString('device_id', deviceId);
      await prefs.setString('mqtt_client_id', deviceId);

      final api = ApiService(prefs);
      final result = await api.pairWithCode(
        endpoint: endpoint,
        pairingCode: pairingCode,
        mqttClientId: deviceId,
      );

      final webhookSecret = (result['webhook_secret'] as String?)?.trim() ?? '';
      final mqttBrokerUri =
          (result['mqtt_broker_uri'] as String?)?.trim() ?? '';
      final mqttUsername = (result['mqtt_username'] as String?)?.trim() ?? '';
      final mqttPassword = (result['mqtt_password'] as String?) ?? '';
      final mqttTopicPrefix =
          (result['mqtt_topic_prefix'] as String?)?.trim() ?? '';

      await _persistPairingState(
        endpoint: endpoint,
        deviceId: deviceId,
        webhookSecret: webhookSecret,
        mqttBrokerUri: mqttBrokerUri,
        mqttUsername: mqttUsername,
        mqttPassword: mqttPassword,
        mqttTopicPrefix: mqttTopicPrefix,
      );

      await FilterServiceChannel.start();

      if (mounted) {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => const HomeScreen()),
        );
      }
    } catch (e) {
      _setStatus('⚠️ ${e.toString()}');
    }
  }

  Future<void> _handleBarcode(BarcodeCapture capture) async {
    if (_pairing) return;
    final raw = capture.barcodes.firstOrNull?.rawValue;
    if (raw == null) return;

    setState(() {
      _pairing = true;
      _status = 'QR code detected — preparing secure device identity…';
    });

    try {
      final json = jsonDecode(raw) as Map<String, dynamic>;
      final endpoint =
          ((json['endpoint'] as String?) ?? (json['loc'] as String?))
                  ?.trimRight()
                  .replaceAll(RegExp(r'/$'), '') ??
              '';
      final pairingToken = json['pairing_token'] as String;
      final webhookSecret = (json['webhook_secret'] as String?) ?? '';
      final mqttBrokerUri = (json['mqtt_broker_uri'] as String?)?.trim() ?? '';
      final mqttUsername = (json['mqtt_username'] as String?)?.trim() ?? '';
      final mqttPassword = (json['mqtt_password'] as String?) ?? '';
      final mqttTopicPrefix =
          (json['mqtt_topic_prefix'] as String?)?.trim() ?? '';

      if (!endpoint.startsWith('https://')) {
        _setStatus(
            '⚠️ Partner endpoint must use HTTPS. Contact your accountability partner.');
        return;
      }

      setState(() => _status = 'Pairing with accountability partner…');

      final prefs = await SharedPreferences.getInstance();
      final deviceId = prefs.getString('device_id')?.trim().isNotEmpty == true
          ? prefs.getString('device_id')!
          : const Uuid().v4();
      await prefs.setString('device_id', deviceId);
      await prefs.setString('mqtt_client_id', deviceId);

      final api = ApiService(prefs);
      await api.pair(
        endpoint: endpoint,
        pairingToken: pairingToken,
        mqttClientId: deviceId,
        webhookSecret: webhookSecret,
        mqttTopicPrefix: mqttTopicPrefix,
      );

      await _persistPairingState(
        endpoint: endpoint,
        deviceId: deviceId,
        webhookSecret: webhookSecret,
        mqttBrokerUri: mqttBrokerUri,
        mqttUsername: mqttUsername,
        mqttPassword: mqttPassword,
        mqttTopicPrefix: mqttTopicPrefix,
      );

      await FilterServiceChannel.start();

      if (mounted) {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => const HomeScreen()),
        );
      }
    } on FormatException {
      _setStatus(
          '⚠️ Invalid QR code. Ask your accountability partner for a new one.');
    } catch (e) {
      _setStatus('⚠️ ${e.toString()}');
    }
  }

  void _setStatus(String msg) {
    setState(() {
      _status = msg;
      _pairing = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final bg = Theme.of(context).scaffoldBackgroundColor;

    return Scaffold(
      body: Stack(
        children: [
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: bg,
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    cs.surface.withOpacity(0.94),
                    bg,
                  ],
                ),
              ),
            ),
          ),
          Positioned(
            top: -80,
            right: -40,
            child:
                _PairingGlowOrb(color: cs.primary.withOpacity(0.2), size: 220),
          ),
          Positioned(
            bottom: -30,
            left: -30,
            child: _PairingGlowOrb(
                color: cs.secondary.withOpacity(0.16), size: 180),
          ),
          SafeArea(
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 16),
                  child: Row(
                    children: [
                      Container(
                        width: 48,
                        height: 48,
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            colors: [
                              cs.primaryContainer,
                              cs.secondaryContainer
                            ],
                          ),
                          borderRadius: BorderRadius.circular(14),
                        ),
                        child: Icon(Icons.qr_code_scanner_rounded,
                            color: cs.onPrimaryContainer),
                      ),
                      const SizedBox(width: 14),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('TPE',
                              style: Theme.of(context).textTheme.titleLarge),
                          Text(
                            'Pair with your accountability partner',
                            style:
                                Theme.of(context).textTheme.bodySmall?.copyWith(
                                      color: cs.onSurfaceVariant,
                                    ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                Expanded(
                  flex: 3,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: Card(
                      child: Padding(
                        padding: const EdgeInsets.all(8),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(16),
                          child: Stack(
                            fit: StackFit.expand,
                            children: [
                              MobileScanner(
                                controller: _scanner,
                                onDetect: _handleBarcode,
                              ),
                              CustomPaint(
                                  painter:
                                      _ScanFramePainter(color: cs.primary)),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Card(
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            if (_pairing) ...[
                              CircularProgressIndicator(color: cs.primary),
                              const SizedBox(height: 16),
                            ],
                            Row(
                              children: [
                                Icon(
                                  _status.startsWith('⚠️')
                                      ? Icons.warning_amber_rounded
                                      : Icons.info_outline_rounded,
                                  color: _status.startsWith('⚠️')
                                      ? cs.error
                                      : cs.primary,
                                  size: 20,
                                ),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Text(
                                    _status,
                                    style: Theme.of(context)
                                        .textTheme
                                        .bodyMedium
                                        ?.copyWith(
                                          color: _status.startsWith('⚠️')
                                              ? cs.error
                                              : cs.onSurfaceVariant,
                                        ),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 16),
                            TextField(
                              controller: _endpointController,
                              enabled: !_pairing,
                              decoration: const InputDecoration(
                                labelText: 'Partner endpoint',
                                hintText: 'https://your-site.example',
                                border: OutlineInputBorder(),
                              ),
                            ),
                            const SizedBox(height: 10),
                            TextField(
                              controller: _pairingCodeController,
                              enabled: !_pairing,
                              textCapitalization: TextCapitalization.characters,
                              decoration: const InputDecoration(
                                labelText: 'Pairing code',
                                hintText: '6 digits',
                                border: OutlineInputBorder(),
                              ),
                            ),
                            const SizedBox(height: 10),
                            SizedBox(
                              width: double.infinity,
                              child: FilledButton.icon(
                                onPressed:
                                    _pairing ? null : _pairWithManualCode,
                                icon: const Icon(Icons.password_rounded),
                                label: const Text('Pair With Code'),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PairingGlowOrb extends StatelessWidget {
  const _PairingGlowOrb({required this.color, required this.size});

  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: RadialGradient(
            colors: [color, color.withOpacity(0)],
          ),
        ),
      ),
    );
  }
}

/// Paints corner brackets inside the scanner viewport to guide the user.
class _ScanFramePainter extends CustomPainter {
  _ScanFramePainter({required this.color});
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 3
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round;

    const len = 28.0;
    const margin = 20.0;

    final corners = [
      // top-left
      [
        Offset(margin, margin + len),
        Offset(margin, margin),
        Offset(margin + len, margin),
      ],
      // top-right
      [
        Offset(size.width - margin - len, margin),
        Offset(size.width - margin, margin),
        Offset(size.width - margin, margin + len),
      ],
      // bottom-right
      [
        Offset(size.width - margin, size.height - margin - len),
        Offset(size.width - margin, size.height - margin),
        Offset(size.width - margin - len, size.height - margin),
      ],
      // bottom-left
      [
        Offset(margin + len, size.height - margin),
        Offset(margin, size.height - margin),
        Offset(margin, size.height - margin - len),
      ],
    ];

    for (final pts in corners) {
      final path = Path()..moveTo(pts[0].dx, pts[0].dy);
      for (final pt in pts.skip(1)) {
        path.lineTo(pt.dx, pt.dy);
      }
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(_ScanFramePainter old) => old.color != color;
}
