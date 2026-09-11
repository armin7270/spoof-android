package com.armin7270.snispoof.core.engine

import com.armin7270.snispoof.core.packet.Ip4
import com.armin7270.snispoof.core.packet.IpPacket
import com.armin7270.snispoof.core.packet.PacketBuilder
import com.armin7270.snispoof.core.tcpip.PacketSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Lets the pure-Kotlin core ask the app to exempt sockets from the VPN. */
interface SocketProtector {
    fun protectSocket(socket: Socket)
    fun protectSocket(socket: DatagramSocket)
}

enum class DohProvider(val id: String, val host: String, val ips: List<String>) {
    CLOUDFLARE("cloudflare", "cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1")),
    GOOGLE("google", "dns.google", listOf("8.8.8.8", "8.8.4.4")),
    QUAD9("quad9", "dns.quad9.net", listOf("9.9.9.9", "149.112.112.112")),
    ADGUARD("adguard", "dns.adguard-dns.com", listOf("94.140.14.14", "94.140.15.15")),
    OPENDNS("opendns", "doh.opendns.com", listOf("208.67.222.222", "208.67.220.220"));

    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: CLOUDFLARE }
}

/**
 * DNS-over-HTTPS resolver with a plain-UDP fallback. Bootstrap connections go
 * to the provider's well-known IPs, so the system DNS (which is us) is never
 * needed — no bootstrap loop.
 */
class DohResolver(
    @Volatile var provider: DohProvider,
    private val protector: SocketProtector,
    private val log: (String) -> Unit,
    /** try plain UDP:53 first (skip TLS entirely) — useful when DoH is blocked */
    private val preferUdp: Boolean = false,
) {
    private data class Entry(val response: ByteArray, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    /** Raw DNS wire-format query → response (cached). */
    suspend fun query(wire: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        // content-based cache key: first 2 bytes are the query ID which varies
        // per query, so normalise it to zero before hashing
        val normalized = wire.copyOf()
        if (normalized.size >= 4) { normalized[0] = 0; normalized[1] = 0 }
        val key = com.armin7270.snispoof.core.util.Hex.encode(normalized)
        val cached = cache[key]
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) return@withContext cached.response

        val response = runCatching {
            if (preferUdp) udpFallback(wire) else httpQuery(wire)
        }.getOrNull()
            ?: runCatching { httpQuery(wire) }.getOrNull()
            ?: runCatching { udpFallback(wire) }.getOrNull()
        if (response != null) {
            val ttl = minAnswerTtl(response).coerceIn(30, 1800)
            cache[key] = Entry(response, System.currentTimeMillis() + ttl * 1000L)
        }
        response
    }

    /** Resolve a hostname to IPv4 addresses (A records only). */
    suspend fun resolveHost(host: String): List<Int> {
        val wire = buildQuery(host, 1)
        val resp = query(wire) ?: return emptyList()
        return parseARecords(resp)
    }

    private fun httpQuery(wire: ByteArray): ByteArray {
        val plain = Socket()
        protector.protectSocket(plain)
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(provider.ips[0], 443), 5000)
            plain.soTimeout = 6000
            val ssl = SSLContext.getDefault().socketFactory
                .createSocket(plain, provider.host, 443, false) as SSLSocket
            val out = ssl.outputStream
            val req = buildString {
                append("POST /dns-query HTTP/1.1\r\n")
                append("Host: ").append(provider.host).append("\r\n")
                append("Content-Type: application/dns-message\r\n")
                append("Accept: application/dns-message\r\n")
                append("Content-Length: ").append(wire.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.write(wire)
            out.flush()

            val input = ssl.inputStream
            // status line + headers
            var contentLength = -1
            var chunked = false
            val header = ByteArrayOutputStream()
            var state = 0
            var last4 = IntArray(4)
            while (true) {
                val b = input.read()
                if (b < 0) throw java.io.IOException("eof in headers")
                header.write(b)
                last4[0] = last4[1]; last4[1] = last4[2]; last4[2] = last4[3]; last4[3] = b
                if (last4[0] == 13 && last4[1] == 10 && last4[2] == 13 && last4[3] == 10) break
                if (header.size() > 16384) throw java.io.IOException("header too big")
                state++
            }
            val headerText = header.toString("ISO-8859-1")
            val statusLine = headerText.lineSequence().firstOrNull() ?: ""
            if (!statusLine.contains(" 200")) throw java.io.IOException("http $statusLine")
            for (line in headerText.lineSequence().drop(1)) {
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true
            }
            return if (chunked) readChunked(input) else {
                val body = if (contentLength >= 0) {
                    ByteArray(contentLength).also { buf ->
                        var off = 0
                        while (off < contentLength) {
                            val n = input.read(buf, off, contentLength - off)
                            if (n < 0) throw java.io.IOException("eof in body")
                            off += n
                        }
                    }
                } else {
                    input.readBytes()
                }
                body
            }
        } finally {
            runCatching { plain.close() }
        }
    }

    private fun readChunked(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            var sizeLine = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return out.toByteArray()
                if (b == '\n'.code) break
                if (b != '\r'.code) sizeLine.append(b.toChar())
            }
            val size = sizeLine.toString().trim().split(";")[0].toInt(16)
            if (size == 0) break
            val chunk = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = input.read(chunk, off, size - off)
                if (n < 0) throw java.io.IOException("eof in chunk")
                off += n
            }
            out.write(chunk)
            input.read(); input.read() // CRLF
        }
        return out.toByteArray()
    }

    private fun udpFallback(wire: ByteArray): ByteArray? {
        val sock = DatagramSocket()
        protector.protectSocket(sock)
        try {
            sock.soTimeout = 4000
            val addr = InetAddress.getByName(provider.ips[0])
            sock.send(DatagramPacket(wire, wire.size, addr, 53))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            return buf.copyOf(resp.length)
        } finally {
            runCatching { sock.close() }
        }
    }

    companion object {
        fun buildQuery(host: String, type: Int, id: Int = 0x1234): ByteArray {
            val out = ByteArrayOutputStream()
            fun u16(v: Int) { out.write((v ushr 8) and 0xff); out.write(v and 0xff) }
            // QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=1 (the EDNS(0) OPT below)
            u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(1)
            for (label in host.trim('.').split('.')) {
                out.write(label.length)
                out.write(label.toByteArray(Charsets.US_ASCII))
            }
            out.write(0)
            u16(type); u16(1)
            // EDNS(0) OPT RR: root name(1) + type(2)=41 + class(2)=UDP payload
            // size + TTL(4) extended-rcode/flags + RDLENGTH(2). The TTL field is
            // 4 bytes wide on the wire, not 2.
            out.write(0); u16(41); u16(1232)
            u16(0); u16(0)   // TTL = 0 (4 bytes)
            u16(0)           // RDLENGTH = 0
            return out.toByteArray()
        }

        fun minAnswerTtl(resp: ByteArray): Int {
            if (resp.size < 12) return 120
            val anCount = ((resp[6].toInt() and 0xff) shl 8) or (resp[7].toInt() and 0xff)
            var p = 12
            val qd = ((resp[4].toInt() and 0xff) shl 8) or (resp[5].toInt() and 0xff)
            repeat(qd) { p = skipName(resp, p); p += 4 }
            var minTtl = Int.MAX_VALUE
            repeat(anCount) {
                p = skipName(resp, p)
                if (p + 10 > resp.size) return@repeat
                p += 4 // type + class
                val ttl = ((resp[p].toInt() and 0xff) shl 24) or ((resp[p + 1].toInt() and 0xff) shl 16) or
                        ((resp[p + 2].toInt() and 0xff) shl 8) or (resp[p + 3].toInt() and 0xff)
                minTtl = minOf(minTtl, ttl)
                p += 4
                val rdlen = ((resp[p].toInt() and 0xff) shl 8) or (resp[p + 1].toInt() and 0xff)
                p += 2 + rdlen
            }
            return if (minTtl == Int.MAX_VALUE) 120 else minTtl
        }

        /** Skip a (possibly compressed) DNS name; returns offset after it. */
        fun skipName(b: ByteArray, start: Int): Int {
            var p = start
            while (p < b.size) {
                val len = b[p].toInt() and 0xff
                if (len == 0) return p + 1
                if (len and 0xc0 == 0xc0) return p + 2
                p += 1 + len
            }
            return p
        }

        /** Extract IPv4 addresses from A records; also returns (ip → name) pairs. */
        fun parseARecords(resp: ByteArray): List<Int> {
            val ips = ArrayList<Int>()
            if (resp.size < 12) return ips
            val anCount = ((resp[6].toInt() and 0xff) shl 8) or (resp[7].toInt() and 0xff)
            var p = 12
            val qd = ((resp[4].toInt() and 0xff) shl 8) or (resp[5].toInt() and 0xff)
            repeat(qd) { p = skipName(resp, p); p += 4 }
            var lastName: String? = null
            repeat(anCount) {
                val nameStart = p
                p = skipName(resp, p)
                if (p + 10 > resp.size) return@repeat
                val type = ((resp[p].toInt() and 0xff) shl 8) or (resp[p + 1].toInt() and 0xff)
                p += 2 + 2 // class
                p += 4    // ttl
                val rdlen = ((resp[p].toInt() and 0xff) shl 8) or (resp[p + 1].toInt() and 0xff)
                p += 2
                if (type == 1 && rdlen == 4 && p + 4 <= resp.size) {
                    ips.add(Ip4.toInt(resp, p))
                    if (lastName == null) lastName = readName(resp, nameStart)
                }
                p += rdlen
            }
            return ips
        }

        fun readName(b: ByteArray, start: Int, depth: Int = 0): String? {
            if (depth > 8) return null
            val sb = StringBuilder()
            var p = start
            while (p < b.size) {
                val len = b[p].toInt() and 0xff
                if (len == 0) break
                if (len and 0xc0 == 0xc0) {
                    val ptr = ((len and 0x3f) shl 8) or (b[p + 1].toInt() and 0xff)
                    val inner = readName(b, ptr, depth + 1) ?: return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(inner)
                    return sb.toString()
                }
                if (p + 1 + len > b.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(b, p + 1, len, Charsets.US_ASCII))
                p += 1 + len
            }
            return sb.toString().ifEmpty { null }
        }

        private fun List<Byte>.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/**
 * Answers UDP:53 queries arriving on the TUN. AAAA is answered with an empty
 * NOERROR so apps use IPv4 inside the tunnel; everything else is passed
 * through to DoH and A records are learned for hostname display / matching.
 * If the primary provider fails, the query is retried across the fallback
 * chain (DoH then plain UDP for every provider).
 */
class DnsServer(
    private val scope: CoroutineScope,
    private val resolver: DohResolver,
    private val fallbackResolvers: List<DohResolver>,
    private val tunIp: Int,
    private val sink: PacketSink,
    private val log: (String) -> Unit,
) {
    /**
     * Learned ip -> hostnames (bounded).
     *
     * A set per address, not a single name: one CDN edge IP answers for every
     * zone it fronts, and the name the phone happened to query last is not
     * necessarily the one an app is connecting to — remembering only that name
     * made hostname-scoped profiles misroute unrelated apps.
     */
    private val hostnameMap = ConcurrentHashMap<Int, MutableSet<String>>()

    /** Every hostname seen resolving to [ip]; may legitimately be several. */
    fun hostnamesFor(ip: Int): Set<String> = hostnameMap[ip] ?: emptySet()

    private data class Entry(val response: ByteArray, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun handleQuery(p: IpPacket) {
        val wireOff = p.udpPayloadOffset
        val wireLen = p.udpPayloadLength
        if (wireLen < 17) return
        val wire = p.data.copyOfRange(wireOff, wireOff + wireLen)
        val srcIp = p.srcIp
        val srcPort = p.srcPort

        // Parse the question up front: a malformed packet must not reach the
        // resolver, and a failed lookup must not silently answer with nothing.
        val qNameEnd = DohResolver.skipName(wire, 12)
        if (qNameEnd + 4 > wire.size) return
        val name = DohResolver.readName(wire, 12) ?: ""
        val qtype = ((wire[qNameEnd].toInt() and 0xff) shl 8) or (wire[qNameEnd + 1].toInt() and 0xff)

        scope.launch {
            try {
                val key = "$name/$qtype"
                val cached = cache[key]
                val response: ByteArray = when {
                    qtype == 28 -> emptyNoError(wire, qNameEnd + 4) // AAAA -> force IPv4
                    cached != null && System.currentTimeMillis() < cached.expiresAt -> cached.response
                    else -> {
                        var resp = resolver.query(wire)
                        for (fb in fallbackResolvers) {
                            if (resp != null) break
                            resp = fb.query(wire)
                        }
                        if (resp != null) {
                            val ttl = DohResolver.minAnswerTtl(resp).coerceIn(30, 1800)
                            cache[key] = Entry(resp, System.currentTimeMillis() + ttl * 1000L)
                            learnARecords(resp, name)
                            resp
                        } else {
                            servFail(wire, qNameEnd + 4)
                        }
                    }
                }
                // the answer may come from the cache or DoH with a different
                // transaction ID — always reply with the client's own ID
                val out = response.copyOf()
                out[0] = wire[0]
                out[1] = wire[1]
                sink.send(PacketBuilder.udp(tunIp, srcIp, 53, srcPort, out))
            } catch (e: Exception) {
                log("dns: ${e.message}")
            }
        }
    }

    private fun learnARecords(resp: ByteArray, fallbackName: String) {
        val ips = DohResolver.parseARecords(resp)
        for (ip in ips) {
            if (hostnameMap.size > 4096) hostnameMap.clear()
            hostnameMap.getOrPut(ip) { ConcurrentHashMap.newKeySet() }.add(fallbackName)
        }
    }

    private fun copyQuestion(wire: ByteArray, qEnd: Int): ByteArray =
        wire.copyOfRange(0, qEnd) // header + question (qEnd = after qtype+qclass)

    private fun headerWith(wire: ByteArray, flagsHi: Int, flagsLo: Int, an: Int): ByteArray {
        val out = wire.copyOf()
        out[2] = flagsHi.toByte(); out[3] = flagsLo.toByte()
        out[6] = (an ushr 8).toByte(); out[7] = an.toByte()
        out[8] = 0; out[9] = 0 // nscount
        out[10] = 0; out[11] = 0 // arcount (drop EDNS OPT)
        return out
    }

    /** Empty NOERROR (0 answers) echoing the question — used to sink AAAA. */
    private fun emptyNoError(wire: ByteArray, qEnd: Int): ByteArray {
        val head = headerWith(copyQuestion(wire, qEnd), 0x81, 0x80, 0)
        return head
    }

    private fun servFail(wire: ByteArray, qEnd: Int): ByteArray {
        val head = headerWith(copyQuestion(wire, qEnd), 0x81, 0x82, 0)
        return head
    }
}
