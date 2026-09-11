package com.armin7270.snispoof.core

import com.armin7270.snispoof.core.engine.DohResolver
import com.armin7270.snispoof.core.packet.Checksum
import com.armin7270.snispoof.core.packet.Ip4
import com.armin7270.snispoof.core.packet.PacketBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks that every packet the engine injects into the TUN carries a valid
 * checksum. A wrong ICMP checksum is not a cosmetic problem: the kernel
 * silently discards the "port unreachable" message, so QUIC blocking stops
 * working and apps never fall back to TCP where the desync runs.
 */
class PacketIntegrityTest {

    private fun ipv4ChecksumOf(pkt: ByteArray): Int =
        Checksum.compute(pkt, 0, 20)

    /** A valid internet checksum evaluates to zero when included in the sum. */
    private fun verifyRange(pkt: ByteArray, off: Int, len: Int) =
        Checksum.compute(pkt, off, len)

    @Test
    fun `icmp port unreachable carries a valid checksum over the quoted packet`() {
        val original = PacketBuilder.udp(
            srcIp = Ip4.parse("198.18.0.2"), dstIp = Ip4.parse("142.250.1.1"),
            srcPort = 51000, dstPort = 443,
            payload = ByteArray(64) { (it + 1).toByte() },
        )
        val icmp = PacketBuilder.icmpUnreachable(
            Ip4.parse("198.18.0.1"), Ip4.parse("198.18.0.2"), original, 0, original.size,
        )
        assertEquals("IPv4 header checksum", 0, ipv4ChecksumOf(icmp))
        assertEquals("ICMP checksum (must cover the quoted datagram)", 0, verifyRange(icmp, 20, icmp.size - 20))
    }

    @Test
    fun `icmpv6 unreachable carries a valid checksum`() {
        val original = PacketBuilder.udp(
            srcIp = Ip4.parse("198.18.0.2"), dstIp = Ip4.parse("142.250.1.1"),
            srcPort = 51000, dstPort = 443, payload = ByteArray(32) { it.toByte() },
        )
        val src = ByteArray(16) { if (it == 15) 2 else 0 }
        val dst = ByteArray(16) { if (it == 0) 0x20 else 0 }
        val icmp6 = PacketBuilder.icmpv6Unreachable(src, dst, original, 0, original.size)

        // the checksum field itself must be non-zero and verify over the pseudo header
        val pseudo = ByteArray(40 + 4 + 4)
        System.arraycopy(icmp6, 8, pseudo, 0, 16)
        System.arraycopy(icmp6, 24, pseudo, 16, 16)
        pseudo[33] = 58
        val upper = icmp6.size - 40
        pseudo[36] = ((upper ushr 8) and 0xff).toByte()
        pseudo[37] = (upper and 0xff).toByte()
        assertEquals("ICMPv6 checksum", 0, Checksum.compute(icmp6, 40, upper, pseudo))
    }

    @Test
    fun `udp packet checksum verifies`() {
        val pkt = PacketBuilder.udp(
            srcIp = Ip4.parse("198.18.0.1"), dstIp = Ip4.parse("8.8.8.8"),
            srcPort = 53, dstPort = 40000, payload = "dns-payload".toByteArray(),
        )
        assertEquals("IPv4 header checksum", 0, ipv4ChecksumOf(pkt))
        val pseudo = ByteArray(12)
        Ip4.fromInt(Ip4.parse("198.18.0.1"), pseudo, 0)
        Ip4.fromInt(Ip4.parse("8.8.8.8"), pseudo, 4)
        pseudo[9] = 17
        val dgram = pkt.size - 20
        pseudo[10] = ((dgram ushr 8) and 0xff).toByte()
        pseudo[11] = (dgram and 0xff).toByte()
        assertEquals("UDP checksum", 0, Checksum.compute(pkt, 20, dgram, pseudo))
    }

    @Test
    fun `tcp packet checksum verifies`() {
        val pkt = PacketBuilder.tcp(
            srcIp = Ip4.parse("198.18.0.1"), dstIp = Ip4.parse("1.2.3.4"),
            srcPort = 443, dstPort = 50000, seq = 7, ack = 9, flags = 0x18,
            window = 65535, payload = "hello".toByteArray(), psh = true,
        )
        assertEquals("IPv4 header checksum", 0, ipv4ChecksumOf(pkt))
        val pseudo = ByteArray(12)
        Ip4.fromInt(Ip4.parse("198.18.0.1"), pseudo, 0)
        Ip4.fromInt(Ip4.parse("1.2.3.4"), pseudo, 4)
        pseudo[9] = 6
        val seg = pkt.size - 20
        pseudo[10] = ((seg ushr 8) and 0xff).toByte()
        pseudo[11] = (seg and 0xff).toByte()
        assertEquals("TCP checksum", 0, Checksum.compute(pkt, 20, seg, pseudo))
    }

    /**
     * The DNS query builder appends an EDNS(0) OPT record, so ARCOUNT must say
     * one. A zero ARCOUNT with an OPT present is a malformed message that
     * strict resolvers answer with FORMERR.
     */
    @Test
    fun `dns query arcount matches the appended opt record`() {
        val q = DohResolver.buildQuery("example.com", 1)
        val qdCount = ((q[4].toInt() and 0xff) shl 8) or (q[5].toInt() and 0xff)
        val arCount = ((q[10].toInt() and 0xff) shl 8) or (q[11].toInt() and 0xff)
        assertEquals("QDCOUNT", 1, qdCount)
        assertEquals("ARCOUNT must count the EDNS(0) OPT record", 1, arCount)

        // walk the question, then confirm the OPT RR really starts where we think
        var p = 12
        while (q[p].toInt() != 0) p += 1 + (q[p].toInt() and 0xff)
        p += 1 + 4 // root label + QTYPE + QCLASS
        assertEquals("OPT root name", 0, q[p].toInt())
        assertEquals("OPT type", 41, ((q[p + 1].toInt() and 0xff) shl 8) or (q[p + 2].toInt() and 0xff))
        assertEquals("OPT udp payload size", 1232, ((q[p + 3].toInt() and 0xff) shl 8) or (q[p + 4].toInt() and 0xff))
        assertTrue("trailing bytes present", p + 11 <= q.size)
    }
}
