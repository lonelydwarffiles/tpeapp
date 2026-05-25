// IFilterCallback.aidl
// Callback interface so callers receive the scan result asynchronously
// without blocking the calling thread.
package com.tpeapp.filter;

oneway interface IFilterCallback {
    /**
     * Called once scanning completes.
     *
     * @param requestId   The opaque ID supplied in the original scan call.
     * @param isSensitive true if adult/sensitive content was detected.
     * @param confidence  Detection confidence in [0.0, 1.0].
     */
    void onScanResult(long requestId, boolean isSensitive, float confidence);
}
