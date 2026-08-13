package com.jacklugano.termvault.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacklugano.termvault.data.db.HostDao
import com.jacklugano.termvault.data.db.HostEntity
import com.jacklugano.termvault.ssh.SshSessionManager
import com.jacklugano.termvault.ssh.SshTerminalTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

/** Esito del "ping" (connect TCP) verso un host. */
sealed interface PingState {
    data object Checking : PingState
    data class Reachable(val millis: Long) : PingState
    data class Unreachable(val reason: String) : PingState
}

@HiltViewModel
class HostListViewModel @Inject constructor(
    private val hostDao: HostDao,
    private val sessionManager: SshSessionManager,
) : ViewModel() {

    val hosts: StateFlow<List<HostEntity>> = hostDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Schede terminale attualmente aperte (per la sezione "Sessioni attive"). */
    val activeSessions: StateFlow<List<SshTerminalTab>> = sessionManager.tabs

    /** Bumpato quando i titoli/stati delle schede cambiano, per ricomporre. */
    val sessionsVersion: StateFlow<Int> = sessionManager.tabsVersion

    private val _pings = MutableStateFlow<Map<Long, PingState>>(emptyMap())
    val pings: StateFlow<Map<Long, PingState>> = _pings

    /** Host attualmente sotto monitoraggio continuo, con numero di tentativi. */
    private val _monitored = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val monitored: StateFlow<Map<Long, Int>> = _monitored
    private val monitorJobs = mutableMapOf<Long, Job>()

    fun delete(host: HostEntity) {
        stopMonitor(host.id)
        viewModelScope.launch { hostDao.delete(host) }
    }

    /** Porta in primo piano la scheda scelta prima di aprire la schermata sessione. */
    fun focusSession(tabId: Long) = sessionManager.setActiveTab(tabId)

    fun closeSession(tabId: Long) = sessionManager.closeTab(tabId)

    /**
     * "Ping" dell'host: apre una connessione TCP alla porta SSH e misura il
     * tempo. Su Android il ping ICMP non è affidabile senza root; il connect TCP
     * verifica esattamente ciò che conta per una sessione SSH.
     */
    fun ping(host: HostEntity, timeoutMs: Int = 4000) {
        _pings.value = _pings.value + (host.id to PingState.Checking)
        viewModelScope.launch {
            _pings.value = _pings.value + (host.id to probe(host, timeoutMs))
        }
    }

    private suspend fun probe(host: HostEntity, timeoutMs: Int): PingState =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host.hostname, host.port), timeoutMs)
                }
                PingState.Reachable((System.nanoTime() - start) / 1_000_000)
            } catch (e: java.net.SocketTimeoutException) {
                PingState.Unreachable("timeout (${timeoutMs}ms)")
            } catch (e: java.net.UnknownHostException) {
                PingState.Unreachable("host non risolto")
            } catch (e: Exception) {
                PingState.Unreachable(e.message?.take(40) ?: "irraggiungibile")
            }
        }

    fun isMonitoring(hostId: Long): Boolean = monitorJobs.containsKey(hostId)

    /**
     * Monitoraggio continuo: pinga a intervalli finché non lo si ferma. Utile
     * per vedere quando un host torna online dopo un reboot o se la VPN regge.
     */
    fun toggleMonitor(host: HostEntity, intervalMs: Long = 3000, timeoutMs: Int = 4000) {
        if (monitorJobs.containsKey(host.id)) {
            stopMonitor(host.id)
            return
        }
        _monitored.value = _monitored.value + (host.id to 0)
        val job = viewModelScope.launch {
            var attempts = 0
            while (isActive) {
                _pings.value = _pings.value + (host.id to PingState.Checking)
                val result = probe(host, timeoutMs)
                attempts++
                _pings.value = _pings.value + (host.id to result)
                _monitored.value = _monitored.value + (host.id to attempts)
                delay(intervalMs)
            }
        }
        monitorJobs[host.id] = job
    }

    private fun stopMonitor(hostId: Long) {
        monitorJobs.remove(hostId)?.cancel()
        _monitored.value = _monitored.value - hostId
    }

    override fun onCleared() {
        monitorJobs.values.forEach { it.cancel() }
        monitorJobs.clear()
        super.onCleared()
    }
}
