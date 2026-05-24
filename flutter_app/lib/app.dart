import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:provider/provider.dart';

import 'screens/pairing_screen.dart';
import 'screens/home_screen.dart';

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
      home: const _RootRouter(),
    );
  }

  ThemeData _buildTheme() {
    const seedColor = Color(0xFFC44569); // deep rose
    final cs = ColorScheme.fromSeed(
      seedColor: seedColor,
      brightness: Brightness.dark,
    );
    return ThemeData(
      colorScheme: cs,
      useMaterial3: true,
      appBarTheme: AppBarTheme(
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 1,
        backgroundColor: cs.surface,
        foregroundColor: cs.onSurface,
        titleTextStyle: TextStyle(
          color: cs.onSurface,
          fontSize: 20,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.15,
        ),
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
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
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        labelStyle: TextStyle(color: cs.onSurfaceVariant),
        hintStyle: TextStyle(color: cs.onSurfaceVariant.withOpacity(0.6)),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
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

class _RootRouter extends StatelessWidget {
  const _RootRouter();

  @override
  Widget build(BuildContext context) {
    final prefs = context.read<SharedPreferences>();
    final isPaired = prefs.getBool('is_paired') ?? false;
    return isPaired ? const HomeScreen() : const PairingScreen();
  }
}
