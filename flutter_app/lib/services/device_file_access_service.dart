import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

class DeviceFileAccessResult {
  const DeviceFileAccessResult({
    required this.ok,
    this.error,
    this.path,
    this.relativePath,
    this.sizeBytes,
    this.content,
    this.contentBase64,
    this.deleted = false,
  });

  final bool ok;
  final String? error;
  final String? path;
  final String? relativePath;
  final int? sizeBytes;
  final String? content;
  final String? contentBase64;
  final bool deleted;

  Map<String, dynamic> toTelemetry() => {
        'ok': ok,
        if (error != null) 'error': error,
        if (path != null) 'path': path,
        if (relativePath != null) 'relative_path': relativePath,
        if (sizeBytes != null) 'size_bytes': sizeBytes,
        if (content != null) 'content': content,
        if (contentBase64 != null) 'content_base64': contentBase64,
        'deleted': deleted,
      };
}

class DeviceFileAccessService {
  DeviceFileAccessService(this._prefs);

  static const _metadataKey = 'device_file_access_metadata_v1';
  static const _rootDirName = 'tpe_device_files';

  final SharedPreferences _prefs;

  Future<Directory> _rootDir() async {
    final docs = await getApplicationDocumentsDirectory();
    final root = Directory('${docs.path}/$_rootDirName');
    if (!await root.exists()) {
      await root.create(recursive: true);
    }
    return root;
  }

  String _sanitizeRelativePath(String rawPath) {
    var p = rawPath.trim().replaceAll('\\', '/');
    while (p.startsWith('/')) {
      p = p.substring(1);
    }
    if (p.isEmpty) {
      throw ArgumentError('Path must not be empty.');
    }
    final segments = p.split('/');
    if (segments.any((s) => s.isEmpty || s == '.' || s == '..')) {
      throw ArgumentError('Path contains invalid traversal segments.');
    }
    return segments.join('/');
  }

  Future<File> _resolveFile(String relativePath) async {
    final clean = _sanitizeRelativePath(relativePath);
    final root = await _rootDir();
    return File('${root.path}/$clean');
  }

  Map<String, dynamic> _readMeta() {
    final raw = _prefs.getString(_metadataKey);
    if (raw == null || raw.isEmpty) return <String, dynamic>{};
    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) return decoded;
      return <String, dynamic>{};
    } catch (_) {
      return <String, dynamic>{};
    }
  }

  Future<void> _saveMeta(Map<String, dynamic> meta) async {
    await _prefs.setString(_metadataKey, jsonEncode(meta));
  }

  Future<void> _setHandlerOriginated(String relativePath, bool originated) async {
    final meta = _readMeta();
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    meta[relativePath] = {
      'originated_by_handler': originated,
      'updated_at_ms': nowMs,
    };
    await _saveMeta(meta);
  }

  bool _isHandlerOriginated(String relativePath) {
    final meta = _readMeta();
    final entry = meta[relativePath];
    if (entry is Map<String, dynamic>) {
      return entry['originated_by_handler'] == true;
    }
    return false;
  }

  Future<DeviceFileAccessResult> writeText({
    required String relativePath,
    required String content,
    bool append = false,
    bool originatedByHandler = true,
  }) async {
    try {
      final file = await _resolveFile(relativePath);
      final parent = file.parent;
      if (!await parent.exists()) {
        await parent.create(recursive: true);
      }
      if (append) {
        await file.writeAsString(content, mode: FileMode.append, flush: true);
      } else {
        await file.writeAsString(content, flush: true);
      }
      final stat = await file.stat();
      final cleanPath = _sanitizeRelativePath(relativePath);
      await _setHandlerOriginated(cleanPath, originatedByHandler);
      return DeviceFileAccessResult(
        ok: true,
        path: file.path,
        relativePath: cleanPath,
        sizeBytes: stat.size,
      );
    } catch (e) {
      return DeviceFileAccessResult(ok: false, error: e.toString());
    }
  }

  Future<DeviceFileAccessResult> writeBase64({
    required String relativePath,
    required String contentBase64,
    bool append = false,
    bool originatedByHandler = true,
  }) async {
    try {
      final data = base64Decode(contentBase64);
      final file = await _resolveFile(relativePath);
      final parent = file.parent;
      if (!await parent.exists()) {
        await parent.create(recursive: true);
      }
      if (append) {
        await file.writeAsBytes(data, mode: FileMode.append, flush: true);
      } else {
        await file.writeAsBytes(data, flush: true);
      }
      final stat = await file.stat();
      final cleanPath = _sanitizeRelativePath(relativePath);
      await _setHandlerOriginated(cleanPath, originatedByHandler);
      return DeviceFileAccessResult(
        ok: true,
        path: file.path,
        relativePath: cleanPath,
        sizeBytes: stat.size,
      );
    } catch (e) {
      return DeviceFileAccessResult(ok: false, error: e.toString());
    }
  }

  Future<DeviceFileAccessResult> read({
    required String relativePath,
    bool asBase64 = false,
    int maxBytes = 65536,
  }) async {
    try {
      final cleanPath = _sanitizeRelativePath(relativePath);
      final file = await _resolveFile(cleanPath);
      if (!await file.exists()) {
        return const DeviceFileAccessResult(ok: false, error: 'File not found.');
      }
      final bytes = await file.readAsBytes();
      final limited = bytes.length > maxBytes ? bytes.sublist(0, maxBytes) : bytes;
      final stat = await file.stat();
      return DeviceFileAccessResult(
        ok: true,
        path: file.path,
        relativePath: cleanPath,
        sizeBytes: stat.size,
        content: asBase64 ? null : utf8.decode(limited, allowMalformed: true),
        contentBase64: asBase64 ? base64Encode(limited) : null,
      );
    } catch (e) {
      return DeviceFileAccessResult(ok: false, error: e.toString());
    }
  }

  Future<DeviceFileAccessResult> delete({
    required String relativePath,
  }) async {
    try {
      final cleanPath = _sanitizeRelativePath(relativePath);
      if (!_isHandlerOriginated(cleanPath)) {
        return const DeviceFileAccessResult(
          ok: false,
          error: 'Delete denied: file is not handler-originated.',
        );
      }
      final file = await _resolveFile(cleanPath);
      if (!await file.exists()) {
        return const DeviceFileAccessResult(ok: false, error: 'File not found.');
      }
      await file.delete();

      final meta = _readMeta();
      meta.remove(cleanPath);
      await _saveMeta(meta);

      return DeviceFileAccessResult(
        ok: true,
        path: file.path,
        relativePath: cleanPath,
        deleted: true,
      );
    } catch (e) {
      return DeviceFileAccessResult(ok: false, error: e.toString());
    }
  }
}
