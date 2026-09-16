package com.uacspoofer.mobile.engine.faketcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FakeTcpSettings(
    val edgeIp: String = DEFAULT_EDGE_IP,
    val edgePort: Int = DEFAULT_EDGE_PORT,
    val fakeSni: String = DEFAULT_FAKE_SNI,
    val localPort: Int = DEFAULT_LOCAL_PORT,
    val bypassMethod: String = DEFAULT_BYPASS_METHOD,
) {
    companion object {
        const val DEFAULT_EDGE_IP = "188.114.98.0"
        const val DEFAULT_EDGE_PORT = 443
        const val DEFAULT_FAKE_SNI = "auth.vercel.com"
        const val DEFAULT_LOCAL_PORT = 40443
        const val DEFAULT_BYPASS_METHOD = "wrong_seq_fake_tls"
    }
}

class FakeTcpEngineStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val mutableSettings = MutableStateFlow(loadSettings())
    val settings: StateFlow<FakeTcpSettings> = mutableSettings.asStateFlow()

    fun snapshot(): FakeTcpSettings = mutableSettings.value

    fun save(settings: FakeTcpSettings) {
        prefs.edit()
            .putString(KEY_EDGE_IP, settings.edgeIp)
            .putInt(KEY_EDGE_PORT, settings.edgePort)
            .putString(KEY_FAKE_SNI, settings.fakeSni)
            .putInt(KEY_LOCAL_PORT, settings.localPort)
            .putString(KEY_BYPASS_METHOD, settings.bypassMethod)
            .apply()
        mutableSettings.value = settings
    }

    private fun loadSettings(): FakeTcpSettings {
        return FakeTcpSettings(
            edgeIp = prefs.getString(KEY_EDGE_IP, FakeTcpSettings.DEFAULT_EDGE_IP) ?: FakeTcpSettings.DEFAULT_EDGE_IP,
            edgePort = prefs.getInt(KEY_EDGE_PORT, FakeTcpSettings.DEFAULT_EDGE_PORT),
            fakeSni = prefs.getString(KEY_FAKE_SNI, FakeTcpSettings.DEFAULT_FAKE_SNI) ?: FakeTcpSettings.DEFAULT_FAKE_SNI,
            localPort = prefs.getInt(KEY_LOCAL_PORT, FakeTcpSettings.DEFAULT_LOCAL_PORT),
            bypassMethod = prefs.getString(KEY_BYPASS_METHOD, FakeTcpSettings.DEFAULT_BYPASS_METHOD) ?: FakeTcpSettings.DEFAULT_BYPASS_METHOD,
        )
    }

    companion object {
        private const val PREFS = "fake_tcp_engine_prefs_v1"
        private const val KEY_EDGE_IP = "edge_ip"
        private const val KEY_EDGE_PORT = "edge_port"
        private const val KEY_FAKE_SNI = "fake_sni"
        private const val KEY_LOCAL_PORT = "local_port"
        private const val KEY_BYPASS_METHOD = "bypass_method"

        @Volatile private var instance: FakeTcpEngineStore? = null

        fun get(context: Context): FakeTcpEngineStore = instance ?: synchronized(this) {
            instance ?: FakeTcpEngineStore(context.applicationContext).also { instance = it }
        }
    }
}
