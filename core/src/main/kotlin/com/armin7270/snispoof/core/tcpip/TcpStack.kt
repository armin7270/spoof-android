package com.armin7270.snispoof.core.tcpip

import com.armin7270.snispoof.core.packet.Flags
import com.armin7270.snispoof.core.packet.Ip4
import com.armin7270.snispoof.core.packet.IpPacket
import com.armin7270.snispoof.core.packet.PacketBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

fun interface PacketSink {
    /** Inject a fully built packet into the TUN device (inbound direction). */
    fun send(packet: ByteArray)
}

fun interface FlowListener {
    fun onFlow(flow: TcpFlow)
}

/** TCP sequence comparison honoring 32-bit wrap. */
internal fun seqLess(a: Long, b: Long): Boolean = ((b - a) and 0xffffffffL) in 1..0x7fffffffL
internal fun seqLessEq(a: Long, b: Long): Boolean = a == b || seqLess(a, b)

private val ISS_COUNTER = AtomicInteger(0x2a4f1c)

/**
 * One TCP connection terminated on the TUN side (the patterniha relay's
 * `incoming_sock` equivalent). Thread-safe: the TUN reader thread calls
 * [handle], the relay coroutine calls [read]/[write]/[close]/[rst].
 */
class TcpFlow internal constructor(
    val srcIp: Int,
    val srcPort: Int,
    val dstIp: Int,
    val dstPort: Int,
    private val stack: TcpStack,
) {
    enum class State { SYN_RECEIVED, ESTABLISHED, CLOSED }

    @Volatile var state: State = State.SYN_RECEIVED
        private set

    // receive side (app -> relay)
    private var rcvNext: Long = 0
    private val outOfOrder = TreeMap<Long, ByteArray>()
    // Bounded receive queue (~600 KB at max MSS) so a stalled relay exerts TCP
    // flow control instead of growing the heap; see bufferForRelay().
    private val rcvChannel = Channel<ByteArray>(512)
    @Volatile private var finReceived = false
    private var pendingFin: Long = -1L

    // send side (relay -> app)
    private var iss: Long = 0
    private var sndUna: Long = 0
    private var sndNext: Long = 0
    private var peerWindow = 0
    private var finSent = false
    private var finSeq: Long = 0
    @Volatile private var finAcked = false
    private var synSeq: Long = -1L
    private var synAckSent = false
    @Volatile private var lastProbeAt: Long = 0L

    class Segment(val seq: Long, val data: ByteArray, val fin: Boolean) {
        var sentAt: Long = 0
        var retries: Int = 0
    }
    private val sendQueue = ArrayDeque<Segment>()

    @Volatile var lastActivity: Long = System.currentTimeMillis()
        private set
    @Volatile var bytesUp: Long = 0; private set
    @Volatile var bytesDown: Long = 0; private set

    private val windowSignal = Channel<Unit>(Channel.CONFLATED)
    private val mss: Int get() = stack.mss

    // ------------------------------------------------------------------ in

    /** Called by the TUN reader thread (single-threaded) with packets for this flow. */
    internal fun handle(p: IpPacket) {
        lastActivity = System.currentTimeMillis()
        val flags = p.tcpFlags
        val seq = p.tcpSeq
        val ack = p.tcpAck
        val payLen = p.tcpPayloadLength

        if (flags and Flags.RST != 0) {
            abortByPeer()
            return
        }

        when (state) {
            State.SYN_RECEIVED -> {
                if (flags and Flags.SYN != 0) {
                    if (synSeq == -1L || synSeq != seq) {
                        // first SYN (or a client restarting with a new ISN)
                        synSeq = seq
                        synAckSent = false
                    }
                    // retransmitted SYN: re-send the SAME SYN-ACK (same ISS)
                    sendSynAck(seq)
                    return
                }
                if (flags and Flags.ACK != 0 && ack == sndNext) {
                    state = State.ESTABLISHED
                    // This ACK advertises the peer's receive window and is the only
                    // one we may ever see for server-speaks-first protocols (SSH,
                    // SMTP, IMAP, ...). Without recording it here, transmit()
                    // refuses to send because peerWindow is still 0, processAck()
                    // never runs again and the flow stalls with an ever-growing
                    // send queue.
                    synchronized(this) { peerWindow = p.tcpWindow }
                    stack.listener?.onFlow(this)
                    if (payLen > 0) receiveData(seq, payLen, p)
                }
                // anything else before the handshake completes: ignored
            }

            State.ESTABLISHED -> {
                if (flags and Flags.SYN != 0) return // stray SYN
                if (flags and Flags.ACK != 0) processAck(ack, p.tcpWindow)
                if (payLen > 0) receiveData(seq, payLen, p)
                if (flags and Flags.FIN != 0) receiveFin(seq, payLen)
            }

            State.CLOSED -> { /* nothing */ }
        }
    }

    private fun receiveData(seq: Long, payLen: Int, p: IpPacket) {
        val payOff = p.tcpPayloadOffset
        if (payLen <= 0) return
        val end = (seq + payLen) and 0xffffffffL

        if (seq == rcvNext) {
            val chunk = p.data.copyOfRange(payOff, payOff + payLen)
            if (!bufferForRelay(chunk)) return // backpressure: don't ACK what we can't hold
            rcvNext = end
            drainOutOfOrder()
        } else if (seqLess(rcvNext, seq)) {
            // future data: buffer it (bounded)
            if (outOfOrder.size < 512) outOfOrder[seq] = p.data.copyOfRange(payOff, payOff + payLen)
        } else if (seqLess(rcvNext, end)) {
            // partial overlap: clip the fresh tail
            val skip = (rcvNext - seq).toInt()
            val chunk = p.data.copyOfRange(payOff + skip, payOff + payLen)
            if (!bufferForRelay(chunk)) return
            rcvNext = end
            drainOutOfOrder()
        }
        // else: pure retransmission of already-received data -> just re-ACK
        sendAck()
    }

    /**
     * Queues a chunk for the relay, reporting whether it fits.
     *
     * The queue is bounded so a relay that stops reading cannot grow the heap.
     * When it is full the segment is deliberately left unacknowledged: the ACK we
     * send still covers the old [rcvNext], so the peer retransmits — standard TCP
     * flow control — instead of the data being dropped silently.
     */
    private fun bufferForRelay(chunk: ByteArray): Boolean {
        if (!rcvChannel.trySend(chunk).isSuccess) return false
        bytesDown += chunk.size
        return true
    }

    private fun drainOutOfOrder() {
        while (true) {
            val first = outOfOrder.firstEntry() ?: break
            val seq = first.key
            val chunk = first.value
            val end = (seq + chunk.size) and 0xffffffffL
            if (seq == rcvNext) {
                if (!bufferForRelay(chunk)) return
                rcvNext = end
                outOfOrder.remove(seq)
            } else if (seqLess(seq, rcvNext) && seqLess(rcvNext, end)) {
                val skip = (rcvNext - seq).toInt()
                if (!bufferForRelay(chunk.copyOfRange(skip, chunk.size))) return
                rcvNext = end
                outOfOrder.remove(seq)
            } else break
        }
        if (pendingFin >= 0 && pendingFin == rcvNext) {
            val f = pendingFin
            pendingFin = -1
            receiveFin(f, 0)
        }
    }

    private fun receiveFin(seq: Long, payLen: Int) {
        val finSeq = (seq + payLen) and 0xffffffffL
        if (finSeq == rcvNext) {
            finReceived = true
            rcvNext = (finSeq + 1) and 0xffffffffL
            sendAck()
            rcvChannel.close() // EOF for the relay
        } else if (seqLess(finSeq, rcvNext)) {
            finReceived = true
            sendAck()
        } else {
            pendingFin = finSeq // arrive after out-of-order data drains
        }
    }

    // ----------------------------------------------------------------- out

    private fun sendSynAck(clientSeq: Long) {
        if (!synAckSent) {
            iss = ((ISS_COUNTER.incrementAndGet().toLong() * 2654435761L) +
                    (System.currentTimeMillis() and 0xffffffL)) and 0xffffffffL
            rcvNext = (clientSeq + 1) and 0xffffffffL
            sndUna = iss
            sndNext = (iss + 1) and 0xffffffffL
            synAckSent = true
        }
        val pkt = PacketBuilder.tcp(
            srcIp = dstIp, dstIp = srcIp, srcPort = dstPort, dstPort = srcPort,
            seq = iss, ack = rcvNext, flags = Flags.SYN or Flags.ACK,
            window = stack.rcvWindow, payload = null, psh = false, mssOpt = mss,
        )
        stack.sink.send(pkt)
    }

    private fun sendAck() {
        val pkt = PacketBuilder.tcp(
            srcIp = dstIp, dstIp = srcIp, srcPort = dstPort, dstPort = srcPort,
            seq = sndNext, ack = rcvNext, flags = Flags.ACK,
            window = stack.rcvWindow, payload = null, psh = false,
        )
        stack.sink.send(pkt)
    }

    /** Queue relay data for transmission (respects the peer window). */
    internal fun enqueue(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(this) {
            var off = 0
            while (off < data.size) {
                val n = min(mss, data.size - off)
                val seg = Segment(sndNext, data.copyOfRange(off, off + n), false)
                sndNext = (sndNext + n) and 0xffffffffL
                sendQueue.addLast(seg)
                off += n
            }
            bytesUp += data.size
        }
        transmit()
    }

    internal fun sendFin() {
        synchronized(this) {
            if (finSent) return
            finSent = true
            finSeq = sndNext
            sndNext = (sndNext + 1) and 0xffffffffL
            sendQueue.addLast(Segment(finSeq, ByteArray(0), true))
        }
        transmit()
    }

    internal fun sendRst() {
        runCatching {
            val pkt = PacketBuilder.tcp(
                srcIp = dstIp, dstIp = srcIp, srcPort = dstPort, dstPort = srcPort,
                seq = sndNext, ack = rcvNext, flags = Flags.RST or Flags.ACK,
                window = 0, payload = null, psh = false,
            )
            stack.sink.send(pkt)
        }
        destroy()
    }

    /** Push queued segments that fit into the peer's advertised window. */
    private fun transmit() {
        synchronized(this) {
            val windowEnd = (sndUna + (if (peerWindow == 0) 0 else peerWindow)) and 0xffffffffL
            for (seg in sendQueue) {
                if (seg.sentAt > 0) continue // in flight; retransmission belongs to tick()
                val segEnd = (seg.seq + maxOf(seg.data.size, if (seg.fin) 1 else 0)) and 0xffffffffL
                val fits = if (peerWindow == 0) false else
                    seqLessEq(segEnd, windowEnd) || (seg.fin && seqLessEq(seg.seq, windowEnd))
                if (!fits) continue
                sendSegment(seg)
            }
        }
    }

    private fun sendSegment(seg: Segment) {
        val flags = if (seg.fin) Flags.ACK or Flags.FIN else Flags.ACK
        val pkt = PacketBuilder.tcp(
            srcIp = dstIp, dstIp = srcIp, srcPort = dstPort, dstPort = srcPort,
            seq = seg.seq, ack = rcvNext, flags = flags,
            window = stack.rcvWindow,
            payload = if (seg.data.isEmpty()) null else seg.data,
            psh = false,
        )
        stack.sink.send(pkt)
        seg.sentAt = System.currentTimeMillis()
    }

    private fun processAck(ack: Long, window: Int) {
        var progress = false
        var finDone = false
        synchronized(this) {
            if (seqLess(sndUna, ack)) {
                val it = sendQueue.iterator()
                while (it.hasNext()) {
                    val seg = it.next()
                    val segEnd = (seg.seq + maxOf(seg.data.size, if (seg.fin) 1 else 0)) and 0xffffffffL
                    if (seqLessEq(segEnd, ack)) {
                        if (seg.fin) finDone = true
                        it.remove()
                    } else break
                }
                sndUna = ack
                progress = true
            }
            peerWindow = window
        }
        if (finDone) finAcked = true
        if (progress) {
            transmit()
            windowSignal.trySend(Unit)
        }
        if (finAcked && finReceived) destroy()
    }

    /** Retransmission tick — called periodically by the stack. */
    internal fun tick(now: Long) {
        val toResend = synchronized(this) {
            val list = mutableListOf<Segment>()
            for (seg in sendQueue) {
                if (seg.retries > 12) { abortByPeer(); return }
                if (seg.sentAt > 0 && now - seg.sentAt >= (400L shl min(seg.retries, 4))) {
                    seg.retries++
                    seg.sentAt = now
                    list.add(seg)
                }
            }
            list
        }
        for (seg in toResend) sendSegment(seg)

        // zero-window probe: if the peer's window is closed or was never learned,
        // nudge it with the oldest queued segment. This must also cover segments
        // that have never been sent (sentAt == 0), otherwise a queue built while
        // the window was unknown stays stuck forever.
        val windowOpen = synchronized(this) { peerWindow > 0 }
        if (!windowOpen) {
            val oldest = synchronized(this) { sendQueue.firstOrNull() }
            if (oldest != null && now - lastProbeAt >= 2000) {
                lastProbeAt = now
                sendSegment(oldest)
            }
        }
    }

    // ------------------------------------------------------- relay-side API

    /** Next bytes from the app, or null on EOF/RST. */
    suspend fun read(): ByteArray? = try {
        rcvChannel.receive()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Send bytes to the app. Queues and transmits what fits; the rest follows on ACKs. */
    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        if (state == State.CLOSED) return
        enqueue(data)
    }

    /** Relay finished: send FIN and let the stack finish the close. */
    fun close() {
        if (state == State.CLOSED) return
        sendFin()
        // If the app already closed its side and acked ours, finish now.
        if (finReceived) destroy()
    }

    /** Hard reset towards the app and destroy local state. */
    fun rst() {
        sendRst()
    }

    private fun abortByPeer() {
        state = State.CLOSED
        rcvChannel.close()
        windowSignal.trySend(Unit)
        stack.remove(this)
    }

    internal fun destroy() {
        state = State.CLOSED
        rcvChannel.close()
        windowSignal.close()
        synchronized(this) { sendQueue.clear() }
        stack.remove(this)
    }

    fun tupleKey(): String = "$srcIp:$srcPort>$dstIp:$dstPort"
}

/**
 * Userspace TCP implementation over the TUN device. It plays the role that the
 * OS routing + local listen socket plays for the Windows patterniha core:
 * it terminates the phone's TCP connections and hands clean byte streams to
 * [FlowListener] (the relay).
 */
class TcpStack(
    val sink: PacketSink,
    private val mtu: Int,
    val listener: FlowListener? = null,
    val stackLog: ((String) -> Unit)? = null,
) {
    val mss: Int = (mtu - 40).coerceIn(216, 65495)
    val rcvWindow: Int = 65535

    private val flows = ConcurrentHashMap<String, TcpFlow>()

    /** Entry point from the TUN reader thread for every IPv4/TCP packet. */
    fun dispatch(p: IpPacket) {
        val key = key(p.srcIp, p.srcPort, p.dstIp, p.dstPort)
        val flow = flows[key]
        if (flow == null) {
            if (p.hasFlag(Flags.SYN) && !p.hasFlag(Flags.ACK)) {
                stackLog?.invoke("tcp: new flow ${p.srcIp}:${p.srcPort} -> ${Ip4.toString(p.dstIp)}:${p.dstPort}")
                val nf = TcpFlow(p.srcIp, p.srcPort, p.dstIp, p.dstPort, this)
                flows[key] = nf
                nf.handle(p) // sends SYN-ACK
            } else {
                // no such connection: reset so the app fails fast
                val rst = PacketBuilder.tcp(
                    srcIp = p.dstIp, dstIp = p.srcIp, srcPort = p.dstPort, dstPort = p.srcPort,
                    seq = 0, ack = p.tcpSeq + p.tcpPayloadLength + (if (p.hasFlag(Flags.FIN)) 1 else 0),
                    flags = Flags.RST or Flags.ACK, window = 0, payload = null, psh = false,
                )
                sink.send(rst)
            }
        } else {
            flow.handle(p)
        }
    }

    internal fun remove(flow: TcpFlow) {
        flows.remove(key(flow.srcIp, flow.srcPort, flow.dstIp, flow.dstPort), flow)
    }

    fun activeFlows(): Collection<TcpFlow> = flows.values

    /** Periodic maintenance: retransmissions and idle cleanup. */
    fun tick() {
        val now = System.currentTimeMillis()
        for (f in flows.values.toTypedArray()) f.tick(now)
        for (f in flows.values.toTypedArray()) {
            if (f.state == TcpFlow.State.CLOSED && now - f.lastActivity > 5000) f.destroy()
        }
    }

    fun shutdown() {
        for (f in flows.values.toTypedArray()) f.rst()
        flows.clear()
    }

    private fun key(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int) =
        "$srcIp|$srcPort|$dstIp|$dstPort"
}
