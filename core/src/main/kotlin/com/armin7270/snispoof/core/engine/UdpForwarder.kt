package com.armin7270.snispoof.core.engine

import com.armin7270.snispoof.core.packet.Ip4
import com.armin7270.snispoof.core.packet.IpPacket
import com.armin7270.snispoof.core.packet.PacketBuilder
import com.armin7270.snispoof.core.tcpip.PacketSink
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Session-wide traffic counters. */
class TrafficStats {
    val startAt = System.currentTimeMillis()
    val upBytes = AtomicLong(0)
    val downBytes = AtomicLong(0)
    @Volatile var lastPingMs: Long = -1

    fun up(n: Int) = upBytes.addAndGet(n.toLong())
    fun down(n: Int) = downBytes.addAndGet(n.toLong())

    fun reset() {
        upBytes.set(0); downBytes.set(0)
    }
}

/**
 * NAT for general UDP traffic (non-DNS) across the TUN: one protected
 * DatagramSocket per flow, with idle expiry. QUIC (UDP 443) can be blocked —
 * apps then fall back to TCP where the SNI desync applies.
 */
class UdpForwarder(
    private val protector: SocketProtector,
    private val sink: PacketSink,
    private val tunIp: Int,
    private val stats: TrafficStats,
    @Volatile var blockQuic: Boolean,
    private val log: (String) -> Unit,
) : java.io.Closeable {

    private data class FlowKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

    private val flows = ConcurrentHashMap<FlowKey, DatagramSocket>()
    private val lastUse = ConcurrentHashMap<FlowKey, AtomicLong>()

    fun handle(p: IpPacket) {
        val off = p.udpPayloadOffset
        val len = p.udpPayloadLength
        if (len <= 0) return

        if (blockQuic && p.dstPort == 443) {
            // tell the app the port is unreachable so it falls back to TCP fast
            runCatching {
                sink.send(PacketBuilder.icmpUnreachable(tunIp, p.srcIp, p.data, p.offset, minOf(p.length, 64)))
            }
            return
        }

        val key = FlowKey(p.srcIp, p.srcPort, p.dstIp, p.dstPort)
        val sock = flows[key] ?: createFlow(key) ?: return
        lastUse[key]?.set(System.currentTimeMillis())
        val data = p.data.copyOfRange(off, off + len)
        try {
            val addr = InetAddress.getByAddress(Ip4.bytes(p.dstIp))
            sock.send(DatagramPacket(data, data.size, addr, p.dstPort))
            stats.up(len)
        } catch (e: Exception) {
            removeFlow(key)
        }
    }

    private fun createFlow(key: FlowKey): DatagramSocket? = runCatching {
        val sock = DatagramSocket(null)
        protector.protectSocket(sock)
        sock.reuseAddress = true
        sock.bind(null)
        sock.soTimeout = 60_000
        // Pin the socket to the flow's peer: after connect() the OS drops any
        // datagram whose source does not match, so a spoofed reply can neither
        // be delivered to the app nor counted as its traffic.
        sock.connect(InetAddress.getByAddress(Ip4.bytes(key.dstIp)), key.dstPort)
        flows[key] = sock
        lastUse[key] = AtomicLong(System.currentTimeMillis())
        val self = this
        Thread({
            val buf = ByteArray(65535)
            while (true) {
                try {
                    val resp = DatagramPacket(buf, buf.size)
                    sock.receive(resp)
                    // connected socket: source is always key.dstIp/key.dstPort
                    val pkt = PacketBuilder.udp(
                        srcIp = key.dstIp, dstIp = key.srcIp,
                        srcPort = key.dstPort, dstPort = key.srcPort,
                        payload = buf.copyOfRange(0, resp.length),
                    )
                    self.sink.send(pkt)
                    self.stats.down(resp.length)
                    self.lastUse[key]?.set(System.currentTimeMillis())
                } catch (e: java.net.SocketTimeoutException) {
                    break
                } catch (e: Exception) {
                    break
                }
            }
            self.removeFlow(key)
        }, "udp-fwd-${key.srcPort}-${key.dstPort}").apply { isDaemon = true }.start()
        sock
    }.getOrNull()

    private fun removeFlow(key: FlowKey) {
        val sock = flows.remove(key)
        lastUse.remove(key)
        runCatching { sock?.close() }
    }

    /** Close flows idle for more than [idleMs] milliseconds. */
    fun tick(idleMs: Long = 90_000) {
        val now = System.currentTimeMillis()
        for ((key, ts) in lastUse) {
            if (now - ts.get() > idleMs) removeFlow(key)
        }
    }

    override fun close() {
        for (key in flows.keys.toList()) removeFlow(key)
    }
}
