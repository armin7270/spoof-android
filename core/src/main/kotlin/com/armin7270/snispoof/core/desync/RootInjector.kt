package com.armin7270.snispoof.core.desync

import com.armin7270.snispoof.core.util.Io
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kotlin side of the root helper protocol (the faithful port of patterniha's
 * `FakeTcpInjector`). The helper binary runs as root via `su`, sniffs the
 * handshake of a protected socket with AF_PACKET and injects the fake
 * ClientHello with seq = (syn_seq + 1 - len(fake)) & 0xffffffff — exactly what
 * fake_tcp.py does through WinDivert on Windows.
 *
 * Wire protocol (stdin/stdout, line based):
 *   -> WATCH <id> <srcIp> <srcPort> <dstIp> <dstPort> <hexFakeData>
 *   <- RESULT <id> OK
 *   <- RESULT <id> FAIL <reason>
 *   -> PING            <- PONG
 */
class RootInjector(private val helperPath: String, private val log: (String) -> Unit) {

    private val procLock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val ids = AtomicInteger(1)
    @Volatile private var dead = false

    suspend fun start(): Boolean {
        // The health check suspends, so it must happen OUTSIDE the monitor.
        val spawned = synchronized(procLock) {
            if (process != null && process!!.isAlive) return true
            try {
                val p = ProcessBuilder("su", "-c", helperPath)
                    .redirectErrorStream(false)
                    .start()
                process = p
                writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.US_ASCII))
                dead = false
                val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.US_ASCII))
                Thread({
                    try {
                        while (!dead) {
                            val line = reader.readLine() ?: break
                            handleLine(line.trim())
                        }
                    } catch (_: Exception) {
                    } finally {
                        dead = true
                        for (d in pending.values) d.complete(false)
                        pending.clear()
                    }
                }, "root-injector-reader").apply { isDaemon = true }.start()
                true
            } catch (e: Exception) {
                log("root helper start failed: ${e.message}")
                false
            }
        }
        if (!spawned) return false
        // start() runs off the main thread, so this is a bounded coroutine wait
        // rather than a runBlocking() stall.
        val ok = runCatching { ping() }.getOrDefault(false)
        if (!ok) log("root helper: no PONG")
        return ok
    }

    private fun handleLine(line: String) {
        if (line == "PONG") {
            pending.remove(-1)?.complete(true)
            return
        }
        if (line.startsWith("RESULT ")) {
            val parts = line.split(" ", limit = 3)
            val id = parts.getOrNull(1)?.toIntOrNull() ?: return
            val ok = parts.getOrNull(2)?.trim()?.startsWith("OK") == true
            if (!ok) log("root helper: ${parts.getOrNull(2) ?: "fail"}")
            pending.remove(id)?.complete(ok)
        } else if (line.startsWith("LOG ")) {
            log("helper: ${line.removePrefix("LOG ")}")
        }
    }

    private suspend fun ping(): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending[-1] = d
        return try {
            send("PING")
            withTimeoutOrNull(2000) { d.await() } ?: false
        } finally {
            pending.remove(-1)
        }
    }

    /**
     * Installs the sniffer for one connection's 4-tuple BEFORE the TCP connect
     * and waits for the fake-packet ACK afterwards. Returns true when the DPI
     * has been fed the fake ClientHello and the server is talking to us.
     */
    suspend fun watchAndInject(
        srcIp: String, srcPort: Int, dstIp: String, dstPort: Int,
        fakeData: ByteArray, timeoutMs: Long = 2500,
    ): Boolean {
        if (dead && !start()) return false
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<Boolean>()
        pending[id] = d
        val hex = com.armin7270.snispoof.core.util.Hex.encode(fakeData)
        send("WATCH $id $srcIp $srcPort $dstIp $dstPort $hex")
        val result = withTimeoutOrNull(timeoutMs) { d.await() }
        if (result == null) {
            pending.remove(id)
            send("CANCEL $id")
        }
        return result == true
    }

    private fun send(line: String) {
        synchronized(procLock) {
            val w = writer ?: throw IllegalStateException("helper not started")
            w.write(line)
            w.newLine()
            w.flush()
        }
    }

    fun stop() {
        synchronized(procLock) {
            dead = true
            runCatching { writer?.let { it.write("EXIT"); it.newLine(); it.flush() } }
            runCatching { process?.destroy() }
            writer = null
            process = null
        }
    }
}
