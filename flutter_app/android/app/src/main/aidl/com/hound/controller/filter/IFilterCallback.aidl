// IFilterCallback.aidl
package com.hound.controller.filter;

oneway interface IFilterCallback {
    void onScanResult(long requestId, boolean isSensitive, float confidence);
}