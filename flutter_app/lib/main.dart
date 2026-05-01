import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'services/task_repository.dart';
import 'services/chat_repository.dart';
import 'services/ritual_repository.dart';
import 'services/vitals_sync_service.dart';
import 'services/websocket_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();

  // Initialise WorkManager so the vitals-sync background task can be
  // registered or resumed when the user enables Health Connect sync.
  await VitalsSyncService.instance.initialize();

  runApp(
    MultiProvider(
      providers: [
        Provider<SharedPreferences>.value(value: prefs),
        ChangeNotifierProvider(create: (_) => TaskRepository(prefs)),
        ChangeNotifierProvider(create: (_) => ChatRepository(prefs)),
        ChangeNotifierProvider(create: (_) => RitualRepository(prefs)),
        Provider(create: (_) => WebSocketService(prefs)),
      ],
      child: const TpeApp(),
    ),
  );
}
