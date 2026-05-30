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
}