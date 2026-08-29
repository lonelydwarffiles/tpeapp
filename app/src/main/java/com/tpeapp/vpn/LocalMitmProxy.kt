package com.hound.controller.vpn

import android.content.Context
import androidx.preference.PreferenceManager
import com.hound.controller.service.FilterService
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-throughput local TCP relay used by the VPN tunnel.
 *
 * Protocol from VPN -> proxy per accepted socket:
 *   TPE-TARGET <host>:<port>\n
 * followed by the raw client TCP stream.
 *
 * This class never decrypts, rewrites, or otherwise modifies TCP payloads.
 * It only performs metadata sampling (SNI/HTTP Host) from client bytes.
 */
class LocalMitmProxy(
    private val context: Context,
    private val protectSocket: (Socket) -> Boolean,
) {

    companion object {
        private const val STREAM_BUFFER_SIZE = 64 * 1024
        private const val PROBE_MAX_BYTES = 16 * 1024
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): Int {
        if (!running.compareAndSet(false, true)) {
            return server?.localPort ?: -1
        }
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        server = socket
        acceptThread = thread(start = true, isDaemon = true, name = "tpe-proxy-accept") {
            acceptLoop(socket)
        }
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (running.get()) {
            val client = try {
                serverSocket.accept()
            } catch (_: Exception) {
                if (!running.get()) return
                continue
            }

            thread(start = true, isDaemon = true, name = "tpe-proxy-client") {
                handleClient(client)
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { local ->
            runCatching { local.keepAlive = true }
            val clientIn = local.getInputStream()
            val clientOut = local.getOutputStream()
            val target = readTargetLine(clientIn) ?: return

            val upstream = Socket()
            try {
                protectSocket(upstream)
                runCatching { upstream.keepAlive = true }
                upstream.connect(InetSocketAddress(target.host, target.port), 8000)

                val destinationIp = upstream.inetAddress?.hostAddress ?: target.host
                val upstreamIn = upstream.getInputStream()
                val upstreamOut = upstream.getOutputStream()

                runBlocking {
                    coroutineScope {
                        val upstreamToClient = async(Dispatchers.IO) {
                            pipe(upstreamIn, clientOut)
                        }
                        val clientToUpstream = async(Dispatchers.IO) {
                            pipeClientToUpstream(
                                input = clientIn,
                                output = upstreamOut,
                                target = target,
                                destinationIp = destinationIp,
                            )
                            runCatching { upstreamOut.flush() }
                            runCatching { upstream.shutdownOutput() }
                        }
                        clientToUpstream.await()
                        upstreamToClient.await()
                    }
                }
            } catch (_: IOException) {
                // Transport errors are expected on mobile network changes.
            } finally {
                runCatching { upstream.close() }
            }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                return
            }
            if (read <= 0) return
            try {
                output.write(buffer, 0, read)
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun pipeClientToUpstream(
        input: InputStream,
        output: OutputStream,
        target: ProxyTarget,
        destinationIp: String,
    ) {
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        val probe = ByteArrayOutputStream(PROBE_MAX_BYTES)
        var metadataLogged = false
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                return
            }
            if (read <= 0) {
                if (!metadataLogged) {
                    val fallback = target.host.takeUnless { isIpLiteralHost(it) }
                    recordMetadata(destinationIp = destinationIp, domain = fallback)
                }
                return
            }

            if (!metadataLogged) {
                val remaining = PROBE_MAX_BYTES - probe.size()
                if (remaining > 0) {
                    probe.write(buffer, 0, minOf(read, remaining))
                }
                val sampled = probe.toByteArray()
                val domain = parseTlsClientHelloSni(sampled)
                    ?: parseHttpHost(sampled)
                    ?: target.host.takeUnless { isIpLiteralHost(it) }
                if (!domain.isNullOrBlank() || probe.size() >= PROBE_MAX_BYTES) {
                    recordMetadata(destinationIp = destinationIp, domain = domain)
                    metadataLogged = true
                }
            }

            try {
                output.write(buffer, 0, read)
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun readTargetLine(input: InputStream): ProxyTarget? {
        val line = ByteArrayOutputStream(128)
        while (line.size() < 256) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
        }
        val text = line.toString(Charsets.US_ASCII.name()).trim()
        if (!text.startsWith("TPE-TARGET ")) return null
        val endpoint = text.removePrefix("TPE-TARGET ").trim()
        val lastColon = endpoint.lastIndexOf(':')
        if (lastColon <= 0 || lastColon >= endpoint.lastIndex) return null
        val host = endpoint.substring(0, lastColon).trim()
        val port = endpoint.substring(lastColon + 1).trim().toIntOrNull() ?: return null
        return ProxyTarget(host, port)
    }

    private fun parseHttpHost(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val text = payload.toString(Charsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return null
        val head = text.substring(0, headerEnd)
        for (line in head.split("\r\n")) {
            if (line.startsWith("Host:", ignoreCase = true)) {
                return line.substringAfter(':').trim().substringBefore(':').ifBlank { null }
            }
        }
        return null
    }

    private fun parseTlsClientHelloSni(payload: ByteArray): String? {
        if (payload.size < 5) return null
        if ((payload[0].toInt() and 0xFF) != 22) return null
        if ((payload[5].toInt() and 0xFF) != 1) return null

        val recordLen = readU16(payload, 3)
        if (payload.size < 5 + recordLen || recordLen < 42) return null

        var idx = 9
        if (idx + 34 > payload.size) return null
        idx += 34

        val sessionIdLen = readU8(payload, idx)
        idx += 1 + sessionIdLen
        if (idx + 2 > payload.size) return null

        val cipherSuitesLen = readU16(payload, idx)
        idx += 2 + cipherSuitesLen
        if (idx + 1 > payload.size) return null

        val compressionMethodsLen = readU8(payload, idx)
        idx += 1 + compressionMethodsLen
        if (idx + 2 > payload.size) return null

        val extensionsLen = readU16(payload, idx)
        idx += 2
        val extEnd = minOf(idx + extensionsLen, payload.size)

        while (idx + 4 <= extEnd) {
            val extType = readU16(payload, idx)
            val extSize = readU16(payload, idx + 2)
            idx += 4
            if (idx + extSize > extEnd) break

            if (extType == 0 && extSize >= 5) {
                val sniListLen = readU16(payload, idx)
                var sniIdx = idx + 2
                val sniEnd = minOf(idx + extSize, sniIdx + sniListLen)
                while (sniIdx + 3 <= sniEnd) {
                    val nameType = readU8(payload, sniIdx)
                    val nameLen = readU16(payload, sniIdx + 1)
                    sniIdx += 3
                    if (sniIdx + nameLen > sniEnd) break
                    if (nameType == 0 && nameLen > 0) {
                        return runCatching {
                            payload.copyOfRange(sniIdx, sniIdx + nameLen).toString(Charsets.US_ASCII).trim()
                        }.getOrNull()
                    }
                    sniIdx += nameLen
                }
            }
            idx += extSize
        }
        return null
    }

    private fun recordMetadata(destinationIp: String, domain: String?) {
        val normalizedDomain = domain?.trim()?.trimEnd('.')?.lowercase(Locale.US).orEmpty()
        val normalizedIp = destinationIp.trim()
        if (normalizedDomain.isBlank() && normalizedIp.isBlank()) return

        val now = System.currentTimeMillis()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(FilterService.PREF_INTERCEPT_SNI_FEED_JSON, "[]")?.trim().orEmpty()
        val arr = runCatching { JSONArray(raw.ifBlank { "[]" }) }.getOrElse { JSONArray() }

        arr.put(JSONObject().apply {
            put("host", normalizedDomain)
            put("domain", normalizedDomain)
            put("timestamp_ms", now)
            put("destination_ip", normalizedIp)
        })
        while (arr.length() > 120) {
            arr.remove(0)
        }
        prefs.edit().putString(FilterService.PREF_INTERCEPT_SNI_FEED_JSON, arr.toString()).apply()

        TpeVpnPolicyManager.addFlowSample(
            context = context,
            endpointKey = if (normalizedIp.isBlank()) null else normalizedIp,
            domain = normalizedDomain.ifBlank { null },
            packageName = null,
        )
    }

    private fun isIpLiteralHost(host: String): Boolean {
        val normalized = host.trim()
        if (normalized.isBlank()) return false
        return runCatching {
            InetAddress.getByName(normalized).hostAddress.equals(normalized, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun readU8(data: ByteArray, offset: Int): Int {
        if (offset !in data.indices) return 0
        return data[offset].toInt() and 0xFF
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private data class ProxyTarget(
        val host: String,
        val port: Int,
    )
}