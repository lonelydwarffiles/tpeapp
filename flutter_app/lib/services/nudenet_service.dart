import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:flutter_onnxruntime/flutter_onnxruntime.dart';
import 'package:image/image.dart' as img;

enum CensorStyle { blackout, heavyBlur, pixelate }

class DetectionBox {
  const DetectionBox({
    required this.x1,
    required this.y1,
    required this.x2,
    required this.y2,
    required this.score,
  });

  final double x1;
  final double y1;
  final double x2;
  final double y2;
  final double score;

  List<double> toList() => [x1, y1, x2, y2, score];
}

class NudeNetService {
  NudeNetService._();

  static final NudeNetService instance = NudeNetService._();

  static const String _modelAsset = 'assets/320.ort';
  static const int _modelSize = 320;

  final OnnxRuntime _onnx = OnnxRuntime();
  OrtSession? _session;
  bool _initialized = false;

  Future<void> warmUp() async {
    if (_initialized) return;
    _session = await _onnx.createSessionFromAsset(_modelAsset);
    _initialized = true;
  }

  Future<Uint8List> censorImageBytes(
    Uint8List originalBytes, {
    double scoreThreshold = 0.35,
    CensorStyle style = CensorStyle.blackout,
  }) async {
    await warmUp();

    final preprocess = await compute(_prepareInputForOnnx, <String, dynamic>{
      'imageBytes': originalBytes,
      'modelSize': _modelSize,
    });

    if (preprocess['valid'] != true) return originalBytes;

    final input = preprocess['input'] as Float32List;
    final originalWidth = preprocess['width'] as int;
    final originalHeight = preprocess['height'] as int;

    final modelDetections = await _runDetection(input, scoreThreshold: scoreThreshold);
    if (modelDetections.isEmpty) return originalBytes;

    final scaledBoxes = modelDetections.map((box) {
      final sx = originalWidth / _modelSize;
      final sy = originalHeight / _modelSize;
      return [
        (box.x1 * sx).clamp(0, originalWidth - 1).toDouble(),
        (box.y1 * sy).clamp(0, originalHeight - 1).toDouble(),
        (box.x2 * sx).clamp(0, originalWidth - 1).toDouble(),
        (box.y2 * sy).clamp(0, originalHeight - 1).toDouble(),
        box.score,
      ];
    }).toList();

    return compute(_applyLocalizedCensor, <String, dynamic>{
      'imageBytes': originalBytes,
      'boxes': scaledBoxes,
      'style': style.name,
    });
  }

  Future<List<DetectionBox>> _runDetection(
    Float32List input, {
    required double scoreThreshold,
  }) async {
    final session = _session;
    if (session == null) return const [];

    final inputName = session.inputNames.isNotEmpty ? session.inputNames.first : 'images';
    final inputTensor = await OrtValue.fromList(
      input,
      [1, 3, _modelSize, _modelSize],
    );
    final outputs = await session.run({inputName: inputTensor});

    final List<DetectionBox> detections = [];
    for (final tensor in outputs.values) {
      detections.addAll(await _parseDetectionTensor(tensor, scoreThreshold));
    }

    await inputTensor.dispose();
    for (final tensor in outputs.values) {
      await tensor.dispose();
    }

    return _nms(detections, iouThreshold: 0.45);
  }

  Future<List<DetectionBox>> _parseDetectionTensor(
    OrtValue tensor,
    double scoreThreshold,
  ) async {
    final rawData = _tensorDataToList(await tensor.asFlattenedList());
    if (rawData.isEmpty) return const [];

    final shape = List<int>.from(tensor.shape);

    if (shape.length == 3) {
      final a = shape[1];
      final b = shape[2];

      if (b >= 6) {
        return _parseRowMajor(rawData, rows: a, cols: b, threshold: scoreThreshold);
      }

      if (a >= 6) {
        return _parseAttrMajor(rawData, attrs: a, count: b, threshold: scoreThreshold);
      }
    }

    if (shape.length == 2 && shape[1] >= 6) {
      return _parseRowMajor(rawData, rows: shape[0], cols: shape[1], threshold: scoreThreshold);
    }

    return const [];
  }

  List<DetectionBox> _parseRowMajor(
    List<double> values, {
    required int rows,
    required int cols,
    required double threshold,
  }) {
    final out = <DetectionBox>[];
    for (var r = 0; r < rows; r++) {
      final base = r * cols;
      final x1 = values[base + 0];
      final y1 = values[base + 1];
      final x2 = values[base + 2];
      final y2 = values[base + 3];
      final score = values[base + 4];

      if (score < threshold) continue;
      if (x2 <= x1 || y2 <= y1) continue;
      out.add(DetectionBox(x1: x1, y1: y1, x2: x2, y2: y2, score: score));
    }
    return out;
  }

  List<DetectionBox> _parseAttrMajor(
    List<double> values, {
    required int attrs,
    required int count,
    required double threshold,
  }) {
    final out = <DetectionBox>[];
    for (var i = 0; i < count; i++) {
      final cx = values[(0 * count) + i];
      final cy = values[(1 * count) + i];
      final w = values[(2 * count) + i];
      final h = values[(3 * count) + i];
      final obj = values[(4 * count) + i];

      double classProb = 0;
      for (var a = 5; a < attrs; a++) {
        classProb = math.max(classProb, values[(a * count) + i]);
      }
      final score = obj * (classProb == 0 ? 1 : classProb);
      if (score < threshold) continue;

      final x1 = cx - (w / 2);
      final y1 = cy - (h / 2);
      final x2 = cx + (w / 2);
      final y2 = cy + (h / 2);
      if (x2 <= x1 || y2 <= y1) continue;

      out.add(DetectionBox(x1: x1, y1: y1, x2: x2, y2: y2, score: score));
    }
    return out;
  }

  List<double> _tensorDataToList(dynamic data) {
    if (data is Float32List) return data.toList(growable: false);
    if (data is Float64List) return data.toList(growable: false);
    if (data is Int32List) {
      return data.map((e) => e.toDouble()).toList(growable: false);
    }
    if (data is List) {
      return data.expand<double>((e) {
        if (e is num) return [e.toDouble()];
        if (e is List) return _flattenNumList(e);
        return const [];
      }).toList(growable: false);
    }
    return const [];
  }

  List<double> _flattenNumList(List<dynamic> list) {
    final out = <double>[];
    for (final item in list) {
      if (item is num) {
        out.add(item.toDouble());
      } else if (item is List) {
        out.addAll(_flattenNumList(item));
      }
    }
    return out;
  }

  List<DetectionBox> _nms(List<DetectionBox> boxes, {required double iouThreshold}) {
    if (boxes.isEmpty) return const [];

    final sorted = [...boxes]..sort((a, b) => b.score.compareTo(a.score));
    final kept = <DetectionBox>[];

    while (sorted.isNotEmpty) {
      final current = sorted.removeAt(0);
      kept.add(current);
      sorted.removeWhere((other) => _iou(current, other) > iouThreshold);
    }

    return kept;
  }

  double _iou(DetectionBox a, DetectionBox b) {
    final x1 = math.max(a.x1, b.x1);
    final y1 = math.max(a.y1, b.y1);
    final x2 = math.min(a.x2, b.x2);
    final y2 = math.min(a.y2, b.y2);

    final interW = math.max(0.0, x2 - x1);
    final interH = math.max(0.0, y2 - y1);
    final interArea = interW * interH;

    final areaA = (a.x2 - a.x1) * (a.y2 - a.y1);
    final areaB = (b.x2 - b.x1) * (b.y2 - b.y1);
    final union = areaA + areaB - interArea;

    if (union <= 0) return 0;
    return interArea / union;
  }

  void dispose() {
    _session?.close();
    _session = null;
    _initialized = false;
  }
}

Map<String, dynamic> _prepareInputForOnnx(Map<String, dynamic> args) {
  final imageBytes = args['imageBytes'] as Uint8List;
  final modelSize = args['modelSize'] as int;

  final source = img.decodeImage(imageBytes);
  if (source == null) {
    return {
      'valid': false,
      'width': 0,
      'height': 0,
      'input': Float32List(0),
    };
  }

  final resized = img.copyResize(
    source,
    width: modelSize,
    height: modelSize,
    interpolation: img.Interpolation.linear,
  );

  final input = Float32List(1 * 3 * modelSize * modelSize);
  var rOffset = 0;
  var gOffset = modelSize * modelSize;
  var bOffset = 2 * modelSize * modelSize;

  for (var y = 0; y < modelSize; y++) {
    for (var x = 0; x < modelSize; x++) {
      final p = resized.getPixel(x, y);
      input[rOffset++] = p.r / 255.0;
      input[gOffset++] = p.g / 255.0;
      input[bOffset++] = p.b / 255.0;
    }
  }

  return {
    'valid': true,
    'width': source.width,
    'height': source.height,
    'input': input,
  };
}

Uint8List _applyLocalizedCensor(Map<String, dynamic> args) {
  final imageBytes = args['imageBytes'] as Uint8List;
  final boxes = (args['boxes'] as List).cast<List<dynamic>>();
  final style = _resolveCensorStyle(args['style'] as String?);

  final source = img.decodeImage(imageBytes);
  if (source == null || boxes.isEmpty) return imageBytes;

  for (final b in boxes) {
    if (b.length < 4) continue;
    final x1 = b[0].round().clamp(0, source.width - 1);
    final y1 = b[1].round().clamp(0, source.height - 1);
    final x2 = b[2].round().clamp(0, source.width - 1);
    final y2 = b[3].round().clamp(0, source.height - 1);

    if (x2 <= x1 || y2 <= y1) continue;

    switch (style) {
      case CensorStyle.heavyBlur:
        _heavyBlurRegion(source, x1, y1, x2, y2);
        break;
      case CensorStyle.pixelate:
        _pixelateRegion(source, x1, y1, x2, y2);
        break;
      case CensorStyle.blackout:
        img.fillRect(
          source,
          x1: x1,
          y1: y1,
          x2: x2,
          y2: y2,
          color: img.ColorRgba8(0, 0, 0, 255),
        );
        break;
    }
  }

  return Uint8List.fromList(img.encodeJpg(source, quality: 92));
}

void _heavyBlurRegion(img.Image source, int x1, int y1, int x2, int y2) {
  final w = x2 - x1;
  final h = y2 - y1;
  if (w < 2 || h < 2) return;

  final region = img.copyCrop(source, x: x1, y: y1, width: w, height: h);
  final smallW = math.max(1, w ~/ 18);
  final smallH = math.max(1, h ~/ 18);
  final shrunk = img.copyResize(region, width: smallW, height: smallH, interpolation: img.Interpolation.average);
  final blurred = img.copyResize(shrunk, width: w, height: h, interpolation: img.Interpolation.linear);
  img.compositeImage(source, blurred, dstX: x1, dstY: y1);
}

void _pixelateRegion(img.Image source, int x1, int y1, int x2, int y2) {
  final w = x2 - x1;
  final h = y2 - y1;
  if (w < 2 || h < 2) return;

  final region = img.copyCrop(source, x: x1, y: y1, width: w, height: h);
  final shrunk = img.copyResize(
    region,
    width: math.min(10, w),
    height: math.min(10, h),
    interpolation: img.Interpolation.average,
  );
  final pixelated = img.copyResize(
    shrunk,
    width: w,
    height: h,
    interpolation: img.Interpolation.nearest,
  );
  img.compositeImage(source, pixelated, dstX: x1, dstY: y1);
}

CensorStyle _resolveCensorStyle(String? raw) {
  final normalized = raw?.trim().toLowerCase().replaceAll('-', '_') ?? '';
  switch (normalized) {
    case 'pixelate':
      return CensorStyle.pixelate;
    case 'heavy_blur':
    case 'heavyblur':
    case 'blur':
      return CensorStyle.heavyBlur;
    case 'blackout':
    default:
      return CensorStyle.blackout;
  }
}
