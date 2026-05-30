// IFilterCallback.aidl
package com.tpeapp.filter;

oneway interface IFilterCallback {
    void onScanResult(long requestId, boolean isSensitive, float confidence);
}