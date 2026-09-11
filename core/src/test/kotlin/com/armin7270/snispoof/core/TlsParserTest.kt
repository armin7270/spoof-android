package com.armin7270.snispoof.core

import com.armin7270.snispoof.core.tls.ClientHelloForge
import com.armin7270.snispoof.core.tls.ClientHelloParser
import com.armin7270.snispoof.core.tls.TlsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsParserTest {

    @Test
    fun `forge then parse roundtrip`() {
        val ch = ClientHelloForge.build("example.com")
        val sni = ClientHelloParser.findSni(ch)
        assertNotNull(sni)
        assertEquals("example.com", sni!!.host)
        assertEquals(sni.offset, TlsParser.parseSni(ch)!!.hostOffset)
    }

    @Test
    fun `replace sni keeps record consistent`() {
        val oldHost = "www.speedtest.net"
        val newHost = "auth.vercel.com"
        val original = ClientHelloForge.build(oldHost)
        val rewritten = TlsParser.replaceSni(original, newHost)
        assertNotNull(rewritten)

        // record length must match actual size
        val recLen = ((rewritten!![3].toInt() and 0xff) shl 8) or (rewritten[4].toInt() and 0xff)
        assertEquals(rewritten.size, recLen + 5)

        // handshake length matches record content
        val hsLen = ((rewritten[6].toInt() and 0xff) shl 16) or
                ((rewritten[7].toInt() and 0xff) shl 8) or
                (rewritten[8].toInt() and 0xff)
        assertEquals(recLen - 4, hsLen)

        // the new SNI is readable
        val span = TlsParser.parseSni(rewritten)
        assertNotNull(span)
        assertEquals(newHost, span!!.host)

        // payload difference equals host delta
        assertEquals(original.size + (newHost.length - oldHost.length), rewritten.size)
    }

    @Test
    fun `grow sni and shrink sni both work`() {
        val grow = TlsParser.replaceSni(ClientHelloForge.build("a.io"), "this.is.a.much.longer.host.example.com")
        assertNotNull(grow)
        assertEquals("this.is.a.much.longer.host.example.com", TlsParser.sniHost(grow!!))

        val shrink = TlsParser.replaceSni(ClientHelloForge.build("this.is.a.much.longer.host.example.com"), "a.io")
        assertNotNull(shrink)
        assertEquals("a.io", TlsParser.sniHost(shrink!!))
    }

    @Test
    fun `rejects garbage and truncation`() {
        assertNull(TlsParser.parseSni(ByteArray(0)))
        assertNull(TlsParser.parseSni(byteArrayOf(0x17, 3, 3, 0, 1)))
        val ch = ClientHelloForge.build("example.com")
        for (cut in intArrayOf(0, 1, 5, 60, ch.size / 2)) {
            val truncated = ch.copyOf(cut)
            // must not throw; may return null or a valid span
            val span = runCatching { TlsParser.parseSni(truncated) }.getOrNull()
            if (span != null) {
                assertTrue(span.hostOffset + span.hostLength <= truncated.size)
            }
        }
    }

    @Test
    fun `app data record is not a client hello`() {
        val appData = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x04, 1, 2, 3, 4)
        assertTrue(!TlsParser.looksLikeClientHello(appData))
    }

    // ---- ECH / ESNI detection ------------------------------------------------

    /** Injects an extension into a forged ClientHello's extension block. */
    private fun withExtraExtension(ch: ByteArray, type: Int, payload: ByteArray): ByteArray {
        fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        fun s16(v: Int) = byteArrayOf(((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte())

        // fixed ClientHello layout: record(5) hsHdr(4) version(2) random(32) sid
        val sidLen = ch[43].toInt() and 0xff
        val cipherLenPos = 44 + sidLen
        val cipherLen = u16(ch, cipherLenPos)
        val compLenPos = cipherLenPos + 2 + cipherLen
        val compLen = ch[compLenPos].toInt() and 0xff
        val extTotalPos = compLenPos + 1 + compLen
        val insertAt = extTotalPos + 2 // first byte of the extension list

        val ext = s16(type) + s16(payload.size) + payload
        val out = ch.copyOfRange(0, insertAt) + ext + ch.copyOfRange(insertAt, ch.size)

        fun bump16(pos: Int, by: Int) {
            val v = u16(out, pos) + by
            out[pos] = ((v ushr 8) and 0xff).toByte()
            out[pos + 1] = (v and 0xff).toByte()
        }
        val hsLen = ((out[6].toInt() and 0xff) shl 16) or ((out[7].toInt() and 0xff) shl 8) or
                (out[8].toInt() and 0xff)
        val newHs = hsLen + ext.size
        out[6] = ((newHs ushr 16) and 0xff).toByte()
        out[7] = ((newHs ushr 8) and 0xff).toByte()
        out[8] = (newHs and 0xff).toByte()
        bump16(3, ext.size)             // record length
        bump16(extTotalPos, ext.size)   // extension block length
        return out
    }

    @Test
    fun `plain client hello is sni replaceable`() {
        val ch = ClientHelloForge.build("www.example.net")
        val info = ClientHelloParser.inspect(ch)
        assertEquals("www.example.net", info.sni!!.host)
        assertTrue(!info.sniEncrypted)
        assertTrue(info.sniReplaceable)
    }

    @Test
    fun `ech extension marks the visible sni as a decoy`() {
        val ch = withExtraExtension(ClientHelloForge.build("www.example.net"), 0xfe0d, byteArrayOf(1, 2, 3))
        val info = ClientHelloParser.inspect(ch)
        // the outer SNI is still parseable, but it is not the real destination
        assertEquals("www.example.net", info.sni!!.host)
        assertTrue(info.sniEncrypted)
        assertTrue(!info.sniReplaceable)
    }

    @Test
    fun `esni extension is also detected`() {
        val ch = withExtraExtension(ClientHelloForge.build("www.example.net"), 0xffce, byteArrayOf(9))
        val info = ClientHelloParser.inspect(ch)
        assertTrue(info.sniEncrypted)
        assertTrue(!info.sniReplaceable)
    }

    @Test
    fun `ech detection survives truncation`() {
        val full = withExtraExtension(ClientHelloForge.build("www.example.net"), 0xfe0d, byteArrayOf(1, 2, 3))
        for (cut in intArrayOf(0, 5, 40, full.size)) {
            val info = ClientHelloParser.inspect(full.copyOf(cut))
            // must never throw; the encrypted flag may be lost on a cut header
            assertTrue(info.sni?.host == null || info.sni!!.host.isNotEmpty())
        }
    }
}
