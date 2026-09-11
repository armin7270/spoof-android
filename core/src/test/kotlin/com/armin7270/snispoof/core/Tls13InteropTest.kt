package com.armin7270.snispoof.core

import com.armin7270.snispoof.core.proxy.Tls13Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic interoperability test for [Tls13Client] against a fake TLS 1.3
 * server that follows RFC 8446 to the letter.
 *
 * This exists because the previous "live endpoint" smoke test swallowed every
 * failure into `Assume.assumeTrue(false)`, so it reported *skipped* instead of
 * *failed* when the handshake was broken. This test needs no network and fails
 * loudly.
 *
 * The critical detail it pins down is the TLSInnerPlaintext layout:
 *
 *     struct {
 *         opaque content[TLSPlaintext.length];
 *         ContentType type;
 *         uint8 zeros[length_of_padding];
 *     } TLSInnerPlaintext;
 *
 * i.e. content first, content type as the last non-zero byte.
 */
class Tls13InteropTest {

    private val sha256 = MessageDigest.getInstance("SHA-256")

    private fun sha(b: ByteArray): ByteArray = sha256.digest(b)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return m.doFinal(data)
    }

    private fun hkdfExpand(secret: ByteArray, info: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < len) {
            t = hmac(secret, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(32, len - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    private fun expandLabel(secret: ByteArray, label: String, ctx: ByteArray, len: Int): ByteArray {
        val full = "tls13 $label".toByteArray(Charsets.US_ASCII)
        val info = ByteArray(2 + 1 + full.size + 1 + ctx.size)
        info[0] = ((len ushr 8) and 0xff).toByte()
        info[1] = (len and 0xff).toByte()
        info[2] = full.size.toByte()
        full.copyInto(info, 3)
        info[3 + full.size] = ctx.size.toByte()
        ctx.copyInto(info, 4 + full.size)
        return hkdfExpand(secret, info, len)
    }

    private fun deriveSecret(secret: ByteArray, label: String, th: ByteArray) =
        expandLabel(secret, label, th, 32)

    private fun aead(key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)
        )
        c.updateAAD(aad)
        return c.doFinal(input)
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

    private fun u16(v: Int) = byteArrayOf(((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte())
    private fun u24(v: Int) = byteArrayOf(
        ((v ushr 16) and 0xff).toByte(), ((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte()
    )
    private fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    private fun toLe32(u: BigInteger): ByteArray {
        val be = u.toByteArray()
        val stripped = if (be.size > 1 && be[0] == 0.toByte()) be.copyOfRange(1, be.size) else be
        val le = ByteArray(32)
        for (i in stripped.indices) {
            if (i >= 32) break
            le[i] = stripped[stripped.size - 1 - i]
        }
        return le
    }

    private fun readFull(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IllegalStateException("eof")
            off += r
        }
        return buf
    }

    /** Reads a raw TLS record: returns (outerContentType, body). */
    private fun readRecord(input: InputStream): Pair<Int, ByteArray> {
        val hdr = readFull(input, 5)
        val ct = hdr[0].toInt() and 0xff
        val len = u16(hdr, 3)
        return ct to readFull(input, len)
    }

    /**
     * RFC 8446 fake server. Talks just enough TLS 1.3 (X25519 + AES-128-GCM)
     * to complete a 1-RTT handshake and exchange application data.
     */
    private inner class FakeServer(private val input: InputStream, private val output: OutputStream) {

        private val transcript = java.io.ByteArrayOutputStream()
        private var serverSeq = 0L

        private lateinit var sHsKey: ByteArray
        private lateinit var sHsIv: ByteArray
        private lateinit var cHsKey: ByteArray
        private lateinit var cHsIv: ByteArray
        private lateinit var sApKey: ByteArray
        private lateinit var sApIv: ByteArray
        private lateinit var cApKey: ByteArray
        private lateinit var cApIv: ByteArray
        private lateinit var hsSecret: ByteArray
        private lateinit var cHsSecret: ByteArray
        private lateinit var thServerFinished: ByteArray

        fun handshake(): ByteArray /* session id echoed */ {
            // ---- ClientHello ----
            val (ct, body) = readRecord(input)
            assertEquals("expected a plaintext handshake record", 22, ct)
            assertEquals("expected ClientHello", 0x01, body[0].toInt())
            transcript.write(body)

            var p = 4
            p += 2                     // legacy_version
            p += 32                    // random
            val sidLen = body[p].toInt() and 0xff
            p += 1
            val sessionId = body.copyOfRange(p, p + sidLen)
            p += sidLen
            val cipherLen = u16(body, p)
            p += 2 + cipherLen
            val compLen = body[p].toInt() and 0xff
            p += 1 + compLen
            val extTotal = u16(body, p)
            p += 2
            val extEnd = minOf(body.size, p + extTotal)

            var clientPub: ByteArray? = null
            while (p + 4 <= extEnd) {
                val type = u16(body, p)
                val len = u16(body, p + 2)
                val b = p + 4
                if (type == 0x0033) {
                    // key_share: client_shares<2> then entries
                    var q = b + 2
                    while (q + 4 <= b + len) {
                        val group = u16(body, q)
                        val klen = u16(body, q + 2)
                        if (group == 0x001d) clientPub = body.copyOfRange(q + 4, q + 4 + klen)
                        q += 4 + klen
                    }
                }
                p = b + len
            }
            val peerLe = clientPub ?: throw IllegalStateException("client sent no x25519 key_share")

            // ---- ServerHello ----
            val kpg = KeyPairGenerator.getInstance("XDH")
            kpg.initialize(NamedParameterSpec.X25519)
            val kp = kpg.genKeyPair()
            val serverPubLe = toLe32((kp.public as XECPublicKey).u)
            val serverRandom = ByteArray(32).also { SecureRandom().nextBytes(it) }

            val exts = u16(0x002b) + u16(2) + u16(0x0304) +
                    // KeyShareEntry = group(2) + key_exchange<2> + key(32) = 36 bytes
                    u16(0x0033) + u16(2 + 2 + 32) + u16(0x001d) + u16(32) + serverPubLe
            val shBody = byteArrayOf(0x03, 0x03) + serverRandom +
                    byteArrayOf(sessionId.size.toByte()) + sessionId +
                    u16(0x1301) + byteArrayOf(0x00) + u16(exts.size) + exts
            val sh = byteArrayOf(0x02) + u24(shBody.size) + shBody
            transcript.write(sh)
            output.write(byteArrayOf(0x16, 0x03, 0x03) + u16(sh.size) + sh)
            output.flush()

            // ---- key schedule up to handshake secrets ----
            val shared = run {
                val kag = KeyAgreement.getInstance("XDH")
                kag.init(kp.private)
                val kf = KeyFactory.getInstance("XDH")
                val peer = kf.generatePublic(
                    XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, peerLe.reversedArray()))
                )
                kag.doPhase(peer, true)
                val s = kag.generateSecret()
                if (s.size == 32) s else ByteArray(32 - s.size) + s
            }
            val emptyHash = sha(ByteArray(0))
            val early = hmac(ByteArray(32), ByteArray(32))
            hsSecret = hmac(deriveSecret(early, "derived", emptyHash), shared)
            val thSh = sha(transcript.toByteArray())
            cHsSecret = deriveSecret(hsSecret, "c hs traffic", thSh)
            val sHsSecret = deriveSecret(hsSecret, "s hs traffic", thSh)
            cHsKey = expandLabel(cHsSecret, "key", ByteArray(0), 16)
            cHsIv = expandLabel(cHsSecret, "iv", ByteArray(0), 12)
            sHsKey = expandLabel(sHsSecret, "key", ByteArray(0), 16)
            sHsIv = expandLabel(sHsSecret, "iv", ByteArray(0), 12)

            // ---- middlebox-compat CCS (plaintext, outer type 20) ----
            // Real servers (OpenSSL, nginx, ...) send this; a client must skip it.
            output.write(byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01))
            output.flush()

            // ---- encrypted handshake: EE + Cert + CV in one record, Finished in the next ----
            val ee = byteArrayOf(0x08) + u24(2) + u16(0)                       // empty extensions
            val cert = byteArrayOf(0x0b) + u24(8) + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
            val cv = byteArrayOf(0x0f) + u24(4) + byteArrayOf(0x08, 0x04, 0x00, 0x00)
            val first = ee + cert + cv
            transcript.write(first)
            writeEncryptedRecord(first + byteArrayOf(0x16))

            // server Finished: verify_data covers ClientHello..CertificateVerify,
            // i.e. NOT the server's own Finished (RFC 8446 §4.4.4)
            val sFinKey = expandLabel(sHsSecret, "finished", ByteArray(0), 32)
            val sVerify = hmac(sFinKey, sha(transcript.toByteArray()))
            val sFin = byteArrayOf(0x14) + u24(sVerify.size) + sVerify
            transcript.write(sFin)
            // ...but the client's Finished, and the application secrets, cover the
            // transcript INCLUDING the server's Finished (RFC 8446 §4.4.4 / §7.1)
            thServerFinished = sha(transcript.toByteArray())
            writeEncryptedRecord(sFin + byteArrayOf(0x16))

            serverSeq = 0
            return sessionId
        }

        /** Decrypts and validates the client's Finished, then switches to app keys. */
        fun expectClientFinished() {
            val (ct, body) = readRecord(input)
            assertEquals("client Finished must use outer content type 23", 23, ct)
            val plain = aead(cHsKey, nonce(cHsIv, 0), recordHeader(23, body.size), body, false)
            val (type, content) = splitInnerPlaintext(plain)
            assertEquals("client Finished inner type", 0x16, type)
            assertEquals("client Finished handshake type", 0x14, content[0].toInt() and 0xff)
            val verify = content.copyOfRange(4, content.size)
            val cFinKey = expandLabel(cHsSecret, "finished", ByteArray(0), 32)
            assertTrue(
                "client Finished verify_data mismatch",
                hmac(cFinKey, thServerFinished).contentEquals(verify)
            )

            // application traffic secrets
            val master = hmac(deriveSecret(hsSecret, "derived", sha(ByteArray(0))), ByteArray(32))
            val cApSecret = deriveSecret(master, "c ap traffic", thServerFinished)
            val sApSecret = deriveSecret(master, "s ap traffic", thServerFinished)
            cApKey = expandLabel(cApSecret, "key", ByteArray(0), 16)
            cApIv = expandLabel(cApSecret, "iv", ByteArray(0), 12)
            sApKey = expandLabel(sApSecret, "key", ByteArray(0), 16)
            sApIv = expandLabel(sApSecret, "iv", ByteArray(0), 12)
        }

        fun sendAppData(plain: ByteArray, splitAt: Int = -1) {
            if (splitAt <= 0 || splitAt >= plain.size) {
                writeAppRecord(plain)
            } else {
                writeAppRecord(plain.copyOfRange(0, splitAt))
                writeAppRecord(plain.copyOfRange(splitAt, plain.size))
            }
        }

        fun writeAppRecord(plain: ByteArray) {
            val key = sApKey
            val iv = sApIv
            var seq = serverSeq
            val inner = plain + byteArrayOf(0x17)
            val hdr = recordHeader(23, inner.size + 16)
            val enc = aead(key, nonce(iv, seq), hdr, inner, true)
            seq++
            serverSeq = seq
            output.write(hdr + enc)
            output.flush()
        }

        /** Reads application data until [want] bytes have arrived across records. */
        fun readAppData(want: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < want) {
                val (ct, body) = readRecord(input)
                assertEquals("client app data must use outer content type 23", 23, ct)
                val plain = aead(cApKey, nonce(cApIv, clientSeq), recordHeader(23, body.size), body, false)
                val (type, content) = splitInnerPlaintext(plain)
                assertEquals("client app data inner type", 0x17, type)
                clientSeq++
                out.write(content)
            }
            return out.toByteArray()
        }

        private var clientSeq = 0L

        private fun recordHeader(ct: Int, len: Int) =
            byteArrayOf(ct.toByte(), 0x03, 0x03) + u16(len)

        private fun writeEncryptedRecord(inner: ByteArray) {
            val hdr = recordHeader(23, inner.size + 16)
            val enc = aead(sHsKey, nonce(sHsIv, serverSeq), hdr, inner, true)
            serverSeq++
            output.write(hdr + enc)
            output.flush()
        }
    }

    /**
     * Splits a TLSInnerPlaintext exactly as RFC 8446 defines it: strip trailing
     * zero padding, the last remaining byte is the content type, everything
     * before it is the content.
     */
    private fun splitInnerPlaintext(plain: ByteArray): Pair<Int, ByteArray> {
        var end = plain.size
        while (end > 1 && plain[end - 1] == 0.toByte()) end--
        require(end >= 1) { "empty inner plaintext" }
        val type = plain[end - 1].toInt() and 0xff
        return type to plain.copyOfRange(0, end - 1)
    }

    @Test
    fun `tls13 client interoperates with an rfc8446 server and exchanges data`() {
        val server = ServerSocket(0)
        val receivedByServer = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        val serverThread = Thread {
            try {
                val c = server.accept()
                c.tcpNoDelay = true
                val fake = FakeServer(c.getInputStream(), c.getOutputStream())
                fake.handshake()
                fake.expectClientFinished()
                fake.sendAppData("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray(), splitAt = 20)
                receivedByServer.put(fake.readAppData(3000))
                c.close()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }.apply { isDaemon = true }
        serverThread.start()

        val socket = java.net.Socket()
        socket.soTimeout = 10_000
        socket.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 5000)

        val response = StringBuilder()
        var clientError: Throwable? = null
        try {
            val session = Tls13Client.handshake(
                socket.getInputStream(), socket.getOutputStream(), "example.com"
            )

            // client -> server application data, split across two records by the Writer
            val payload = ByteArray(3000) { 'X'.code.toByte() }
            session.output.write(payload)
            session.output.flush()

            // server -> client application data, split across two records
            val buf = ByteArray(4096)
            while (response.length < 38) {
                val n = session.input.read(buf)
                if (n < 0) break
                response.append(String(buf, 0, n, Charsets.ISO_8859_1))
            }
        } catch (t: Throwable) {
            clientError = t
        } finally {
            runCatching { socket.close() }
            runCatching { server.close() }
        }

        // Report BOTH sides: a client-side abort shows up at the fake server as a
        // bare EOF, which on its own says nothing about the real cause.
        val serverErr = failure.get()
        val clientErr = clientError
        if (serverErr != null || clientErr != null) {
            val sb = StringBuilder()
            sb.append("TLS 1.3 interop failed.")
            if (clientErr != null) sb.append("\n  client: $clientErr")
            if (serverErr != null) sb.append("\n  server: $serverErr")
            throw AssertionError(sb.toString(), clientErr ?: serverErr)
        }

        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", response.toString())

        val atServer = receivedByServer.poll(10, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("server never received the client payload", atServer != null)
        assertEquals("client -> server payload corrupted", 3000, atServer!!.size)
    }
}
