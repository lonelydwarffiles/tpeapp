package com.tpeapp.review

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tpeapp.R
import com.tpeapp.ui.MainActivity

/**
 * ScreencastService — a foreground service with [foregroundServiceType="mediaProjection"].
 *
 * Responsibilities:
 *  • Displays a mandatory persistent notification (required by Android 14+ / upcoming Android 16).
 *  • Creates a [android.media.projection.MediaProjection] from the permission grant result that
 *    [ReviewActivity] passes in via the starting Intent.
 *  • Hands the [android.media.projection.MediaProjection] to [StreamCoordinator] to begin
 *    capturing and broadcasting via WebRTC.
 *
 * Start this service with:
 *   ```
 *   Intent(context, ScreencastService::class.java).apply {
 *       putExtra(EXTRA_RESULT_CODE, resultCode)
 *       putExtra(EXTRA_RESULT_DATA, data)        // Intent returned by MediaProjectionManager
 *       putExtra(EXTRA_SESSION_ID, sessionId)    // Partner's session ID from the dashboard
 *   }
 *   ```
 */
class ScreencastService : Service() {

    // ------------------------------------------------------------------
    //  Constants
    // ------------------------------------------------------------------

    companion object {
        private const val TAG             = "ScreencastService"
        private const val CHANNEL_ID      = "tpe_screencast"
        private const val NOTIFICATION_ID = 2001

        const val EXTRA_RESULT_CODE    = "extra_result_code"
        const val EXTRA_RESULT_DATA    = "extra_result_data"
        const val EXTRA_SESSION_ID     = "extra_session_id"
        const val EXTRA_REMOTE_CONTROL = "extra_remote_control"

        const val ACTION_STOP = "com.tpeapp.ACTION_STOP_SCREENCAST"
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            Log.e(TAG, "Null intent received — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode        = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData        = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val sessionId         = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val remoteControl     = intent.getBooleanExtra(EXTRA_REMOTE_CONTROL, false)

        if (resultData == null || resultCode == 0) {
            Log.e(TAG, "Missing MediaProjection permission result — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Must call startForeground before acquiring the MediaProjection on Android 10+.
        startForeground(NOTIFICATION_ID, buildForegroundNotification(remoteControl))

        StreamCoordinator.start(applicationContext, resultCode, resultData, sessionId, remoteControl)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        StreamCoordinator.stop()
        Log.i(TAG, "ScreencastService destroyed")
    }

    // ------------------------------------------------------------------
    //  Notification helpers
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screencast_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.screencast_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(remoteControlActive: Boolean = false) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.screencast_notification_title))
            .setContentText(
                if (remoteControlActive)
                    getString(R.string.screencast_notification_text_remote_control)
                else
                    getString(R.string.screencast_notification_text)
            )
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(buildStopAction())
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun buildStopAction(): NotificationCompat.Action {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreencastService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_shield,
            getString(R.string.screencast_action_stop),
            stopIntent
        ).build()
    }
}
