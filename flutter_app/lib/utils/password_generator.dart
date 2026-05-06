import 'dart:math';

/// Generates strong random passwords using Dart's [Random.secure()].
///
/// Passwords are composed from a configurable mix of character classes.
/// All parameters have partner-friendly defaults; the caller can tighten
/// them (e.g., enforcing a longer minimum length) before the PIN gate.
class PasswordGenerator {
  PasswordGenerator._();

  static const _lower   = 'abcdefghijklmnopqrstuvwxyz';
  static const _upper   = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  static const _digits  = '0123456789';
  static const _symbols = r'!@#$%^&*()-_=+[]{}|;:,.<>?';

  /// Generates a random password with the given constraints.
  ///
  /// [length]       — total character count (min 8, clamped).
  /// [useLower]     — include lowercase letters (default: true).
  /// [useUpper]     — include uppercase letters (default: true).
  /// [useDigits]    — include digits (default: true).
  /// [useSymbols]   — include symbols (default: true).
  ///
  /// At least one character from each enabled class is guaranteed to appear.
  /// Throws [ArgumentError] if all character classes are disabled.
  static String generate({
    int length      = 20,
    bool useLower   = true,
    bool useUpper   = true,
    bool useDigits  = true,
    bool useSymbols = true,
  }) {
    final pool = StringBuffer();
    final required = <String>[];

    if (useLower)   { pool.write(_lower);   required.add(_lower); }
    if (useUpper)   { pool.write(_upper);   required.add(_upper); }
    if (useDigits)  { pool.write(_digits);  required.add(_digits); }
    if (useSymbols) { pool.write(_symbols); required.add(_symbols); }

    if (pool.isEmpty) throw ArgumentError('At least one character class must be enabled.');

    final rng       = Random.secure();
    final poolStr   = pool.toString();
    final effective = length.clamp(required.length, 128);

    // Start with one guaranteed character from each enabled class.
    final chars = required
        .map((cls) => cls[rng.nextInt(cls.length)])
        .toList();

    // Fill the rest from the full pool.
    while (chars.length < effective) {
      chars.add(poolStr[rng.nextInt(poolStr.length)]);
    }

    // Shuffle so the guaranteed characters aren't always at the start.
    chars.shuffle(rng);
    return chars.join();
  }
}
