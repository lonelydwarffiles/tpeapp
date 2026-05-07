import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

import '../models/saved_media_item.dart';

class DeviceMediaService extends ChangeNotifier {
  DeviceMediaService(this._prefs) {
    _load();
  }

  static const _itemsKey = 'saved_media_items_json';

  final SharedPreferences _prefs;
  final _picker = ImagePicker();
  final _uuid = const Uuid();

  List<SavedMediaItem> _items = [];

  List<SavedMediaItem> get items => List.unmodifiable(_items);

  List<SavedMediaItem> itemsForSub(String? subId) =>
      _items.where((i) => i.subId == subId).toList();

  void _load() {
    final json = _prefs.getString(_itemsKey);
    if (json == null) return;
    try {
      final decoded = jsonDecode(json) as List<dynamic>;
      _items = decoded
          .map((e) => SavedMediaItem.fromJson(e as Map<String, dynamic>))
          .toList()
        ..sort((a, b) => b.savedAtMs.compareTo(a.savedAtMs));
    } catch (_) {
      _items = [];
    }
  }

  Future<void> _save() async {
    await _prefs.setString(
      _itemsKey,
      jsonEncode(_items.map((e) => e.toJson()).toList()),
    );
  }

  Future<Directory> _libraryDirectory() async {
    final docs = await getApplicationDocumentsDirectory();
    final dir = Directory('${docs.path}/tpe_saved_media');
    if (!await dir.exists()) {
      await dir.create(recursive: true);
    }
    return dir;
  }

  String _extensionFor(String sourcePath) {
    final dot = sourcePath.lastIndexOf('.');
    if (dot == -1 || dot == sourcePath.length - 1) return '';
    return sourcePath.substring(dot);
  }

  Future<String> _copyToLibrary(String sourcePath, SavedMediaType type) async {
    final source = File(sourcePath);
    final dir = await _libraryDirectory();
    final ext = _extensionFor(sourcePath);
    final filename = '${type.name}_${DateTime.now().microsecondsSinceEpoch}_${_uuid.v4()}$ext';
    final target = File('${dir.path}/$filename');
    await source.copy(target.path);
    return target.path;
  }

  Future<void> addImages({String? subId}) async {
    final picked = await _picker.pickMultiImage();
    if (picked.isEmpty) return;

    final nowMs = DateTime.now().millisecondsSinceEpoch;
    var offset = 0;
    for (final image in picked) {
      final savedPath = await _copyToLibrary(image.path, SavedMediaType.image);
      _items.add(
        SavedMediaItem(
          id: _uuid.v4(),
          path: savedPath,
          type: SavedMediaType.image,
          savedAtMs: nowMs + offset,
          subId: subId,
        ),
      );
      offset += 1;
    }

    _items.sort((a, b) => b.savedAtMs.compareTo(a.savedAtMs));
    await _save();
    notifyListeners();
  }

  Future<void> addVideo({String? subId}) async {
    final picked = await _picker.pickVideo(source: ImageSource.gallery);
    if (picked == null) return;

    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final savedPath = await _copyToLibrary(picked.path, SavedMediaType.video);
    _items.insert(
      0,
      SavedMediaItem(
        id: _uuid.v4(),
        path: savedPath,
        type: SavedMediaType.video,
        savedAtMs: nowMs,
        subId: subId,
      ),
    );

    await _save();
    notifyListeners();
  }

  Future<String> saveTextReport({
    required String filenamePrefix,
    required String content,
  }) async {
    final dir = await _libraryDirectory();
    final nowUs = DateTime.now().microsecondsSinceEpoch;
    final name = '${filenamePrefix}_${nowUs}_${_uuid.v4()}.txt';
    final file = File('${dir.path}/$name');
    await file.writeAsString(content);
    return file.path;
  }

  Future<void> removeItem(String id) async {
    final idx = _items.indexWhere((i) => i.id == id);
    if (idx < 0) return;

    final item = _items[idx];
    _items.removeAt(idx);

    final file = File(item.path);
    if (await file.exists()) {
      await file.delete();
    }

    await _save();
    notifyListeners();
  }
}
