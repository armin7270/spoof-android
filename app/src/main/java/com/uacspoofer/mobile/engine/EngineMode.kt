package com.uacspoofer.mobile.engine

import com.uacspoofer.mobile.core.ConnectionState

enum class EngineMode(val id: String) {
    XRAY_CF("xray_cf"),
    TOR_WEBTUNNEL("tor_webtunnel"),
    UAC_POW("uac_pow"),
    FAKE_TCP("fake_tcp");

    val isTor: Boolean get() = this == TOR_WEBTUNNEL
    val isXray: Boolean get() = this == XRAY_CF
    val isPow: Boolean get() = this == UAC_POW
    val isFakeTcp: Boolean get() = this == FAKE_TCP

    fun next(): EngineMode = when (this) {
        XRAY_CF -> TOR_WEBTUNNEL
        TOR_WEBTUNNEL -> UAC_POW
        UAC_POW -> FAKE_TCP
        FAKE_TCP -> XRAY_CF
    }

    fun toggled(): EngineMode = next()

    companion object {
        fun fromStored(raw: String?): EngineMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: XRAY_CF
    }
}

enum class EngineModeChangeResult {
    APPLIED,
    BLOCKED_WHILE_ACTIVE,
}

fun canChangeEngineMode(state: ConnectionState): Boolean =
    state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR
