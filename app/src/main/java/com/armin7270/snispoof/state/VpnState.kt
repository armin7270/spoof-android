package com.armin7270.snispoof.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
}

/** Live engine statistics rendered by the UI. */
data class EngineStats(
    val packetsInspected: Long = 0,
    val packetsDropped: Long = 0,
    val fragmentsInjected: Long = 0,
    val clientHellosSeen: Long = 0,
    val activeFlows: Long = 0,
    val upBytes: Long = 0,
    val downBytes: Long = 0,
    val upRate: Long = 0,
    val downRate: Long = 0,
    val uptimeSec: Long = 0,
    val lastPingMs: Long = -1,
)

data class VpnUiState(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val stats: EngineStats = EngineStats(),
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Process-wide store shared between the UI and the VPN service
 * (same pattern as the reference app's ConnectionStateStore).
 */
object VpnStateStore {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _stats = MutableStateFlow(EngineStats())
    val stats: StateFlow<EngineStats> = _stats

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val startedAtMs = MutableStateFlow(0L)

    // Read-modify-write on the flows below happens from engine threads and
    // service coroutines at once; without this lock bursts of events drop lines
    // or stats updates instead of composing.
    private val logLock = Any()
    private val statsLock = Any()

    fun markConnecting() {
        _errorMessage.value = null
        _state.value = ConnectionState.CONNECTING
    }

    fun markConnected() {
        startedAtMs.value = System.currentTimeMillis()
        _state.value = ConnectionState.CONNECTED
    }

    fun markDisconnected() {
        startedAtMs.value = 0
        _state.value = ConnectionState.DISCONNECTED
    }

    fun markError(message: String) {
        _errorMessage.value = message
        _state.value = ConnectionState.ERROR
    }

    fun clearError() {
        _errorMessage.value = null
        if (_state.value == ConnectionState.ERROR) _state.value = ConnectionState.DISCONNECTED
    }

    fun error(): String? = _errorMessage.value

    fun uptimeSec(): Long {
        val started = startedAtMs.value
        return if (started == 0L) 0 else (System.currentTimeMillis() - started) / 1000
    }

    fun updateStats(transform: (EngineStats) -> EngineStats) {
        synchronized(statsLock) { _stats.value = transform(_stats.value) }
    }

    fun setStats(s: EngineStats) {
        _stats.value = s
    }

    fun log(line: String) {
        val stamped = "%1\$TH:%1\$TM:%1\$TS.%1\$TL  %2\$s"
            .format(System.currentTimeMillis(), line)
        android.util.Log.i("SNISpoof", line)
        synchronized(logLock) {
            val next = ArrayList<String>(_logs.value.size + 1)
            next.addAll(_logs.value)
            next.add(stamped)
            _logs.value = if (next.size > 500) next.subList(next.size - 500, next.size) else next
        }
    }

    fun clearLogs() {
        synchronized(logLock) { _logs.value = emptyList() }
    }
}
