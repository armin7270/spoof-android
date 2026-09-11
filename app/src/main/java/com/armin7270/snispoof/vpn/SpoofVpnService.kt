package com.armin7270.snispoof.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.armin7270.snispoof.R
import com.armin7270.snispoof.state.AppSettings
import com.armin7270.snispoof.state.ConnectionState
import com.armin7270.snispoof.state.EngineStats
import com.armin7270.snispoof.state.PerAppMode
import com.armin7270.snispoof.state.PreferencesRepository
import com.armin7270.snispoof.state.ProfileStore
import com.armin7270.snispoof.state.VpnStateStore
import com.armin7270.snispoof.core.desync.DesyncMethod
import com.armin7270.snispoof.core.desync.NoRootDesync
import com.armin7270.snispoof.core.desync.RootInjector
import com.armin7270.snispoof.core.engine.DohProvider
import com.armin7270.snispoof.core.engine.DohResolver
import com.armin7270.snispoof.core.engine.EngineCounters
import com.armin7270.snispoof.core.engine.PacketEngine
import com.armin7270.snispoof.core.engine.ProfileRouter
import com.armin7270.snispoof.core.engine.SocketProtector
import com.armin7270.snispoof.core.engine.SpoofProfile
import com.armin7270.snispoof.core.engine.TrafficStats
import com.armin7270.snispoof.core.packet.Ip4
import com.armin7270.snispoof.core.tcpip.TcpFlow
import com.armin7270.snispoof.core.tls.ClientHelloForge
import com.armin7270.snispoof.core.tls.TlsParser
import com.armin7270.snispoof.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class SpoofVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private var tunPfd: ParcelFileDescriptor? = null
    private var engine: PacketEngine? = null
    private var protector: ServiceProtector? = null
    private var rootInjector: RootInjector? = null
    private var tunJob: kotlinx.coroutines.Job? = null
    private var tickJob: kotlinx.coroutines.Job? = null
    private var statsJob: kotlinx.coroutines.Job? = null

    private val counters = EngineCounters()
    private val stats = TrafficStats()
    private lateinit var prefs: PreferencesRepository
    private lateinit var configStore: com.armin7270.snispoof.state.ConfigStore
    @Volatile private var tunHandler: TunPacketHandler? = null

    companion object {
        const val ACTION_CONNECT = "com.armin7270.snispoof.CONNECT"
        const val ACTION_DISCONNECT = "com.armin7270.snispoof.DISCONNECT"
        private const val CHANNEL_ID = "spoof_tunnel"
        private const val NOTIF_ID = 42
        private const val TUN_ADDRESS = "198.18.0.1"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
        configStore = com.armin7270.snispoof.state.ConfigStore.get(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_CONNECT, null -> {
                // Promote to foreground on the calling (main) thread first: reading
                // settings, establishing the TUN and probing the root helper can take
                // seconds, and none of it may block the main thread.
                notifyState(getString(R.string.notif_connecting))
                if (!running.get() && starting.compareAndSet(false, true)) {
                    scope.launch {
                        try {
                            connect()
                        } finally {
                            starting.set(false)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        val st = VpnStateStore.state.value
        if (st == ConnectionState.CONNECTED || st == ConnectionState.CONNECTING ||
            st == ConnectionState.DISCONNECTING
        ) {
            VpnStateStore.markDisconnected()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ connect

    private suspend fun connect() {
        if (running.get()) return
        VpnStateStore.markConnecting()
        VpnStateStore.log(getString(R.string.log_connecting))
        notifyState(getString(R.string.notif_connecting))

        val settings = prefs.current()
        val profile = ProfileStore.get(this).selected()
        if (profile == null) {
            fail(getString(R.string.err_no_profile))
            return
        }

        val pfd = establishVpn(settings)
        if (pfd == null) {
            fail(getString(R.string.err_vpn_declined))
            return
        }
        tunPfd = pfd
        running.set(true)

        startEngine(profile, settings)
        startTunLoop(pfd)
        startMaintenance()
        VpnStateStore.markConnected()
        VpnStateStore.log(getString(R.string.log_connected, profile.name))
        notifyState(getString(R.string.notif_connected))
    }

    private fun fail(message: String) {
        VpnStateStore.markError(message)
        VpnStateStore.log(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establishVpn(settings: AppSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(settings.mtu)
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(settings.dnsIp)
            .setBlocking(true)

        // our own traffic must never re-enter the tunnel
        runCatching { builder.addDisallowedApplication(packageName) }
        when (settings.perAppMode) {
            PerAppMode.WHITELIST ->
                settings.perAppPackages.forEach { runCatching { builder.addAllowedApplication(it) } }
            PerAppMode.BLACKLIST ->
                settings.perAppPackages.forEach { runCatching { builder.addDisallowedApplication(it) } }
            PerAppMode.ALL -> Unit
        }
        return runCatching { builder.establish() }
            .onFailure { VpnStateStore.log("establish failed: ${it.message}") }
            .getOrNull()
    }

    // ------------------------------------------------------------------ engine

    private suspend fun startEngine(profile: SpoofProfile, settings: AppSettings) {
        protector = ServiceProtector()
        val resolver = DohResolver(DohProvider.CLOUDFLARE, protector!!, ::appLog)
        engine = PacketEngine(
            scope = scope,
            sink = ::writeToTun,
            protector = protector!!,
            router = ProfileRouter(listOf(profile)),
            resolver = resolver,
            tunIp = Ip4.parse(TUN_ADDRESS),
            mtu = settings.mtu,
            blockQuic = settings.blockQuic,
            stats = stats,
            counters = counters,
            relay = ::startRelay,
            log = ::appLog,
        )

        if (settings.rootMode && profile.desyncMethod == DesyncMethod.WRONG_SEQ.id) {
            val helper = helperPath()
            if (helper == null) {
                // wrong_seq is the one technique Android cannot emulate without
                // raw injection, so say so instead of silently degrading.
                VpnStateStore.log(
                    "root helper not found (expected libspoofhelper.so in jniLibs or " +
                            "/data/local/tmp/spoofhelper) — using split desync"
                )
            } else {
                rootInjector = RootInjector(helper, ::appLog)
                if (!rootInjector!!.start()) {
                    VpnStateStore.log(getString(R.string.log_root_unavailable))
                    rootInjector = null
                }
            }
        }
    }

    private fun writeToTun(packet: ByteArray) {
        tunHandler?.write(packet)
    }

    private fun startTunLoop(pfd: ParcelFileDescriptor) {
        val handler = TunPacketHandler(pfd, engine!!)
        tunHandler = handler
        tunJob = scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                handler.runLoop()
            }
            if (running.get()) disconnect()
        }
    }

    private fun startMaintenance() {
        tickJob = scope.launch {
            while (isActive && running.get()) {
                delay(1000)
                engine?.tick()
            }
        }
        statsJob = scope.launch {
            var prevUp = 0L
            var prevDown = 0L
            while (isActive && running.get()) {
                delay(1000)
                val up = stats.upBytes.get()
                val down = stats.downBytes.get()
                VpnStateStore.setStats(
                    EngineStats(
                        packetsInspected = counters.packetsInspected.get(),
                        packetsDropped = counters.packetsDropped.get(),
                        fragmentsInjected = counters.fragmentsInjected.get(),
                        clientHellosSeen = counters.clientHellosSeen.get(),
                        activeFlows = counters.activeFlows.get(),
                        upBytes = up,
                        downBytes = down,
                        upRate = up - prevUp,
                        downRate = down - prevDown,
                        uptimeSec = VpnStateStore.uptimeSec(),
                        lastPingMs = stats.lastPingMs,
                    )
                )
                prevUp = up
                prevDown = down
            }
        }
    }

    // ------------------------------------------------------------------ relay

    private fun startRelay(
        flow: TcpFlow,
        decision: com.armin7270.snispoof.core.engine.ProfileRouter.Decision,
    ) {
        scope.launch {
            val params = decision.desync
            val dstIpBytes = Ip4.bytes(decision.targetIp)
            val dst = InetSocketAddress(InetAddress.getByAddress(dstIpBytes), decision.targetPort)
            val dstLabel = "${Ip4.toString(decision.targetIp)}:${decision.targetPort}"
            val activeConfig = configStore.active()

            // ---- config mode: tunnel through the imported proxy -----------
            if (activeConfig != null) {
                relayViaConfig(flow, activeConfig, decision, dstLabel)
                return@launch
            }

            // ---- direct mode: patterniha desync against the real dst ------
            var socket = Socket()
            protector?.protectSocket(socket)
            socket.tcpNoDelay = true
            try {
                val handledByRoot = tryWrongSeqRoot(socket, flow, decision, dst)
                if (handledByRoot) return@launch

                withTimeoutOrNull(8000) { socket.connect(dst, 8000) }
                    ?: throw java.io.IOException("connect timeout $dstLabel")

                val first = readFirstPayload(flow)
                if (first == null) {
                    // server-speaks-first protocol or dead flow — plain pump
                    pump(flow, socket)
                    return@launch
                }
                if (TlsParser.looksLikeClientHello(first)) {
                    counters.clientHellosSeen.incrementAndGet()
                    VpnStateStore.log(
                        "TLS ${TlsParser.sniHost(first) ?: "?"} → $dstLabel" +
                                if (params != null) " [${params.method.id}]" else ""
                    )
                }
                if (params != null && params.method != DesyncMethod.OFF) {
                    val desync = NoRootDesync(params, ::appLog) {
                        counters.fragmentsInjected.addAndGet(it.toLong())
                    }
                    val used = desync.apply(socket, dst, first)
                    if (used !== socket) {
                        // fake_rst_rebind swapped the socket
                        runCatching { socket.close() }
                        socket = used
                        protector?.protectSocket(socket)
                    }
                    // apply() already wrote the (possibly rewritten) first payload
                    pump(flow, socket)
                } else {
                    pump(flow, socket, first)
                }
            } catch (e: Exception) {
                VpnStateStore.log("relay $dstLabel failed: ${e.message}")
                runCatching { flow.rst() }
                runCatching { socket.close() }
            }
        }
    }

    /** Tunnels the flow through the imported VLESS/Trojan config. */
    private suspend fun relayViaConfig(
        flow: TcpFlow,
        config: com.armin7270.snispoof.core.proxy.ProxyConfig,
        decision: com.armin7270.snispoof.core.engine.ProfileRouter.Decision,
        dstLabel: String,
    ) {
        var tunnel: com.armin7270.snispoof.core.proxy.ProxyTunnel.Tunnel? = null
        try {
            val desyncParams = decision.desync
            val opened = com.armin7270.snispoof.core.proxy.ProxyTunnel.open(
                config = config,
                targetIp = decision.targetIp,
                targetPort = decision.targetPort,
                protector = protector,
                desync = desyncParams,
                onFragment = { counters.fragmentsInjected.addAndGet(it.toLong()) },
            )
            tunnel = opened
            VpnStateStore.log("tunnel ${config.name} → $dstLabel")
            pump(flow, opened.output, opened.input, opened)
        } catch (e: Exception) {
            VpnStateStore.log("tunnel $dstLabel failed: ${e.message}")
            runCatching { flow.rst() }
            runCatching { tunnel?.close() }
        }
    }

    /**
     * The faithful patterniha wrong_seq injection through the root helper:
     * bind the socket to a known local port, have the helper watch the
     * handshake, then connect. The helper re-sends the final ACK as a PSH
     * segment carrying the fake ClientHello with
     * seq = (syn_seq + 1 - len(fake)) & 0xffffffff — exactly fake_tcp.py.
     */
    private suspend fun tryWrongSeqRoot(
        socket: Socket,
        flow: TcpFlow,
        decision: com.armin7270.snispoof.core.engine.ProfileRouter.Decision,
        dst: InetSocketAddress,
    ): Boolean {
        val injector = rootInjector ?: return false
        val params = decision.desync ?: return false
        if (params.method != DesyncMethod.WRONG_SEQ) return false

        val ifaceIp = detectLocalIp(decision.targetIp) ?: run {
            VpnStateStore.log("wrong_seq: no local ip, falling back to split")
            return false
        }
        val fake = ClientHelloForge.build(decision.profile?.fakeSni ?: params.fakeSni)
        return try {
            socket.bind(InetSocketAddress(InetAddress.getByName(ifaceIp), 0))
            val ok = injector.watchAndInject(
                ifaceIp, socket.localPort,
                Ip4.toString(decision.targetIp), decision.targetPort, fake,
            )
            if (!ok) {
                VpnStateStore.log("wrong_seq: injection failed, falling back to split")
                false
            } else {
                VpnStateStore.log("wrong_seq: fake ClientHello injected via root helper")
                counters.fragmentsInjected.incrementAndGet()
                withTimeoutOrNull(8000) { socket.connect(dst, 8000) }
                    ?: throw java.io.IOException("connect timeout after injection")
                pump(flow, socket)
                true
            }
        } catch (e: Exception) {
            VpnStateStore.log("wrong_seq error: ${e.message}")
            runCatching { flow.rst() }
            runCatching { socket.close() }
            true
        }
    }

    /** UDP-connect trick — the port of network_tools.py get_default_interface_ipv4. */
    private fun detectLocalIp(dstIp: Int): String? = runCatching {
        val s = DatagramSocket()
        protector?.protectSocket(s)
        try {
            s.connect(InetAddress.getByAddress(Ip4.bytes(dstIp)), 53)
            (s.localAddress as? Inet4Address)?.hostAddress
        } finally {
            runCatching { s.close() }
        }
    }.getOrNull()

    /**
     * Returns the first payload of the flow, reassembled far enough to hold one
     * complete TLS record.
     *
     * A single TCP segment is *not* a whole ClientHello: with a small MTU, or a
     * large (GREASE/ECH) ClientHello, the browser's handshake arrives spread over
     * several segments. Desyncing only the first fragment silently degrades to a
     * plain write — the split lands inside the record header, `parseSni` fails and
     * the DPI sees an unmodified ClientHello. So accumulate up to the record
     * length advertised in the first 5 bytes.
     */
    private suspend fun readFirstPayload(flow: TcpFlow, timeoutMs: Long = 15_000): ByteArray? =
        withTimeoutOrNull(timeoutMs) {
            val head = flow.read() ?: return@withTimeoutOrNull null
            if (!TlsParser.looksLikeTls(head)) return@withTimeoutOrNull head
            val recordLen = ((head[3].toInt() and 0xff) shl 8) or (head[4].toInt() and 0xff)
            val total = 5 + recordLen
            // a ClientHello record never legitimately exceeds 2^14 + 2^11
            if (recordLen <= 0 || total > 18432) return@withTimeoutOrNull head
            if (head.size >= total) return@withTimeoutOrNull head
            val buf = head.copyOf(total)
            var have = head.size
            while (have < total) {
                val more = flow.read() ?: break
                val n = minOf(more.size, total - have)
                System.arraycopy(more, 0, buf, have, n)
                have += n
            }
            if (have == total) buf else buf.copyOf(have)
        }

    private suspend fun pump(flow: TcpFlow, socket: Socket, first: ByteArray? = null) {
        pump(flow, socket.getOutputStream(), socket.getInputStream(), socket, first)
    }

    private suspend fun pump(
        flow: TcpFlow,
        out: java.io.OutputStream,
        input: java.io.InputStream,
        closeable: java.io.Closeable,
        first: ByteArray? = null,
    ) {
        val upstream = scope.launch {
            if (first != null) {
                stats.up(first.size)
                runCatching { out.write(first); out.flush() }
            }
            while (true) {
                val data = flow.read() ?: break
                stats.up(data.size)
                out.write(data)
                out.flush()
            }
        }
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                stats.down(n)
                flow.write(buf.copyOf(n))
            }
            flow.close()
        } catch (e: Exception) {
            flow.rst()
        } finally {
            upstream.cancel()
            runCatching { closeable.close() }
        }
    }

    private fun appLog(line: String) {
        VpnStateStore.log(line)
    }

    // ------------------------------------------------------------------ teardown

    private fun disconnect() {
        if (!running.getAndSet(false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        VpnStateStore.markDisconnected()
        VpnStateStore.log(getString(R.string.log_disconnected))
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        running.set(false)
        tunJob?.cancel(); tunJob = null
        tickJob?.cancel(); tickJob = null
        statsJob?.cancel(); statsJob = null
        engine?.shutdown(); engine = null
        rootInjector?.stop(); rootInjector = null
        runCatching { tunHandler?.close() }; tunHandler = null
        runCatching { tunPfd?.close() }; tunPfd = null
    }

    /**
     * Locates the optional root helper.
     *
     * The packaged `libspoofhelper.so` is the intended location once the native
     * source under app/src/main/cpp is wired into the build, but the README has
     * always documented `adb push spoofhelper /data/local/tmp/`, and nothing is
     * ever compiled into jniLibs today. Checking both means the documented
     * install actually works instead of always reporting "root unavailable".
     */
    private fun helperPath(): String? = listOf(
        File(applicationInfo.nativeLibraryDir, "libspoofhelper.so"),
        File("/data/local/tmp/spoofhelper"),
    ).firstOrNull { it.canExecute() || (it.isFile && it.canRead()) }?.absolutePath

    // ------------------------------------------------------------------ notif

    private fun createNotificationChannel() {
        // NotificationChannel and NotificationManager.createNotificationChannel
        // only exist from API 26, while minSdk is 24 — calling this unguarded
        // crashed the service with NoClassDefFoundError on Android 7.x.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        ch.description = getString(R.string.notif_channel_desc)
        nm.createNotificationChannel(ch)
    }

    private fun notifyState(text: String) {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SpoofVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_disconnect), stop)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure { VpnStateStore.log("notif: ${it.message}") }
    }

    inner class ServiceProtector : SocketProtector {
        override fun protectSocket(socket: Socket) {
            runCatching { protect(socket) }
        }

        override fun protectSocket(socket: DatagramSocket) {
            runCatching { protect(socket) }
        }
    }
}
