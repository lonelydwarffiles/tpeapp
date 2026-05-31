package android.support.v4.content

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Compatibility bridge for legacy libraries that still reference
 * android.support.v4.content.LocalBroadcastManager at runtime.
 */
class LocalBroadcastManager private constructor(
    private val delegate: androidx.localbroadcastmanager.content.LocalBroadcastManager,
) {

    fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        delegate.registerReceiver(receiver, filter)
    }

    fun unregisterReceiver(receiver: BroadcastReceiver) {
        delegate.unregisterReceiver(receiver)
    }

    fun sendBroadcast(intent: Intent): Boolean {
        return delegate.sendBroadcast(intent)
    }

    companion object {
        @JvmStatic
        fun getInstance(context: Context): LocalBroadcastManager {
            return LocalBroadcastManager(
                androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(context.applicationContext),
            )
        }
    }
}
