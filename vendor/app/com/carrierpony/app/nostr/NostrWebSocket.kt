package com.carrierpony.app.nostr

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Minimal RFC6455 websocket client over SSLSocket (wss) or plain Socket (ws), with no
 * HTTP dependency, mirroring how RelayClient uses plain HttpURLConnection. Text frames
 * only for our use; client-masks every frame as the spec requires, answers server pings
 * with a pong, and honours a server close. Blocking IO, meant to run on Dispatchers.IO.
 */
@OptIn(ExperimentalEncodingApi::class)
class NostrWebSocket(private val url: String) {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var inp: BufferedInputStream? = null
    private val writeLock = Any()

    fun connect(connectTimeoutMs: Int = 15000) {
        val uri = URI(url)
        val secure = uri.scheme == "wss"
        require(secure || uri.scheme == "ws") { "not a websocket url: $url" }
        val host = uri.host ?: error("no host in $url")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
        val path = ((uri.rawPath ?: "").ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")

        val s: Socket = if (secure) {
            (SSLSocketFactory.getDefault().createSocket() as SSLSocket).also { ssl ->
                // Default SSLSocket does NOT verify the hostname; turn it on explicitly.
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
                ssl.startHandshake()
            }
        } else {
            Socket().also { it.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs) }
        }
        val o = s.getOutputStream()
        val i = BufferedInputStream(s.getInputStream())

        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.Default.encode(keyBytes)
        val req = "GET $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        o.write(req.toByteArray(Charsets.ISO_8859_1)); o.flush()

        val header = readHeader(i)
        val statusOk = header.startsWith("HTTP/1.1 101") || header.startsWith("HTTP/1.0 101")
        val accept = Regex("(?i)sec-websocket-accept:\\s*(\\S+)").find(header)?.groupValues?.get(1)
        require(statusOk && accept == acceptFor(key)) { "websocket handshake failed" }

        socket = s; out = o; inp = i
    }

    private fun readHeader(i: BufferedInputStream): String {
        val sb = StringBuilder(); var state = 0
        while (true) {
            val b = i.read(); if (b == -1) break
            sb.append(b.toChar())
            state = when {
                (state == 0 || state == 2) && b == '\r'.code -> state + 1
                (state == 1 || state == 3) && b == '\n'.code -> state + 1
                else -> 0
            }
            if (state == 4) break
        }
        return sb.toString()
    }

    fun sendText(text: String) {
        val o = out ?: return
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val frame = encodeFrame(0x1, text.toByteArray(Charsets.UTF_8), mask)
        synchronized(writeLock) { o.write(frame); o.flush() }
    }

    /** Read one text message, answering pings along the way. Null on close or socket end. */
    fun readText(): String? {
        val i = inp ?: return null
        try {
            while (true) {
                val b0 = req(i); val opcode = b0 and 0x0F
                val b1 = req(i)
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((req(i) shl 8) or req(i)).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or req(i).toLong() } }
                val mk = if (b1 and 0x80 != 0) ByteArray(4) { req(i).toByte() } else null
                val payload = ByteArray(len.toInt())
                var off = 0
                while (off < payload.size) { val r = i.read(payload, off, payload.size - off); if (r == -1) return null; off += r }
                if (mk != null) for (idx in payload.indices) payload[idx] = (payload[idx].toInt() xor mk[idx % 4].toInt()).toByte()
                when (opcode) {
                    0x1 -> return String(payload, Charsets.UTF_8)
                    0x8 -> { close(); return null }
                    0x9 -> sendControl(0xA, payload)
                    else -> {} // pong / continuation / binary: ignore
                }
            }
        } catch (_: EOFException) { return null } catch (_: Exception) { return null }
    }

    private fun sendControl(opcode: Int, payload: ByteArray) {
        val o = out ?: return
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val frame = encodeFrame(opcode, payload, mask)
        synchronized(writeLock) { o.write(frame); o.flush() }
    }

    fun close() {
        try { sendControl(0x8, ByteArray(0)) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null; inp = null
    }

    private fun req(i: BufferedInputStream): Int { val b = i.read(); if (b == -1) throw EOFException(); return b }

    companion object {
        /** RFC6455 accept token: base64(sha1(key + magic GUID)). */
        fun acceptFor(key: String): String = Base64.Default.encode(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))

        /** One client-masked frame: FIN set, given opcode, mask bit set, 4-byte mask key. */
        fun encodeFrame(opcode: Int, payload: ByteArray, maskKey: ByteArray): ByteArray {
            require(maskKey.size == 4)
            val head = ArrayList<Byte>(payload.size + 8)
            head.add((0x80 or opcode).toByte())
            when {
                payload.size < 126 -> head.add((0x80 or payload.size).toByte())
                payload.size < 65536 -> {
                    head.add((0x80 or 126).toByte())
                    head.add((payload.size ushr 8 and 0xFF).toByte())
                    head.add((payload.size and 0xFF).toByte())
                }
                else -> {
                    head.add((0x80 or 127).toByte())
                    for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0))
                        head.add((payload.size.toLong() ushr shift).toByte())
                }
            }
            for (b in maskKey) head.add(b)
            for (idx in payload.indices) head.add((payload[idx].toInt() xor maskKey[idx % 4].toInt()).toByte())
            return head.toByteArray()
        }
    }
}
