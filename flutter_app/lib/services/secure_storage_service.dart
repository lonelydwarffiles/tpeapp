import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class SecureStorageService {
  SecureStorageService._();

  static final SecureStorageService instance = SecureStorageService._();

  static const _storage = FlutterSecureStorage();

  static const handlerApiKeyKey = 'handler_api_key';

  Future<String?> readHandlerApiKey() => _storage.read(key: handlerApiKeyKey);

  Future<void> writeHandlerApiKey(String value) =>
      _storage.write(key: handlerApiKeyKey, value: value);
}
