package com.uacspoofer.mobile.engine.faketcp

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Port of ClientHelloMaker from SNI-Spoofing-1.0 (packet_templates.py).
 * Constructs standard TLS 1.3 ClientHello frames (517 bytes) with custom spoofed SNI
 * and randomized session/key parameters to bypass DPI filtering.
 */
object FakeTcpTemplate {
    private const val TEMPLATE_HEX =
        "1603010200010001fc030341d5b549d9cd1adfa7296c8418d157dc7b624c842824ff493b9375bb48d34f2b20bf018bcc90a7c89a230094815ad0c15b736e38c01209d72d282cb5e2105328150024130213031301c02cc030c02bc02fcca9cca8c024c028c023c027009f009e006b006700ff0100018f0000000b00090000066d63692e6972000b000403000102000a00160014001d0017001e0019001801000101010201030104002300000010000e000c02683208687474702f312e310016000000170000000d002a0028040305030603080708080809080a080b080408050806040105010601030303010302040205020602002b00050403040303002d00020101003300260024001d0020435bacc4d05f9d41fef44ab3ad55616c36e0613473e2338770efdaa98693d217001500d50000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"

    private val templateBytes: ByteArray = hexToBytes(TEMPLATE_HEX)
    private val templateSni = "mci.ir".toByteArray(StandardCharsets.US_ASCII)

    private val static1 = templateBytes.copyOfRange(0, 11)
    private val static2 = byteArrayOf(0x20.toByte())
    private val static3 = templateBytes.copyOfRange(76, 120)
    private val static4 = templateBytes.copyOfRange(127 + templateSni.size, 262 + templateSni.size)
    private val static5 = byteArrayOf(0x00.toByte(), 0x15.toByte())

    private val random = SecureRandom()

    fun buildClientHello(
        targetSni: String = "auth.vercel.com",
        randomBytes: ByteArray? = null,
        sessionId: ByteArray? = null,
        keyShare: ByteArray? = null,
    ): ByteArray {
        val sniBytes = targetSni.toByteArray(StandardCharsets.US_ASCII)
        val rnd = randomBytes ?: ByteArray(32).also { random.nextBytes(it) }
        val sess = sessionId ?: ByteArray(32).also { random.nextBytes(it) }
        val ks = keyShare ?: ByteArray(32).also { random.nextBytes(it) }

        // Server Name Extension: [ext_len: 2] [name_list_len: 2] [name_type: 1 (0x00)] [name_len: 2] [name_bytes]
        val serverNameExt = ByteBuffer.allocate(sniBytes.size + 7).apply {
            putShort((sniBytes.size + 5).toShort())
            putShort((sniBytes.size + 3).toShort())
            put(0x00.toByte())
            putShort(sniBytes.size.toShort())
            put(sniBytes)
        }.array()

        // Padding Extension: 219 - len(target_sni)
        val padLen = (219 - sniBytes.size).coerceAtLeast(0)
        val paddingExt = ByteBuffer.allocate(2 + padLen).apply {
            putShort(padLen.toShort())
            put(ByteArray(padLen))
        }.array()

        val totalLen = static1.size + rnd.size + static2.size + sess.size +
                static3.size + serverNameExt.size + static4.size + ks.size +
                static5.size + paddingExt.size

        return ByteBuffer.allocate(totalLen).apply {
            put(static1)
            put(rnd)
            put(static2)
            put(sess)
            put(static3)
            put(serverNameExt)
            put(static4)
            put(ks)
            put(static5)
            put(paddingExt)
        }.array()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
