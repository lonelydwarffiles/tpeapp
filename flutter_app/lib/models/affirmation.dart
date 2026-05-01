/// Mirrors [com.tpeapp.affirmation.AffirmationEntry].
class Affirmation {
  const Affirmation({required this.id, required this.text});

  final String id;
  final String text;

  factory Affirmation.fromJson(Map<String, dynamic> json) =>
      Affirmation(id: json['id'] as String, text: json['text'] as String);

  Map<String, dynamic> toJson() => {'id': id, 'text': text};
}
