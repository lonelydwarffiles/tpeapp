import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:provider/provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:uuid/uuid.dart';

import 'channels/accessibility_setup_channel.dart';
import 'channels/filter_service_channel.dart';
import 'channels/remote_control_channel.dart';
import 'channels/device_command_channel.dart';
import 'services/api_service.dart';
import 'services/websocket_service.dart';
import 'screens/home_screen.dart';
import 'widgets/kiosk_task_overlay.dart';

const _permissionsBootstrapCompleteKey = 'permissions_bootstrap_complete';
const _autoEnrollmentStateKey = 'auto_enrollment_state';
const _autoEnrollmentErrorKey = 'auto_enrollment_error';
const _lastLatKey = 'last_status_lat';
const _lastLonKey = 'last_status_lon';
const _lastBatteryPctKey = 'last_status_battery_pct';

/// Root widget for the Flutter app shell.
class TpeApp extends StatelessWidget {
  const TpeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'TPE',
      debugShowCheckedModeBanner: false,
      theme: _buildTheme(),
      builder: (context, child) {
        return Stack(
          children: [
            if (child != null) child,
            const Positioned.fill(child: KioskTaskOverlay()),
          ],
        );
      },
      home: const _StartupGate(child: _RootRouter()),
    );
  }

  ThemeData _buildTheme() {
    const seedColor = Color(0xFFE07A8E);
    const scaffold = Color(0xFF100D11);
    final cs = ColorScheme.fromSeed(
      seedColor: seedColor,
      brightness: Brightness.dark,
    ).copyWith(
      primary: const Color(0xFFE8AEB7),
      secondary: const Color(0xFFB897C8),
      tertiary: const Color(0xFF68AAB5),
      surface: const Color(0xFF18131A),
      surfaceContainerLow: const Color(0xFF211A24),
      surfaceContainer: const Color(0xFF271F2B),
      surfaceContainerHighest: const Color(0xFF312736),
      outlineVariant: const Color(0xFF5D4A60),
    );

    final baseTextTheme = GoogleFonts.dmSansTextTheme(
      ThemeData(brightness: Brightness.dark).textTheme,
    ).apply(
      bodyColor: cs.onSurface,
      displayColor: cs.onSurface,
    );

    final textTheme = baseTextTheme.copyWith(
      headlineLarge: GoogleFonts.cormorantGaramond(
        fontSize: 44,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.2,
        color: cs.onSurface,
      ),
      headlineMedium: GoogleFonts.cormorantGaramond(
        fontSize: 34,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.2,
        color: cs.onSurface,
      ),
      titleLarge: GoogleFonts.cormorantGaramond(
        fontSize: 28,
        fontWeight: FontWeight.w700,
        color: cs.onSurface,
      ),
      labelLarge: GoogleFonts.dmSans(
        fontWeight: FontWeight.w700,
        letterSpacing: 0.3,
      ),
    );

    return ThemeData(
      colorScheme: cs,
      scaffoldBackgroundColor: scaffold,
      textTheme: textTheme,
      useMaterial3: true,
      appBarTheme: AppBarTheme(
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 1,
        backgroundColor: Colors.transparent,
        foregroundColor: cs.onSurface,
        titleTextStyle: textTheme.titleLarge?.copyWith(
          letterSpacing: 0.15,
        ),
      ),
      cardTheme: CardThemeData(
        elevation: 2,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(color: cs.outlineVariant.withOpacity(0.4)),
        ),
        color: cs.surfaceContainerLow,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: cs.surfaceContainerHighest,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(24),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(24),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(24),
          borderSide: BorderSide(color: cs.primary, width: 1.5),
        ),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        labelStyle: TextStyle(color: cs.onSurfaceVariant),
        hintStyle: TextStyle(color: cs.onSurfaceVariant.withOpacity(0.6)),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          textStyle: textTheme.labelLarge,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          textStyle: textTheme.labelLarge,
        ),
      ),
      chipTheme: ChipThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        labelPadding: const EdgeInsets.symmetric(horizontal: 4),
      ),
      dividerTheme: DividerThemeData(
        color: cs.outlineVariant.withOpacity(0.4),
        thickness: 1,
        space: 1,
      ),
      listTileTheme: const ListTileThemeData(
        contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      ),
      drawerTheme: DrawerThemeData(
        backgroundColor: cs.surface,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.only(
            topRight: Radius.circular(24),
            bottomRight: Radius.circular(24),
          ),
        ),
      ),
      sliderTheme: SliderThemeData(
        activeTrackColor: cs.primary,
        thumbColor: cs.primary,
        overlayColor: cs.primary.withOpacity(0.12),
        inactiveTrackColor: cs.surfaceContainerHighest,
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        backgroundColor: cs.inverseSurface,
        contentTextStyle: TextStyle(color: cs.onInverseSurface),
      ),
      dialogTheme: DialogThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        backgroundColor: cs.surfaceContainerHigh,
      ),
    );
  }
}

class _StartupGate extends StatefulWidget {
  const _StartupGate({required this.child});

  final Widget child;

  @override
  State<_StartupGate> createState() => _StartupGateState();
}

class _StartupGateState extends State<_StartupGate> {
  static const String _defaultEndpoint =
      String.fromEnvironment('TPE_DEFAULT_ENDPOINT', defaultValue: 'https://mochii.live');
    static const String _defaultAutoPairKey =
      String.fromEnvironment('TPE_AUTO_PAIR_KEY');
  static const String _fallbackLiveEndpoint = 'https://mochii.live';

  bool _bootstrapping = true;
  bool _acknowledged = false;
  bool _rootAvailable = false;
  bool _rootCheckFailed = false;
  bool _accessibilityEnabled = false;
  bool _accessibilityCheckFailed = false;
  bool _autoEnrollInFlight = false;
  bool _deviceStatusInFlight = false;
  Timer? _autoEnrollTimer;
  Timer? _deviceStatusTimer;
  final Map<Permission, PermissionStatus> _statuses = {};
  static const Duration _deviceStatusInterval = Duration(seconds: 75);

  static const _requiredPermissions = [
    Permission.camera,
    Permission.microphone,
    Permission.notification,
    Permission.locationWhenInUse,
    Permission.locationAlways,
    Permission.bluetoothScan,
    Permission.bluetoothConnect,
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _bootstrap());
  }

  Future<void> _bootstrap() async {
    final prefs = context.read<SharedPreferences>();
    final seen = prefs.getBool(_permissionsBootstrapCompleteKey) ?? false;
    final statuses = await _requestRequiredPermissions();

    var rootAvailable = false;
    var rootCheckFailed = false;
    var accessibilityEnabled = false;
    var accessibilityCheckFailed = false;
    try {
      rootAvailable = await RemoteControlChannel.isRootAvailable();
    } on PlatformException {
      rootCheckFailed = true;
    } on MissingPluginException {
      rootCheckFailed = true;
    }

    try {
      accessibilityEnabled = await AccessibilitySetupChannel.isEnabled();
    } on PlatformException {
      accessibilityCheckFailed = true;
    } on MissingPluginException {
      accessibilityCheckFailed = true;
    }

    final missingPermissions =
        statuses.values.any((status) => !status.isGranted);
    final missingAccessibility = !accessibilityEnabled;
    if (!mounted) return;

    setState(() {
      _statuses
        ..clear()
        ..addAll(statuses);
      _rootAvailable = rootAvailable;
      _rootCheckFailed = rootCheckFailed;
      _accessibilityEnabled = accessibilityEnabled;
      _accessibilityCheckFailed = accessibilityCheckFailed;
      _bootstrapping = false;
      _acknowledged = seen && !missingPermissions && !missingAccessibility;
    });

    // Keep command transport alive independent of individual screens.
    unawaited(context.read<WebSocketService>().ensureConnected());
    _ensureDeviceStatusLoop(prefs);

    _ensureAutoEnrollmentLoop(prefs);
  }

  void _ensureDeviceStatusLoop(SharedPreferences prefs) {
    _deviceStatusTimer?.cancel();
    unawaited(_pushDeviceStatus(prefs));
    _deviceStatusTimer = Timer.periodic(_deviceStatusInterval, (_) {
      unawaited(_pushDeviceStatus(prefs));
    });
  }

  Future<void> _pushDeviceStatus(SharedPreferences prefs) async {
    if (_deviceStatusInFlight) return;
    if (!(prefs.getBool('is_paired') ?? false)) return;
    final endpoint = (prefs.getString('partner_endpoint_url') ?? '').trim();
    if (endpoint.isEmpty) return;

    _deviceStatusInFlight = true;
    try {
      double? lat;
      double? lon;
      int? batteryPct;
      try {
        final snapshot = await DeviceCommandChannel.getDeviceSnapshot();
        final rawLat = snapshot?['lat'];
        final rawLon = snapshot?['lon'];
        final rawBattery = snapshot?['battery_pct'];
        if (rawLat is num && rawLon is num) {
          lat = rawLat.toDouble();
          lon = rawLon.toDouble();
          await prefs.setDouble(_lastLatKey, lat);
          await prefs.setDouble(_lastLonKey, lon);
        }
        if (rawBattery is num) {
          batteryPct = rawBattery.toInt().clamp(0, 100);
          await prefs.setInt(_lastBatteryPctKey, batteryPct);
        }
      } catch (_) {
        // Keep heartbeat path alive even if native snapshot is unavailable.
      }

      lat ??= prefs.getDouble(_lastLatKey);
      lon ??= prefs.getDouble(_lastLonKey);
      batteryPct ??= prefs.getInt(_lastBatteryPctKey);

      await ApiService(prefs).postDeviceStatus(
        batteryPct: batteryPct,
        lat: lat,
        lon: lon,
      );
    } catch (_) {
      // Best-effort heartbeat; failures are retried by timer.
    } finally {
      _deviceStatusInFlight = false;
    }
  }

  void _ensureAutoEnrollmentLoop(SharedPreferences prefs) {
    _autoEnrollTimer?.cancel();
    _attemptAutoEnrollment(prefs);
    _autoEnrollTimer = Timer.periodic(const Duration(seconds: 12), (_) {
      _attemptAutoEnrollment(prefs);
    });
  }

  Future<void> _attemptAutoEnrollment(SharedPreferences prefs) async {
    if (_autoEnrollInFlight) return;
    if (prefs.getBool('is_paired') ?? false) {
      await prefs.setString(_autoEnrollmentStateKey, 'connected');
      unawaited(context.read<WebSocketService>().ensureConnected());
      unawaited(_pushDeviceStatus(prefs));
      _autoEnrollTimer?.cancel();
      _autoEnrollTimer = null;
      return;
    }

    var endpoint = (prefs.getString('partner_endpoint_url') ?? '')
        .trim()
        .replaceAll(RegExp(r'/$'), '');
    if (endpoint.isEmpty) {
      endpoint = (_defaultEndpoint.trim().isNotEmpty
              ? _defaultEndpoint
              : _fallbackLiveEndpoint)
          .trim()
          .replaceAll(RegExp(r'/$'), '');
      if (endpoint.isNotEmpty) {
        await prefs.setString('partner_endpoint_url', endpoint);
      }
    }
    if (endpoint.isNotEmpty &&
        !endpoint.startsWith('https://') &&
        !endpoint.startsWith('http://')) {
      endpoint = 'https://$endpoint';
      await prefs.setString('partner_endpoint_url', endpoint);
    }
    if (!_isAllowedEnrollmentEndpoint(endpoint)) {
      await prefs.setString(_autoEnrollmentStateKey, 'retrying');
      await prefs.setString(
        _autoEnrollmentErrorKey,
        'Endpoint must be HTTPS (or local HTTP in debug). Current: ${endpoint.isEmpty ? '(empty)' : endpoint}',
      );
      return;
    }

    await prefs.setString(_autoEnrollmentStateKey, 'enrolling');
    await prefs.remove(_autoEnrollmentErrorKey);
    _autoEnrollInFlight = true;
    try {
      final deviceId = prefs.getString('device_id')?.trim().isNotEmpty == true
          ? prefs.getString('device_id')!
          : const Uuid().v4();
      await prefs.setString('device_id', deviceId);
      await prefs.setString('mqtt_client_id', deviceId);

      var autoPairKey = (prefs.getString('auto_pair_key') ?? '').trim();
      if (autoPairKey.isEmpty) {
        autoPairKey = _defaultAutoPairKey.trim();
        if (autoPairKey.isNotEmpty) {
          await prefs.setString('auto_pair_key', autoPairKey);
        }
      }
      final api = ApiService(prefs);
      final result = await api.pairAuto(
        endpoint: endpoint,
        mqttClientId: deviceId,
        autoPairKey: autoPairKey.isEmpty ? null : autoPairKey,
      );

      final webhookSecret = (result['webhook_secret'] as String?)?.trim() ?? '';
      final mqttBrokerUri =
          (result['mqtt_broker_uri'] as String?)?.trim() ?? '';
      final mqttUsername = (result['mqtt_username'] as String?)?.trim() ?? '';
      final mqttPassword = (result['mqtt_password'] as String?) ?? '';
      final mqttTopicPrefix =
          (result['mqtt_topic_prefix'] as String?)?.trim() ?? '';

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

      await prefs.setString(_autoEnrollmentStateKey, 'connected');
      await prefs.remove(_autoEnrollmentErrorKey);
      _autoEnrollTimer?.cancel();
      _autoEnrollTimer = null;
      unawaited(context.read<WebSocketService>().ensureConnected());
      unawaited(_pushDeviceStatus(prefs));
      // Enrollment should not be blocked by native service startup edge cases.
      unawaited(
        FilterServiceChannel.start()
            .timeout(const Duration(seconds: 6))
            .catchError((_) {}),
      );
    } catch (e) {
      await prefs.setString(_autoEnrollmentStateKey, 'retrying');
      await prefs.setString(_autoEnrollmentErrorKey, _compactEnrollmentError(e));
      // Keep retry loop running until backend/endpoint becomes available.
    } finally {
      _autoEnrollInFlight = false;
    }
  }

  bool _isAllowedEnrollmentEndpoint(String endpoint) {
    final uri = Uri.tryParse(endpoint);
    if (uri == null || uri.host.trim().isEmpty) return false;
    if (uri.scheme == 'https') return true;
    if (uri.scheme != 'http' || !kDebugMode) return false;

    final host = uri.host.toLowerCase().trim();
    if (host == 'localhost' || host == '127.0.0.1' || host == '10.0.2.2') {
      return true;
    }
    if (host.startsWith('192.168.') || host.startsWith('10.')) {
      return true;
    }
    if (RegExp(r'^172\.(1[6-9]|2\d|3[0-1])\.').hasMatch(host)) {
      return true;
    }
    return false;
  }

  String _compactEnrollmentError(Object error) {
    final raw = error.toString().trim();
    if (raw.isEmpty) return 'Enrollment request failed';
    final singleLine = raw.replaceAll(RegExp(r'\s+'), ' ');
    return singleLine.length <= 180
        ? singleLine
        : '${singleLine.substring(0, 180)}...';
  }

  Future<Map<Permission, PermissionStatus>>
      _requestRequiredPermissions() async {
    final statuses = <Permission, PermissionStatus>{};
    for (final permission in _requiredPermissions) {
      var status = await permission.status;
      if (!status.isGranted) {
        status = await permission.request();
      }
      statuses[permission] = status;
    }
    return statuses;
  }

  Future<void> _continue() async {
    await context
        .read<SharedPreferences>()
        .setBool(_permissionsBootstrapCompleteKey, true);
    if (!mounted) return;
    setState(() => _acknowledged = true);
  }

  @override
  void dispose() {
    _autoEnrollTimer?.cancel();
    _deviceStatusTimer?.cancel();
    super.dispose();
  }

  Future<void> _openSystemSettings() async {
    await openAppSettings();
  }

  Future<void> _openAccessibilitySettings() async {
    await AccessibilitySetupChannel.openSettings();
  }

  String _labelFor(Permission permission) {
    switch (permission) {
      case Permission.camera:
        return 'Camera';
      case Permission.microphone:
        return 'Microphone';
      case Permission.notification:
        return 'Notifications';
      case Permission.locationWhenInUse:
        return 'Location';
      case Permission.locationAlways:
        return 'Background Location';
      case Permission.bluetoothScan:
        return 'Bluetooth Scan';
      case Permission.bluetoothConnect:
        return 'Bluetooth Connect';
      default:
        return permission.toString();
    }
  }

  String _statusLabel(PermissionStatus status) {
    if (status.isGranted) return 'Granted';
    if (status.isPermanentlyDenied) return 'Blocked in system settings';
    if (status.isRestricted) return 'Restricted';
    if (status.isLimited) return 'Limited';
    return 'Denied';
  }

  @override
  Widget build(BuildContext context) {
    if (_bootstrapping) {
      return const Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 16),
              Text(
                  'Requesting required permissions and checking root access...'),
            ],
          ),
        ),
      );
    }

    if (!_acknowledged) {
      final missingPermissions = _statuses.entries
          .where((entry) => !entry.value.isGranted)
          .map((entry) => entry.key)
          .toList();
      return Scaffold(
        appBar: AppBar(title: const Text('Device Setup')),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(
              'This device needs its Android permissions granted before the app can operate reliably.',
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            const SizedBox(height: 16),
            ..._requiredPermissions.map(
              (permission) => ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(
                  _statuses[permission]?.isGranted == true
                      ? Icons.check_circle
                      : Icons.error_outline,
                ),
                title: Text(_labelFor(permission)),
                subtitle: Text(_statusLabel(
                    _statuses[permission] ?? PermissionStatus.denied)),
              ),
            ),
            const Divider(height: 32),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                _rootAvailable ? Icons.check_circle : Icons.warning_amber,
              ),
              title: const Text('Root access'),
              subtitle: Text(
                _rootCheckFailed
                    ? 'Root check failed. The native channel did not return a usable result.'
                    : _rootAvailable
                        ? 'Root access detected.'
                        : 'Root access not detected. Full device-control features will be limited.',
              ),
            ),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                _accessibilityEnabled ? Icons.check_circle : Icons.error_outline,
              ),
              title: const Text('Accessibility Service'),
              subtitle: Text(
                _accessibilityCheckFailed
                    ? 'Accessibility status check failed. Open system accessibility settings and enable the TPE service.'
                    : _accessibilityEnabled
                        ? 'Accessibility Service is enabled.'
                        : 'Enable the TPE Accessibility Service in Android Accessibility settings.',
              ),
            ),
            const SizedBox(height: 24),
            if (missingPermissions.isNotEmpty)
              FilledButton(
                onPressed: _bootstrap,
                child: const Text('Retry Permission Requests'),
              ),
            if (!_accessibilityEnabled) ...[
              if (missingPermissions.isNotEmpty) const SizedBox(height: 12),
              OutlinedButton(
                onPressed: _openAccessibilitySettings,
                child: const Text('Open Accessibility Settings'),
              ),
            ],
            if (missingPermissions.any((permission) =>
                _statuses[permission]?.isPermanentlyDenied == true)) ...[
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: _openSystemSettings,
                child: const Text('Open App Settings'),
              ),
            ],
            const SizedBox(height: 12),
            TextButton(
              onPressed: _continue,
              child: const Text('Continue Anyway'),
            ),
          ],
        ),
      );
    }

    return widget.child;
  }
}

class _RootRouter extends StatelessWidget {
  const _RootRouter();

  @override
  Widget build(BuildContext context) {
    return const HomeScreen();
  }
}
