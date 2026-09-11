package com.armin7270.snispoof.core.util

/**
 * Minimal standard-alphabet Base64.
 *
 * `java.util.Base64` only exists from Android API 26 while this app supports
 * API 24, so the pure-JVM core module must not depend on it. Implemented here
 * to keep the proxy/WebSocket code working on every supported API level.
 */
object Base64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Standard alphabet, `=` padded — what a WebSocket `Sec-WebSocket-Key` needs. */
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or
                    ((bytes[i + 1].toInt() and 0xff) shl 8) or
                    (bytes[i + 2].toInt() and 0xff)
            sb.append(ALPHABET[(n ushr 18) and 0x3f])
            sb.append(ALPHABET[(n ushr 12) and 0x3f])
            sb.append(ALPHABET[(n ushr 6) and 0x3f])
            sb.append(ALPHABET[n and 0x3f])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xff) shl 16
                sb.append(ALPHABET[(n ushr 18) and 0x3f])
                sb.append(ALPHABET[(n ushr 12) and 0x3f])
                sb.append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 0x3f])
                sb.append(ALPHABET[(n ushr 12) and 0x3f])
                sb.append(ALPHABET[(n ushr 6) and 0x3f])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    /**
     * Lenient decode: whitespace, newlines and `=` padding are ignored, and both
     * the standard and URL-safe alphabets are accepted. Subscription exports and
     * `vmess://` payloads are frequently line-wrapped, which is why
     * `Base64.getMimeDecoder()` was used before.
     *
     * @throws IllegalArgumentException on an invalid character.
     */
    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var acc = 0
        var bits = 0
        for (c in text) {
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t' || c == '=') continue
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' , '-' -> 62
                '/' , '_' -> 63
                else -> throw IllegalArgumentException("bad base64 char '$c'")
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
