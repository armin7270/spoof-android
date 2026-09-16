package com.uacspoofer.mobile.engine.faketcp

import android.util.Log
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.vpn.SocketProtector
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class FakeTcpBridge(
    private val protector: SocketProtector,
) {
    private val lifecycleMutex = Mutex()
    private var workerJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val activeConnections = ConcurrentHashMap<Socket, Socket>()

    val totalTxBytes = AtomicLong(0L)
    val totalRxBytes = AtomicLong(0L)

    suspend fun start(settings: FakeTcpSettings) = lifecycleMutex.withLock {
        stopLocked()
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), settings.localPort))
        serverSocket = server

        val rootJob = SupervisorJob()
        workerJob = rootJob
        val scope = CoroutineScope(rootJob + Dispatchers.IO)

        AppLogRepository.info(
            LogSource.SERVICE,
            "Fake TCP bridge listening on 127.0.0.1:${settings.localPort} -> edge ${settings.edgeIp}:${settings.edgePort} (SNI=${settings.fakeSni})"
        )

        scope.launch {
            acceptLoop(server, settings, scope)
        }
    }

    suspend fun stop() = withContext(NonCancellable) {
        lifecycleMutex.withLock { stopLocked() }
    }

    private fun stopLocked() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        workerJob?.cancel()
        workerJob = null

        activeConnections.forEach { (client, remote) ->
            runCatching { client.close() }
            runCatching { remote.close() }
        }
        activeConnections.clear()
    }

    private suspend fun acceptLoop(
        server: ServerSocket,
        settings: FakeTcpSettings,
        scope: CoroutineScope,
    ) {
        while (scope.isActive) {
            try {
                val clientSocket = server.accept()
                clientSocket.tcpNoDelay = true
                scope.launch {
                    handleClient(clientSocket, settings)
                }
            } catch (e: Throwable) {
                if (server.isClosed) break
                Log.w(TAG, "FakeTcpBridge accept error", e)
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket, settings: FakeTcpSettings) {
        val edgeSocket = Socket()
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val firstByte = clientIn.read()
            if (firstByte < 0) return

            if (firstByte == 0x05) {
                // SOCKS5 greeting: read methods
                val nMethods = clientIn.read()
                if (nMethods <= 0) return
                val methods = ByteArray(nMethods)
                readExact(clientIn, methods)

                // Accept NO AUTH
                clientOut.write(byteArrayOf(0x05, 0x00))
                clientOut.flush()

                // SOCKS5 request: VER, CMD, RSV, ATYP
                val header = ByteArray(4)
                readExact(clientIn, header)
                val cmd = header[1].toInt() and 0xFF
                val atyp = header[3].toInt() and 0xFF

                if (cmd != 0x01) { // 0x01 = CONNECT
                    clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    clientOut.flush()
                    return
                }

                when (atyp) {
                    0x01 -> { // IPv4: 4 bytes IP + 2 bytes port
                        val addrPort = ByteArray(6)
                        readExact(clientIn, addrPort)
                    }
                    0x03 -> { // Domain: 1 byte len + domain bytes + 2 bytes port
                        val len = clientIn.read()
                        if (len < 0) return
                        val domainPort = ByteArray(len + 2)
                        readExact(clientIn, domainPort)
                    }
                    0x04 -> { // IPv6: 16 bytes IP + 2 bytes port
                        val addrPort = ByteArray(18)
                        readExact(clientIn, addrPort)
                    }
                    else -> return
                }

                connectEdgeAndInject(edgeSocket, settings)
                activeConnections[clientSocket] = edgeSocket

                // SOCKS5 success reply: BND.ADDR 0.0.0.0, BND.PORT 0
                clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
            } else {
                // Raw TCP direct mode (port forwarder for VLESS / direct proxy)
                connectEdgeAndInject(edgeSocket, settings)
                activeConnections[clientSocket] = edgeSocket
                edgeSocket.getOutputStream().apply {
                    write(firstByte)
                    flush()
                }
                totalTxBytes.incrementAndGet()
            }

            val clientToEdge = CoroutineScope(Dispatchers.IO).launch {
                relayStream(clientSocket.getInputStream(), edgeSocket.getOutputStream(), totalTxBytes)
            }
            val edgeToClient = CoroutineScope(Dispatchers.IO).launch {
                relayStream(edgeSocket.getInputStream(), clientSocket.getOutputStream(), totalRxBytes)
            }

            clientToEdge.join()
            edgeToClient.join()
        } catch (_: Exception) {
        } finally {
            activeConnections.remove(clientSocket)
            runCatching { clientSocket.close() }
            runCatching { edgeSocket.close() }
        }
    }

    private fun connectEdgeAndInject(edgeSocket: Socket, settings: FakeTcpSettings) {
        edgeSocket.tcpNoDelay = true
        edgeSocket.keepAlive = true
        if (!protector.protect(edgeSocket)) {
            Log.w(TAG, "Socket protector failed to protect edge socket")
        }
        edgeSocket.connect(InetSocketAddress(settings.edgeIp, settings.edgePort), 5000)

        val fakePayload = FakeTcpTemplate.buildClientHello(settings.fakeSni)
        edgeSocket.getOutputStream().apply {
            write(fakePayload)
            flush()
        }
        totalTxBytes.addAndGet(fakePayload.size.toLong())
    }

    private fun readExact(input: InputStream, buffer: ByteArray, length: Int = buffer.size) {
        var offset = 0
        while (offset < length) {
            val r = input.read(buffer, offset, length - offset)
            if (r < 0) throw java.io.EOFException("Unexpected EOF during read")
            offset += r
        }
    }

    private fun relayStream(input: InputStream, output: OutputStream, counter: AtomicLong) {
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                output.flush()
                counter.addAndGet(read.toLong())
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "FakeTcpBridge"
    }
}
