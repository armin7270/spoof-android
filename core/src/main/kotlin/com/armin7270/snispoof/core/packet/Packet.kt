package com.armin7270.snispoof.core.packet

/**
 * Minimal IPv4/IPv6/TCP/UDP packet parse & build.
 * All multi-byte fields are big-endian, mirroring the wire format that the
 * patterniha core manipulates on Windows via WinDivert.
 */
object Flags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val URG = 0x20
}

object Protocols {
    const val ICMP = 1
    const val TCP = 6
    const val UDP = 17
    const val ICMPv6 = 58
}

object Checksum {
    /** Internet checksum over [off, off+len) of data, optionally seeded with a pseudo header. */
    fun compute(data: ByteArray, off: Int, len: Int, pseudo: ByteArray? = null): Int {
        var sum: Long = 0
        if (pseudo != null) {
            var i = 0
            while (i < pseudo.size) {
                sum += ((pseudo[i].toInt() and 0xff) shl 8) or (pseudo[i + 1].toInt() and 0xff)
                sum = (sum and 0xffffL) + (sum ushr 16)
                i += 2
            }
        }
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            sum = (sum and 0xffffL) + (sum ushr 16)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xff) shl 8
            sum = (sum and 0xffffL) + (sum ushr 16)
        }
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return (sum.toInt().inv()) and 0xffff
    }
}

object Ip4 {
    fun toInt(b: ByteArray, off: Int = 0): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
                ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

    fun fromInt(ip: Int, out: ByteArray = ByteArray(4), off: Int = 0) {
        out[off] = (ip ushr 24).toByte()
        out[off + 1] = (ip ushr 16).toByte()
        out[off + 2] = (ip ushr 8).toByte()
        out[off + 3] = ip.toByte()
    }

    fun bytes(ip: Int): ByteArray {
        val out = ByteArray(4)
        fromInt(ip, out, 0)
        return out
    }

    fun toString(ip: Int): String =
        "${(ip ushr 24) and 0xff}.${(ip ushr 16) and 0xff}.${(ip ushr 8) and 0xff}.${ip and 0xff}"

    fun parse(s: String): Int {
        val parts = s.trim().split(".")
        require(parts.size == 4) { "bad ipv4: $s" }
        var v = 0
        for (p in parts) {
            val n = p.toInt()
            require(n in 0..255) { "bad ipv4: $s" }
            v = (v shl 8) or n
        }
        return v
    }

    fun inCidr(ip: Int, base: Int, prefix: Int): Boolean {
        if (prefix <= 0) return true
        val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
        return (ip and mask) == (base and mask)
    }
}

/** Parsed packet from the TUN device (no copy: view over [data]). */
class IpPacket(val data: ByteArray, val offset: Int, val length: Int) {
    val version: Int = (data[offset].toInt() ushr 4) and 0xf

    val isTcp: Boolean get() = version == 4 && protocol == Protocols.TCP
    val isUdp: Boolean get() = version == 4 && protocol == Protocols.UDP
    val isIcmp: Boolean get() = version == 4 && protocol == Protocols.ICMP
    val isIpv6: Boolean get() = version == 6

    val ihl: Int = if (version == 4) (data[offset].toInt() and 0xf) * 4 else 0
    val totalLength: Int get() = ((data[offset + 2].toInt() and 0xff) shl 8) or (data[offset + 3].toInt() and 0xff)
    val protocol: Int get() = data[offset + 9].toInt() and 0xff
    val srcIp: Int get() = Ip4.toInt(data, offset + 12)
    val dstIp: Int get() = Ip4.toInt(data, offset + 20)

    val l4Offset: Int get() = offset + ihl
    val l4Length: Int get() = minOf(length, if (totalLength == 0) length else totalLength) - ihl

    val srcPort: Int get() = ((data[l4Offset].toInt() and 0xff) shl 8) or (data[l4Offset + 1].toInt() and 0xff)
    val dstPort: Int get() = ((data[l4Offset + 2].toInt() and 0xff) shl 8) or (data[l4Offset + 3].toInt() and 0xff)

    val tcpSeq: Long get() = getLong(l4Offset + 4)
    val tcpAck: Long get() = getLong(l4Offset + 8)
    val dataOffset: Int get() = ((data[l4Offset + 12].toInt() ushr 4) and 0xf) * 4
    val tcpFlags: Int get() = data[l4Offset + 13].toInt() and 0x3f
    val tcpWindow: Int get() = ((data[l4Offset + 14].toInt() and 0xff) shl 8) or (data[l4Offset + 15].toInt() and 0xff)
    val tcpPayloadOffset: Int get() = l4Offset + dataOffset
    val tcpPayloadLength: Int get() = l4Length - dataOffset

    val udpPayloadOffset: Int get() = l4Offset + 8
    val udpPayloadLength: Int get() {
        val dgramLen = ((data[l4Offset + 4].toInt() and 0xff) shl 8) or (data[l4Offset + 5].toInt() and 0xff)
        return minOf(l4Length, dgramLen) - 8
    }

    val icmpType: Int get() = data[l4Offset].toInt() and 0xff
    val icmpv6Type: Int get() = data[l4Offset].toInt() and 0xff

    // IPv6 (minimal parse for drop/notice)
    val ipv6PayloadLength: Int get() = ((data[offset + 4].toInt() and 0xff) shl 8) or (data[offset + 5].toInt() and 0xff)
    val ipv6NextHeader: Int get() = data[offset + 6].toInt() and 0xff
    val ipv6Src: ByteArray get() = data.copyOfRange(offset + 8, offset + 24)
    val ipv6Dst: ByteArray get() = data.copyOfRange(offset + 24, offset + 40)

    private fun getLong(off: Int): Long =
        ((data[off].toLong() and 0xff) shl 24) or ((data[off + 1].toLong() and 0xff) shl 16) or
                ((data[off + 2].toLong() and 0xff) shl 8) or (data[off + 3].toLong() and 0xff)

    fun hasFlag(f: Int): Boolean = version == 4 && (tcpFlags and f) != 0

    companion object {
        /** Strict bounds validation before any field access. */
        fun valid(data: ByteArray, off: Int, len: Int): Boolean {
            if (off < 0 || len < 20 || off + len > data.size) return false
            val v = (data[off].toInt() ushr 4) and 0xf
            if (v != 4) return false
            val ihl = (data[off].toInt() and 0xf) * 4
            if (ihl < 20 || ihl > len) return false
            val total = ((data[off + 2].toInt() and 0xff) shl 8) or (data[off + 3].toInt() and 0xff)
            if (total < ihl || total > len) return false
            if (data[off + 9].toInt() and 0xff == 6 || data[off + 9].toInt() and 0xff == 17) {
                return ihl + 4 <= total // need at least src/dst ports
            }
            return true
        }
    }
}

/** Builds packets that we (the VPN userspace stack) inject back into the TUN. */
object PacketBuilder {

    /** Full IPv4 + TCP packet. Caller owns the returned array. */
    fun tcp(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray?, psh: Boolean,
        mssOpt: Int = 0, ipId: Int = 0, ttl: Int = 64,
    ): ByteArray {
        val optLen = if (mssOpt > 0) 4 else 0
        val tcpLen = 20 + optLen
        val payLen = payload?.size ?: 0
        val total = 20 + tcpLen + payLen
        val out = ByteArray(total)

        // IPv4 header
        out[0] = 0x45
        out[1] = 0
        out[2] = (total ushr 8).toByte(); out[3] = total.toByte()
        out[4] = ((ipId ushr 8) and 0xff).toByte(); out[5] = (ipId and 0xff).toByte()
        out[6] = 0x40 // DF
        out[7] = 0
        out[8] = ttl.toByte()
        out[9] = Protocols.TCP.toByte()
        out[12] = (srcIp ushr 24).toByte(); out[13] = (srcIp ushr 16).toByte()
        out[14] = (srcIp ushr 8).toByte(); out[15] = srcIp.toByte()
        out[16] = (dstIp ushr 24).toByte(); out[17] = (dstIp ushr 16).toByte()
        out[18] = (dstIp ushr 8).toByte(); out[19] = dstIp.toByte()
        val ipCsum = Checksum.compute(out, 0, 20)
        out[10] = (ipCsum ushr 8).toByte(); out[11] = ipCsum.toByte()

        // TCP header
        val t = 20
        out[t] = (srcPort ushr 8).toByte(); out[t + 1] = srcPort.toByte()
        out[t + 2] = (dstPort ushr 8).toByte(); out[t + 3] = dstPort.toByte()
        putLong(out, t + 4, seq)
        putLong(out, t + 8, ack)
        out[t + 12] = (((tcpLen / 4) and 0xf) shl 4).toByte()
        val fl = flags or (if (psh || payLen > 0) Flags.PSH else 0)
        out[t + 13] = fl.toByte()
        out[t + 14] = ((window ushr 8) and 0xff).toByte(); out[t + 15] = (window and 0xff).toByte()
        out[t + 16] = 0; out[t + 17] = 0 // checksum placeholder
        out[t + 18] = 0; out[t + 19] = 0 // urgent

        if (mssOpt > 0) {
            out[t + 20] = 2 // MSS kind
            out[t + 21] = 4 // len
            out[t + 22] = ((mssOpt ushr 8) and 0xff).toByte()
            out[t + 23] = (mssOpt and 0xff).toByte()
        }

        if (payload != null) System.arraycopy(payload, 0, out, 20 + tcpLen, payLen)

        // TCP checksum with pseudo header
        val pseudo = ByteArray(12)
        Ip4.fromInt(srcIp, pseudo, 0)
        Ip4.fromInt(dstIp, pseudo, 4)
        pseudo[8] = 0; pseudo[9] = Protocols.TCP.toByte()
        pseudo[10] = ((tcpLen + payLen) ushr 8).toByte(); pseudo[11] = (tcpLen + payLen).toByte()
        val csum = Checksum.compute(out, t, tcpLen + payLen, pseudo)
        out[t + 16] = (csum ushr 8).toByte(); out[t + 17] = csum.toByte()
        return out
    }

    /** Full IPv4 + UDP packet. */
    fun udp(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        payload: ByteArray, ipId: Int = 0, ttl: Int = 64,
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45
        out[1] = 0
        out[2] = (total ushr 8).toByte(); out[3] = total.toByte()
        out[4] = ((ipId ushr 8) and 0xff).toByte(); out[5] = (ipId and 0xff).toByte()
        out[6] = 0x40
        out[7] = 0
        out[8] = ttl.toByte()
        out[9] = Protocols.UDP.toByte()
        out[12] = (srcIp ushr 24).toByte(); out[13] = (srcIp ushr 16).toByte()
        out[14] = (srcIp ushr 8).toByte(); out[15] = srcIp.toByte()
        out[16] = (dstIp ushr 24).toByte(); out[17] = (dstIp ushr 16).toByte()
        out[18] = (dstIp ushr 8).toByte(); out[19] = dstIp.toByte()
        val ipCsum = Checksum.compute(out, 0, 20)
        out[10] = (ipCsum ushr 8).toByte(); out[11] = ipCsum.toByte()

        val u = 20
        out[u] = (srcPort ushr 8).toByte(); out[u + 1] = srcPort.toByte()
        out[u + 2] = (dstPort ushr 8).toByte(); out[u + 3] = dstPort.toByte()
        val dgramLen = 8 + payload.size
        out[u + 4] = (dgramLen ushr 8).toByte(); out[u + 5] = dgramLen.toByte()
        out[u + 6] = 0; out[u + 7] = 0
        System.arraycopy(payload, 0, out, u + 8, payload.size)

        val pseudo = ByteArray(12)
        Ip4.fromInt(srcIp, pseudo, 0)
        Ip4.fromInt(dstIp, pseudo, 4)
        pseudo[8] = 0; pseudo[9] = Protocols.UDP.toByte()
        pseudo[10] = (dgramLen ushr 8).toByte(); pseudo[11] = dgramLen.toByte()
        val csum = Checksum.compute(out, u, dgramLen, pseudo)
        out[u + 6] = (csum ushr 8).toByte(); out[u + 7] = csum.toByte()
        return out
    }

    /** ICMPv4 destination unreachable (port unreachable) quoting the original packet. */
    fun icmpUnreachable(srcIp: Int, dstIp: Int, original: ByteArray, off: Int, len: Int): ByteArray {
        val quoted = minOf(len, 8 + 20)
        val total = 20 + 8 + quoted
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = (total ushr 8).toByte(); out[3] = total.toByte()
        out[8] = 64
        out[9] = Protocols.ICMP.toByte()
        out[12] = (srcIp ushr 24).toByte(); out[13] = (srcIp ushr 16).toByte()
        out[14] = (srcIp ushr 8).toByte(); out[15] = srcIp.toByte()
        out[16] = (dstIp ushr 24).toByte(); out[17] = (dstIp ushr 16).toByte()
        out[18] = (dstIp ushr 8).toByte(); out[19] = dstIp.toByte()
        // the IPv4 header checksum must be filled in before anything else sums the
        // header, and the ICMP checksum must cover the quoted datagram, so the
        // payload copy has to happen BEFORE both checksums are computed.
        val ipCsum = Checksum.compute(out, 0, 20)
        out[10] = (ipCsum ushr 8).toByte(); out[11] = ipCsum.toByte()
        out[20] = 3 // dest unreachable
        out[21] = 3 // port unreachable
        System.arraycopy(original, off, out, 28, quoted)
        val csum = Checksum.compute(out, 20, 8 + quoted)
        out[22] = (csum ushr 8).toByte(); out[23] = csum.toByte()
        return out
    }

    /** ICMPv6 destination unreachable — used to make apps fall back to IPv4. */
    fun icmpv6Unreachable(src: ByteArray, dst: ByteArray, original: ByteArray, off: Int, len: Int): ByteArray {
        val quoted = minOf(len, 1280 - 40 - 8)
        val total = 40 + 8 + quoted
        val out = ByteArray(total)
        out[0] = 0x60
        out[4] = ((8 + quoted) ushr 8).toByte(); out[5] = (8 + quoted).toByte()
        out[6] = Protocols.ICMPv6.toByte()
        out[7] = 64
        System.arraycopy(src, 0, out, 8, 16)
        System.arraycopy(dst, 0, out, 24, 16)
        out[40] = 1 // destination unreachable
        out[41] = 0
        System.arraycopy(original, off, out, 48, quoted)
        val pseudo = ByteArray(40 + 4 + 4)
        System.arraycopy(out, 8, pseudo, 0, 16)          // src
        System.arraycopy(out, 24, pseudo, 16, 16)        // dst
        pseudo[32] = 0; pseudo[33] = Protocols.ICMPv6.toByte()
        val upper = 8 + quoted
        pseudo[36] = (upper ushr 8).toByte(); pseudo[37] = upper.toByte()
        val csum = Checksum.compute(out, 40, upper, pseudo)
        out[42] = (csum ushr 8).toByte(); out[43] = csum.toByte()
        return out
    }

    private fun putLong(out: ByteArray, off: Int, v: Long) {
        out[off] = (v ushr 24).toByte(); out[off + 1] = (v ushr 16).toByte()
        out[off + 2] = (v ushr 8).toByte(); out[off + 3] = v.toByte()
    }
}
