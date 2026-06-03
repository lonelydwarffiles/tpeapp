package com.tpeapp.vpn

import android.content.Context
import android.graphics.BitmapFactory
import androidx.preference.PreferenceManager
import com.tpeapp.censor.CensorshipEngine
import com.tpeapp.service.FilterService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Lightweight local MITM proxy used by the VPN TCP relay.
 *
 * Protocol from VPN -> proxy per accepted socket:
 *   TPE-TARGET <ip>:<port>\n
 * followed by the raw client TCP stream.
 */
class LocalMitmProxy(
    private val context: Context,
    private val protectSocket: (Socket) -> Boolean,
) {

    companion object {
        private const val HEADER_DELIMITER = "\r\n\r\n"
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var censorEngine: CensorshipEngine? = null

    fun start(): Int {
        if (!running.compareAndSet(false, true)) {
            return server?.localPort ?: -1
        }
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        server = socket
        acceptThread = thread(start = true, isDaemon = true, name = "tpe-mitm-accept") {
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
        runCatching { censorEngine?.close() }
        censorEngine = null
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (running.get()) {
            val client = try {
                serverSocket.accept()
            } catch (_: Exception) {
                if (!running.get()) return
                continue
            }

            thread(start = true, isDaemon = true, name = "tpe-mitm-client") {
                handleClient(client)
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { local ->
            val input = BufferedInputStream(local.getInputStream())
            val output = BufferedOutputStream(local.getOutputStream())
            val target = readTargetLine(input) ?: return

            if (target.port == 80) {
                handleHttpFlow(input, output, target)
            } else {
                handlePassThrough(input, output, target)
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

    private fun handlePassThrough(clientIn: InputStream, clientOut: OutputStream, target: ProxyTarget) {
        val upstream = Socket()
        try {
            protectSocket(upstream)
            upstream.connect(InetSocketAddress(target.host, target.port), 8000)
            val upIn = BufferedInputStream(upstream.getInputStream())
            val upOut = BufferedOutputStream(upstream.getOutputStream())

            val upstreamToClient = thread(start = true, isDaemon = true) {
                copyStream(upIn, clientOut)
            }
            copyStream(clientIn, upOut)
            runCatching { upstream.shutdownOutput() }
            upstreamToClient.join(250)
        } finally {
            runCatching { upstream.close() }
        }
    }

    private fun handleHttpFlow(clientIn: BufferedInputStream, clientOut: BufferedOutputStream, target: ProxyTarget) {
        val upstream = Socket()
        try {
            protectSocket(upstream)
            upstream.connect(InetSocketAddress(target.host, target.port), 8000)
            val upIn = BufferedInputStream(upstream.getInputStream())
            val upOut = BufferedOutputStream(upstream.getOutputStream())

            val requestHeader = readHeaderBlock(clientIn) ?: return
            upOut.write(requestHeader)
            val requestContentLength = parseContentLength(requestHeader)
            if (requestContentLength > 0) {
                relayFixedLength(clientIn, upOut, requestContentLength)
            }
            upOut.flush()

            val responseHeader = readHeaderBlock(upIn) ?: return
            val contentType = parseHeaderValue(responseHeader, "content-type")?.lowercase(Locale.US).orEmpty()
            val encoding = parseHeaderValue(responseHeader, "content-encoding")?.lowercase(Locale.US).orEmpty()
            val transferEncoding = parseHeaderValue(responseHeader, "transfer-encoding")?.lowercase(Locale.US).orEmpty()
            val responseLength = parseContentLength(responseHeader)

            val isImage = contentType.startsWith("image/")
            val isRewritable = isImage && encoding.isEmpty() && transferEncoding.isEmpty() && responseLength in 1..MAX_IMAGE_BYTES

            if (!isRewritable) {
                clientOut.write(responseHeader)
                clientOut.flush()
                if (responseLength >= 0) {
                    relayFixedLength(upIn, clientOut, responseLength)
                } else {
                    copyStream(upIn, clientOut)
                }
                clientOut.flush()
                return
            }

            val originalBody = readFixedLength(upIn, responseLength)
            val rewrittenBody = censorImageIfNeeded(originalBody, contentType)
            val rewrittenHeader = rewriteContentLength(responseHeader, rewrittenBody.size)
            clientOut.write(rewrittenHeader)
            clientOut.write(rewrittenBody)
            clientOut.flush()
        } finally {
            runCatching { upstream.close() }
        }
    }

    private fun censorImageIfNeeded(imageBytes: ByteArray, contentType: String): ByteArray {
        val source = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes
        val style = currentCensorStyle()
        val forbidden = currentForbiddenClassIds()
        return try {
            val result = engine().censorBitmap(source, censorStyle = style, forbiddenClassIds = forbidden)
            val format = if (contentType.contains("png")) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
            val quality = if (format == android.graphics.Bitmap.CompressFormat.PNG) 100 else 95
            ByteArrayOutputStream().use { os ->
                result.outputBitmap.compress(format, quality, os)
                val out = os.toByteArray()
                if (!result.outputBitmap.isRecycled) result.outputBitmap.recycle()
                out
            }
        } catch (_: Exception) {
            imageBytes
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun engine(): CensorshipEngine {
        val existing = censorEngine
        if (existing != null) return existing
        val created = CensorshipEngine(context)
        censorEngine = created
        return created
    }

    private fun currentCensorStyle(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(FilterService.PREF_MEDIA_CENSOR_STYLE, "pixelate")?.trim().orEmpty().ifBlank { "pixelate" }
    }

    private fun currentForbiddenClassIds(): Set<Int> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(FilterService.PREF_MEDIA_FORBIDDEN_CLASS_IDS, "[2,3,4,6,14]")?.trim().orEmpty()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    add(arr.optInt(i, -1))
                }
            }.filter { it >= 0 }.toSet().ifEmpty { setOf(2, 3, 4, 6, 14) }
        }.getOrDefault(setOf(2, 3, 4, 6, 14))
    }

    private fun readHeaderBlock(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream(1024)
        val needle = HEADER_DELIMITER.toByteArray(Charsets.US_ASCII)
        var match = 0
        while (out.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            if (b.toByte() == needle[match]) {
                match += 1
                if (match == needle.size) {
                    return out.toByteArray()
                }
            } else {
                match = if (b.toByte() == needle[0]) 1 else 0
            }
        }
        return null
    }

    private fun parseContentLength(headerBytes: ByteArray): Int {
        val value = parseHeaderValue(headerBytes, "content-length") ?: return -1
        return value.toIntOrNull() ?: -1
    }

    private fun parseHeaderValue(headerBytes: ByteArray, name: String): String? {
        val text = headerBytes.toString(Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        for (line in lines) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase(Locale.US)
            if (key == name) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    private fun rewriteContentLength(headerBytes: ByteArray, bodyLength: Int): ByteArray {
        val text = headerBytes.toString(Charsets.ISO_8859_1)
        val lines = text.split("\r\n").toMutableList()
        var replaced = false
        for (i in lines.indices) {
            val line = lines[i]
            if (line.lowercase(Locale.US).startsWith("content-length:")) {
                lines[i] = "Content-Length: $bodyLength"
                replaced = true
                break
            }
        }
        if (!replaced) {
            val insertIdx = lines.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 } ?: lines.size
            lines.add(insertIdx, "Content-Length: $bodyLength")
        }
        return lines.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
    }

    private fun relayFixedLength(input: InputStream, output: OutputStream, length: Int) {
        var remaining = length
        val buffer = ByteArray(16 * 1024)
        while (remaining > 0) {
            val chunk = minOf(buffer.size, remaining)
            val read = input.read(buffer, 0, chunk)
            if (read <= 0) return
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readFixedLength(input: InputStream, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length)
        relayFixedLength(input, out, length)
        return out.toByteArray()
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: Exception) {
                return
            }
            if (read <= 0) return
            try {
                output.write(buffer, 0, read)
                output.flush()
            } catch (_: Exception) {
                return
            }
        }
    }

    private data class ProxyTarget(
        val host: String,
        val port: Int,
    )
}
