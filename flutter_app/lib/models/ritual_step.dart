/// Mirrors [com.tpeapp.ritual.RitualStep].
class RitualStep {
  const RitualStep({
    required this.id,
    required this.title,
    required this.description,
    required this.requiresPhoto,
  });

  final String id;
  final String title;
  final String description;
  final bool requiresPhoto;

  factory RitualStep.fromJson(Map<String, dynamic> json) => RitualStep(
        id: json['id'] as String,
        title: json['title'] as String,
        description: (json['description'] as String?) ?? '',
        requiresPhoto: (json['requiresPhoto'] as bool?) ?? false,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'description': description,
        'requiresPhoto': requiresPhoto,
      };
}
