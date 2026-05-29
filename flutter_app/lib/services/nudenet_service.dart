import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;
import 'package:tflite_flutter/tflite_flutter.dart';

/// Lightweight on-device content-safety classifier backed by [nudenet.tflite].
///
/// The interpreter is loaded from `assets/nudenet.tflite` once during [warmUp]
/// and kept alive in memory so subsequent scans do not pay a cold-start cost.
///
/// Image preprocessing (decode + resize to [_inputSize]×[_inputSize] + normalise)
/// runs in a separate Dart [Isolate] via [compute] so the UI thread is never
/// blocked.  Inference itself runs on the interpreter's own background threads
/// (configured to 2 threads via [InterpreterOptions]).
///
/// Usage:
/// ```dart
/// // Warm up early in the app lifecycle (e.g. in main or HomeScreen.initState).
/// await NudeNetService.instance.warmUp();
///
/// // Classify a JPEG / PNG byte array.
/// final score = await NudeNetService.instance.classifyBytes(imageBytes);
/// if (score >= 0.55) { /* content blocked */ }
/// ```
class NudeNetService {
  NudeNetService._();

  /// Singleton instance — the interpreter is kept alive for the process lifetime.
  static final NudeNetService instance = NudeNetService._();

  // -------------------------------------------------------------------------
  //  Constants
  // -------------------------------------------------------------------------

  static const String _modelAsset = 'assets/nudenet.tflite';

  /// Side length (pixels) the input image is resized to before inference.
  /// Must match the first/second spatial dimension of the model's input tensor
  /// (expected shape: [1, _inputSize, _inputSize, 3]).
  static const int _inputSize = 320;

  /// Number of output classes ([safe_score, unsafe_score]).
  static const int _outputClasses = 2;

  /// Index within the output vector that holds the "unsafe" probability.
  static const int _unsafeIdx = 1;

  // -------------------------------------------------------------------------
  //  State
  // -------------------------------------------------------------------------

  Interpreter? _interpreter;
  bool _initialized = false;

  // -------------------------------------------------------------------------
  //  Public API
  // -------------------------------------------------------------------------

  /// Loads the TFLite model from assets and allocates the interpreter tensors.
  ///
  /// Calling [warmUp] explicitly at startup avoids the first-scan latency
  /// spike.  Subsequent calls are no-ops if the interpreter is already ready.
  Future<void> warmUp() async {
    if (_initialized) return;
    final options = InterpreterOptions()..threads = 2;
    _interpreter = await Interpreter.fromAsset(_modelAsset, options: options);
    _interpreter!.allocateTensors();
    _initialized = true;
  }

  /// Runs content-safety inference on raw [imageBytes] (JPEG or PNG).
  ///
  /// Returns a score in `[0.0, 1.0]` representing the probability that the
  /// image contains adult / sensitive content.  A score ≥ 0.55 is typically
  /// treated as a violation.
  ///
  /// Image decoding and resizing execute in a background [Isolate] via
  /// [compute]; UI responsiveness is preserved even for large images.
  Future<double> classifyBytes(Uint8List imageBytes) async {
    await warmUp();

    // Preprocessing runs in a background isolate — never blocks the UI thread.
    final input = await compute(_preprocessImage, _PreprocessArgs(imageBytes, _inputSize));

    // Inference runs on the interpreter's worker threads.
    final output = [List<double>.filled(_outputClasses, 0.0)];
    _interpreter!.run(input, output);

    return output[0][_unsafeIdx].clamp(0.0, 1.0);
  }

  /// Releases interpreter resources.  Call when the service is no longer needed
  /// (e.g. `dispose` of a widget that owns this service).
  void dispose() {
    _interpreter?.close();
    _interpreter = null;
    _initialized = false;
  }
}

// ---------------------------------------------------------------------------
//  Isolate helpers — must be top-level functions for [compute].
// ---------------------------------------------------------------------------

/// Arguments passed to the preprocessing isolate.
class _PreprocessArgs {
  const _PreprocessArgs(this.imageBytes, this.inputSize);

  final Uint8List imageBytes;
  final int inputSize;
}

/// Decodes [args.imageBytes], resizes to [args.inputSize]×[args.inputSize],
/// and returns a normalised NHWC float tensor shaped [1, H, W, 3] with values
/// in `[0.0, 1.0]`.
///
/// This function is executed in a separate [Isolate] by [compute] to avoid
/// jank on the UI thread.
List<List<List<List<double>>>> _preprocessImage(_PreprocessArgs args) {
  final decoded = img.decodeImage(args.imageBytes);

  // Return a zeroed tensor when the image cannot be decoded rather than throw.
  if (decoded == null) {
    return [
      List.generate(
        args.inputSize,
        (_) => List.generate(args.inputSize, (_) => [0.0, 0.0, 0.0]),
      ),
    ];
  }

  final resized = img.copyResize(
    decoded,
    width: args.inputSize,
    height: args.inputSize,
    interpolation: img.Interpolation.linear,
  );

  // Build NHWC tensor: [batch=1, height, width, channels=3].
  return [
    List.generate(args.inputSize, (y) {
      return List.generate(args.inputSize, (x) {
        final pixel = resized.getPixel(x, y);
        return [
          pixel.r / 255.0,
          pixel.g / 255.0,
          pixel.b / 255.0,
        ];
      });
    }),
  ];
}
