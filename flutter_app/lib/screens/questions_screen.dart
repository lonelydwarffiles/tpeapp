import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/api_service.dart';

/// Dart equivalent of [QuestionsActivity] (partner-facing screen).
///
/// Fetches, answers, and deletes "Puppy Pouch" questions via [ApiService].
/// PIN-protected in [HomeScreen] before navigation.
class QuestionsScreen extends StatefulWidget {
  const QuestionsScreen({super.key});

  @override
  State<QuestionsScreen> createState() => _QuestionsScreenState();
}

class _QuestionsScreenState extends State<QuestionsScreen> {
  List<Map<String, dynamic>> _questions = [];
  bool _loading = false;
  String? _error;

  late ApiService _api;

  @override
  void initState() {
    super.initState();
    SharedPreferences.getInstance().then((prefs) {
      _api = ApiService(prefs);
      _load();
    });
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final questions = await _api.fetchQuestions();
      setState(() => _questions = questions);
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      setState(() => _loading = false);
    }
  }

  Future<void> _answer(String id) async {
    final controller = TextEditingController();
    final answer = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Answer Question'),
        content: TextField(
          controller: controller,
          maxLines: 3,
          decoration: const InputDecoration(hintText: 'Your answer…'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.pop(ctx, controller.text.trim()),
            child: const Text('Send'),
          ),
        ],
      ),
    );
    if (answer == null || answer.isEmpty) return;
    try {
      await _api.answerQuestion(id, answer);
      await _load();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('⚠️ $e')));
      }
    }
  }

  Future<void> _delete(String id) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete Question'),
        content: const Text('Are you sure you want to delete this question?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Delete')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await _api.deleteQuestion(id);
      await _load();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('⚠️ $e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Questions'),
        actions: [IconButton(icon: const Icon(Icons.refresh), onPressed: _load)],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(child: Text('⚠️ $_error'))
              : _questions.isEmpty
                  ? const Center(child: Text('No unanswered questions.'))
                  : ListView.separated(
                      itemCount: _questions.length,
                      separatorBuilder: (_, __) => const Divider(),
                      itemBuilder: (_, i) {
                        final q = _questions[i];
                        final id = q['id'] as String? ?? '';
                        final text = q['question'] as String? ?? '';
                        return ListTile(
                          title: Text(text),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(
                                icon: const Icon(Icons.reply),
                                tooltip: 'Answer',
                                onPressed: () => _answer(id),
                              ),
                              IconButton(
                                icon: const Icon(Icons.delete),
                                tooltip: 'Delete',
                                onPressed: () => _delete(id),
                              ),
                            ],
                          ),
                        );
                      },
                    ),
    );
  }
}
