import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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
import 'services/notification_buzz_service.dart';
import 'channels/text_replacement_channel.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SharedPreferences prefs;
  try {
    prefs = await SharedPreferences.getInstance();
  } on MissingPluginException {
    // In hybrid host builds, plugin registration can be temporarily unavailable.
    // Fall back to in-memory preferences so the UI can still boot.
    SharedPreferences.setMockInitialValues(<String, Object>{});
    prefs = await SharedPreferences.getInstance();
  } on PlatformException {
    // Some plugin failures surface as channel errors instead of MissingPluginException.
    SharedPreferences.setMockInitialValues(<String, Object>{});
    prefs = await SharedPreferences.getInstance();
  }
  await BleService().configurePersistence(prefs);
  try {
    await NotificationBuzzService.instance.start();
  } on MissingPluginException {
    // Optional startup hook; continue rendering app UI.
  } on PlatformException {
    // Optional startup hook; continue rendering app UI.
  }

  // Seed default pronoun/pup-lingo replacements on first launch.
  try {
    await TextReplacementChannel.ensureDefaults();
  } on MissingPluginException {
    // Keep startup resilient if the native channel isn't available yet.
  } on PlatformException {
    // Keep startup resilient if the native channel isn't available yet.
  }
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
  try {
    await VitalsSyncService.instance.initialize();
  } on MissingPluginException {
    // Workmanager is optional for foreground UI startup.
  } on PlatformException {
    // Workmanager is optional for foreground UI startup.
  }

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
