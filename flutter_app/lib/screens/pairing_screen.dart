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
      _status = 'QR code detected — preparing secure device identity…';
    });

    try {
      final json = jsonDecode(raw) as Map<String, dynamic>;
      final endpoint    = (json['endpoint'] as String).trimRight().replaceAll(RegExp(r'/$'), '');
      final pairingToken = json['pairing_token'] as String;
      final webhookSecret = (json['webhook_secret'] as String?) ?? '';
      final mqttBrokerUri = (json['mqtt_broker_uri'] as String?)?.trim() ?? '';
      final mqttUsername = (json['mqtt_username'] as String?)?.trim() ?? '';
      final mqttPassword = (json['mqtt_password'] as String?) ?? '';
      final mqttTopicPrefix = (json['mqtt_topic_prefix'] as String?)?.trim() ?? '';

      if (!endpoint.startsWith('https://')) {
        _setStatus('⚠️ Partner endpoint must use HTTPS. Contact your accountability partner.');
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
      );

      // Persist paired state and webhook configuration.
      await prefs.setBool('is_paired', true);
      await prefs.setString('partner_endpoint_url', endpoint);
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
