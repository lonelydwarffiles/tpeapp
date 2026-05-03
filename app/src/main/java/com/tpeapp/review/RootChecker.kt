package com.tpeapp.review

import android.util.Log

/**
 * RootChecker — lightweight helper that determines whether the device is rooted
 * and the calling process has been granted superuser execution rights.
 *
 * Detection strategy:
 *  1. Locate the `su` binary in common installation paths.
 *  2. Execute `su -c echo tpe_root_check` and verify it exits with code 0 within
 *     a 2-second timeout.  A non-zero exit or a timeout means root is unavailable
 *     or the superuser manager denied the request.
 *
 * Results are cached for the lifetime of the process to avoid repeated root-prompt
 * pop-ups.  The cache can be invalidated by calling [invalidateCache].
 */
object RootChecker {

    private const val TAG = "RootChecker"
    private const val TIMEOUT_MS = 2_000L

    /** Common paths where `su` can be installed on rooted Android devices. */
    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
    )

    @Volatile private var cachedResult: Boolean? = null

    /**
     * The cached result of the last [isRootAvailable] call, or `null` if no
     * check has been performed yet.  Callers on the platform thread can read
     * this without blocking; it will be `null` until the first explicit
     * [isRootAvailable] call completes on a background thread.
     */
    val cachedAvailable: Boolean?
        get() = cachedResult

    /**
     * Returns `true` if root is available *and* the superuser manager has granted
     * execution rights to this process.
     *
     * The first call may block for up to [TIMEOUT_MS] ms while waiting for a
     * superuser-prompt response.  Subsequent calls return the cached result
     * immediately.
     */
    fun isRootAvailable(): Boolean {
        cachedResult?.let { return it }
        val result = suBinaryExists() && canExecuteSu()
        cachedResult = result
        Log.i(TAG, "Root check result: $result")
        return result
    }

    /** Clears the cached result so the next [isRootAvailable] call re-evaluates. */
    fun invalidateCache() {
        cachedResult = null
    }

    // ------------------------------------------------------------------
    //  Internal helpers
    // ------------------------------------------------------------------

    private fun suBinaryExists(): Boolean =
        SU_PATHS.any { java.io.File(it).exists() }

    private fun canExecuteSu(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "echo tpe_root_check")
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                Log.w(TAG, "su execution timed out — treating device as non-rooted")
                return false
            }
            val exitCode = process.exitValue()
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "su execution failed — treating device as non-rooted", e)
            false
        }
    }
}
