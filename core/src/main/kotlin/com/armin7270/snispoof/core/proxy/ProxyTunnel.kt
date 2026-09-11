package com.armin7270.snispoof.core.proxy

import com.armin7270.snispoof.core.desync.DesyncMethod
import com.armin7270.snispoof.core.desync.DesyncParams
import com.armin7270.snispoof.core.desync.TcpFragmenter
import com.armin7270.snispoof.core.packet.Ip4
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Opens the tunnel to the proxy server described by [ProxyConfig] and speaks
 * VLESS / Trojan / VMess to reach (targetIp, targetPort). The tunnel's own TLS
 * ClientHello is fragmented through [TcpFragmenter] — the patterniha desync
 * applied to the one connection that matters.
 */
object ProxyTunnel {

    class Tunnel(val output: OutputStream, val input: InputStream) : java.io.Closeable {
        private val closeables = ArrayList<java.io.Closeable>()
        internal fun own(c: java.io.Closeable) { closeables.add(c) }
        override fun close() { closeables.forEach { runCatching { it.close() } } }
    }

    fun open(
        config: ProxyConfig,
        targetIp: Int,
        targetPort: Int,
        protector: com.armin7270.snispoof.core.engine.SocketProtector?,
        desync: DesyncParams?,
        onFragment: (Int) -> Unit,
    ): Tunnel {
        val socket = Socket()
        protector?.protectSocket(socket)
        socket.tcpNoDelay = true
        val addr = config.address
        val ip = runCatching { Ip4.parse(addr) }.getOrNull()
        val dst = if (ip != null) {
            InetSocketAddress(java.net.InetAddress.getByAddress(Ip4.bytes(ip)), config.port)
        } else {
            InetSocketAddress(java.net.InetAddress.getByName(addr), config.port)
        }
        socket.connect(dst, 10000)

        try {
            var streamOut: OutputStream = socket.getOutputStream()
            var streamIn: InputStream = socket.getInputStream()

            if (config.tls) {
                val tls = Tls13Client.handshake(
                    input = socket.getInputStream(),
                    output = socket.getOutputStream(),
                    sni = config.tlsSni,
                    alpn = config.alpn.ifBlank { "http/1.1" },
                    fragmenter = { ch ->
                        val plan = TcpFragmenter.planForPayload(
                            desync?.method ?: DesyncMethod.SPLIT_SNI,
                            desync ?: DesyncParams(method = DesyncMethod.SPLIT_SNI),
                            ch,
                        )
                        onFragment(plan.size)
                        plan.map { ch.copyOfRange(it.offset, it.offset + it.length) }
                    },
                    fragmentDelayMs = desync?.delayMs ?: 0,
                )
                streamOut = tls.output
                streamIn = tls.input
            }

            val request = buildProxyRequest(config, targetIp, targetPort)
            val result: Tunnel = if (config.net == ProxyNetwork.WS) {
                val ws = WsStream(streamIn, streamOut, config.wsHost, config.path.ifBlank { "/" })
                ws.openAndSend(request)
                Tunnel(WsWriteAdapter(ws), WsReader(ws))
            } else {
                streamOut.write(request)
                streamOut.flush()
                Tunnel(streamOut, streamIn)
            }
            result.own(socket)
            return result
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** VLESS/Trojan request preamble for (ip, port). */
    internal fun buildProxyRequest(config: ProxyConfig, targetIp: Int, targetPort: Int): ByteArray {
        val addr = byteArrayOf(1) + Ip4.bytes(targetIp)
        val port = byteArrayOf(((targetPort ushr 8) and 0xff).toByte(), (targetPort and 0xff).toByte())
        return when (config.proto) {
            ProxyProtocol.VLESS -> {
                val uuidBytes = uuidToBytes(config.uuid)
                    ?: throw java.io.IOException("vless: invalid uuid '${config.uuid}'")
                // ver(0) + uuid(16) + addons(0) + cmd TCP(1) + port(2) + atyp(1) + addr
                byteArrayOf(0) + uuidBytes + byteArrayOf(0) + byteArrayOf(1) + port + addr
            }
            ProxyProtocol.TROJAN -> {
                if (config.password.isBlank()) throw java.io.IOException("trojan: empty password")
                val hash = MessageDigest.getInstance("SHA-224")
                    .digest(config.password.toByteArray(Charsets.US_ASCII))
                    .joinToString("") { "%02x".format(it) }
                    .toByteArray(Charsets.US_ASCII)
                val crlf = byteArrayOf(0x0D, 0x0A)
                hash + crlf + byteArrayOf(0x01) + addr + port + crlf // CONNECT
            }
            // parsed and stored by the importer, but not implemented here
            ProxyProtocol.VMESS -> throw java.io.IOException("vmess: not supported in this build")
        }
    }

    internal fun uuidToBytes(uuid: String): ByteArray? {
        val hex = uuid.replace("-", "")
        if (hex.length != 32) return null
        return try {
            ByteArray(16) { ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte() }
        } catch (e: Exception) { null }
    }

    /** Adapts a WsStream to a plain OutputStream for the pump loop. */
    private class WsWriteAdapter(private val ws: WsStream) : OutputStream() {
        override fun write(b: Int) = throw UnsupportedOperationException()
        override fun write(b: ByteArray, off: Int, len: Int) {
            val chunk = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            ws.send(chunk)
        }
        override fun flush() = Unit
    }

    /** InputStream view over ws.receive() used by the pump loop. */
    private class WsReader(private val ws: WsStream) : InputStream(), java.io.Closeable {
        private var buffer = ByteArray(0)
        private var pos = 0
        private var eof = false

        override fun read(): Int {
            if (!fill()) return -1
            return buffer[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!fill()) return -1
            val n = kotlin.math.min(len, buffer.size - pos)
            System.arraycopy(buffer, pos, b, off, n)
            pos += n
            return n
        }

        private fun fill(): Boolean {
            if (pos < buffer.size) return true
            if (eof) return false
            val msg = ws.receive()
            if (msg == null) { eof = true; return false }
            buffer = msg
            pos = 0
            return true
        }

        override fun close() { eof = true }
    }
}
