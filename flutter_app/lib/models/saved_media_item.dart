enum SavedMediaType { image, video }

class SavedMediaItem {
  const SavedMediaItem({
    required this.id,
    required this.path,
    required this.type,
    required this.savedAtMs,
    this.subId,
  });

  final String id;
  final String path;
  final SavedMediaType type;
  final int savedAtMs;
  final String? subId;

  factory SavedMediaItem.fromJson(Map<String, dynamic> json) => SavedMediaItem(
        id: json['id'] as String,
        path: json['path'] as String,
        type: SavedMediaType.values.byName(json['type'] as String),
        savedAtMs: json['savedAtMs'] as int,
        subId: json['subId'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'path': path,
        'type': type.name,
        'savedAtMs': savedAtMs,
        if (subId != null) 'subId': subId,
      };
}
