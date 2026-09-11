package com.armin7270.snispoof.state

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VpnViewModel(private val appContext: Context) : ViewModel() {

    private val prefs = PreferencesRepository(appContext)
    val profileStore = ProfileStore.get(appContext)
    val configStore = ConfigStore.get(appContext)

    val connectionState: StateFlow<ConnectionState> = VpnStateStore.state
    val stats: StateFlow<EngineStats> = VpnStateStore.stats
    val logs: StateFlow<List<String>> = VpnStateStore.logs
    val errorMessage: StateFlow<String?> = VpnStateStore.errorMessage

    val settings: StateFlow<AppSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val profiles: StateFlow<List<com.armin7270.snispoof.core.engine.SpoofProfile>> = profileStore.profiles
    val selectedProfileId: StateFlow<String?> = profileStore.selectedId

    val configs: StateFlow<List<com.armin7270.snispoof.core.proxy.ProxyConfig>> = configStore.configs
    val selectedConfigId: StateFlow<String?> = configStore.selectedId

    fun connect() {
        VpnStateStore.clearError()
        VpnController.start(appContext)
    }

    fun disconnect() = VpnController.stop(appContext)

    fun toggle() {
        when (connectionState.value) {
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> connect()
            ConnectionState.CONNECTED -> disconnect()
            ConnectionState.CONNECTING, ConnectionState.DISCONNECTING -> Unit
        }
    }

    fun selectProfile(id: String) = profileStore.select(id)

    fun saveProfile(profile: com.armin7270.snispoof.core.engine.SpoofProfile) = profileStore.save(profile)

    fun deleteProfile(id: String) = profileStore.delete(id)

    fun importProfiles(text: String): Int {
        val imported = com.armin7270.snispoof.core.engine.ProfileCodec.decode(text) +
                listOfNotNull(
                    com.armin7270.snispoof.core.engine.ProfileCodec.fromPatternihaConfig(text)
                )
        val before = profiles.value.size
        profileStore.importAll(imported)
        return profiles.value.size - before
    }

    // ---- proxy configs ----

    fun importConfigs(text: String): com.armin7270.snispoof.state.ImportResult =
        configStore.import(text)

    fun selectConfig(id: String?) = configStore.select(id)

    fun deleteConfig(id: String) = configStore.delete(id)

    fun setAutoStartBoot(v: Boolean) = viewModelScope.launch { prefs.setAutoStartBoot(v) }
    fun setBlockQuic(v: Boolean) = viewModelScope.launch { prefs.setBlockQuic(v) }
    fun setDnsProvider(v: DnsProvider) = viewModelScope.launch { prefs.setDnsProvider(v) }
    fun setCustomDns(v: String) = viewModelScope.launch { prefs.setCustomDns(v) }
    fun setMtu(v: Int) = viewModelScope.launch { prefs.setMtu(v) }
    fun setRootMode(v: Boolean) = viewModelScope.launch { prefs.setRootMode(v) }
    fun setPerAppMode(v: PerAppMode) = viewModelScope.launch { prefs.setPerAppMode(v) }
    fun togglePerAppPackage(pkg: String) = viewModelScope.launch { prefs.togglePerAppPackage(pkg) }
    fun setLanguage(v: String) = viewModelScope.launch { prefs.setLanguage(v) }

    companion object {
        fun factory(context: Context) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VpnViewModel(context.applicationContext) as T
        }
    }
}
