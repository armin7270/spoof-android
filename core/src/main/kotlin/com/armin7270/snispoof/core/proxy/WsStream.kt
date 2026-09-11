package com.armin7270.snispoof.core.proxy

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import kotlin.math.min

/**
 * Minimal RFC 6455 WebSocket client side over an established byte stream.
 * Binary frames only (that is all VLESS/Trojan-over-WS need); server frames
 * are unmasked, client frames are masked. Ping/Pong handled internally.
 */
class WsStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val host: String,
    private val path: String,
) : Closeable {

    private var open = false
    private var closed = false

    private fun upgrade() {
        val keyB = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = com.armin7270.snispoof.core.util.Base64.encode(keyB)
        val req = buildString {
            append("GET ").append(if (path.startsWith("/")) path else "/$path").append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Protocol: binary\r\n")
            append("\r\n")
        }
        output.write(req.toByteArray(Charsets.US_ASCII))
        output.flush()
        // read the response headers
        val header = StringBuilder()
        var last4 = IntArray(4)
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.IOException("ws: eof during upgrade")
            last4[0] = last4[1]; last4[1] = last4[2]; last4[2] = last4[3]; last4[3] = b
            header.append(b.toChar())
            if (last4.contentEquals(intArrayOf(13, 10, 13, 10))) break
            if (header.length > 16384) throw java.io.IOException("ws: oversized upgrade response")
        }
        val text = header.toString()
        val statusLine = text.lineSequence().firstOrNull() ?: ""
        if (!statusLine.contains(" 101")) throw java.io.IOException("ws: upgrade failed: $statusLine")
        open = true
    }

    fun openAndSend(first: ByteArray) {
        upgrade()
        writeFrame(first, OP_BINARY)
    }

    fun send(data: ByteArray) {
        writeFrame(data, OP_BINARY)
    }

    private fun writeFrame(payload: ByteArray, opcode: Int) {
        if (closed) throw java.io.IOException("ws: closed")
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode) // FIN + opcode
        val len = payload.size
        when {
            len < 126 -> header.write(len)
            len < 65536 -> { header.write(126); header.write((len ushr 8) and 0xff); header.write(len and 0xff) }
            else -> {
                header.write(127)
                for (shift in 56 downTo 0 step 8) header.write(((len.toLong() ushr shift) and 0xff).toInt())
            }
        }
        val hdrBytes = header.toByteArray() + mask
        val masked = ByteArray(len)
        for (i in 0 until len) masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        output.write(hdrBytes)
        output.write(masked)
        output.flush()
    }

    /** Reads the next data message (assembles continuation frames). */
    fun receive(): ByteArray? {
        var assembled = ByteArray(0)
        var gotFin = false
        while (!gotFin) {
            val (fin, opcode, payload) = readFrame() ?: return null
            when (opcode) {
                OP_BINARY, OP_CONT -> {
                    assembled = assembled + payload
                    if (fin) gotFin = true
                }
                OP_PING -> writeFrame(payload, OP_PONG)
                OP_PONG -> Unit
                OP_CLOSE -> { closed = true; return null }
                else -> Unit
            }
        }
        return assembled
    }

    private fun readFrame(): Triple<Boolean, Int, ByteArray>? {
        val h = readFull(2) ?: return null
        val b0 = h[0].toInt() and 0xff
        val b1 = h[1].toInt() and 0xff
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0f
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) {
            val e = readFull(2) ?: return null
            len = ((e[0].toLong() and 0xff) shl 8) or (e[1].toLong() and 0xff)
        } else if (len == 127L) {
            val e = readFull(8) ?: return null
            len = 0
            for (byte in e) len = (len shl 8) or (byte.toLong() and 0xff)
        }
        if (len > 32L * 1024 * 1024) throw java.io.IOException("ws: frame too large $len")
        val mask = if (masked) readFull(4) ?: return null else ByteArray(0)
        var payload = if (len > 0) readFull(len.toInt()) ?: return null else ByteArray(0)
        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return Triple(fin, opcode, payload)
    }

    private fun readFull(n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    override fun close() { closed = true }

    companion object {
        private const val OP_CONT = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
    }
}
