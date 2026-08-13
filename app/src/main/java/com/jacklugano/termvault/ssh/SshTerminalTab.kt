package com.jacklugano.termvault.ssh

import com.jacklugano.termvault.data.db.HostEntity
import com.jacklugano.termvault.data.db.KnownHostDao
import com.jacklugano.termvault.data.db.PortForwardEntity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong

sealed interface TabState {
    data object Idle : TabState
    data object Connecting : TabState
    data object Connected : TabState

    /** Serve conferma dell'utente sulla chiave host. storedFingerprint != null => MISMATCH. */
    data class AwaitingHostKey(val info: HostKeyInfo, val storedFingerprint: String?) : TabState

    data class Reconnecting(val attempt: Int, val delaySeconds: Int) : TabState
    data class Failed(val message: String) : TabState
    data class Closed(val message: String? = null) : TabState
}

data class ForwardRuntime(
    val config: PortForwardEntity,
    val active: Boolean,
    val error: String? = null,
)

/**
 * Una scheda terminale = una connessione SSH + un TerminalSession termux in
 * stream mode. Gestisce connessione (anche via jump host), riconnessione con
 * backoff, resize del pty e port forwarding.
 */
class SshTerminalTab(
    val host: HostEntity,
    private val credentials: SshCredentials,
    private val jumpHost: HostEntity?,
    private val jumpCredentials: SshCredentials?,
    private val knownHostDao: KnownHostDao,
    private val scope: CoroutineScope,
    private val onAllTabsChanged: () -> Unit,
) {
    val id: Long = NEXT_ID.incrementAndGet()

    private val _state = MutableStateFlow<TabState>(TabState.Idle)
    val state: StateFlow<TabState> = _state

    private val _title = MutableStateFlow(host.name)
    val title: StateFlow<String> = _title

    private val _forwards = MutableStateFlow<List<ForwardRuntime>>(emptyList())
    val forwards: StateFlow<List<ForwardRuntime>> = _forwards

    /** Callback impostate dalla UI quando la TerminalView è collegata. */
    var onTextChanged: (() -> Unit)? = null
    var onCopyToClipboard: ((String) -> Unit)? = null
    var onPasteFromClipboard: (() -> String?)? = null
    var onBell: (() -> Unit)? = null

    lateinit var terminalSession: TerminalSession
        private set

    private var client: SSHClient? = null
    private var jumpClient: SSHClient? = null
    private var shell: Session.Shell? = null
    private var sshSession: Session? = null

    private var cols = 80
    private var rows = 24
    private var cellW = 8
    private var cellH = 16

    @Volatile private var userClosed = false
    private val activeForwarders = mutableMapOf<Long, AutoCloseable>()
    private val forwarderSockets = mutableMapOf<Long, ServerSocket>()

    companion object {
        private val NEXT_ID = AtomicLong(0)
        private const val TERM_TYPE = "xterm-256color"
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            onTextChanged?.invoke()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            _title.value = changedSession.title?.takeIf { it.isNotBlank() } ?: host.name
            onAllTabsChanged()
        }

        override fun onSessionFinished(finishedSession: TerminalSession) = Unit

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            onCopyToClipboard?.invoke(text)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val text = onPasteFromClipboard?.invoke() ?: return
            session.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) {
            onBell?.invoke()
        }

        override fun onColorsChanged(session: TerminalSession) {
            onTextChanged?.invoke()
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private val streamCallback = object : TerminalSession.StreamCallback {
        override fun onResize(columns: Int, rows_: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            cols = columns
            rows = rows_
            cellW = cellWidthPixels
            cellH = cellHeightPixels
            val s = shell ?: return
            scope.launch(Dispatchers.IO) {
                runCatching { s.changeWindowDimensions(columns, rows_, cellWidthPixels, cellHeightPixels) }
            }
        }

        override fun onTerminationRequested() {
            close()
        }
    }

    /** Da chiamare sul main thread prima di connect(). */
    fun initTerminal(transcriptRows: Int) {
        terminalSession = TerminalSession(transcriptRows, sessionClient, streamCallback)
    }

    fun connect() {
        if (userClosed) return
        _state.value = TabState.Connecting
        scope.launch(Dispatchers.IO) { doConnect() }
    }

    private suspend fun doConnect() {
        try {
            val effectivePort = credentials.portOverride ?: host.port

            var jump: SSHClient? = null
            val ssh = SSHClient()
            ssh.connectTimeout = 15_000
            ssh.addHostKeyVerifier(DatabaseHostKeyVerifier(knownHostDao))

            if (jumpHost != null && jumpCredentials != null) {
                jump = SSHClient()
                jump.connectTimeout = 15_000
                jump.addHostKeyVerifier(DatabaseHostKeyVerifier(knownHostDao))
                jump.connect(jumpHost.hostname, jumpHost.port)
                authenticate(jump, jumpHost, jumpCredentials)
                ssh.connectVia(jump.newDirectConnection(host.hostname, effectivePort))
            } else {
                ssh.connect(host.hostname, effectivePort)
            }

            authenticate(ssh, host, credentials)

            val session = ssh.startSession()
            session.allocatePTY(TERM_TYPE, cols, rows, cellW, cellH, emptyMap())
            val sh = session.startShell()

            client = ssh
            jumpClient = jump
            sshSession = session
            shell = sh
            _state.value = TabState.Connected
            appendInfo("\r\n[connesso a ${host.hostname}]\r\n")

            startIoThreads(sh)
        } catch (e: Exception) {
            teardownConnection()
            // sshj esegue il key exchange sul thread di trasporto e riconsegna
            // le eccezioni del verifier incapsulate in TransportException:
            // vanno cercate lungo la catena delle cause.
            val unknownKey = findCause<UnknownHostKeyException>(e)
            val mismatch = findCause<HostKeyMismatchException>(e)
            val authError = findCause<UserAuthException>(e)
            when {
                unknownKey != null ->
                    _state.value = TabState.AwaitingHostKey(unknownKey.info, storedFingerprint = null)

                mismatch != null ->
                    _state.value = TabState.AwaitingHostKey(mismatch.info, mismatch.storedFingerprint)

                authError != null ->
                    _state.value = TabState.Failed("Autenticazione fallita: ${authError.message}")

                e is TransportException || e is IOException ->
                    handleDisconnect("Connessione fallita: ${e.message}")

                else -> _state.value = TabState.Failed("Errore: ${e.message}")
            }
        }
    }

    private inline fun <reified T : Throwable> findCause(root: Throwable): T? {
        var current: Throwable? = root
        var depth = 0
        while (current != null && depth < 12) {
            if (current is T) return current
            current = current.cause
            depth++
        }
        return null
    }

    private fun authenticate(ssh: SSHClient, target: HostEntity, creds: SshCredentials) {
        val user = creds.username.ifBlank { target.username }
        if (user.isBlank()) throw UserAuthException("Nessun nome utente disponibile")

        if (creds.hasKey) {
            val keyProvider = creds.keyProvider ?: ssh.loadKeys(
                String(creds.privateKey!!),
                null,
                creds.passphrase?.let { ReusablePasswordFinder(it) },
            )
            ssh.authPublickey(user, keyProvider)
        } else if (creds.hasPassword) {
            ssh.authPassword(user, ReusablePasswordFinder(creds.password!!))
        } else {
            throw UserAuthException("Nessuna credenziale disponibile (né chiave né password)")
        }
    }

    private fun startIoThreads(sh: Session.Shell) {
        val input = sh.inputStream
        val output = sh.outputStream

        Thread({
            val buffer = ByteArray(8 * 1024)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    terminalSession.appendOutput(buffer, 0, read)
                }
            } catch (_: Exception) {
                // stream chiuso
            }
            handleDisconnect("connessione persa")
        }, "ssh-reader-$id").start()

        Thread({
            val buffer = ByteArray(4 * 1024)
            try {
                while (true) {
                    val toWrite = terminalSession.readTerminalInput(buffer)
                    if (toWrite == -1) break
                    output.write(buffer, 0, toWrite)
                    output.flush()
                }
            } catch (_: Exception) {
                // stream chiuso
            }
        }, "ssh-writer-$id").start()

        // Riattiva i forwarding configurati come attivi prima della disconnessione.
        val toRestore = _forwards.value.filter { it.active }.map { it.config }
        _forwards.value = _forwards.value.map { it.copy(active = false) }
        toRestore.forEach { startForward(it) }
    }

    /**
     * La connessione è caduta o la shell remota è terminata. Niente
     * riconnessione automatica: si marca la sessione come chiusa e si lascia
     * all'utente l'eventuale riconnessione manuale (vedi [retry]).
     */
    @Synchronized
    private fun handleDisconnect(reason: String) {
        if (userClosed) return
        val relevant = _state.value is TabState.Connected ||
            _state.value is TabState.Connecting
        if (!relevant) return
        teardownConnection()
        appendInfo("\r\n[$reason — sessione chiusa]\r\n")
        _state.value = TabState.Closed(reason)
    }

    /** L'utente ha accettato la chiave host (prima connessione o sostituzione esplicita). */
    fun acceptHostKey(info: HostKeyInfo, replaceExisting: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (replaceExisting) knownHostDao.deleteAllFor(info.hostname, info.port)
            knownHostDao.upsert(info.toEntity())
            _state.value = TabState.Connecting
            doConnect()
        }
    }

    fun rejectHostKey() {
        close("chiave host rifiutata")
    }

    /** Riconnessione MANUALE, avviata solo dall'utente. */
    fun retry() {
        if (userClosed) return
        connect()
    }

    /** Invia testo (snippet o incolla) come input al canale remoto. */
    fun sendText(text: String) {
        terminalSession.write(text)
    }

    // ---- Port forwarding ----------------------------------------------------

    fun setForwardConfigs(configs: List<PortForwardEntity>) {
        val current = _forwards.value.associateBy { it.config.id }
        _forwards.value = configs.map { cfg ->
            current[cfg.id] ?: ForwardRuntime(cfg, active = false)
        }
    }

    fun startForward(config: PortForwardEntity) {
        val ssh = client ?: run {
            updateForward(config.id) { it.copy(error = "non connesso") }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                when (config.type) {
                    com.jacklugano.termvault.data.db.ForwardType.LOCAL -> {
                        val serverSocket = ServerSocket()
                        serverSocket.reuseAddress = true
                        serverSocket.bind(InetSocketAddress(config.bindHost, config.bindPort))
                        val params = Parameters(
                            config.bindHost, config.bindPort,
                            config.targetHost, config.targetPort,
                        )
                        val forwarder: LocalPortForwarder = ssh.newLocalPortForwarder(params, serverSocket)
                        synchronized(activeForwarders) {
                            forwarderSockets[config.id] = serverSocket
                            activeForwarders[config.id] = AutoCloseable {
                                runCatching { forwarder.close() }
                                runCatching { serverSocket.close() }
                            }
                        }
                        updateForward(config.id) { it.copy(active = true, error = null) }
                        // listen() blocca finché il forwarder non viene chiuso.
                        Thread({ runCatching { forwarder.listen() } }, "fwd-L-${config.id}").start()
                    }

                    com.jacklugano.termvault.data.db.ForwardType.REMOTE -> {
                        val forwarder: RemotePortForwarder = ssh.remotePortForwarder
                        val forward = forwarder.bind(
                            RemotePortForwarder.Forward(config.bindHost, config.bindPort),
                            SocketForwardingConnectListener(
                                InetSocketAddress(config.targetHost, config.targetPort)
                            ),
                        )
                        synchronized(activeForwarders) {
                            activeForwarders[config.id] = AutoCloseable {
                                runCatching { forwarder.cancel(forward) }
                            }
                        }
                        updateForward(config.id) { it.copy(active = true, error = null) }
                    }
                }
            } catch (e: Exception) {
                updateForward(config.id) { it.copy(active = false, error = e.message) }
            }
        }
    }

    fun stopForward(configId: Long) {
        scope.launch(Dispatchers.IO) {
            synchronized(activeForwarders) {
                activeForwarders.remove(configId)?.let { runCatching { it.close() } }
                forwarderSockets.remove(configId)
            }
            updateForward(configId) { it.copy(active = false, error = null) }
        }
    }

    private fun updateForward(configId: Long, transform: (ForwardRuntime) -> ForwardRuntime) {
        _forwards.value = _forwards.value.map { if (it.config.id == configId) transform(it) else it }
    }

    // ---- Chiusura ------------------------------------------------------------

    fun close(reason: String? = null) {
        if (userClosed) return
        userClosed = true
        scope.launch(Dispatchers.IO) {
            teardownConnection()
            credentials.wipe()
            jumpCredentials?.wipe()
            _state.value = TabState.Closed(reason)
            terminalSession.onStreamClosed(0)
            onAllTabsChanged()
        }
    }

    private fun teardownConnection() {
        synchronized(activeForwarders) {
            activeForwarders.values.forEach { runCatching { it.close() } }
            activeForwarders.clear()
            forwarderSockets.clear()
        }
        _forwards.value = _forwards.value.map { it.copy(active = false) }
        runCatching { shell?.close() }
        runCatching { sshSession?.close() }
        runCatching { client?.disconnect() }
        runCatching { jumpClient?.disconnect() }
        shell = null
        sshSession = null
        client = null
        jumpClient = null
    }

    private fun appendInfo(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        terminalSession.appendOutput(bytes, 0, bytes.size)
    }
}

/**
 * PasswordFinder riutilizzabile: sshj può richiedere la password più volte
 * (retry, riconnessione). Restituisce sempre una copia, che sshj azzera dopo l'uso.
 */
private class ReusablePasswordFinder(private val secret: CharArray) : PasswordFinder {
    override fun reqPassword(resource: Resource<*>?): CharArray = secret.copyOf()
    override fun shouldRetry(resource: Resource<*>?): Boolean = false
}
