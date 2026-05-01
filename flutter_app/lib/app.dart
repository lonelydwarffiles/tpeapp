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
      title: 'TPE App',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF6200EE),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const _RootRouter(),
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
