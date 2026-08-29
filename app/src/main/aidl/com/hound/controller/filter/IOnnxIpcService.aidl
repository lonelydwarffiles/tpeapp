package com.hound.controller.filter;

/**
 * Synchronous IPC contract for image moderation checks from Xposed hooks.
 */
interface IOnnxIpcService {

    /**
     * Analyze an encoded image payload and return true when it should be censored.
     */
    boolean analyzeImage(in byte[] imageBytes);
}
