package com.tpeapp.filter;

import android.os.ParcelFileDescriptor;
import com.tpeapp.filter.IFilterCallback;

interface IFilterService {
    oneway void scanImageBytes(long requestId, in byte[] imageData, IFilterCallback callback);
    oneway void scanImageFd(long requestId, in ParcelFileDescriptor fd, IFilterCallback callback);
    boolean isReady();
    void setConfidenceThreshold(float threshold);
    void setTextReplacementDict(String json);
    void setTextReplacementPolicy(String json);
    String getTextReplacementDict();
    String getTextReplacementPolicy();
    String getRestrictedVocabulary();
    String getToneMode();
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