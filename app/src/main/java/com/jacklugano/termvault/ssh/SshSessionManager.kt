package com.jacklugano.termvault.ssh

import android.content.Context
import android.content.Intent
import com.jacklugano.termvault.data.db.HostEntity
import com.jacklugano.termvault.data.db.KnownHostDao
import com.jacklugano.termvault.data.db.PortForwardDao
import com.jacklugano.termvault.service.SshSessionService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registro globale delle schede terminale attive. Vive quanto il processo:
 * le sessioni sopravvivono alla navigazione e sono tenute in vita dal
 * foreground service finché c'è almeno una scheda aperta.
 */
@Singleton
class SshSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val knownHostDao: KnownHostDao,
    private val portForwardDao: PortForwardDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tabs = MutableStateFlow<List<SshTerminalTab>>(emptyList())
    val tabs: StateFlow<List<SshTerminalTab>> = _tabs

    private val _activeTabId = MutableStateFlow<Long?>(null)
    val activeTabId: StateFlow<Long?> = _activeTabId

    /** Contatore bumpato quando i titoli delle schede cambiano (per ricomposizioni). */
    private val _tabsVersion = MutableStateFlow(0)
    val tabsVersion: StateFlow<Int> = _tabsVersion

    suspend fun openTab(
        host: HostEntity,
        credentials: SshCredentials,
        jumpHost: HostEntity? = null,
        jumpCredentials: SshCredentials? = null,
        transcriptRows: Int = 4000,
    ): SshTerminalTab {
        val tab = SshTerminalTab(
            host = host,
            credentials = credentials,
            jumpHost = jumpHost,
            jumpCredentials = jumpCredentials,
            knownHostDao = knownHostDao,
            scope = scope,
            onAllTabsChanged = { _tabsVersion.value++ },
        )
        // TerminalSession usa un Handler del main thread.
        withContext(Dispatchers.Main) { tab.initTerminal(transcriptRows) }
        tab.setForwardConfigs(portForwardDao.getForHost(host.id))
        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id
        updateService()
        tab.connect()
        return tab
    }

    fun setActiveTab(id: Long?) {
        _activeTabId.value = id
    }

    fun tabById(id: Long?): SshTerminalTab? = _tabs.value.firstOrNull { it.id == id }

    fun closeTab(id: Long) {
        val tab = tabById(id) ?: return
        tab.close()
        _tabs.value = _tabs.value - tab
        if (_activeTabId.value == id) {
            _activeTabId.value = _tabs.value.lastOrNull()?.id
        }
        updateService()
    }

    fun closeAll() {
        _tabs.value.forEach { it.close() }
        _tabs.value = emptyList()
        _activeTabId.value = null
        updateService()
    }

    private fun updateService() {
        val intent = Intent(context, SshSessionService::class.java)
        if (_tabs.value.isEmpty()) {
            context.stopService(intent)
        } else {
            context.startForegroundService(intent)
        }
    }
}
