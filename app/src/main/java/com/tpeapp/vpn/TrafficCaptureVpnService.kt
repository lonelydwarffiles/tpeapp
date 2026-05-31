package com.tpeapp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local VPN tunnel used for handler-driven traffic restriction + capture counters.
 *
 * This implementation intentionally does not forward packets, so apps routed
 * through the tunnel are effectively blocked while bytes/packets are counted.
 */
class TrafficCaptureVpnService : VpnService() {

    companion object {
        private const val ACTION_CONNECT = "com.tpeapp.vpn.CONNECT"
        private const val ACTION_DISCONNECT = "com.tpeapp.vpn.DISCONNECT"
        private const val CHANNEL_ID = "tpe_vpn"
        private const val NOTIFICATION_ID = 44071
        private const val PROMPT_COOLDOWN_MS = 15_000L

        const val CONNECT_RESULT_PERMISSION_PROMPTED = "permission_prompted"
        const val CONNECT_RESULT_SERVICE_START_REQUESTED = "service_start_requested"
        const val CONNECT_RESULT_FAILED = "failed"

        @Volatile
        private var lastPermissionPromptAtMs: Long = 0L

        fun requestConnect(context: Context): String {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                val now = System.currentTimeMillis()
                if (now - lastPermissionPromptAtMs < PROMPT_COOLDOWN_MS) {
                    return CONNECT_RESULT_PERMISSION_PROMPTED
                }
                return try {
                    val intent = Intent(context, VpnConsentProxyActivity::class.java).apply {
                        if (context !is Activity) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    context.startActivity(intent)
                    lastPermissionPromptAtMs = now
                    CONNECT_RESULT_PERMISSION_PROMPTED
                } catch (_: Exception) {
                    CONNECT_RESULT_FAILED
                }
            }

            val intent = Intent(context, TrafficCaptureVpnService::class.java).apply {
                action = ACTION_CONNECT
            }
            return if (startServiceCompat(context, intent)) {
                CONNECT_RESULT_SERVICE_START_REQUESTED
            } else {
                CONNECT_RESULT_FAILED
            }
        }

        fun requestDisconnect(context: Context) {
            val intent = Intent(context, TrafficCaptureVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            startServiceCompat(context, intent)
        }

        private fun startServiceCompat(context: Context, intent: Intent): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private var tunnelFd: ParcelFileDescriptor? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startTunnel()
            ACTION_DISCONNECT -> {
                stopTunnel()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel() {
        val policy = VpnPolicyManager.localTunnelPolicy(this)
        if (!policy.useLocalTunnel) {
            VpnPolicyManager.setTunnelRuntime(this, active = false, error = "Local tunnel mode not enabled")
            return
        }

        stopTunnel()

        try {
            ensureNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("Network policy active"))

            val builder = Builder()
                .setSession("TPE Network Policy")
                .setMtu(1500)
                .setBlocking(true)
                .addAddress("10.7.0.2", 32)
                .addRoute("0.0.0.0", 0)

            if (policy.captureIpv6) {
                builder.addAddress("fd66:66:66::2", 128)
                builder.addRoute("::", 0)
            }

            applyAppRoutingPolicy(builder, policy)

            val established = builder.establish()
            if (established == null) {
                VpnPolicyManager.setTunnelRuntime(this, active = false, error = "VpnService establish() returned null")
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }

            tunnelFd = established
            VpnPolicyManager.setTunnelRuntime(this, active = true)

            running.set(true)
            captureThread = Thread {
                var bytesAccum = 0L
                var packetsAccum = 0L
                var lastFlush = System.currentTimeMillis()
                val buffer = ByteArray(32768)

                try {
                    FileInputStream(established.fileDescriptor).use { input ->
                        while (running.get()) {
                            val read = input.read(buffer)
                            if (read <= 0) continue
                            bytesAccum += read.toLong()
                            packetsAccum += 1L

                            val now = System.currentTimeMillis()
                            if (now - lastFlush >= 1000L || packetsAccum >= 64L) {
                                VpnPolicyManager.addCapturedTraffic(this, bytesAccum, packetsAccum)
                                bytesAccum = 0L
                                packetsAccum = 0L
                                lastFlush = now
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Runtime failures are reflected by tunnel inactive state on shutdown.
                } finally {
                    if (bytesAccum > 0L || packetsAccum > 0L) {
                        VpnPolicyManager.addCapturedTraffic(this, bytesAccum, packetsAccum)
                    }
                }
            }.apply {
                name = "tpe-vpn-capture"
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            VpnPolicyManager.setTunnelRuntime(this, active = false, error = e.message ?: "failed to start tunnel")
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun stopTunnel() {
        running.set(false)
        captureThread?.interrupt()
        captureThread = null

        try {
            tunnelFd?.close()
        } catch (_: Exception) {
        }
        tunnelFd = null

        VpnPolicyManager.setTunnelRuntime(this, active = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun applyAppRoutingPolicy(builder: Builder, policy: VpnPolicyManager.LocalTunnelPolicy) {
        // Keep this app out of the tunnel so handler control traffic is never self-blocked.
        runCatching { builder.addDisallowedApplication(packageName) }

        when (policy.restrictionMode.lowercase()) {
            "allow_list", "allow_only" -> {
                for (pkg in policy.allowedPackages) {
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
            "block_list", "deny_list" -> {
                for (pkg in policy.blockedPackages) {
                    runCatching { builder.addAllowedApplication(pkg) }
                }
            }
            "block_all" -> {
                // Default routing already captures all non-disallowed apps.
            }
            else -> {
                // Unknown mode falls back to restrictive all-app routing.
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TPE VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Network restriction and capture tunnel"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("TPE VPN")
                .setContentText(content)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("TPE VPN")
                .setContentText(content)
                .setOngoing(true)
                .build()
        }
    }
}
