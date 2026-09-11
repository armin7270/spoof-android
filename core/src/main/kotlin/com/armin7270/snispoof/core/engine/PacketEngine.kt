package com.armin7270.snispoof.core.engine

import com.armin7270.snispoof.core.packet.IpPacket
import com.armin7270.snispoof.core.packet.PacketBuilder
import com.armin7270.snispoof.core.tcpip.FlowListener
import com.armin7270.snispoof.core.tcpip.PacketSink
import com.armin7270.snispoof.core.tcpip.TcpFlow
import com.armin7270.snispoof.core.tcpip.TcpStack
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong

/** Engine-wide counters shown in the UI. */
class EngineCounters {
    val packetsInspected = AtomicLong(0)
    val packetsDropped = AtomicLong(0)
    val fragmentsInjected = AtomicLong(0)
    val clientHellosSeen = AtomicLong(0)
    val activeFlows = AtomicLong(0)

    fun reset() {
        packetsInspected.set(0)
        packetsDropped.set(0)
        fragmentsInjected.set(0)
        clientHellosSeen.set(0)
        activeFlows.set(0)
    }
}

/** Callback the app registers to relay an accepted TCP flow. */
fun interface RelayStarter {
    fun start(flow: TcpFlow, decision: ProfileRouter.Decision)
}

/**
 * The user-space packet engine — the Android replacement for the WinDivert
 * loop of the patterniha core. Every raw IP packet read from the TUN device
 * flows into [onPacket]; TCP is terminated by the userspace stack, DNS is
 * answered locally (AAAA sunk to force IPv4) and the rest of UDP is NAT-ed
 * through protected datagram sockets.
 */
class PacketEngine(
    private val scope: CoroutineScope,
    sink: PacketSink,
    private val protector: SocketProtector,
    private val router: ProfileRouter,
    private val resolver: DohResolver,
    private val tunIp: Int,
    mtu: Int,
    @Volatile var blockQuic: Boolean,
    private val stats: TrafficStats,
    private val counters: EngineCounters,
    private val relay: RelayStarter,
    private val log: (String) -> Unit,
) {
    val dnsServer = DnsServer(
        scope = scope,
        resolver = resolver,
        fallbackResolvers = listOf(
            DohResolver(DohProvider.GOOGLE, protector, log),
            DohResolver(DohProvider.CLOUDFLARE, protector, log, preferUdp = true),
            DohResolver(DohProvider.GOOGLE, protector, log, preferUdp = true),
        ),
        tunIp = tunIp,
        sink = sink,
        log = log,
    )
    val udpForwarder = UdpForwarder(protector, sink, tunIp, stats, blockQuic, log)
    val tcpStack = TcpStack(sink, mtu, FlowListener { flow -> onTcpFlow(flow) }, log)
    private fun onTcpFlow(flow: TcpFlow) {
        counters.activeFlows.incrementAndGet()
        val decision = router.pick(flow.dstIp, flow.dstPort, dnsServer.hostnamesFor(flow.dstIp))
        relay.start(flow, decision)
    }

    /** Single entry point for the TUN reader loop. Bounds-checked. */
    fun onPacket(data: ByteArray, offset: Int, length: Int) {
        counters.packetsInspected.incrementAndGet()
        if (offset < 0 || length < 20 || offset + length > data.size) {
            counters.packetsDropped.incrementAndGet()
            return
        }
        when (data[offset].toInt() ushr 4) {
            4 -> handleV4(IpPacket(data, offset, length))
            6 -> {
                // no IPv6 route inside the tunnel: tell apps to use IPv4
                counters.packetsDropped.incrementAndGet()
                runCatching {
                    if (length >= 48) {
                        val src = data.copyOfRange(offset + 8, offset + 24)
                        val dst = data.copyOfRange(offset + 24, offset + 40)
                        val quoted = minOf(length, 200)
                        tcpStack.sink.send(
                            PacketBuilder.icmpv6Unreachable(dst, src, data, offset, quoted)
                        )
                    }
                }
            }
            else -> counters.packetsDropped.incrementAndGet()
        }
    }

    private fun handleV4(p: IpPacket) {
        val ihl = p.ihl
        val total = p.totalLength
        if (ihl < 20 || total < ihl || total > p.length) {
            counters.packetsDropped.incrementAndGet()
            return
        }
        when (p.protocol) {
            6 -> tcpStack.dispatch(p)
            17 -> {
                if (p.l4Offset + 8 > p.offset + total) {
                    counters.packetsDropped.incrementAndGet()
                    return
                }
                if (p.dstPort == 53) {
                    dnsServer.handleQuery(p)
                } else {
                    udpForwarder.handle(p)
                }
            }
            1 -> { /* ICMP: ignore */ }
            else -> counters.packetsDropped.incrementAndGet()
        }
    }

    /** Periodic maintenance (retransmits, idle UDP cleanup). */
    fun tick() {
        tcpStack.tick()
        udpForwarder.tick()
        counters.activeFlows.set(tcpStack.activeFlows().count { it.state == TcpFlow.State.ESTABLISHED || it.state == TcpFlow.State.SYN_RECEIVED }.toLong())
    }

    fun shutdown() {
        tcpStack.shutdown()
        udpForwarder.close()
    }
}
