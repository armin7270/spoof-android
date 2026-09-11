package com.armin7270.snispoof.core.proxy

import com.armin7270.snispoof.core.crypto.X25519
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Minimal TLS 1.3 client (X25519 + TLS_AES_128_GCM_SHA256, insecure cert mode)
 * with a hand-built ClientHello so the handshake bytes can be fragmented like
 * the patterniha core does. Used for the proxy tunnel's own TLS connection.
 */
object Tls13Client {

    private const val CT_CCS = 20
    private const val CT_ALERT = 21
    private const val CT_HANDSHAKE = 22
    private const val CT_APPDATA = 23

    class Session internal constructor(
        val output: OutputStream,
        val input: InputStream,
    )

    /** Runs the handshake on an already-connected socket. */
    fun handshake(
        input: InputStream,
        output: OutputStream,
        sni: String,
        alpn: String = "http/1.1",
        fragmenter: ((ByteArray) -> List<ByteArray>)? = null,
        fragmentDelayMs: Int = 0,
    ): Session {
        val conn = Connection(input, output, sni, alpn)
        return conn.run(fragmenter, fragmentDelayMs)
    }

    /**
     * X25519 is bundled (see [com.armin7270.snispoof.core.crypto.X25519]) so the
     * tunnel works on every supported Android release — the JDK `XDH` provider it
     * replaced only exists from API 33, which silently killed config mode on
     * Android 7-12.
     */
    fun isSupported(): Boolean = true

    // ------------------------------------------------------------------ crypto

    internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    internal fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    internal fun hkdfExpand(secret: ByteArray, info: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < len) {
            t = hmacSha256(secret, t + info + byteArrayOf(counter.toByte()))
            val n = min(32, len - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    internal fun expandLabel(secret: ByteArray, label: String, context: ByteArray, len: Int): ByteArray {
        val full = "tls13 $label".toByteArray(Charsets.US_ASCII)
        val info = ByteArray(2 + 1 + full.size + 1 + context.size)
        info[0] = ((len ushr 8) and 0xff).toByte()
        info[1] = (len and 0xff).toByte()
        info[2] = full.size.toByte()
        full.copyInto(info, 3)
        info[3 + full.size] = context.size.toByte()
        context.copyInto(info, 4 + full.size)
        return hkdfExpand(secret, info, len)
    }

    private fun deriveSecret(secret: ByteArray, label: String, th: ByteArray): ByteArray =
        expandLabel(secret, label, th, 32)

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun aead(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)
        )
        c.updateAAD(aad)
        return c.doFinal(plain)
    }

    private fun nonce(iv: ByteArray, seq: Long): ByteArray {
        val n = iv.copyOf()
        var s = seq
        for (i in 11 downTo 4) {
            n[i] = (n[i].toInt() xor (s and 0xff).toInt()).toByte()
            s = s ushr 8
        }
        return n
    }

    // ------------------------------------------------------------- connection

    private class Connection(
        private val input: InputStream,
        private val output: OutputStream,
        private val sni: String,
        private val alpn: String,
    ) {
        private val transcript = ByteArrayOutputStream()
        private var clientSeq = 0L
        private var serverSeq = 0L

        /** keys of the epoch currently used to read server records */
        private lateinit var sKey: ByteArray
        private lateinit var sIv: ByteArray

        private lateinit var cHsKey: ByteArray
        private lateinit var cHsIv: ByteArray
        private lateinit var cApKey: ByteArray
        private lateinit var cApIv: ByteArray

        fun run(fragmenter: ((ByteArray) -> List<ByteArray>)?, delayMs: Int): Session {
            val (privScalar, pubLe) = X25519.keyPair()
            val random = ByteArray(32).also { SecureRandom().nextBytes(it) }

            val ch = buildClientHello(pubLe, random)
            // The transcript covers handshake messages only — TLS record headers
            // are NOT part of it (RFC 8446 §4.4.1). Folding the 5-byte record
            // header in here makes every derived secret differ from the peer's.
            transcript.write(ch, 5, ch.size - 5)

            val chunks = fragmenter?.invoke(ch) ?: listOf(ch)
            for ((i, c) in chunks.withIndex()) {
                output.write(c)
                if (delayMs > 0 && i < chunks.size - 1) Thread.sleep(delayMs.toLong())
            }
            output.flush()

            // ServerHello (plaintext handshake record)
            while (true) {
                val (ct, _, body) = readRecord()
                when (ct) {
                    CT_HANDSHAKE -> {
                        if (body[0].toInt() != 0x02) throw IOException("tls: expected ServerHello")
                        transcript.write(body)
                        parseServerHello(body, privScalar)
                        break
                    }
                    CT_ALERT -> throw IOException("tls: alert ${body.getOrNull(1) ?: body.getOrNull(0)}")
                    CT_CCS -> Unit // middlebox CCS before SH: ignore
                    else -> throw IOException("tls: unexpected record $ct before SH")
                }
            }

            // Encrypted handshake: EE, Cert, CV, Finished. Handshake messages may
            // be coalesced into one record or span several, so accumulate.
            var pending = ByteArray(0)
            var serverFinished = false
            while (!serverFinished) {
                val (ct, content) = readDecrypted()
                if (ct != CT_HANDSHAKE) throw IOException("tls: unexpected record $ct in handshake")
                pending += content
                var q = 0
                while (q + 4 <= pending.size) {
                    val hsType = pending[q].toInt() and 0xff
                    val hsLen = u24(pending, q + 1)
                    if (q + 4 + hsLen > pending.size) break // message continues in the next record
                    val msg = pending.copyOfRange(q, q + 4 + hsLen)
                    transcript.write(msg)
                    when (hsType) {
                        0x08, 0x0b, 0x0f -> Unit // encrypted extensions / cert / cert verify
                        0x14 -> serverFinished = true
                        else -> throw IOException("tls: unexpected handshake type $hsType")
                    }
                    q += 4 + hsLen
                    if (serverFinished) break
                }
                pending = if (q >= pending.size) ByteArray(0) else pending.copyOfRange(q, pending.size)
            }

            // application (master) secrets from the full transcript
            val emptyHash = sha256(ByteArray(0))
            val thFull = transcriptHash()
            val master = hkdfExtract(deriveSecret(hsSecret!!, "derived", emptyHash), ByteArray(32))
            val cApSecret = deriveSecret(master, "c ap traffic", thFull)
            val sApSecret = deriveSecret(master, "s ap traffic", thFull)
            cApKey = expandLabel(cApSecret, "key", ByteArray(0), 16)
            cApIv = expandLabel(cApSecret, "iv", ByteArray(0), 12)

            // client Finished under the handshake keys
            val fk = expandLabel(cHsSecret!!, "finished", ByteArray(0), 32)
            val verify = hmacSha256(fk, transcriptHash())
            val finMsg = byteArrayOf(0x14) +
                    byteArrayOf(((verify.size ushr 16) and 0xff).toByte(), ((verify.size ushr 8) and 0xff).toByte(), (verify.size and 0xff).toByte()) +
                    verify
            transcript.write(finMsg)
            // TLSInnerPlaintext = content || type (RFC 8446 §5.2)
            val inner = finMsg + byteArrayOf(CT_HANDSHAKE.toByte())
            val hdr = recordHeader(CT_APPDATA, inner.size + 16)
            val enc = aead(cHsKey, nonce(cHsIv, clientSeq), hdr, inner, true)
            clientSeq++
            output.write(hdr + enc)
            output.flush()

            // both directions move to the application epoch (sequence numbers restart)
            clientSeq = 0
            useServerKeys(
                expandLabel(sApSecret, "key", ByteArray(0), 16),
                expandLabel(sApSecret, "iv", ByteArray(0), 12),
            )

            return Session(Writer(), Reader())
        }

        // ------------------------------------------------------------ CH build

        private fun buildClientHello(pubLe: ByteArray, random: ByteArray): ByteArray {
            fun u16(v: Int) = byteArrayOf(((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte())
            fun u24(v: Int) = byteArrayOf(
                ((v ushr 16) and 0xff).toByte(), ((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte()
            )
            val sni = sni.encodeToByteArray()
            val session = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val exts = ArrayList<ByteArray>()
            // server_name: list<2> = entryLen<2> + nameType(1) + hostLen<2> + host
            exts.add(u16(0x0000) + u16(sni.size + 5) + u16(sni.size + 3) +
                    byteArrayOf(0) + u16(sni.size) + sni)
            exts.add(u16(0x0017) + u16(0)) // extended_master_secret
            exts.add(u16(0x000a) + u16(6) + u16(4) + u16(0x001d) + u16(0x0018))
            exts.add(u16(0x000b) + u16(2) + byteArrayOf(1, 0))
            // ALPN: ProtocolNameList<2> = ProtocolName<1> per entry.
            // data = listLen(2) + nameLen(1) + name  -> 3 + alpn.length
            exts.add(u16(0x0010) + u16(3 + alpn.length) +
                    u16(1 + alpn.length) + byteArrayOf(alpn.length.toByte()) + alpn.encodeToByteArray())
            exts.add(u16(0x000d) + u16(12) + u16(10) +
                    u16(0x0403) + u16(0x0804) + u16(0x0401) + u16(0x0501) + u16(0x0805))
            // key_share (ClientHello form): client_shares<2> = KeyShareEntry
            // data = listLen(2) + group(2) + keyLen(2) + key(32)  -> 38
            exts.add(u16(0x0033) + u16(2 + 2 + 2 + 32) + u16(2 + 2 + 32) +
                    u16(0x001d) + u16(32) + pubLe)
            exts.add(u16(0x002b) + u16(3) + byteArrayOf(2) + u16(0x0304)) // TLS 1.3 only
            exts.add(u16(0x002d) + u16(2) + byteArrayOf(1, 1))
            val extBlock = u16(exts.sumOf { it.size }) + exts.reduce { a, b -> a + b }
            val ciphers = u16(2) + u16(0x1301)
            val body = u16(0x0303) + random + byteArrayOf(32) + session + ciphers +
                    byteArrayOf(1, 0) + extBlock
            return byteArrayOf(0x16) + u16(0x0301) + u16(body.size + 4) +
                    byteArrayOf(0x01) + u24(body.size) + body
        }

        // ------------------------------------------------------------- parsing

        private var hsSecret: ByteArray? = null
        private var cHsSecret: ByteArray? = null

        private fun parseServerHello(sh: ByteArray, privateScalar: ByteArray) {
            if (sh.size < 44) throw IOException("tls: short ServerHello")
            val sidLen = sh[38].toInt() and 0xff
            var p = 39 + sidLen
            if (p + 2 > sh.size) throw IOException("tls: short SH ciphers")
            val cipher = u16(sh, p)
            p += 2
            if (cipher != 0x1301) throw IOException("tls: server chose 0x${cipher.toString(16)}")
            val compLen = sh[p].toInt() and 0xff
            p += 1 + compLen
            if (p + 2 > sh.size) throw IOException("tls: short SH exts")
            val extTotal = u16(sh, p)
            p += 2
            val end = minOf(sh.size, p + extTotal)
            var srvPub: ByteArray? = null
            while (p + 4 <= end) {
                val type = u16(sh, p)
                val len = u16(sh, p + 2)
                val b = p + 4
                if (b + len > end) throw IOException("tls: malformed SH ext")
                if (type == 0x0033 && len >= 36 && u16(sh, b) == 0x001d) {
                    val klen = u16(sh, b + 2)
                    srvPub = sh.copyOfRange(b + 4, b + 4 + klen)
                }
                p = b + len
            }
            val srv = srvPub ?: throw IOException("tls: no x25519 key_share")
            // The peer's key_share is a 32-byte little-endian u-coordinate; the
            // bundled RFC 7748 implementation takes exactly that.
            val shared = X25519.scalarMult(privateScalar, srv)

            val emptyHash = sha256(ByteArray(0))
            val early = hkdfExtract(ByteArray(32), ByteArray(32))
            val derived = deriveSecret(early, "derived", emptyHash)
            hsSecret = hkdfExtract(derived, shared)
            val th = transcriptHash()
            cHsSecret = deriveSecret(hsSecret!!, "c hs traffic", th)
            val sHsSecret = deriveSecret(hsSecret!!, "s hs traffic", th)
            cHsKey = expandLabel(cHsSecret!!, "key", ByteArray(0), 16)
            cHsIv = expandLabel(cHsSecret!!, "iv", ByteArray(0), 12)
            useServerKeys(
                expandLabel(sHsSecret, "key", ByteArray(0), 16),
                expandLabel(sHsSecret, "iv", ByteArray(0), 12),
            )
            clientSeq = 0
        }

        // ---------------------------------------------------------- record io

        private fun transcriptHash(): ByteArray = sha256(transcript.toByteArray())

        private fun u16(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

        private fun u24(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xff) shl 16) or ((b[off + 1].toInt() and 0xff) shl 8) or
                    (b[off + 2].toInt() and 0xff)

        private fun readRecord(): Triple<Int, Int, ByteArray> {
            val hdr = ByteArray(5)
            readFull(hdr)
            val ct = hdr[0].toInt() and 0xff
            val len = u16(hdr, 3)
            if (len <= 0 || len > 16640) throw IOException("tls: bad record len $len")
            val body = ByteArray(len)
            readFull(body)
            return Triple(ct, len, body)
        }

        private fun recordHeader(ct: Int, len: Int): ByteArray =
            byteArrayOf(ct.toByte(), 0x03, 0x03) +
                    byteArrayOf(((len ushr 8) and 0xff).toByte(), (len and 0xff).toByte())

        /** Switches the read direction to a new key epoch; sequence restarts at 0. */
        private fun useServerKeys(key: ByteArray, iv: ByteArray) {
            sKey = key
            sIv = iv
            serverSeq = 0
        }

        /**
         * Reads one record and returns the real inner content type plus content.
         *
         * Handles, in the encrypted epoch:
         *  - plaintext CCS (outer type 20): middlebox compatibility, no sequence
         *    number is consumed and the record carries no AEAD payload;
         *  - plaintext alerts (outer type 21);
         *  - AEAD-protected records (outer type 23), whose TLSInnerPlaintext is
         *    `content || type || zeros` (RFC 8446 §5.2) — so the content type is
         *    the last non-zero byte, NOT the first byte.
         */
        private fun readDecrypted(): Pair<Int, ByteArray> {
            while (true) {
                val hdr = ByteArray(5)
                readFull(hdr)
                val outerCt = hdr[0].toInt() and 0xff
                val len = u16(hdr, 3)
                if (len <= 0 || len > 16640) throw IOException("tls: bad record len $len")
                val body = ByteArray(len)
                readFull(body)
                when (outerCt) {
                    CT_CCS -> continue // no sequence number consumed
                    CT_ALERT -> throw IOException("tls: alert ${body.getOrNull(1) ?: body.getOrNull(0)}")
                    CT_APPDATA -> {
                        if (len <= 16) throw IOException("tls: bad encrypted record len $len")
                        val plain = aead(sKey, nonce(sIv, serverSeq), hdr, body, false)
                        serverSeq++
                        return splitInnerPlaintext(plain)
                    }
                    else -> throw IOException("tls: unexpected record type $outerCt")
                }
            }
        }

        /**
         * TLSInnerPlaintext = content || ContentType || zeros. Strips the zero
         * padding and splits off the trailing content type.
         */
        private fun splitInnerPlaintext(plain: ByteArray): Pair<Int, ByteArray> {
            var end = plain.size
            while (end > 1 && plain[end - 1] == 0.toByte()) end--
            if (end < 1) throw IOException("tls: empty inner plaintext")
            val type = plain[end - 1].toInt() and 0xff
            return type to plain.copyOfRange(0, end - 1)
        }

        private fun readFull(buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) throw IOException("tls: eof")
                off += n
            }
        }

        private inner class Writer : OutputStream() {
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len <= 0) return
                var o = off
                var remaining = len
                while (remaining > 0) {
                    val chunk = min(1400, remaining)
                    // TLSInnerPlaintext = content || type (RFC 8446 §5.2)
                    val inner = b.copyOfRange(o, o + chunk) + byteArrayOf(CT_APPDATA.toByte())
                    val hdr = recordHeader(CT_APPDATA, inner.size + 16)
                    val enc = aead(cApKey, nonce(cApIv, clientSeq), hdr, inner, true)
                    clientSeq++
                    output.write(hdr + enc)
                    o += chunk
                    remaining -= chunk
                }
                output.flush()
            }
        }

        private inner class Reader : InputStream() {
            private var buffer = ByteArray(0)
            private var pos = 0

            override fun read(): Int {
                if (!fill()) return -1
                return buffer[pos++].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (!fill()) return -1
                val n = min(len, buffer.size - pos)
                System.arraycopy(buffer, pos, b, off, n)
                pos += n
                return n
            }

            private fun fill(): Boolean {
                if (pos < buffer.size) return true
                while (true) {
                    val (ct, content) = readDecrypted()
                    when (ct) {
                        CT_APPDATA -> {
                            if (content.isEmpty()) continue
                            buffer = content
                            pos = 0
                            return true
                        }
                        CT_HANDSHAKE -> Unit // NewSessionTicket / KeyUpdate
                        CT_ALERT -> throw IOException("tls: alert ${content.getOrNull(0)}")
                        else -> Unit
                    }
                }
            }
        }
    }
}
