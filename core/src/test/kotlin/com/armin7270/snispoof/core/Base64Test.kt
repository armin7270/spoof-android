package com.armin7270.snispoof.core

import com.armin7270.snispoof.core.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `java.util.Base64` is API 26+ while the app supports API 24, so the core
 * module carries its own implementation. These pin the behaviour the proxy and
 * WebSocket code depends on.
 */
class Base64Test {

    @Test
    fun `encode matches the standard alphabet and padding`() {
        assertEquals("", Base64.encode(ByteArray(0)))
        assertEquals("Zg==", Base64.encode("f".toByteArray()))
        assertEquals("Zm8=", Base64.encode("fo".toByteArray()))
        assertEquals("Zm9v", Base64.encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", Base64.encode("foob".toByteArray()))
        assertEquals("Zm9vYmE=", Base64.encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", Base64.encode("foobar".toByteArray()))
    }

    @Test
    fun `encode agrees with the jdk implementation`() {
        val rnd = java.util.Random(1234)
        for (n in 0..64) {
            val b = ByteArray(n).also { rnd.nextBytes(it) }
            assertEquals("len=$n", java.util.Base64.getEncoder().encodeToString(b), Base64.encode(b))
        }
    }

    @Test
    fun `decode round-trips and tolerates mime wrapping`() {
        val rnd = java.util.Random(99)
        for (n in 0..64) {
            val b = ByteArray(n).also { rnd.nextBytes(it) }
            val enc = Base64.encode(b)
            assertArrayEquals("len=$n", b, Base64.decode(enc))
            // line-wrapped subscriptions (what getMimeDecoder used to handle)
            val wrapped = enc.chunked(8).joinToString("\r\n")
            assertArrayEquals("wrapped len=$n", b, Base64.decode(wrapped))
        }
    }

    @Test
    fun `decode accepts the url-safe alphabet`() {
        // 0xfb 0xef 0xbf -> 6-bit groups 62,62,62,63 -> "+++/" (or "---_" url-safe)
        val b = byteArrayOf(0xfb.toByte(), 0xef.toByte(), 0xbf.toByte())
        assertArrayEquals(b, java.util.Base64.getDecoder().decode("+++/"))
        assertArrayEquals(b, Base64.decode("+++/"))
        assertArrayEquals(b, Base64.decode("---_"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects invalid characters`() {
        Base64.decode("not*base64")
    }
}
