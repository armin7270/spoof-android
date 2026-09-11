package com.armin7270.snispoof.core.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 (RFC 7748) implemented on java.math.BigInteger.
 *
 * Why not the JDK `XDH` provider? It only exists on Android from API 33, while
 * this app supports API 24. Bundling the curve here makes the TLS proxy tunnel
 * and the scanners work on every supported release.
 *
 * The Montgomery ladder follows RFC 7748 §5 verbatim; field arithmetic is mod
 * p = 2^255 - 19. BigInteger is not constant time — for this client the scalar
 * is an ephemeral, single-use key for the device's own connection, so the
 * exposure differs from a long-term server key.
 */
object X25519 {

    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private const val A24 = 121665L

    /** The standard X25519 base point, u = 9, little-endian encoded. */
    val BASEPOINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** RFC 7748 §5: force the scalar into the valid, non-twisty range. */
    fun clamp(scalar: ByteArray): ByteArray {
        require(scalar.size == 32) { "x25519 scalar must be 32 bytes" }
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return k
    }

    /** Generates a fresh key pair: (private scalar, public key), both 32 LE bytes. */
    fun keyPair(random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val clamped = clamp(secret)
        return clamped to publicKey(clamped)
    }

    /** Public key for a (clamped) private scalar, 32 little-endian bytes. */
    fun publicKey(clampedScalar: ByteArray): ByteArray =
        scalarMult(clampedScalar, BASEPOINT)

    /**
     * The X25519 function: the u-coordinate of [scalar] * [uCoordinate].
     * Both inputs and the result are 32-byte little-endian arrays.
     */
    fun scalarMult(scalar: ByteArray, uCoordinate: ByteArray): ByteArray {
        require(scalar.size == 32 && uCoordinate.size == 32) { "x25519 inputs must be 32 bytes" }
        val k = BigInteger(1, clamp(scalar).reversedArray())

        // "implementations of X25519 ... MUST mask the most significant bit in
        // the final byte" (RFC 7748 §5)
        val uBytes = uCoordinate.copyOf()
        uBytes[31] = (uBytes[31].toInt() and 127).toByte()
        val x1 = BigInteger(1, uBytes.reversedArray()).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val daPlusCb = da.add(cb)
            val daMinusCb = da.subtract(cb)

            x3 = daPlusCb.multiply(daPlusCb).mod(P)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(BigInteger.valueOf(A24).multiply(e)).mod(P)).mod(P)
        }
        // RFC 7748 swaps both pairs here, but the final inversion only reads x2
        // and z2, so swapping x3/z3 would be dead work.
        if (swap == 1) {
            x2 = x3
            z2 = z3
        }

        // z2 is inverted with z^(p-2), the standard trick for p prime
        val result = x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P)
        return toLe32(result)
    }

    /** Encodes a field element as 32 little-endian bytes. */
    private fun toLe32(v: BigInteger): ByteArray {
        val be = ByteArray(32)
        val raw = v.toByteArray()
        val stripped = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        require(stripped.size <= 32) { "x25519 result overflow" }
        System.arraycopy(stripped, 0, be, 32 - stripped.size, stripped.size)
        return be.reversedArray()
    }
}
