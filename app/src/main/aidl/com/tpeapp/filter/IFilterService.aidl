// IFilterService.aidl
// Primary IPC contract exposed by FilterService to any bound client.
package com.tpeapp.filter;

import com.tpeapp.filter.IFilterCallback;

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
}
