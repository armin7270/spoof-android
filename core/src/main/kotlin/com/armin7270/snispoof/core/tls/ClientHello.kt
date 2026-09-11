package com.armin7270.snispoof.core.tls

import java.security.SecureRandom

/**
 * Builds a realistic (Chrome-like) TLS ClientHello carrying an arbitrary SNI —
 * the Kotlin port of patterniha's `utils/packet_templates.py ClientHelloMaker`.
 * Used by the SNI scanner, the fake_rst_rebind desync and the root wrong_seq
 * injector.
 */
object ClientHelloForge {

    private val rnd = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    /** Cipher suite list similar to Chrome's (TLS 1.3 + 1.2 suites). */
    private val CIPHERS = intArrayOf(
        0x1301, 0x1302, 0x1303,                      // TLS1.3: AESGCM(x2), CHACHA20
        0xc02b, 0xc02f, 0xc02c, 0xc030,              // ECDHE-ECDSA/RSA AES GCM
        0xcca9, 0xcca8, 0xc013, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035,
    )

    /** @return full TLS record bytes: 16 03 01 LL LL | handshake */
    fun build(sniHost: String): ByteArray {
        val sni = sniHost.encodeToByteArray()
        require(sni.isNotEmpty() && sni.size <= 255) { "bad sni" }

        val random = randomBytes(32)
        val sessionId = randomBytes(32)
        val keyShare = randomBytes(32)

        val ext = ArrayList<ByteArray>()

        // server_name: listLen | nameType(0) | hostLen | host
        ext.add(ext(0x0000, s16(sni.size + 3) + ub(0) + s16(sni.size) + sni))
        // extended_master_secret
        ext.add(ext(0x0017, ByteArray(0)))
        // renegotiation_info
        ext.add(ext(0xff01, ub(1) + ub(0)))
        // supported_groups: x25519, secp256r1, secp384r1, secp521r1
        ext.add(ext(0x000a, s16(8) + s16(0x001d) + s16(0x0017) + s16(0x0018) + s16(0x0019)))
        // ec_point_formats: uncompressed
        ext.add(ext(0x000b, ub(1) + ub(0)))
        // session_ticket (empty)
        ext.add(ext(0x0023, ByteArray(0)))
        // ALPN: h2, http/1.1
        ext.add(ext(0x0010, s16(2 + 1 + 2 + 1 + 8) + ub(2) + "h2".encodeToByteArray() + ub(8) + "http/1.1".encodeToByteArray()))
        // status_request (OCSP, empty responder/requestor ids)
        ext.add(ext(0x0005, ub(1) + ub(0) + ub(0) + s16(0)))
        // signature_algorithms
        val sigAlgs = s16(0x0403) + s16(0x0804) + s16(0x0401) + s16(0x0503) +
                s16(0x0805) + s16(0x0501) + s16(0x0806) + s16(0x0601) + s16(0x0201)
        ext.add(ext(0x000d, s16(sigAlgs.size) + sigAlgs))
        // signed_certificate_timestamp
        ext.add(ext(0x0012, ByteArray(0)))
        // key_share: x25519
        ext.add(ext(0x0033, s16(2 + 2 + 2 + keyShare.size) + s16(0x001d) + s16(keyShare.size) + keyShare))
        // psk_key_exchange_modes: psk_dhe_ke
        ext.add(ext(0x002d, ub(1) + ub(1)))
        // supported_versions: TLS1.3, TLS1.2
        ext.add(ext(0x002b, ub(2) + s16(0x0304) + s16(0x0303)))
        // compress_certificate: brotli, zstd
        ext.add(ext(0x001b, ub(2) + ub(2) + ub(3)))

        var extLen = 0
        for (e in ext) extLen += e.size
        val extBlock = s16(extLen) + ext.reduce { a, b -> a + b }

        val cipherBlock = s16(CIPHERS.size * 2) + CIPHERS.map { s16(it) }.reduce { a, b -> a + b }

        val body = s16(0x0303) + random +
                ub(sessionId.size) + sessionId +
                cipherBlock +
                ub(1) + ub(0) +
                extBlock

        val handshake = ub(0x01) + len24(body.size) + body
        return ub(0x16) + s16(0x0301) + s16(handshake.size) + handshake
    }

    // ---- byte builders -----------------------------------------------------
    private fun ub(v: Int): ByteArray = byteArrayOf(v.toByte())
    private fun s16(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte())
    private fun len24(v: Int): ByteArray =
        byteArrayOf(((v ushr 16) and 0xff).toByte(), ((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte())
    private fun ext(type: Int, data: ByteArray): ByteArray = s16(type) + s16(data.size) + data
}

/** Where the SNI lives inside an outgoing ClientHello byte buffer. */
class SniLocation(val offset: Int, val length: Int, val host: String)

/**
 * Parses a ClientHello produced by the phone's own apps — used by the
 * split-at-SNI desync and for live logging of which hostname is being opened.
 */
object ClientHelloParser {

    fun looksLikeTls(data: ByteArray, off: Int = 0, len: Int = data.size): Boolean {
        if (len < 6 || off < 0 || off + 6 > data.size) return false
        if (data[off] != 0x16.toByte()) return false
        // record version: major must be 0x03, minor 0x00..0x04 (SSL3..TLS1.3).
        // Checking the *major* byte against 0x03..0x04 accepted 0x04 as a major
        // version, which never exists on the wire.
        val major = data[off + 1].toInt() and 0xff
        val minor = data[off + 2].toInt() and 0xff
        return major == 0x03 && minor in 0x00..0x04
    }

    /** @return SNI location or null if this is not a ClientHello with an SNI. */
    fun findSni(data: ByteArray, off: Int = 0, len: Int = data.size): SniLocation? {
        if (!looksLikeTls(data, off, len)) return null
        if (!isClientHello(data, off, len)) return null
        return sniFromExtensions(data, off, len)?.second
    }

    /**
     * Full picture of an outgoing ClientHello: where the visible SNI is, and
     * whether the real server name is hidden behind Encrypted ClientHello / ESNI.
     */
    fun inspect(data: ByteArray, off: Int = 0, len: Int = data.size): ClientHelloInspect {
        if (!looksLikeTls(data, off, len) || len < 6 || !isClientHello(data, off, len)) {
            return ClientHelloInspect(null, false)
        }
        val sni = sniFromExtensions(data, off, len)?.second
        var hidden: HiddenSni? = null
        var p = headerEnd(data, off, len)
        if (p < 0) return ClientHelloInspect(sni, false)
        val extEnd = minOf(off + len, p + u16(data, p))
        p += 2
        while (p + 4 <= extEnd) {
            val type = u16(data, p)
            val eLen = u16(data, p + 2)
            val eStart = p + 4
            if (eStart + eLen > extEnd) break // truncated: stop, don't guess
            if (hidden == null) hidden = HiddenSni.fromCode(type)
            p = eStart + eLen
        }
        return ClientHelloInspect(sni, hidden != null)
    }

    // ---- shared parsing helpers --------------------------------------------

    /** True when the handshake message at [off] is a ClientHello (type 0x01). */
    private fun isClientHello(data: ByteArray, off: Int, len: Int): Boolean =
        off + 6 <= off + len && data[off + 5] == 0x01.toByte()

    /** Offset of the extension-list length field, or -1 if the header is malformed. */
    private fun headerEnd(data: ByteArray, off: Int, len: Int): Int {
        var p = off + 5 + 4 // record header + handshake header
        p += 2              // client version
        p += 32             // random
        if (p >= off + len) return -1
        val sidLen = data[p].toInt() and 0xff
        p += 1 + sidLen
        if (p + 2 > off + len) return -1
        val cipherLen = u16(data, p); p += 2 + cipherLen
        if (p >= off + len) return -1
        val compLen = data[p].toInt() and 0xff; p += 1 + compLen
        if (p + 2 > off + len) return -1
        return p
    }

    /** Walks the extension block looking for server_name. */
    private fun sniFromExtensions(data: ByteArray, off: Int, len: Int): Pair<Int, SniLocation>? {
        val p0 = headerEnd(data, off, len)
        if (p0 < 0) return null
        var p = p0 + 2 // skip extTotal
        val extEnd = minOf(off + len, p0 + u16(data, p0))
        while (p + 4 <= extEnd) {
            val type = u16(data, p)
            val eLen = u16(data, p + 2)
            val eStart = p + 4
            if (eStart + eLen > extEnd) return null
            if (type == 0x0000 && eLen >= 5) {
                val listLen = u16(data, eStart)
                var q = eStart + 2
                val listEnd = minOf(eStart + 2 + listLen, eStart + eLen)
                while (q + 3 <= listEnd) {
                    val nameType = data[q].toInt() and 0xff
                    val nameLen = u16(data, q + 1)
                    if (nameType == 0 && nameLen in 1..(listEnd - q - 3)) {
                        val host = String(data, q + 3, nameLen, Charsets.US_ASCII)
                        val absOff = q + 3
                        return Pair(absOff, SniLocation(absOff, nameLen, host))
                    }
                    q += 3 + nameLen
                }
                return null
            }
            p = eStart + eLen
        }
        return null
    }

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
}

/**
 * Result of inspecting an outgoing ClientHello for what the desync layer can do
 * with it — including the case where the SNI is deliberately not on the wire.
 */
data class ClientHelloInspect(
    val sni: SniLocation?,
    /** Encrypted ClientHello / ESNI present: the visible SNI is decoy, not real. */
    val sniEncrypted: Boolean,
) {
    /**
     * `sni_replace` rewrites bytes that do not exist when the SNI is encrypted,
     * so asking for it is a no-op at best. Fragmentation is still meaningful and
     * stays available.
     */
    val sniReplaceable: Boolean get() = sni != null && !sniEncrypted
}

/** ClientHello extension types that hide the server name from the wire. */
enum class HiddenSni(val code: Int, val label: String) {
    /** RFC 9180-based Encrypted ClientHello (drafts I-D.ietf-tls-ech). */
    ECH(0xfe0d, "ECH"),

    /** The older draft ESNI extension, still seen on some deployments. */
    ESNI(0xffce, "ESNI");

    companion object {
        fun fromCode(code: Int): HiddenSni? = entries.firstOrNull { it.code == code }
    }
}
