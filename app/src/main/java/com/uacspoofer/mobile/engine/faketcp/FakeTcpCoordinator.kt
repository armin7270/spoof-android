package com.uacspoofer.mobile.engine.faketcp

import android.content.Context
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.engine.pow.PowTun2Socks
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.vpn.SocketProtector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeTcpCoordinator(
    private val context: Context,
    private val protector: SocketProtector,
) {
    private val mutex = Mutex()
    private val bridge = FakeTcpBridge(protector)
    private val store = FakeTcpEngineStore.get(context)

    @Volatile
    var isRunning = false
        private set

    suspend fun connect(
        settings: AdvancedSettingsData,
        establishTun: (address: PowTun2Socks.PrivateAddress, mtu: Int) -> ParcelFileDescriptor?,
    ) = mutex.withLock {
        stopLocked()
        val fakeTcpSettings = store.snapshot()
        AppLogRepository.info(
            LogSource.SERVICE,
            "Starting Fake TCP / SNI Spoofing 1.0 engine (edge=${fakeTcpSettings.edgeIp}:${fakeTcpSettings.edgePort}, SNI=${fakeTcpSettings.fakeSni})"
        )

        bridge.start(fakeTcpSettings)

        val privateAddress = PowTun2Socks.selectPrivateAddress()
        val tunFd = establishTun(privateAddress, settings.tunMtu)
            ?: error("Failed to establish TUN interface for Fake TCP engine")

        val tunStarted = PowTun2Socks.start(
            context = context,
            tunFd = tunFd,
            socksProxyPort = fakeTcpSettings.localPort,
            mtu = settings.tunMtu,
            address = privateAddress,
        )

        if (!tunStarted) {
            stopLocked()
            error("Failed to start tun2socks relay for Fake TCP bridge")
        }

        isRunning = true
        AppLogRepository.success(
            LogSource.SERVICE,
            "Fake TCP SNI Spoofing 1.0 engine connected successfully"
        )
    }

    suspend fun stop() = mutex.withLock {
        stopLocked()
    }

    private suspend fun stopLocked() {
        isRunning = false
        runCatching { PowTun2Socks.stop() }
        runCatching { bridge.stop() }
        AppLogRepository.info(LogSource.SERVICE, "Fake TCP engine stopped")
    }

    fun stats() = bridge.totalTxBytes.get() to bridge.totalRxBytes.get()

    fun tunStats(): com.uacspoofer.mobile.vpn.TunStats {
        val tun = PowTun2Socks.stats()
        if (tun != com.uacspoofer.mobile.vpn.TunStats.ZERO) return tun
        val (tx, rx) = stats()
        return com.uacspoofer.mobile.vpn.TunStats(
            txPackets = 0L,
            txBytes = tx,
            rxPackets = 0L,
            rxBytes = rx,
        )
    }
}
