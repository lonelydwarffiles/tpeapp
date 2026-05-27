import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:provider/provider.dart';
import 'package:permission_handler/permission_handler.dart';

import 'channels/remote_control_channel.dart';
import 'screens/pairing_screen.dart';
import 'screens/home_screen.dart';

const _permissionsBootstrapCompleteKey = 'permissions_bootstrap_complete';

/// Root widget.  Decides whether to show [PairingScreen] or [HomeScreen]
/// based on the `is_paired` flag stored in SharedPreferences.
class TpeApp extends StatelessWidget {
  const TpeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'TPE',
      debugShowCheckedModeBanner: false,
      theme: _buildTheme(),
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
  bool _bootstrapping = true;
  bool _acknowledged = false;
  bool _rootAvailable = false;
  bool _rootCheckFailed = false;
  final Map<Permission, PermissionStatus> _statuses = {};

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
    try {
      rootAvailable = await RemoteControlChannel.isRootAvailable();
    } on PlatformException {
      rootCheckFailed = true;
    } on MissingPluginException {
      rootCheckFailed = true;
    }

    final missingPermissions =
        statuses.values.any((status) => !status.isGranted);
    if (!mounted) return;

    setState(() {
      _statuses
        ..clear()
        ..addAll(statuses);
      _rootAvailable = rootAvailable;
      _rootCheckFailed = rootCheckFailed;
      _bootstrapping = false;
      _acknowledged = seen && !missingPermissions;
    });
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

  Future<void> _openSystemSettings() async {
    await openAppSettings();
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
            const SizedBox(height: 24),
            if (missingPermissions.isNotEmpty)
              FilledButton(
                onPressed: _bootstrap,
                child: const Text('Retry Permission Requests'),
              ),
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
    final prefs = context.read<SharedPreferences>();
    final isPaired = prefs.getBool('is_paired') ?? false;
    return isPaired ? const HomeScreen() : const PairingScreen();
  }
}
