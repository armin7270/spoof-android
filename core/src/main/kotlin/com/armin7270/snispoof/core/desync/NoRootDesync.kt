package com.armin7270.snispoof.core.desync

import com.armin7270.snispoof.core.tls.ClientHelloForge
import com.armin7270.snispoof.core.tls.ClientHelloParser
import com.armin7270.snispoof.core.tls.TlsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Desync strategies, mirroring the role of patterniha's `BYPASS_METHOD`. */
enum class DesyncMethod(val id: String, val needsRoot: Boolean) {
    OFF("off", false),
    SPLIT_N("split_n", false),
    SPLIT_SNI("split_sni", false),
    MULTI_FRAG("multi_frag", false),
    DELAYED("delayed", false),
    SNI_REPLACE("sni_replace", false),
    FAKE_RST_REBIND("fake_rst_rebind", false),
    WRONG_SEQ("wrong_seq", true),      // faithful patterniha injection, needs root
    COMBINED("combined", false);       // SNI replace + split inside the (new) SNI

    companion object {
        fun fromId(id: String): DesyncMethod = entries.firstOrNull { it.id == id } ?: OFF
    }
}

data class DesyncParams(
    val method: DesyncMethod = DesyncMethod.SPLIT_N,
    val splitN: Int = 5,
    /** bytes to skip into the SNI hostname before cutting (default 2) */
    val splitAt: Int = 2,
    val fragmentCount: Int = 4,
    val delayMs: Int = 25,
    val fakeSni: String = "auth.vercel.com",
    val rebindPauseMs: Int = 20,
)

/**
 * No-root desync applied on a plain (kernel) socket: the kernel builds the
 * packets, we only control how the ClientHello bytes are segmented on the
 * wire. This is the same family of techniques the reference Android app
 * achieves through Xray's fragment.
 */
class NoRootDesync(
    private val params: DesyncParams,
    private val log: (String) -> Unit,
    private val onFragment: (Int) -> Unit = {},
) {

    /**
     * Applies the configured method to [firstPayload] (the ClientHello).
     * May replace the socket (fake_rst_rebind) — the returned socket must be
     * used for the rest of the connection. Returns the (possibly rewritten)
     * payload that was actually written.
     */
    suspend fun apply(
        socket: Socket,
        dst: InetSocketAddress,
        firstPayload: ByteArray,
    ): Socket = withContext(Dispatchers.IO) {
        val inspect = ClientHelloParser.inspect(firstPayload)
        if (inspect.sniEncrypted) {
            // The real server name is inside an Encrypted ClientHello; what sits
            // in the visible SNI field is a decoy chosen by the client. Rewriting
            // it cannot influence which host the censor thinks we visit, so say
            // so instead of pretending sni_replace did something.
            log("desync: SNI is encrypted (ECH/ESNI) — sni_replace has no target here")
        }

        when (params.method) {
            DesyncMethod.OFF -> {
                writeAll(socket, firstPayload, 0, firstPayload.size)
                socket
            }

            DesyncMethod.SPLIT_N -> {
                val n = params.splitN.coerceIn(1, firstPayload.size - 1)
                writeAll(socket, firstPayload, 0, n)
                log("desync: split first $n bytes")
                writeAll(socket, firstPayload, n, firstPayload.size - n)
                socket
            }

            DesyncMethod.SPLIT_SNI -> {
                val sni = ClientHelloParser.findSni(firstPayload)
                if (sni == null) {
                    writeAll(socket, firstPayload, 0, firstPayload.size)
                } else {
                    val cut = sni.offset.coerceIn(1, firstPayload.size - 1)
                    writeAll(socket, firstPayload, 0, cut)
                    log("desync: split before SNI at $cut (${sni.host})")
                    writeAll(socket, firstPayload, cut, firstPayload.size - cut)
                }
                socket
            }

            DesyncMethod.MULTI_FRAG -> {
                val parts = params.fragmentCount.coerceIn(2, 64)
                val chunk = (firstPayload.size + parts - 1) / parts
                var off = 0
                while (off < firstPayload.size) {
                    val n = minOf(chunk, firstPayload.size - off)
                    writeAll(socket, firstPayload, off, n)
                    off += n
                }
                log("desync: ${parts} fragments")
                socket
            }

            DesyncMethod.DELAYED -> {
                val n = params.splitN.coerceIn(1, firstPayload.size - 1)
                writeAll(socket, firstPayload, 0, n)
                delay(params.delayMs.toLong().coerceIn(1, 500))
                log("desync: split $n bytes + ${params.delayMs}ms delay")
                writeAll(socket, firstPayload, n, firstPayload.size - n)
                socket
            }

            DesyncMethod.SNI_REPLACE -> {
                if (!inspect.sniReplaceable) {
                    // Nothing to rewrite: no visible SNI, or it is a decoy under ECH.
                    log("desync: no rewritable SNI — sending the ClientHello untouched")
                    writeAll(socket, firstPayload, 0, firstPayload.size)
                    return@withContext socket
                }
                val rewritten = TlsParser.replaceSni(firstPayload, params.fakeSni)
                if (rewritten != null) {
                    log("desync: SNI rewritten to ${params.fakeSni}")
                    onFragment(1)
                    writeAll(socket, rewritten, 0, rewritten.size)
                } else {
                    log("desync: SNI rewrite failed, sending original")
                    writeAll(socket, firstPayload, 0, firstPayload.size)
                }
                socket
            }

            DesyncMethod.COMBINED -> {
                val rewritten = if (inspect.sniReplaceable) {
                    TlsParser.replaceSni(firstPayload, params.fakeSni)
                } else {
                    null
                }
                if (rewritten == null) {
                    log("desync: combined rewrite unavailable, plain split")
                    val n = params.splitN.coerceIn(1, firstPayload.size - 1)
                    writeAll(socket, firstPayload, 0, n)
                    delay(params.delayMs.toLong().coerceIn(0, 500))
                    writeAll(socket, firstPayload, n, firstPayload.size - n)
                } else {
                    val chunks = TcpFragmenter.planForPayload(
                        DesyncMethod.SPLIT_SNI, params, rewritten
                    )
                    onFragment(chunks.size)
                    log("desync: combined (SNI=${params.fakeSni}, ${chunks.size} chunks)")
                    for (c in chunks) {
                        writeAll(socket, rewritten, c.offset, c.length)
                        if (c.delayAfterMs > 0) delay(c.delayAfterMs.toLong())
                    }
                }
                socket
            }

            DesyncMethod.FAKE_RST_REBIND -> {
                val localPort = socket.localPort
                // 1) a valid ClientHello with the allowed fake SNI (DPI sees it first)
                val fake = ClientHelloForge.build(params.fakeSni)
                writeAll(socket, fake, 0, fake.size)
                // 2) let it reach the DPI, then kill the socket with a RST
                delay(params.rebindPauseMs.toLong().coerceIn(5, 200))
                runCatching { socket.setSoLinger(true, 0) }
                runCatching { socket.close() }
                // 3) reconnect reusing the same local 4-tuple (no TIME_WAIT after RST)
                val fresh = Socket()
                fresh.tcpNoDelay = true
                fresh.setReuseAddress(true)
                fresh.bind(InetSocketAddress(localPort))
                log("desync: fake SNI sent, RST, rebind port $localPort")
                withTimeoutOrNull(5000) {
                    fresh.connect(dst, 5000)
                } ?: throw IOException("rebind connect timeout")
                // 4) the real ClientHello
                writeAll(fresh, firstPayload, 0, firstPayload.size)
                fresh
            }

            DesyncMethod.WRONG_SEQ -> socket // handled by RootDesync, not here
        }
    }

    private fun writeAll(socket: Socket, data: ByteArray, off: Int, len: Int) {
        val out = socket.getOutputStream()
        // OutputStream.write(byte[], off, len) sends exactly len bytes or throws
        out.write(data, off, len)
        out.flush()
    }
}
