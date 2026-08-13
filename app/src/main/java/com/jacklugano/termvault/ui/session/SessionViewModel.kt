package com.jacklugano.termvault.ui.session

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacklugano.termvault.crypto.LocalKeyManager
import com.jacklugano.termvault.data.db.AuthMode
import com.jacklugano.termvault.data.db.HostDao
import com.jacklugano.termvault.data.db.HostEntity
import com.jacklugano.termvault.data.db.SnippetDao
import com.jacklugano.termvault.data.db.SnippetEntity
import com.jacklugano.termvault.kp2a.Kp2aFields
import com.jacklugano.termvault.ssh.SshCredentials
import com.jacklugano.termvault.ssh.SshSessionManager
import com.jacklugano.termvault.ssh.SshTerminalTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Richieste che la UI deve soddisfare prima di poter aprire la connessione.
 * La UI mostra il dialog (o lancia l'intent KP2A) e completa il deferred.
 */
sealed interface ConnectPrompt {
    val deferred: CompletableDeferred<Any?>

    /** Chiedi una password all'utente. Completa con CharArray o null (annulla). */
    data class Password(
        val hostName: String,
        val isPassphrase: Boolean,
        override val deferred: CompletableDeferred<Any?>,
    ) : ConnectPrompt

    /**
     * Interroga Keepass2Android con [query]. Completa con Map<String,String>
     * dei campi entry, o null se annullato/KP2A assente.
     */
    data class Kp2aQuery(
        val query: String,
        val hostName: String,
        override val deferred: CompletableDeferred<Any?>,
    ) : ConnectPrompt

    /**
     * Chiedi a "OpenVPN for Android" di attivare [profile]. La UI lancia
     * l'intent e completa con true (lanciato) o false (app assente).
     */
    data class StartVpn(
        val profile: String,
        override val deferred: CompletableDeferred<Any?>,
    ) : ConnectPrompt
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val manager: SshSessionManager,
    private val hostDao: HostDao,
    snippetDao: SnippetDao,
    private val localKeys: LocalKeyManager,
    private val prefs: SharedPreferences,
) : ViewModel() {

    val tabs: StateFlow<List<SshTerminalTab>> = manager.tabs
    val activeTabId: StateFlow<Long?> = manager.activeTabId
    val tabsVersion: StateFlow<Int> = manager.tabsVersion

    val snippets: StateFlow<List<SnippetEntity>> = snippetDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _prompt = MutableStateFlow<ConnectPrompt?>(null)
    val prompt: StateFlow<ConnectPrompt?> = _prompt

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError

    /** Messaggio di avanzamento pre-connessione (es. attivazione VPN). */
    private val _connectStatus = MutableStateFlow<String?>(null)
    val connectStatus: StateFlow<String?> = _connectStatus

    private val _fontSizeSp = MutableStateFlow(prefs.getInt(PREF_FONT_SIZE, 14))
    val fontSizeSp: StateFlow<Int> = _fontSizeSp

    private var connectRequestedFor: Long? = null

    init {
        val requestedHostId = savedStateHandle.get<Long>("hostId") ?: 0L
        if (requestedHostId != 0L) {
            connectTo(requestedHostId)
        }
    }

    /** Apre una NUOVA scheda verso l'host indicato (usata anche dal pulsante +). */
    fun connectTo(hostId: Long) {
        if (connectRequestedFor == hostId) return
        connectRequestedFor = hostId
        viewModelScope.launch {
            try {
                val host = hostDao.getById(hostId) ?: run {
                    _connectError.value = "Host inesistente"
                    return@launch
                }
                val jumpHost = host.jumpHostId?.let { hostDao.getById(it) }

                if (!ensureVpn(host, jumpHost)) return@launch

                val jumpCreds = jumpHost?.let { resolveCredentials(it) }
                if (jumpHost != null && jumpCreds == null) return@launch // annullato

                val creds = resolveCredentials(host) ?: return@launch // annullato

                manager.openTab(host, creds, jumpHost, jumpCreds)
            } catch (e: Exception) {
                _connectError.value = e.message ?: "Errore di connessione"
            } finally {
                connectRequestedFor = null
            }
        }
    }

    /**
     * Risolve le credenziali secondo la modalità dell'host, sospendendo sui
     * prompt UI. Ritorna null se l'utente annulla.
     */
    private suspend fun resolveCredentials(host: HostEntity): SshCredentials? =
        when (host.authMode) {
            AuthMode.PROMPT -> {
                val password = askPassword(host.name, isPassphrase = false) ?: return null
                SshCredentials(username = host.username, password = password)
            }

            AuthMode.KP2A -> {
                val fields = askKp2a(host) ?: return null
                Kp2aFields.toCredentials(fields)
            }

            AuthMode.LOCAL_KEY -> {
                val alias = host.localKeyAlias ?: "host-${host.id}".also { newAlias ->
                    hostDao.upsert(host.copy(localKeyAlias = newAlias))
                }
                val passphrase: CharArray? = if (host.kp2aForPassphrase) {
                    val fields = askKp2a(host) ?: return null
                    Kp2aFields.normalize(fields)[Kp2aFields.PASSWORD]?.toCharArray()
                } else null

                if (!localKeys.exists(alias)) {
                    localKeys.generate(alias, passphrase)
                }
                SshCredentials(
                    username = host.username,
                    passphrase = passphrase,
                    keyProvider = localKeys.keyProvider(alias, passphrase),
                )
            }
        }

    /**
     * Se l'host (o il suo jump host) richiede una VPN OpenVPN, la attiva e
     * attende che la destinazione da raggiungere per prima risponda in TCP.
     * Ritorna false se l'utente/l'ambiente non permette di procedere.
     */
    private suspend fun ensureVpn(host: HostEntity, jumpHost: HostEntity?): Boolean {
        val profile = host.openVpnProfile.ifBlank { jumpHost?.openVpnProfile.orEmpty() }
        if (profile.isBlank()) return true

        // Il primo hop è il jump host se presente, altrimenti l'host stesso.
        val probeHost = jumpHost ?: host

        if (isReachable(probeHost, 2_500)) return true // VPN già attiva

        val deferred = CompletableDeferred<Any?>()
        _prompt.value = ConnectPrompt.StartVpn(profile, deferred)
        val launched = deferred.await() as? Boolean ?: false
        _prompt.value = null
        if (!launched) return false

        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < VPN_WAIT_MS) {
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            _connectStatus.value =
                "VPN \"$profile\" in attivazione — attendo ${probeHost.hostname}… (${elapsed}s)"
            if (isReachable(probeHost, 3_000)) {
                _connectStatus.value = null
                return true
            }
            kotlinx.coroutines.delay(1_500)
        }
        _connectStatus.value = null
        _connectError.value =
            "${probeHost.hostname} non raggiungibile dopo ${VPN_WAIT_MS / 1000}s: " +
                "VPN non attiva o nome profilo errato"
        return false
    }

    private suspend fun isReachable(host: HostEntity, timeoutMs: Int): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                java.net.Socket().use {
                    it.connect(java.net.InetSocketAddress(host.hostname, host.port), timeoutMs)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

    private suspend fun askPassword(hostName: String, isPassphrase: Boolean): CharArray? {
        val deferred = CompletableDeferred<Any?>()
        _prompt.value = ConnectPrompt.Password(hostName, isPassphrase, deferred)
        val result = deferred.await()
        _prompt.value = null
        return result as? CharArray
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun askKp2a(host: HostEntity): Map<String, String>? {
        if (host.kp2aQuery.isBlank()) {
            _connectError.value = "Query KP2A non impostata per ${host.name}"
            return null
        }
        val deferred = CompletableDeferred<Any?>()
        _prompt.value = ConnectPrompt.Kp2aQuery(host.kp2aQuery, host.name, deferred)
        val result = deferred.await()
        _prompt.value = null
        return result as? Map<String, String>
    }

    fun reportKp2aError(message: String) {
        _connectError.value = message
    }

    fun dismissError() {
        _connectError.value = null
    }

    fun setActiveTab(id: Long) = manager.setActiveTab(id)

    fun closeTab(id: Long) = manager.closeTab(id)

    fun activeTab(): SshTerminalTab? = manager.tabById(activeTabId.value)

    fun sendSnippet(snippet: SnippetEntity) {
        val text = if (snippet.autoRun) snippet.command + "\n" else snippet.command
        activeTab()?.sendText(text)
    }

    fun setFontSize(sp: Int) {
        val clamped = sp.coerceIn(8, 32)
        _fontSizeSp.value = clamped
        prefs.edit().putInt(PREF_FONT_SIZE, clamped).apply()
    }

    /** Esporta la chiave pubblica locale dell'host attivo, se in modalità chiave locale. */
    fun publicKeyForActiveTab(): String? {
        val tab = activeTab() ?: return null
        val alias = tab.host.localKeyAlias ?: return null
        return if (localKeys.exists(alias)) localKeys.publicKeyOpenSsh(alias) else null
    }

    companion object {
        private const val PREF_FONT_SIZE = "terminal_font_size_sp"
        private const val VPN_WAIT_MS = 60_000L
    }
}
