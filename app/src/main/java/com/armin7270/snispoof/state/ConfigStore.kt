package com.armin7270.snispoof.state

import android.content.Context
import com.armin7270.snispoof.core.proxy.ProxyConfig
import com.armin7270.snispoof.core.proxy.ProxyConfigParser
import com.armin7270.snispoof.core.proxy.ProxyProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Outcome of a share-link import: what landed, and what had to be refused. */
data class ImportResult(val imported: Int, val unsupported: Int)

/**
 * Persists imported proxy configs (vless/trojan) and the active one.
 *
 * `vmess://` links are still parsed, but refused here with an explicit count —
 * previously they were imported silently and then failed at connect time with
 * "vmess: not supported in this build", which looks like a broken network.
 */
class ConfigStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("configs", Context.MODE_PRIVATE)

    private val _configs = MutableStateFlow(load())
    val configs: StateFlow<List<ProxyConfig>> = _configs

    private val _selectedId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    val selectedId: StateFlow<String?> = _selectedId

    fun active(): ProxyConfig? = _configs.value.firstOrNull { it.id == _selectedId.value }

    fun import(text: String): ImportResult {
        val parsed = ProxyConfigParser.parseAll(text)
        val usable = parsed.filter { it.proto != ProxyProtocol.VMESS }
        val refused = parsed.size - usable.size
        if (usable.isNotEmpty()) {
            val merged = (_configs.value + usable).distinctBy { it.id }
            _configs.value = merged
            prefs.edit().putString(KEY_CONFIGS, ProxyConfig.encode(merged)).apply()
            if (_selectedId.value == null) select(usable.first().id)
        }
        return ImportResult(usable.size, refused)
    }

    fun select(id: String?) {
        _selectedId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun delete(id: String) {
        _configs.value = _configs.value.filterNot { it.id == id }
        prefs.edit().putString(KEY_CONFIGS, ProxyConfig.encode(_configs.value)).apply()
        if (_selectedId.value == id) select(_configs.value.firstOrNull()?.id)
    }

    fun clearAll() {
        _configs.value = emptyList()
        select(null)
        prefs.edit().remove(KEY_CONFIGS).apply()
    }

    private fun load(): List<ProxyConfig> =
        prefs.getString(KEY_CONFIGS, null)?.let { ProxyConfig.decode(it) } ?: emptyList()

    companion object {
        private const val KEY_CONFIGS = "configs_json"
        private const val KEY_SELECTED = "selected_id"

        @Volatile private var instance: ConfigStore? = null
        fun get(context: Context): ConfigStore =
            instance ?: synchronized(this) {
                instance ?: ConfigStore(context.applicationContext).also { instance = it }
            }
    }
}
