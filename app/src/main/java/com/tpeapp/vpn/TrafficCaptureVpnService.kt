package com.tpeapp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.preference.PreferenceManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Local VPN tunnel with in-process forwarding.
 *
 * This service forwards IPv4 UDP traffic and provides a lightweight TCP session
 * bridge. The TCP bridge is intentionally minimal (best-effort, no retransmit
 * scheduler), but it is enough to move from "capture-only" to true forwarding.
 */
class TrafficCaptureVpnService : VpnService() {

    companion object {
        private const val PREF_VPN_MITM_ENABLED = "vpn_mitm_enabled"
    }

    companion object {
        private const val ACTION_CONNECT = "com.tpeapp.vpn.CONNECT"
        private const val ACTION_DISCONNECT = "com.tpeapp.vpn.DISCONNECT"
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
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
    private var tunnelThread: Thread? = null
    private var cleanupThread: Thread? = null
    private var mitmProxy: LocalMitmProxy? = null
    private var mitmPort: Int = -1
    private val running = AtomicBoolean(false)
    private val writeLock = Any()

    private val udpSessions = ConcurrentHashMap<UdpKey, UdpSession>()
    private val tcpSessions = ConcurrentHashMap<TcpKey, TcpSession>()
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    private val stats = TunnelStats()

    @Volatile
    private var blockedRulesCache: List<String> = emptyList()

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
            startForeground(NOTIFICATION_ID, buildNotification("Network forwarding active"))
            blockedRulesCache = VpnPolicyManager.blockedDomainRules(this)
            val proxy = LocalMitmProxy(
                context = applicationContext,
                protectSocket = { socket -> protect(socket) },
            )
            val proxyPort = proxy.start()
            if (proxyPort <= 0) {
                VpnPolicyManager.setTunnelRuntime(this, active = false, error = "MITM proxy failed to start")
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
            mitmProxy = proxy
            mitmPort = proxyPort

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
            tunnelThread = Thread {
                runTunnelLoop(established)
            }.apply {
                name = "tpe-vpn-forward"
                isDaemon = true
                start()
            }

            cleanupThread = Thread {
                runCleanupLoop()
            }.apply {
                name = "tpe-vpn-cleanup"
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
        tunnelThread?.interrupt()
        tunnelThread = null
        cleanupThread?.interrupt()
        cleanupThread = null

        runCatching { mitmProxy?.stop() }
        mitmProxy = null
        mitmPort = -1

        for ((_, session) in udpSessions) {
            session.close()
        }
        udpSessions.clear()

        for ((_, session) in tcpSessions) {
            session.close()
        }
        tcpSessions.clear()

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
        // Keep Android Auto off-tunnel for projection and transport stability.
        runCatching { builder.addDisallowedApplication(ANDROID_AUTO_PACKAGE) }

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

    private fun runTunnelLoop(established: ParcelFileDescriptor) {
        var bytesAccum = 0L
        var packetsAccum = 0L
        var lastFlush = System.currentTimeMillis()
        val packetBuffer = ByteArray(65535)

        try {
            FileInputStream(established.fileDescriptor).use { input ->
                FileOutputStream(established.fileDescriptor).use { output ->
                    while (running.get()) {
                        val read = input.read(packetBuffer)
                        if (read <= 0) continue

                        packetsAccum += 1L
                        bytesAccum += read.toLong()

                        val ip = PacketCodec.parseIpv4(packetBuffer, read)
                        if (ip == null) {
                            stats.droppedPackets++
                            flushStatsIfNeeded(bytesAccum, packetsAccum, lastFlush).also {
                                bytesAccum = it.first
                                packetsAccum = it.second
                                lastFlush = it.third
                            }
                            continue
                        }

                        when (ip.protocol) {
                            17 -> handleUdpPacket(ip, output)
                            6 -> handleTcpPacket(ip, output)
                            else -> stats.droppedPackets++
                        }

                        val flush = flushStatsIfNeeded(bytesAccum, packetsAccum, lastFlush)
                        bytesAccum = flush.first
                        packetsAccum = flush.second
                        lastFlush = flush.third
                    }
                }
            }
        } catch (_: IOException) {
            // Stopping tunnel closes the file descriptor which is expected here.
        } catch (_: Exception) {
            // Runtime failures are reflected by tunnel inactive state on shutdown.
        } finally {
            if (bytesAccum > 0L || packetsAccum > 0L) {
                VpnPolicyManager.addCapturedTraffic(this, bytesAccum, packetsAccum)
            }
            flushForwardingStats()
        }
    }

    private fun flushStatsIfNeeded(bytesAccum: Long, packetsAccum: Long, lastFlush: Long): Triple<Long, Long, Long> {
        val now = System.currentTimeMillis()
        return if (now - lastFlush >= 1000L || packetsAccum >= 64L) {
            VpnPolicyManager.addCapturedTraffic(this, bytesAccum, packetsAccum)
            flushForwardingStats()
            Triple(0L, 0L, now)
        } else {
            Triple(bytesAccum, packetsAccum, lastFlush)
        }
    }

    private fun flushForwardingStats() {
        val forwarded: Long
        val dropped: Long
        synchronized(stats) {
            forwarded = stats.forwardedUdpPackets
            dropped = stats.droppedPackets
            stats.forwardedUdpPackets = 0L
            stats.droppedPackets = 0L
        }

        if (forwarded > 0L) {
            // Approximate byte accounting from packet count keeps telemetry lightweight.
            VpnPolicyManager.addForwardedTraffic(this, bytes = 0L, packets = forwarded)
        }
        if (dropped > 0L) {
            VpnPolicyManager.addDroppedPackets(this, dropped)
        }
    }

    private fun runCleanupLoop() {
        while (running.get()) {
            try {
                Thread.sleep(5_000L)
            } catch (_: InterruptedException) {
                return
            }

            val now = System.currentTimeMillis()
            udpSessions.entries.removeIf { (_, session) ->
                val stale = now - session.lastActivityMs > 30_000L
                if (stale) session.close()
                stale
            }
            tcpSessions.entries.removeIf { (_, session) ->
                val stale = now - session.lastActivityMs > 120_000L || session.closed.get()
                if (stale) session.close()
                stale
            }
        }
    }

    private fun handleUdpPacket(ip: ParsedIpv4Packet, output: FileOutputStream) {
        val udp = PacketCodec.parseUdp(ip) ?: run {
            stats.droppedPackets++
            return
        }

        val key = UdpKey(
            clientIp = ip.source,
            clientPort = udp.sourcePort,
            remoteIp = ip.destination,
            remotePort = udp.destinationPort,
        )

        val session = udpSessions.getOrPut(key) {
            val socket = DatagramSocket().apply {
                soTimeout = 2000
                protect(this)
                connect(InetSocketAddress(ip.destination, udp.destinationPort))
            }
            UdpSession(socket)
        }
        session.lastActivityMs = System.currentTimeMillis()
        if (session.metadataRecorded.compareAndSet(false, true)) {
            val dnsDomain = if (udp.destinationPort == 53) {
                PacketCodec.parseDnsQueryDomain(udp.payload)
            } else {
                null
            }
            if (dnsDomain != null && shouldBlockDomain(dnsDomain)) {
                val blockedPayload = PacketCodec.buildDnsBlockedResponse(udp.payload)
                if (blockedPayload != null) {
                    val blockedPacket = PacketCodec.buildUdpIpv4Packet(
                        source = key.remoteIp,
                        destination = key.clientIp,
                        sourcePort = key.remotePort,
                        destinationPort = key.clientPort,
                        payload = blockedPayload,
                    )
                    synchronized(writeLock) {
                        output.write(blockedPacket)
                        output.flush()
                    }
                    VpnPolicyManager.addBlockedDomainEvent(this, dnsDomain)
                    return
                }
            }
            recordFlowSample(
                protocol = OsConstants.IPPROTO_UDP,
                localPort = udp.sourcePort,
                remoteIp = ip.destination,
                remotePort = udp.destinationPort,
                dnsDomain = dnsDomain,
            )
        }

        if (udp.payload.isNotEmpty()) {
            runCatching {
                val outbound = DatagramPacket(udp.payload, udp.payload.size)
                session.socket.send(outbound)
                stats.forwardedUdpPackets++
            }.onFailure {
                stats.droppedPackets++
                session.close()
                udpSessions.remove(key)
                return
            }
        }

        if (session.receiverStarted.compareAndSet(false, true)) {
            Thread {
                val recvBuffer = ByteArray(65535)
                while (running.get() && !session.closed.get()) {
                    try {
                        val pkt = DatagramPacket(recvBuffer, recvBuffer.size)
                        session.socket.receive(pkt)
                        session.lastActivityMs = System.currentTimeMillis()

                        val udpPacket = PacketCodec.buildUdpIpv4Packet(
                            source = key.remoteIp,
                            destination = key.clientIp,
                            sourcePort = key.remotePort,
                            destinationPort = key.clientPort,
                            payload = pkt.data.copyOf(pkt.length),
                        )

                        synchronized(writeLock) {
                            output.write(udpPacket)
                            output.flush()
                        }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
                session.close()
                udpSessions.remove(key)
            }.apply {
                name = "tpe-vpn-udp-${udp.destinationPort}"
                isDaemon = true
                start()
            }
        }
    }

    private fun handleTcpPacket(ip: ParsedIpv4Packet, output: FileOutputStream) {
        val tcp = PacketCodec.parseTcp(ip) ?: run {
            stats.droppedPackets++
            return
        }

        val key = TcpKey(
            clientIp = ip.source,
            clientPort = tcp.sourcePort,
            remoteIp = ip.destination,
            remotePort = tcp.destinationPort,
        )

        val existing = tcpSessions[key]
        if (existing == null) {
            if (!tcp.syn) {
                stats.droppedPackets++
                return
            }
            openTcpSession(key, tcp, output)
            return
        }

        existing.lastActivityMs = System.currentTimeMillis()

        if (tcp.rst) {
            existing.close()
            tcpSessions.remove(key)
            return
        }

        if (!existing.established && tcp.ack && tcp.ackNumber == existing.serverNextSeq) {
            existing.established = true
            return
        }

        if (!existing.domainChecked && tcp.payload.isNotEmpty()) {
            existing.domainChecked = true
            val tlsSni = if (existing.key.remotePort == 443) {
                PacketCodec.parseTlsClientHelloSni(tcp.payload)
            } else {
                null
            }
            val httpHost = if (existing.key.remotePort == 80 || existing.key.remotePort == 8080) {
                PacketCodec.parseHttpHost(tcp.payload)
            } else {
                null
            }
            val candidate = tlsSni ?: httpHost
            if (candidate != null && shouldBlockDomain(candidate)) {
                sendTcpRst(output, existing.key, tcp)
                existing.close()
                tcpSessions.remove(existing.key)
                VpnPolicyManager.addBlockedDomainEvent(this, candidate)
                return
            }
        }

        if (tcp.payload.isNotEmpty()) {
            val writeOk = consumeClientPayload(existing, tcp.sequenceNumber, tcp.payload)
            if (!writeOk) {
                existing.close()
                tcpSessions.remove(key)
                return
            }
            sendTcpAck(output, existing)
        }

        if (tcp.fin) {
            existing.clientNextSeq += 1
            sendTcpAck(output, existing)
            runCatching { existing.socket.shutdownOutput() }
        }
    }

    private fun openTcpSession(key: TcpKey, syn: ParsedTcpSegment, output: FileOutputStream) {
        val socket = Socket()
        val useMitm = shouldUseMitmForPort(key.remotePort)
        runCatching {
            protect(socket)
            socket.tcpNoDelay = true
            if (useMitm && mitmPort > 0) {
                socket.connect(InetSocketAddress("127.0.0.1", mitmPort), 8000)
            } else {
                socket.connect(InetSocketAddress(key.remoteIp, key.remotePort), 8000)
            }
        }.onFailure {
            sendTcpRst(output, key, syn)
            return
        }

        if (useMitm && mitmPort > 0) {
            runCatching {
                val header = "TPE-TARGET ${key.remoteIp.hostAddress}:${key.remotePort}\n".toByteArray(Charsets.US_ASCII)
                socket.getOutputStream().write(header)
                socket.getOutputStream().flush()
            }.onFailure {
                runCatching { socket.close() }
                sendTcpRst(output, key, syn)
                return
            }
        }

        val serverSeq = Random.nextInt(1, Int.MAX_VALUE / 2).toLong()
        val session = TcpSession(
            key = key,
            socket = socket,
            clientNextSeq = syn.sequenceNumber + 1,
            serverSeq = serverSeq,
            serverNextSeq = serverSeq + 1,
        )
        tcpSessions[key] = session
        recordFlowSample(
            protocol = OsConstants.IPPROTO_TCP,
            localPort = key.clientPort,
            remoteIp = key.remoteIp,
            remotePort = key.remotePort,
            dnsDomain = null,
        )

        val synAck = PacketCodec.buildTcpIpv4Packet(
            source = key.remoteIp,
            destination = key.clientIp,
            sourcePort = key.remotePort,
            destinationPort = key.clientPort,
            sequence = serverSeq,
            ack = session.clientNextSeq,
            syn = true,
            ackFlag = true,
            fin = false,
            rst = false,
            psh = false,
            payload = ByteArray(0),
        )
        synchronized(writeLock) {
            output.write(synAck)
            output.flush()
        }

        Thread {
            val recvBuffer = ByteArray(16 * 1024)
            try {
                val input = socket.getInputStream()
                while (running.get() && !session.closed.get()) {
                    val read = input.read(recvBuffer)
                    if (read <= 0) break
                    val payload = recvBuffer.copyOf(read)

                    val packet = PacketCodec.buildTcpIpv4Packet(
                        source = key.remoteIp,
                        destination = key.clientIp,
                        sourcePort = key.remotePort,
                        destinationPort = key.clientPort,
                        sequence = session.serverNextSeq,
                        ack = session.clientNextSeq,
                        syn = false,
                        ackFlag = true,
                        fin = false,
                        rst = false,
                        psh = true,
                        payload = payload,
                    )
                    session.serverNextSeq += payload.size
                    session.lastActivityMs = System.currentTimeMillis()
                    synchronized(writeLock) {
                        output.write(packet)
                        output.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (!session.closed.get()) {
                    sendTcpFin(output, session)
                }
                session.close()
                tcpSessions.remove(key)
            }
        }.apply {
            name = "tpe-vpn-tcp-${key.remotePort}"
            isDaemon = true
            start()
        }
    }

    private fun sendTcpAck(output: FileOutputStream, session: TcpSession) {
        val ackPacket = PacketCodec.buildTcpIpv4Packet(
            source = session.key.remoteIp,
            destination = session.key.clientIp,
            sourcePort = session.key.remotePort,
            destinationPort = session.key.clientPort,
            sequence = session.serverNextSeq,
            ack = session.clientNextSeq,
            syn = false,
            ackFlag = true,
            fin = false,
            rst = false,
            psh = false,
            payload = ByteArray(0),
        )
        synchronized(writeLock) {
            output.write(ackPacket)
            output.flush()
        }
    }

    private fun sendTcpFin(output: FileOutputStream, session: TcpSession) {
        val finPacket = PacketCodec.buildTcpIpv4Packet(
            source = session.key.remoteIp,
            destination = session.key.clientIp,
            sourcePort = session.key.remotePort,
            destinationPort = session.key.clientPort,
            sequence = session.serverNextSeq,
            ack = session.clientNextSeq,
            syn = false,
            ackFlag = true,
            fin = true,
            rst = false,
            psh = false,
            payload = ByteArray(0),
        )
        session.serverNextSeq += 1
        synchronized(writeLock) {
            output.write(finPacket)
            output.flush()
        }
    }

    private fun sendTcpRst(output: FileOutputStream, key: TcpKey, syn: ParsedTcpSegment) {
        val rst = PacketCodec.buildTcpIpv4Packet(
            source = key.remoteIp,
            destination = key.clientIp,
            sourcePort = key.remotePort,
            destinationPort = key.clientPort,
            sequence = 0,
            ack = syn.sequenceNumber + 1,
            syn = false,
            ackFlag = true,
            fin = false,
            rst = true,
            psh = false,
            payload = ByteArray(0),
        )
        synchronized(writeLock) {
            output.write(rst)
            output.flush()
        }
    }

    private fun consumeClientPayload(session: TcpSession, sequenceNumber: Long, payload: ByteArray): Boolean {
        val chunksToWrite = mutableListOf<ByteArray>()

        synchronized(session.lock) {
            when {
                sequenceNumber < session.clientNextSeq -> {
                    val overlap = (session.clientNextSeq - sequenceNumber).toInt()
                    session.retransmissions += 1
                    if (overlap >= payload.size) {
                        return true
                    }
                    chunksToWrite += payload.copyOfRange(overlap, payload.size)
                }
                sequenceNumber > session.clientNextSeq -> {
                    if (!session.pendingClientSegments.containsKey(sequenceNumber)) {
                        session.pendingClientSegments[sequenceNumber] = payload
                        session.outOfOrderSegments += 1
                    }
                    return true
                }
                else -> {
                    chunksToWrite += payload
                }
            }

            while (true) {
                val pending = session.pendingClientSegments[session.clientNextSeq] ?: break
                chunksToWrite += pending
                session.pendingClientSegments.remove(session.clientNextSeq)
            }
        }

        return runCatching {
            val out = session.socket.getOutputStream()
            for (chunk in chunksToWrite) {
                if (chunk.isEmpty()) continue
                out.write(chunk)
                synchronized(session.lock) {
                    session.clientNextSeq += chunk.size
                }
            }
            out.flush()
            true
        }.getOrElse { false }
    }

    private fun recordFlowSample(
        protocol: Int,
        localPort: Int,
        remoteIp: InetAddress,
        remotePort: Int,
        dnsDomain: String?,
    ) {
        val endpoint = buildString {
            append(if (protocol == OsConstants.IPPROTO_TCP) "tcp" else "udp")
            append(':')
            append(remoteIp.hostAddress ?: "unknown")
            append(':')
            append(remotePort)
        }
        val packageName = resolveOwnerPackage(protocol, localPort, remoteIp, remotePort)
        VpnPolicyManager.addFlowSample(
            context = this,
            endpointKey = endpoint,
            domain = dnsDomain,
            packageName = packageName,
        )
    }

    private fun resolveOwnerPackage(
        protocol: Int,
        localPort: Int,
        remoteIp: InetAddress,
        remotePort: Int,
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cm = connectivityManager ?: return null
        return runCatching {
            val uid = cm.getConnectionOwnerUid(
                protocol,
                InetSocketAddress("10.7.0.2", localPort),
                InetSocketAddress(remoteIp, remotePort),
            )
            if (uid <= 0) return@runCatching null
            packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()
    }

    private fun shouldBlockDomain(domain: String): Boolean {
        val normalized = domain.trim().lowercase()
        if (normalized.isBlank()) return false
        val rules = blockedRulesCache
        if (rules.isEmpty()) return false
        return rules.any { rule ->
            normalized == rule || normalized.endsWith(".$rule") || normalized.contains(rule)
        }
    }

    private fun shouldUseMitmForPort(port: Int): Boolean {
        if (port == 80) return true
        if (port != 443) return false
        return PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(PREF_VPN_MITM_ENABLED, false)
    }
}

private data class UdpKey(
    val clientIp: InetAddress,
    val clientPort: Int,
    val remoteIp: InetAddress,
    val remotePort: Int,
)

private class UdpSession(
    val socket: DatagramSocket,
) {
    val receiverStarted = AtomicBoolean(false)
    val metadataRecorded = AtomicBoolean(false)
    val closed = AtomicBoolean(false)
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
    }
}

private data class TcpKey(
    val clientIp: InetAddress,
    val clientPort: Int,
    val remoteIp: InetAddress,
    val remotePort: Int,
)

private class TcpSession(
    val key: TcpKey,
    val socket: Socket,
    @Volatile var clientNextSeq: Long,
    @Volatile var serverSeq: Long,
    @Volatile var serverNextSeq: Long,
) {
    @Volatile var established: Boolean = false
    @Volatile var domainChecked: Boolean = false
    val closed = AtomicBoolean(false)
    val lock = Any()
    val pendingClientSegments = TreeMap<Long, ByteArray>()
    @Volatile var retransmissions: Long = 0L
    @Volatile var outOfOrderSegments: Long = 0L
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
    }
}

private class TunnelStats {
    @Volatile var forwardedUdpPackets: Long = 0L
    @Volatile var droppedPackets: Long = 0L
}

private data class ParsedIpv4Packet(
    val source: InetAddress,
    val destination: InetAddress,
    val protocol: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
    val raw: ByteArray,
)

private data class ParsedUdpDatagram(
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
)

private data class ParsedTcpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val ackNumber: Long,
    val syn: Boolean,
    val ack: Boolean,
    val fin: Boolean,
    val rst: Boolean,
    val payload: ByteArray,
)

private object PacketCodec {
    fun parseIpv4(buffer: ByteArray, length: Int): ParsedIpv4Packet? {
        if (length < 20) return null
        val version = (buffer[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihl = (buffer[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null
        val totalLength = readU16(buffer, 2)
        if (totalLength < ihl || totalLength > length) return null
        val protocol = buffer[9].toInt() and 0xFF
        val source = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
        val destination = InetAddress.getByAddress(buffer.copyOfRange(16, 20))
        return ParsedIpv4Packet(
            source = source,
            destination = destination,
            protocol = protocol,
            payloadOffset = ihl,
            payloadLength = totalLength - ihl,
            raw = buffer.copyOf(totalLength),
        )
    }

    fun parseUdp(ip: ParsedIpv4Packet): ParsedUdpDatagram? {
        if (ip.payloadLength < 8) return null
        val udpStart = ip.payloadOffset
        val sourcePort = readU16(ip.raw, udpStart)
        val destinationPort = readU16(ip.raw, udpStart + 2)
        val udpLength = readU16(ip.raw, udpStart + 4)
        if (udpLength < 8 || udpLength > ip.payloadLength) return null
        val payloadLen = udpLength - 8
        val payloadStart = udpStart + 8
        val payload = if (payloadLen <= 0) ByteArray(0) else ip.raw.copyOfRange(payloadStart, payloadStart + payloadLen)
        return ParsedUdpDatagram(sourcePort, destinationPort, payload)
    }

    fun parseTcp(ip: ParsedIpv4Packet): ParsedTcpSegment? {
        if (ip.payloadLength < 20) return null
        val tcpStart = ip.payloadOffset
        val sourcePort = readU16(ip.raw, tcpStart)
        val destinationPort = readU16(ip.raw, tcpStart + 2)
        val sequence = readU32(ip.raw, tcpStart + 4)
        val ackNumber = readU32(ip.raw, tcpStart + 8)
        val dataOffset = ((ip.raw[tcpStart + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffset < 20 || dataOffset > ip.payloadLength) return null
        val flags = ip.raw[tcpStart + 13].toInt() and 0x3F
        val payloadStart = tcpStart + dataOffset
        val payloadLen = maxOf(0, ip.payloadLength - dataOffset)
        val payload = if (payloadLen == 0) ByteArray(0) else ip.raw.copyOfRange(payloadStart, payloadStart + payloadLen)
        return ParsedTcpSegment(
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sequenceNumber = sequence,
            ackNumber = ackNumber,
            syn = (flags and 0x02) != 0,
            ack = (flags and 0x10) != 0,
            fin = (flags and 0x01) != 0,
            rst = (flags and 0x04) != 0,
            payload = payload,
        )
    }

    fun parseDnsQueryDomain(payload: ByteArray): String? {
        if (payload.size < 12) return null
        val qdCount = readU16(payload, 4)
        if (qdCount <= 0) return null

        var idx = 12
        val labels = mutableListOf<String>()
        while (idx < payload.size) {
            val len = payload[idx].toInt() and 0xFF
            idx += 1
            if (len == 0) break
            if (len > 63 || idx + len > payload.size) return null
            labels += payload.copyOfRange(idx, idx + len).toString(Charsets.US_ASCII)
            idx += len
        }
        if (labels.isEmpty()) return null
        return labels.joinToString(".").lowercase()
    }

    fun buildDnsBlockedResponse(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val out = query.copyOf()
        out[2] = (out[2].toInt() or 0x80).toByte() // QR=1
        out[3] = (out[3].toInt() or 0x80).toByte() // RA=1
        out[3] = (out[3].toInt() and 0xF0 or 0x03).toByte() // RCODE=3 (NXDOMAIN)
        out[6] = 0
        out[7] = 0
        out[8] = 0
        out[9] = 0
        out[10] = 0
        out[11] = 0
        return out
    }

    fun parseTlsClientHelloSni(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val contentType = payload[0].toInt() and 0xFF
        if (contentType != 0x16) return null
        val recordLen = readU16(payload, 3)
        if (payload.size < 5 + recordLen || recordLen < 42) return null
        if ((payload[5].toInt() and 0xFF) != 0x01) return null

        var idx = 5 + 4 // handshake header
        idx += 2 // version
        idx += 32 // random
        if (idx >= payload.size) return null

        val sessionLen = payload[idx].toInt() and 0xFF
        idx += 1 + sessionLen
        if (idx + 2 > payload.size) return null
        val cipherLen = readU16(payload, idx)
        idx += 2 + cipherLen
        if (idx >= payload.size) return null
        val compLen = payload[idx].toInt() and 0xFF
        idx += 1 + compLen
        if (idx + 2 > payload.size) return null
        val extLen = readU16(payload, idx)
        idx += 2
        val extEnd = (idx + extLen).coerceAtMost(payload.size)

        while (idx + 4 <= extEnd) {
            val extType = readU16(payload, idx)
            val extSize = readU16(payload, idx + 2)
            idx += 4
            if (idx + extSize > extEnd) break
            if (extType == 0x0000 && extSize >= 5) {
                var sniIdx = idx + 2
                val sniEnd = idx + extSize
                while (sniIdx + 3 <= sniEnd) {
                    val nameType = payload[sniIdx].toInt() and 0xFF
                    val nameLen = readU16(payload, sniIdx + 1)
                    sniIdx += 3
                    if (sniIdx + nameLen > sniEnd) break
                    if (nameType == 0 && nameLen > 0) {
                        return payload.copyOfRange(sniIdx, sniIdx + nameLen).toString(Charsets.US_ASCII).lowercase()
                    }
                    sniIdx += nameLen
                }
            }
            idx += extSize
        }
        return null
    }

    fun parseHttpHost(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val head = payload.copyOfRange(0, minOf(payload.size, 4096)).toString(Charsets.US_ASCII)
        val lower = head.lowercase()
        val hostIndex = lower.indexOf("\r\nhost:")
        if (hostIndex < 0) return null
        val valueStart = hostIndex + 7
        val valueEnd = lower.indexOf("\r\n", valueStart).takeIf { it >= 0 } ?: return null
        return head.substring(valueStart, valueEnd).trim().lowercase()
    }

    fun buildUdpIpv4Packet(
        source: InetAddress,
        destination: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x45
        out[1] = 0x00
        writeU16(out, 2, totalLen)
        writeU16(out, 4, Random.nextInt(0, 65535))
        writeU16(out, 6, 0)
        out[8] = 64
        out[9] = 17
        writeU16(out, 10, 0)
        writeIp(out, 12, source)
        writeIp(out, 16, destination)
        writeU16(out, 10, checksum(out, 0, 20))

        val udpOffset = 20
        writeU16(out, udpOffset, sourcePort)
        writeU16(out, udpOffset + 2, destinationPort)
        writeU16(out, udpOffset + 4, udpLen)
        writeU16(out, udpOffset + 6, 0)
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, udpOffset + 8, payload.size)
        }

        val udpChecksum = transportChecksum(
            protocol = 17,
            source = source.address,
            destination = destination.address,
            segment = out,
            offset = udpOffset,
            length = udpLen,
        )
        writeU16(out, udpOffset + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return out
    }

    fun buildTcpIpv4Packet(
        source: InetAddress,
        destination: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        ack: Long,
        syn: Boolean,
        ackFlag: Boolean,
        fin: Boolean,
        rst: Boolean,
        psh: Boolean,
        payload: ByteArray,
    ): ByteArray {
        val tcpHeaderLen = 20
        val tcpLen = tcpHeaderLen + payload.size
        val totalLen = 20 + tcpLen
        val out = ByteArray(totalLen)

        out[0] = 0x45
        out[1] = 0
        writeU16(out, 2, totalLen)
        writeU16(out, 4, Random.nextInt(0, 65535))
        writeU16(out, 6, 0)
        out[8] = 64
        out[9] = 6
        writeU16(out, 10, 0)
        writeIp(out, 12, source)
        writeIp(out, 16, destination)
        writeU16(out, 10, checksum(out, 0, 20))

        val tcpOffset = 20
        writeU16(out, tcpOffset, sourcePort)
        writeU16(out, tcpOffset + 2, destinationPort)
        writeU32(out, tcpOffset + 4, sequence)
        writeU32(out, tcpOffset + 8, ack)
        out[tcpOffset + 12] = 0x50

        var flags = 0
        if (fin) flags = flags or 0x01
        if (syn) flags = flags or 0x02
        if (rst) flags = flags or 0x04
        if (psh) flags = flags or 0x08
        if (ackFlag) flags = flags or 0x10
        out[tcpOffset + 13] = flags.toByte()

        writeU16(out, tcpOffset + 14, 65535)
        writeU16(out, tcpOffset + 16, 0)
        writeU16(out, tcpOffset + 18, 0)

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, tcpOffset + tcpHeaderLen, payload.size)
        }

        val tcpChecksum = transportChecksum(
            protocol = 6,
            source = source.address,
            destination = destination.address,
            segment = out,
            offset = tcpOffset,
            length = tcpLen,
        )
        writeU16(out, tcpOffset + 16, tcpChecksum)
        return out
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        val b0 = data[offset].toLong() and 0xFF
        val b1 = data[offset + 1].toLong() and 0xFF
        val b2 = data[offset + 2].toLong() and 0xFF
        val b3 = data[offset + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun writeU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = ((value ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeIp(out: ByteArray, offset: Int, address: InetAddress) {
        val bytes = address.address
        if (bytes.size != 4) throw IllegalArgumentException("Only IPv4 is supported")
        System.arraycopy(bytes, 0, out, offset, 4)
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        var remaining = length
        while (remaining > 1) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2
            remaining -= 2
        }
        if (remaining > 0) {
            sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun transportChecksum(
        protocol: Int,
        source: ByteArray,
        destination: ByteArray,
        segment: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var sum = 0L

        for (i in 0 until 4 step 2) {
            sum += (((source[i].toInt() and 0xFF) shl 8) or (source[i + 1].toInt() and 0xFF)).toLong()
            sum += (((destination[i].toInt() and 0xFF) shl 8) or (destination[i + 1].toInt() and 0xFF)).toLong()
        }
        sum += protocol.toLong() and 0xFF
        sum += length.toLong() and 0xFFFF

        var i = offset
        var remaining = length
        while (remaining > 1) {
            sum += (((segment[i].toInt() and 0xFF) shl 8) or (segment[i + 1].toInt() and 0xFF)).toLong()
            i += 2
            remaining -= 2
        }
        if (remaining > 0) {
            sum += ((segment[i].toInt() and 0xFF) shl 8).toLong()
        }

        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }
}
