import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'services/api_service.dart';
import 'services/task_repository.dart';
import 'services/chat_repository.dart';
import 'services/kiosk_task_controller.dart';
import 'services/ritual_repository.dart';
import 'services/vitals_sync_service.dart';
import 'services/websocket_service.dart';
import 'services/ble_service.dart';
import 'services/intiface_service.dart';
import 'channels/text_replacement_channel.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();

  // Seed default pronoun/pup-lingo replacements on first launch.
  await TextReplacementChannel.ensureDefaults();
  unawaited(() async {
    try {
      final dict = await TextReplacementChannel.getDict();
      final policy = await TextReplacementChannel.getPolicy();
      await ApiService(prefs).pushTextReplacementDict(
        dict: dict,
      );
      await ApiService(prefs).pushTextReplacementPolicy(
        policy: policy.isEmpty ? TextReplacementChannel.defaultPolicy : policy,
      );
    } catch (_) {
      // Best-effort sync only.
    }
  }());

  // Initialise WorkManager so the vitals-sync background task can be
  // registered or resumed when the user enables Health Connect sync.
  await VitalsSyncService.instance.initialize();

  runApp(
    MultiProvider(
      providers: [
        Provider<SharedPreferences>.value(value: prefs),
        ChangeNotifierProvider(create: (_) => TaskRepository(prefs)),
        ChangeNotifierProvider(create: (_) => KioskTaskController(prefs)),
        ChangeNotifierProvider(create: (_) => ChatRepository(prefs)),
        ChangeNotifierProvider(create: (_) => RitualRepository(prefs)),
        Provider(create: (_) => WebSocketService(prefs)),
        ChangeNotifierProvider(create: (_) => BleService()),
        ChangeNotifierProvider(create: (_) => IntifaceService()),
      ],
      child: const TpeApp(),
    ),
  );
}
