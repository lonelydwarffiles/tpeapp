import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../channels/fcm_channel.dart';
import '../channels/filter_service_channel.dart';
import '../services/api_service.dart';
import 'home_screen.dart';

/// Dart equivalent of [PairingActivity].
///
/// Scans the partner's QR code, retrieves the FCM token via [FcmChannel],
/// POSTs the pairing request to the partner backend via [ApiService], and
/// writes `is_paired = true` to SharedPreferences on success.
class PairingScreen extends StatefulWidget {
  const PairingScreen({super.key});

  @override
  State<PairingScreen> createState() => _PairingScreenState();
}

class _PairingScreenState extends State<PairingScreen> {
  final MobileScannerController _scanner = MobileScannerController();
  bool _pairing = false;
  String _status = 'Scan the QR code provided by your accountability partner.';

  @override
  void dispose() {
    _scanner.dispose();
    super.dispose();
  }

  Future<void> _handleBarcode(BarcodeCapture capture) async {
    if (_pairing) return;
    final raw = capture.barcodes.firstOrNull?.rawValue;
    if (raw == null) return;

    setState(() {
      _pairing = true;
      _status = 'QR code detected — retrieving device token…';
    });

    try {
      final json = jsonDecode(raw) as Map<String, dynamic>;
      final endpoint    = (json['endpoint'] as String).trimRight().replaceAll(RegExp(r'/$'), '');
      final pairingToken = json['pairing_token'] as String;
      final webhookSecret = (json['webhook_secret'] as String?) ?? '';

      if (!endpoint.startsWith('https://')) {
        _setStatus('⚠️ Partner endpoint must use HTTPS. Contact your accountability partner.');
        return;
      }

      setState(() => _status = 'Retrieving FCM token…');

      final fcmToken = await FcmChannel.refresh();
      if (fcmToken == null) {
        _setStatus('⚠️ Could not retrieve device token. Check Google Play Services.');
        return;
      }

      setState(() => _status = 'Pairing with accountability partner…');

      final prefs = await SharedPreferences.getInstance();
      final api = ApiService(prefs);
      await api.pair(
        endpoint: endpoint,
        pairingToken: pairingToken,
        fcmToken: fcmToken,
      );

      // Persist paired state and webhook configuration.
      await prefs.setBool('is_paired', true);
      await prefs.setString('partner_endpoint_url', endpoint);
      await prefs.setString('fcm_registration_token', fcmToken);
      await prefs.setString('webhook_url', '$endpoint/api/tpe/webhook');
      if (webhookSecret.isNotEmpty) {
        await prefs.setString('webhook_bearer_token', webhookSecret);
      }

      await FilterServiceChannel.start();

      if (mounted) {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => const HomeScreen()),
        );
      }
    } on FormatException {
      _setStatus('⚠️ Invalid QR code. Ask your accountability partner for a new one.');
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
    return Scaffold(
      appBar: AppBar(title: const Text('Pair with Partner')),
      body: Column(
        children: [
          Expanded(
            flex: 3,
            child: MobileScanner(
              controller: _scanner,
              onDetect: _handleBarcode,
            ),
          ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Center(
                child: _pairing
                    ? const CircularProgressIndicator()
                    : Text(
                        _status,
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.bodyLarge,
                      ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
