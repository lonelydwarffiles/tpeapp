import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'services/task_repository.dart';
import 'services/chat_repository.dart';
import 'services/ritual_repository.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();

  runApp(
    MultiProvider(
      providers: [
        Provider<SharedPreferences>.value(value: prefs),
        ChangeNotifierProvider(create: (_) => TaskRepository(prefs)),
        ChangeNotifierProvider(create: (_) => ChatRepository(prefs)),
        ChangeNotifierProvider(create: (_) => RitualRepository(prefs)),
      ],
      child: const TpeApp(),
    ),
  );
}
