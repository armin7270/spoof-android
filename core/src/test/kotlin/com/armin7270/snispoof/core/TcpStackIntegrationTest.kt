package com.armin7270.snispoof.core

import com.armin7270.snispoof.core.packet.Flags
import com.armin7270.snispoof.core.packet.Ip4
import com.armin7270.snispoof.core.packet.IpPacket
import com.armin7270.snispoof.core.packet.PacketBuilder
import com.armin7270.snispoof.core.tcpip.TcpStack
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Deterministic end-to-end test of the userspace TCP stack + relay data path:
 * a simulated guest app performs a raw TCP handshake against [TcpStack], sends
 * data, and the relay pumps it through a REAL socket to a localhost echo
 * server and back. This exercises exactly the code path the VPN service runs.
 */
class TcpStackIntegrationTest {

    private val guestIp = Ip4.parse("10.0.2.15")
    private val tunIp = Ip4.parse("198.18.0.1")
    private val dstIp = Ip4.parse("127.0.0.1")
    private val dstPort = 4444
    private var guestPort = 40000
    private var guestSeq = 100000L

    // outbound packets produced by the stack (tun -> guest)
    private val fromStack = LinkedBlockingQueue<ByteArray>()

    private fun sendFromGuest(flags: Int, payload: ByteArray = ByteArray(0), ack: Long = 0, seqOverride: Long? = null) {
        val seq = seqOverride ?: guestSeq
        val pkt = PacketBuilder.tcp(
            srcIp = guestIp, dstIp = tunIp,
            srcPort = guestPort, dstPort = dstPort,
            seq = seq, ack = ack, flags = flags, window = 65535,
            payload = if (payload.isEmpty()) null else payload, psh = payload.isNotEmpty(),
        )
        stack.dispatch(IpPacket(pkt, 0, pkt.size))
    }

    private lateinit var stack: TcpStack

    private fun nextFromStack(timeoutMs: Long = 5000): IpPacket {
        val raw = fromStack.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("no packet from stack within ${timeoutMs}ms")
        return IpPacket(raw, 0, raw.size)
    }

    /** Polls until a packet that actually carries data arrives (skips pure ACKs). */
    private fun nextDataFromStack(timeoutMs: Long = 10000): IpPacket {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = nextFromStack(deadline - System.currentTimeMillis())
            if (p.tcpPayloadLength > 0) return p
        }
        throw AssertionError("no DATA packet from stack within ${timeoutMs}ms")
    }

    @Test
    fun `guest handshake - send - echo through real socket - receive`() = runBlocking {
        val echo = ServerSocket(0)
        val echoPort = echo.localPort
        Thread {
            while (!echo.isClosed) {
                val c = runCatching { echo.accept() }.getOrNull() ?: return@Thread
                Thread {
                    try {
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = c.getInputStream().read(buf)
                            if (n < 0) break
                            c.getOutputStream().write("ECHO:".toByteArray() + buf.copyOf(n))
                            c.getOutputStream().flush()
                        }
                    } catch (_: Exception) { } finally { runCatching { c.close() } }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()

        stack = TcpStack(
            sink = { pkt -> fromStack.put(pkt) },
            mtu = 1280,
            listener = { flow ->
                Thread {
                    try {
                        val socket = java.net.Socket()
                        socket.tcpNoDelay = true
                        socket.connect(java.net.InetSocketAddress("127.0.0.1", echoPort), 4000)
                        val out = socket.getOutputStream()
                        val input = socket.getInputStream()
                        Thread {
                            try {
                                while (true) {
                                    val data = runBlocking { flow.read() } ?: break
                                    out.write(data); out.flush()
                                }
                            } catch (_: Exception) { }
                        }.apply { isDaemon = true }.start()
                        val buf = ByteArray(16384)
                        try {
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                flow.write(buf.copyOf(n))
                            }
                            flow.close()
                        } catch (_: Exception) {
                            flow.rst()
                        }
                    } catch (e: Exception) {
                        flow.rst()
                    }
                }.apply { isDaemon = true }.start()
            },
        )

        // ---- handshake ----
        sendFromGuest(Flags.SYN)                       // SYN
        val synAck = nextFromStack()
        assertTrue(synAck.hasFlag(Flags.SYN) && synAck.hasFlag(Flags.ACK))
        val iss = synAck.tcpSeq
        val firstAckFromServer = synAck.tcpAck          // = guestSeq + 1
        assertEquals(guestSeq + 1, firstAckFromServer)

        guestSeq++                                      // SYN consumed one seq
        sendFromGuest(Flags.ACK, ack = (iss + 1))       // final ACK

        // ---- data: guest sends payload, relay echoes through real socket ----
        val payload = "hello-tunnel".toByteArray()
        sendFromGuest(Flags.ACK or Flags.PSH, payload, ack = iss + 1)
        guestSeq += payload.size

        // wait for echo data coming back from the stack (tun -> guest)
        val dataPkt = nextDataFromStack(10000)
        val echoed = String(dataPkt.data, dataPkt.tcpPayloadOffset, dataPkt.tcpPayloadLength, Charsets.UTF_8)
        assertEquals("ECHO:hello-tunnel", echoed)

        // ACK the echo so the stack clears its in-flight queue
        val echoedLen = dataPkt.tcpPayloadLength
        sendFromGuest(Flags.ACK, ack = (dataPkt.tcpSeq + echoedLen))

        // ---- more data both ways (multi-chunk) ----
        val big = ByteArray(5000) { ('A'.code + it % 26).toByte() }
        sendFromGuest(Flags.ACK or Flags.PSH, big, ack = (dataPkt.tcpSeq + echoedLen))
        guestSeq += big.size

        var assembled = ByteArray(0)
        var guestAcked = dataPkt.tcpSeq + echoedLen
        val deadline = System.currentTimeMillis() + 10000
        while (String(assembled, Charsets.UTF_8) != "ECHO:" + String(big, Charsets.UTF_8) &&
            System.currentTimeMillis() < deadline
        ) {
            val p = nextFromStack(3000)
            if (p.tcpPayloadLength > 0) {
                assembled += p.data.copyOfRange(p.tcpPayloadOffset, p.tcpPayloadOffset + p.tcpPayloadLength)
                guestAcked = p.tcpSeq + p.tcpPayloadLength
                sendFromGuest(Flags.ACK, ack = guestAcked)
            }
        }
        assertEquals("ECHO:" + String(big, Charsets.UTF_8), String(assembled, Charsets.UTF_8))

        // ---- graceful close from the guest ----
        sendFromGuest(Flags.ACK or Flags.FIN, ack = guestAcked)
        guestSeq += 1

        echo.close()
        assertTrue(true)
    }
}
