/// Mirrors [com.tpeapp.tasks.Task] and [com.tpeapp.tasks.TaskStatus].
enum TaskStatus { pending, completed, missed }

class Task {
  const Task({
    required this.id,
    required this.title,
    required this.description,
    required this.deadlineMs,
    required this.status,
    this.photoPath,
  });

  final String id;
  final String title;
  final String description;
  final int deadlineMs;
  final TaskStatus status;
  final String? photoPath;

  factory Task.fromJson(Map<String, dynamic> json) => Task(
        id: json['id'] as String,
        title: json['title'] as String,
        description: json['description'] as String,
        deadlineMs: json['deadlineMs'] as int,
        status: TaskStatus.values.byName(
          (json['status'] as String).toLowerCase(),
        ),
        photoPath: json['photoUri'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'description': description,
        'deadlineMs': deadlineMs,
        'status': status.name.toUpperCase(),
        if (photoPath != null) 'photoUri': photoPath,
      };

  Task copyWith({
    String? id,
    String? title,
    String? description,
    int? deadlineMs,
    TaskStatus? status,
    String? photoPath,
  }) =>
      Task(
        id: id ?? this.id,
        title: title ?? this.title,
        description: description ?? this.description,
        deadlineMs: deadlineMs ?? this.deadlineMs,
        status: status ?? this.status,
        photoPath: photoPath ?? this.photoPath,
      );
}
