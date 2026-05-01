/// Mirrors [com.tpeapp.handler.ChatMessage].
class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.role,
    required this.content,
    required this.timestamp,
  });

  final String id;

  /// `"user"` or `"assistant"`.
  final String role;
  final String content;

  /// Unix epoch milliseconds.
  final int timestamp;

  bool get isUser => role == 'user';

  factory ChatMessage.fromJson(Map<String, dynamic> json) => ChatMessage(
        id: json['id'] as String,
        role: json['role'] as String,
        content: json['content'] as String,
        timestamp: json['timestamp'] as int,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'role': role,
        'content': content,
        'timestamp': timestamp,
      };
}
