// IFilterService.aidl
// Primary IPC contract exposed by FilterService to any bound client.
package com.hound.controller.filter;

import com.hound.controller.filter.IFilterCallback;

interface IFilterService {

    /**
     * Asynchronously scan raw image bytes for sensitive content.
     *
     * @param requestId Caller-supplied opaque ID echoed back in the callback.
     * @param imageData JPEG / PNG bytes of the image to classify.
     * @param callback  Result receiver (one-way, no reply required).
     */
    oneway void scanImageBytes(
        long requestId,
        in byte[] imageData,
        IFilterCallback callback
    );

    /**
     * Convenience overload: scan a file already on disk (avoids large Binder
     * parcel by passing a ParcelFileDescriptor instead of raw bytes).
     *
     * @param requestId Caller-supplied opaque ID.
     * @param fd        Read-only file descriptor to the image file.
     * @param callback  Result receiver.
     */
    oneway void scanImageFd(
        long requestId,
        in ParcelFileDescriptor fd,
        IFilterCallback callback
    );

    /** Returns true when the TFLite model has finished loading. */
    boolean isReady();

    /** Live-reload the active confidence threshold (0.0–1.0). */
    void setConfidenceThreshold(float threshold);

    /** Persist the text-replacement dictionary JSON and refresh live caches. */
    void setTextReplacementDict(String json);

    /** Persist the text-replacement policy JSON and refresh live caches. */
    void setTextReplacementPolicy(String json);

    /**
     * Returns the text-replacement dictionary as a JSON string.
     *
     * The JSON object maps Regex pattern strings to replacement template strings
     * (e.g. {"(?i)\\b(good)\\s+(boy|girl)\\b": "$1 pup"}).  Returns an empty
     * string when no dictionary has been configured.
     */
    String getTextReplacementDict();

    /**
     * Returns text-replacement policy overrides as a JSON object.
     *
     * Example:
     * {
     *   "default_mode": "auto",
     *   "packages": {
     *     "com.example.chat": "full",
     *     "com.example.bank": "identity_only"
     *   },
     *   "package_prefixes": {
     *     "com.example.finance.": "off"
     *   }
     * }
     *
     * Supported modes: "auto", "full", "identity_only", "off".
     * Returns an empty string when no policy has been configured.
     */
    String getTextReplacementPolicy();

    /**
     * Returns the restricted vocabulary as a JSON array of lower-case strings.
     *
     * These are the words managed by the Accountability Partner via FCM that
     * the Xposed tone-enforcement hook should redact in any committed text.
     * Returns an empty string when no vocabulary has been configured.
     */
    String getRestrictedVocabulary();

    /**
     * Returns the current tone-enforcement mode: "Strict" or "Soft".
     *
     * In Strict mode the Xposed hook redacts unconditionally.  In Soft mode
     * the hook allows a 3-second bypass window after the user deletes the
     * [Redacted] substitution.  Returns "Soft" when no mode has been set.
     */
    String getToneMode();

    /**
     * Returns media-filter runtime config as JSON.
     *
     * Example:
     * {
     *   "mode": "speed",
     *   "censor_style": "pixelate",
     *   "strict_packages": ["com.example.app"],
     *   "max_in_flight": 4
     * }
     */
    String getMediaFilterConfig();

    /**
     * Synchronously process an image and return an approved display bitmap payload.
     *
     * Input and output are encoded image bytes (PNG/JPEG). The output image keeps
     * original dimensions but applies selective censorship for forbidden detections.
     * Returns null on failure.
     */
    byte[] processImageBytesForDisplay(in byte[] imageData);

    /**
     * Lightweight synchronous detection call used by periodic video tracking.
     *
     * Returns true when forbidden classes are detected above threshold.
     */
    boolean hasForbiddenContent(in byte[] imageData);
}
