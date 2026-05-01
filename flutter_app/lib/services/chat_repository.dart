import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

import '../models/chat_message.dart';

/// Dart equivalent of [com.tpeapp.handler.ChatRepository].
///
/// Persists chat history in SharedPreferences and calls an
/// OpenAI-compatible `/v1/chat/completions` endpoint.
class ChatRepository extends ChangeNotifier {
  ChatRepository(this._prefs) {
    _load();
  }

  static const endpointKey     = _endpointKey;
  static const apiKeyKey       = _apiKeyKey;
  static const modelKey        = _modelKey;
  static const systemPromptKey = _systemPromptKey;

  static const _historyKey      = 'handler_chat_history_json';
  static const _endpointKey     = 'handler_endpoint';
  static const _apiKeyKey       = 'handler_api_key';
  static const _systemPromptKey = 'handler_system_prompt';
  static const _modelKey        = 'handler_model';

  static const _defaultEndpoint = 'https://api.openai.com';
  static const _defaultModel    = 'gpt-4o';
  static const _defaultPrompt   =
      'You are Handler, a strict but caring AI companion in a TPE (Total Power Exchange) '
      'dynamic. You speak with authority and warmth. You hold the sub accountable to their '
      'rules, offer guidance, and track their progress. You may use the word \'Handler\' to '
      'refer to yourself. Keep replies concise unless the sub needs detailed guidance.';
  static const _maxHistory = 100;

  final SharedPreferences _prefs;
  List<ChatMessage> _history = [];

  List<ChatMessage> get history => List.unmodifiable(_history);

  String get endpoint =>
      _prefs.getString(_endpointKey)?.trimRight() ?? _defaultEndpoint;
  String? get apiKey =>
      _prefs.getString(_apiKeyKey)?.let((k) => k.isNotEmpty ? k : null);
  String get model => _prefs.getString(_modelKey) ?? _defaultModel;
  String get systemPrompt =>
      _prefs.getString(_systemPromptKey)?.let((p) => p.isNotEmpty ? p : null) ??
      _defaultPrompt;

  void _load() {
    final json = _prefs.getString(_historyKey);
    if (json == null) return;
    try {
      final list = jsonDecode(json) as List<dynamic>;
      _history =
          list.map((e) => ChatMessage.fromJson(e as Map<String, dynamic>)).toList();
    } catch (_) {
      _history = [];
    }
  }

  Future<void> _save() async {
    final encoded =
        jsonEncode(_history.map((m) => m.toJson()).toList());
    await _prefs.setString(_historyKey, encoded);
  }

  Future<ChatMessage> addUserMessage(String text) async {
    const uuid = Uuid();
    final msg = ChatMessage(
      id: uuid.v4(),
      role: 'user',
      content: text,
      timestamp: DateTime.now().millisecondsSinceEpoch,
    );
    _history.add(msg);
    if (_history.length > _maxHistory) {
      _history.removeRange(0, _history.length - _maxHistory);
    }
    await _save();
    notifyListeners();
    return msg;
  }

  Future<ChatMessage> addAssistantMessage(String text) async {
    const uuid = Uuid();
    final msg = ChatMessage(
      id: uuid.v4(),
      role: 'assistant',
      content: text,
      timestamp: DateTime.now().millisecondsSinceEpoch,
    );
    _history.add(msg);
    if (_history.length > _maxHistory) {
      _history.removeRange(0, _history.length - _maxHistory);
    }
    await _save();
    notifyListeners();
    return msg;
  }

  Future<void> clearHistory() async {
    _history.clear();
    await _prefs.remove(_historyKey);
    notifyListeners();
  }

  /// Sends [userText] to the configured OpenAI-compatible endpoint and returns
  /// the assistant's reply.  Throws on network failure or non-200 response.
  Future<String> sendMessage(String userText) async {
    final baseUrl = endpoint.endsWith('/') ? endpoint.dropLast(1) : endpoint;

    final recent = _history.takeLast(20).toList();

    final messages = [
      {'role': 'system', 'content': systemPrompt},
      ...recent.map((m) => {'role': m.role, 'content': m.content}),
      {'role': 'user', 'content': userText},
    ];

    final headers = {
      'Content-Type': 'application/json',
      if (apiKey != null) 'Authorization': 'Bearer $apiKey',
    };

    final body = jsonEncode({'model': model, 'messages': messages});

    final response = await http
        .post(Uri.parse('$baseUrl/v1/chat/completions'),
            headers: headers, body: body)
        .timeout(const Duration(seconds: 60));

    if (response.statusCode != 200) {
      throw Exception('Handler API error ${response.statusCode}: ${response.body}');
    }

    final decoded = jsonDecode(response.body) as Map<String, dynamic>;
    return (decoded['choices'] as List<dynamic>)[0]['message']['content']
        as String;
  }

  // ── Settings setters (called when FCM updates arrive) ────────────────

  Future<void> setEndpoint(String url) async {
    await _prefs.setString(_endpointKey, url);
  }

  Future<void> setApiKey(String key) async {
    await _prefs.setString(_apiKeyKey, key);
  }

  Future<void> setSystemPrompt(String prompt) async {
    await _prefs.setString(_systemPromptKey, prompt);
  }

  Future<void> setModel(String m) async {
    await _prefs.setString(_modelKey, m);
  }
}

// ── Private extension helpers ──────────────────────────────────────────

extension _StringX on String {
  T let<T>(T Function(String) block) => block(this);
  String dropLast(int n) => substring(0, length - n);
}

extension<T> on List<T> {
  List<T> takeLast(int n) => length <= n ? this : sublist(length - n);
}
