class SubProfile {
  const SubProfile({
    required this.id,
    required this.name,
    required this.preferences,
  });

  final String id;
  final String name;
  final Map<String, int> preferences;

  factory SubProfile.fromJson(Map<String, dynamic> json) => SubProfile(
        id: json['id'] as String,
        name: json['name'] as String,
        preferences: (json['preferences'] as Map<String, dynamic>? ?? const {})
            .map((k, v) => MapEntry(k, (v as num).round())),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'preferences': preferences,
      };

  SubProfile copyWith({
    String? id,
    String? name,
    Map<String, int>? preferences,
  }) =>
      SubProfile(
        id: id ?? this.id,
        name: name ?? this.name,
        preferences: preferences ?? this.preferences,
      );
}
