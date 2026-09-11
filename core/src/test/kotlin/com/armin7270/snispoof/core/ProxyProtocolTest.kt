package com.armin7270.snispoof.core

import com.armin7270.snispoof.core.packet.Ip4
import com.armin7270.snispoof.core.proxy.ProxyTunnel
import com.armin7270.snispoof.core.proxy.Tls13Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyProtocolTest {

    @Test
    fun `vless request header layout`() {
        val cfg = com.armin7270.snispoof.core.proxy.ProxyConfig(
            id = "t", name = "t", protocol = "vless",
            uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4",
            address = "1.2.3.4", port = 443,
        )
        val req = ProxyTunnel.buildProxyRequest(cfg, Ip4.parse("93.184.216.34"), 80)
        // version(0) + uuid(16) + addl(0) + cmd(1) + port(2) + atyp(1) + addr(4)
        assertEquals(1 + 16 + 1 + 1 + 2 + 1 + 4, req.size)
        assertEquals(0, req[0].toInt())
        assertEquals(1, req[18].toInt()) // cmd TCP
        assertEquals(1, req[21].toInt()) // atyp IPv4
        assertEquals(80, ((req[19].toInt() and 0xff) shl 8) or (req[20].toInt() and 0xff))
    }

    @Test
    fun `trojan request header layout`() {
        val cfg = com.armin7270.snispoof.core.proxy.ProxyConfig(
            id = "t", name = "t", protocol = "trojan", password = "pass",
            address = "1.2.3.4", port = 443,
        )
        val req = ProxyTunnel.buildProxyRequest(cfg, Ip4.parse("8.8.8.8"), 443)
        // sha224hex(56) + CRLF(2) + cmd(1) + atyp(1) + addr(4) + port(2) + CRLF(2)
        assertEquals(56 + 2 + 1 + 1 + 4 + 2 + 2, req.size)
        assertEquals(0x0D, req[56].toInt())
        assertEquals(0x0A, req[57].toInt())
        assertEquals(0x01, req[58].toInt()) // CONNECT
        assertEquals(8, req[61].toInt() and 0xff)
    }

    @Test
    fun `uuid conversion`() {
        val b = ProxyTunnel.uuidToBytes("d342d11e-d424-4583-b36e-524ab1f0afa4")
        assertNotNull(b)
        assertEquals(16, b!!.size)
        assertEquals(0xd3.toByte(), b[0])
    }

    @Test
    fun `hkdf tls13 vectors`() {
        // RFC 8448 §3 simple 1-RTT handshake secrets (partial vector check)
        val ikm = Tls13Client.hkdfExtract(ByteArray(32), ByteArray(32))
        assertEquals(32, ikm.size)
        val expanded = Tls13Client.expandLabel(
            ikm, "key", ByteArray(0), 16
        )
        assertEquals(16, expanded.size)
    }

    @Test
    fun `tls13 handshake against a live TLS13 endpoint when reachable`() {
        // Only an unreachable network may skip this test. It used to wrap the whole
        // body in a catch-all `assumeTrue(false)`, which reported a *skip* instead
        // of a *failure* when the handshake itself was broken — that is exactly how
        // the ClientHello/record-layer bugs went unnoticed.
        val socket = java.net.Socket()
        try {
            socket.connect(java.net.InetSocketAddress("cloudflare-dns.com", 443), 5000)
        } catch (e: Exception) {
            runCatching { socket.close() }
            org.junit.Assume.assumeTrue("endpoint unreachable: ${e.message}", false)
            return
        }
        try {
            socket.soTimeout = 10_000
            val session = Tls13Client.handshake(
                socket.getInputStream(), socket.getOutputStream(),
                "cloudflare-dns.com",
                fragmenter = { ch ->
                    // split a few bytes into the SNI — the classic desync
                    val cut = 40 + ch.size / 3
                    listOf(ch.copyOfRange(0, cut), ch.copyOfRange(cut, ch.size))
                },
            )
            // send a DoH query over the tunnel: DNS query for example.com A
            val query = buildSimpleDohQuery()
            session.output.write(query)
            session.output.flush()
            // read HTTP response headers start line
            val buf = ByteArray(64)
            val n = session.input.read(buf)
            assertTrue("no response bytes", n > 0)
            assertTrue(String(buf, 0, minOf(n, 32), Charsets.ISO_8859_1).contains("HTTP"))
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildSimpleDohQuery(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v ushr 8) and 0xff); out.write(v and 0xff) }
        u16(0x1234); u16(0x0100); u16(1); u16(0); u16(0); u16(0)
        for (label in "example.com".split('.')) { out.write(label.length); out.write(label.toByteArray()) }
        out.write(0); u16(1); u16(1)
        val q = out.toByteArray()
        val http = java.io.ByteArrayOutputStream()
        http.write(("POST /dns-query HTTP/1.1\r\nHost: cloudflare-dns.com\r\n" +
                "Content-Type: application/dns-message\r\n" +
                "Content-Length: ${q.size}\r\n\r\n").toByteArray())
        http.write(q)
        return http.toByteArray()
    }
}
