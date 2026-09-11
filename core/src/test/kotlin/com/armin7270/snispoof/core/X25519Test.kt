package com.armin7270.snispoof.core

import com.armin7270.snispoof.core.crypto.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import java.security.interfaces.XECPublicKey

/**
 * Pins the bundled X25519 against RFC 7748's own vectors and against the JDK's
 * independent implementation. This is what lets the TLS tunnel run on Android
 * releases that have no `XDH` provider (anything below API 33).
 */
class X25519Test {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `rfc7748 paragraph 5-2 test vectors`() {
        // vector 1
        assertArrayEquals(
            hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            X25519.scalarMult(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
            ),
        )
        // vector 2
        assertArrayEquals(
            hex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"),
            X25519.scalarMult(
                hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
            ),
        )
    }

    @Test
    fun `rfc7748 paragraph 6-1 diffie-hellman vector`() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertEquals("Alice public", alicePub.toHex(), X25519.publicKey(alicePriv).toHex())
        assertEquals("Bob public", bobPub.toHex(), X25519.publicKey(bobPriv).toHex())
        assertArrayEquals("Alice side", shared, X25519.scalarMult(alicePriv, bobPub))
        assertArrayEquals("Bob side", shared, X25519.scalarMult(bobPriv, alicePub))
    }

    @Test
    fun `agrees with the jdk XDH provider on random keys`() {
        val kpg = KeyPairGenerator.getInstance("XDH")
        kpg.initialize(NamedParameterSpec.X25519)
        repeat(16) {
            val kp = kpg.genKeyPair()
            val jdkPubLe = toLe32((kp.public as XECPublicKey).u)

            val (minePriv, minePub) = X25519.keyPair()

            // shared secret computed by the JDK, using OUR public key
            val kf = KeyFactory.getInstance("XDH")
            val peer = kf.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, minePub.reversedArray())))
            val kag = javax.crypto.KeyAgreement.getInstance("XDH")
            kag.init(kp.private)
            kag.doPhase(peer, true)
            val jdkSecret = pad32(kag.generateSecret())

            // shared secret computed by our implementation, using THEIR public key
            val mineSecret = X25519.scalarMult(minePriv, jdkPubLe)

            assertArrayEquals("iteration $it: our X25519 disagrees with the JDK", jdkSecret, mineSecret)
        }
    }

    @Test
    fun `key pair generation is self consistent`() {
        val (priv, pub) = X25519.keyPair()
        assertEquals(32, priv.size)
        assertEquals(32, pub.size)
        assertArrayEquals(pub, X25519.publicKey(priv))
        // the clamped scalar must be idempotent: clamping again changes nothing
        assertArrayEquals(priv, X25519.clamp(priv))
    }

    @Test
    fun `low order input produces the all-zero output`() {
        // order-1 point: u = 0 (or 1) -> shared secret is 0
        val zero = ByteArray(32)
        assertArrayEquals(ByteArray(32), X25519.scalarMult(X25519.clamp(hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")), zero))
    }

    private fun toLe32(u: BigInteger): ByteArray {
        val raw = u.toByteArray()
        val stripped = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        val be = ByteArray(32)
        System.arraycopy(stripped, 0, be, 32 - stripped.size, stripped.size)
        return be.reversedArray()
    }

    private fun pad32(b: ByteArray): ByteArray =
        if (b.size == 32) b else ByteArray(32 - b.size) + b
}
