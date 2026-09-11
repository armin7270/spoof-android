package com.armin7270.snispoof.core

import com.armin7270.snispoof.core.packet.Flags
import com.armin7270.snispoof.core.packet.Ip4
import com.armin7270.snispoof.core.packet.IpPacket
import com.armin7270.snispoof.core.packet.PacketBuilder
import com.armin7270.snispoof.core.tcpip.TcpStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The guest speaks last, the server speaks first: SSH, SMTP, IMAP, POP3, IRC and
 * most proxy protocols send their banner before the client sends anything.
 *
 * In that case the only segment the guest has sent is the final handshake ACK, so
 * the stack must already know the peer's receive window from it. If it does not,
 * `transmit()` refuses to send anything (window 0), `processAck()` never runs so
 * nothing ever calls it again, the retransmit probe skips segments that were
 * never sent — and the connection stalls forever while the send queue grows.
 */
class TcpStackServerFirstTest {

    private val guestIp = Ip4.parse("10.0.2.15")
    private val tunIp = Ip4.parse("198.18.0.1")
    private val dstIp = Ip4.parse("203.0.113.9")
    private val guestPort = 41000
    private val dstPort = 22

    @Test
    fun `relay data reaches the guest when the server speaks first`() {
        val fromStack = LinkedBlockingQueue<ByteArray>()
        val handshakeDone = java.util.concurrent.CountDownLatch(1)

        val stack = TcpStack(
            sink = { pkt -> fromStack.put(pkt) },
            mtu = 1280,
            listener = { flow ->
                // Server-speaks-first: write immediately, read nothing.
                Thread {
                    flow.write("SSH-2.0-OpenSSH_9.6\r\n".toByteArray())
                    handshakeDone.countDown()
                }.apply { isDaemon = true }.start()
            },
        )

        fun guest(flags: Int, seq: Long, ack: Long, payload: ByteArray? = null, window: Int = 65535) {
            val pkt = PacketBuilder.tcp(
                srcIp = guestIp, dstIp = tunIp, srcPort = guestPort, dstPort = dstPort,
                seq = seq, ack = ack, flags = flags, window = window,
                payload = payload, psh = payload != null,
            )
            stack.dispatch(IpPacket(pkt, 0, pkt.size))
        }

        // ---- handshake: SYN, SYN-ACK, then ONLY a bare final ACK from the guest ----
        val guestIsn = 5000L
        guest(Flags.SYN, seq = guestIsn, ack = 0)
        val synAck = fromStack.poll(5, TimeUnit.SECONDS)
        assertTrue("no SYN-ACK", synAck != null)
        val sa = IpPacket(synAck!!, 0, synAck.size)
        assertTrue(sa.hasFlag(Flags.SYN) && sa.hasFlag(Flags.ACK))
        val iss = sa.tcpSeq
        val guestSeq = (guestIsn + 1) and 0xffffffffL

        // the guest's window is advertised in this ACK — the stack must remember it
        guest(Flags.ACK, seq = guestSeq, ack = (iss + 1) and 0xffffffffL, window = 64240)

        assertTrue("relay never wrote", handshakeDone.await(5, TimeUnit.SECONDS))

        // keep the stack's timers running, exactly as PacketEngine.tick() does
        var data: IpPacket? = null
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline && data == null) {
            stack.tick()
            val raw = fromStack.poll(250, TimeUnit.MILLISECONDS) ?: continue
            val p = IpPacket(raw, 0, raw.size)
            if (p.tcpPayloadLength > 0) data = p
        }

        val delivered = data
        assertTrue(
            "server-speaks-first data never reached the guest (stalled send queue)",
            delivered != null,
        )
        val text = String(
            delivered!!.data, delivered.tcpPayloadOffset, delivered.tcpPayloadLength, Charsets.UTF_8
        )
        assertEquals("SSH-2.0-OpenSSH_9.6\r\n", text)
    }

    @Test
    fun `segments larger than one mss are split and delivered in order`() {
        val fromStack = LinkedBlockingQueue<ByteArray>()
        val stack = TcpStack(
            sink = { pkt -> fromStack.put(pkt) },
            mtu = 1280,
            listener = { flow ->
                Thread {
                    // 3 x MSS worth of data pushed at once
                    flow.write(ByteArray(3800) { (it % 251).toByte() })
                }.apply { isDaemon = true }.start()
            },
        )

        fun guest(flags: Int, seq: Long, ack: Long, window: Int = 65535) {
            val pkt = PacketBuilder.tcp(
                srcIp = guestIp, dstIp = tunIp, srcPort = guestPort + 1, dstPort = dstPort,
                seq = seq, ack = ack, flags = flags, window = window, payload = null, psh = false,
            )
            stack.dispatch(IpPacket(pkt, 0, pkt.size))
        }

        guest(Flags.SYN, seq = 7000L, ack = 0)
        val saRaw = fromStack.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no SYN-ACK")
        val sa = IpPacket(saRaw, 0, saRaw.size)
        val iss = sa.tcpSeq
        guest(Flags.ACK, seq = 7001L, ack = (iss + 1) and 0xffffffffL, window = 64240)

        var assembled = ByteArray(0)
        val deadline = System.currentTimeMillis() + 15000
        while (assembled.size < 3800 && System.currentTimeMillis() < deadline) {
            stack.tick()
            val raw = fromStack.poll(300, TimeUnit.MILLISECONDS) ?: continue
            val p = IpPacket(raw, 0, raw.size)
            if (p.tcpPayloadLength > 0) {
                assembled += p.data.copyOfRange(p.tcpPayloadOffset, p.tcpPayloadOffset + p.tcpPayloadLength)
                guest(Flags.ACK, seq = 7001L, ack = (p.tcpSeq + p.tcpPayloadLength) and 0xffffffffL)
            }
        }
        assertEquals("expected all 3800 bytes across multiple segments", 3800, assembled.size)
        for (i in 0 until 3800) assertEquals("byte $i out of order", (i % 251).toByte(), assembled[i])
    }
}
