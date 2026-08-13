package com.jacklugano.termvault.ui.session

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacklugano.termvault.ssh.SshTerminalTab
import com.jacklugano.termvault.ssh.TabState
import com.termux.view.TerminalView
import keepass2android.pluginsdk.Kp2aControl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    requestedHostId: Long,
    onBack: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val tabsVersion by viewModel.tabsVersion.collectAsStateWithLifecycle()
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val connectError by viewModel.connectError.collectAsStateWithLifecycle()
    val fontSizeSp by viewModel.fontSizeSp.collectAsStateWithLifecycle()
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()

    val modifierKeys = remember { ModifierKeysState() }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var showSnippets by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showPubKey by rememberSaveable { mutableStateOf(false) }
    var showForwards by rememberSaveable { mutableStateOf(false) }

    val activeTab = tabs.firstOrNull { it.id == activeTabId }

    // Permesso notifiche per il foreground service (Android 13+).
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Launcher per la query credenziali verso Keepass2Android.
    val kp2aLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val p = viewModel.prompt.value as? ConnectPrompt.Kp2aQuery ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val fields: Map<String, String> = Kp2aControl.getEntryFieldsFromIntent(result.data)
            if (fields.isEmpty()) {
                viewModel.reportKp2aError("KP2A non ha restituito campi per la query \"${p.query}\"")
                p.deferred.complete(null)
            } else {
                p.deferred.complete(fields)
            }
        } else {
            p.deferred.complete(null)
        }
    }

    LaunchedEffect(prompt) {
        val p = prompt
        if (p is ConnectPrompt.Kp2aQuery) {
            try {
                kp2aLauncher.launch(Kp2aControl.getQueryEntryIntent(p.query))
            } catch (_: ActivityNotFoundException) {
                viewModel.reportKp2aError(
                    "Keepass2Android non è installato: installa l'app o cambia " +
                        "la modalità di autenticazione dell'host."
                )
                p.deferred.complete(null)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        activeTab?.title?.collectAsStateWithLifecycle()?.value ?: "Terminale",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Cerca")
                    }
                    IconButton(onClick = { viewModel.setFontSize(fontSizeSp - 1) }) {
                        Icon(Icons.Default.TextDecrease, contentDescription = "Font più piccolo")
                    }
                    IconButton(onClick = { viewModel.setFontSize(fontSizeSp + 1) }) {
                        Icon(Icons.Default.TextIncrease, contentDescription = "Font più grande")
                    }
                    IconButton(onClick = { showSnippets = true }) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Snippet")
                    }
                    if (activeTab != null) {
                        IconButton(onClick = { showForwards = true }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Port forwarding")
                        }
                    }
                    if (viewModel.publicKeyForActiveTab() != null) {
                        IconButton(onClick = { showPubKey = true }) {
                            Icon(Icons.Default.Key, contentDescription = "Chiave pubblica")
                        }
                    }
                    if (activeTab != null) {
                        IconButton(onClick = { viewModel.closeTab(activeTab.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Chiudi scheda")
                        }
                    }
                },
            )
        },
    ) { padding ->
        // imePadding(): con enableEdgeToEdge la finestra non si ridimensiona da
        // sola all'apertura della tastiera; così il terminale e la riga dei tasti
        // si accorciano restando visibili sopra la tastiera.
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Barra schede custom: ScrollableTabRow di Material3 va in
            // IndexOutOfBounds quando il numero di schede cala (bug noto sul suo
            // indice interno). Qui l'indice non esiste, niente crash.
            if (tabs.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tabs.forEach { tab ->
                        val title by tab.title.collectAsStateWithLifecycle()
                        val selected = tab.id == activeTabId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.shapes.small,
                                )
                                .clickable { viewModel.setActiveTab(tab.id) }
                                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Box(
                                Modifier
                                    .padding(end = 6.dp)
                                    .size(8.dp)
                                    .background(Color(tab.host.color), MaterialTheme.shapes.small),
                            )
                            Text(title, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                            IconButton(
                                onClick = { viewModel.closeTab(tab.id) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Chiudi ${title}",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (activeTab == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Nessuna sessione attiva")
                }
            } else {
                key(activeTab.id) {
                    val tabState by activeTab.state.collectAsStateWithLifecycle()

                    StateBanner(tab = activeTab, state = tabState)

                    TerminalPane(
                        tab = activeTab,
                        fontSizeSp = fontSizeSp,
                        modifierKeys = modifierKeys,
                        onFontSizeChange = { viewModel.setFontSize(it) },
                        onViewReady = { terminalView = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    HostKeyDialogs(tab = activeTab, state = tabState)
                }
            }

            if (showSearch && activeTab != null) {
                ScrollbackSearchBar(
                    tab = activeTab,
                    terminalView = terminalView,
                    onClose = { showSearch = false },
                )
            }

            val keyboardContext = androidx.compose.ui.platform.LocalContext.current
            ExtraKeysRow(
                modifierKeys = modifierKeys,
                terminalView = terminalView,
                onToggleKeyboard = {
                    terminalView?.let { showKeyboard(keyboardContext, it) }
                },
                onCopy = {
                    val tv = terminalView
                    val tab = activeTab
                    if (tv != null && tab != null) {
                        val text = if (tv.isSelectingText) {
                            tv.selectedText.also { tv.stopTextSelectionMode() }
                        } else {
                            tab.terminalSession.emulator?.screen?.transcriptText?.trimEnd()
                        }
                        if (!text.isNullOrEmpty()) tab.onCopyToClipboard?.invoke(text)
                    }
                },
                onPaste = {
                    activeTab?.let { tab ->
                        val text = tab.onPasteFromClipboard?.invoke()
                        if (text != null) tab.terminalSession.emulator?.paste(text)
                    }
                },
            )
        }
    }

    // Connessione iniziale: la avvia il ViewModel in init.

    // Dialog password / passphrase.
    (prompt as? ConnectPrompt.Password)?.let { p ->
        PasswordDialog(prompt = p)
    }

    // Errori di connessione (KP2A assente, host inesistente...).
    connectError?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Errore") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
            },
        )
    }

    if (showSnippets) {
        ModalBottomSheet(onDismissRequest = { showSnippets = false }) {
            if (snippets.isEmpty()) {
                Text(
                    "Nessuno snippet salvato. Creali dalla lista host.",
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                    items(snippets, key = { it.id }) { snippet ->
                        TextButton(
                            onClick = {
                                viewModel.sendSnippet(snippet)
                                showSnippets = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(snippet.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    snippet.command,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForwards && activeTab != null) {
        val forwards by activeTab.forwards.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = { showForwards = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Port forwarding", style = MaterialTheme.typography.titleMedium)
                if (forwards.isEmpty()) {
                    Text(
                        "Nessun forwarding configurato per questo host. " +
                            "Aggiungili dalla schermata di modifica host.",
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                forwards.forEach { fwd ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val arrow = if (fwd.config.type.name == "LOCAL") "→" else "←"
                            Text(
                                "${fwd.config.type.name} ${fwd.config.bindHost}:${fwd.config.bindPort} " +
                                    "$arrow ${fwd.config.targetHost}:${fwd.config.targetPort}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            fwd.error?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        androidx.compose.material3.Switch(
                            checked = fwd.active,
                            onCheckedChange = { on ->
                                if (on) activeTab.startForward(fwd.config)
                                else activeTab.stopForward(fwd.config.id)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showPubKey) {
        val pubKey = viewModel.publicKeyForActiveTab()
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { showPubKey = false },
            title = { Text("Chiave pubblica (authorized_keys)") },
            text = {
                SelectionContainer {
                    Text(
                        pubKey ?: "n/d",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, pubKey)
                    }
                    context.startActivity(Intent.createChooser(share, "Esporta chiave pubblica"))
                }) { Text("Condividi") }
            },
            dismissButton = {
                TextButton(onClick = { showPubKey = false }) { Text("Chiudi") }
            },
        )
    }
}

@Composable
private fun StateBanner(tab: SshTerminalTab, state: TabState) {
    val (text, color) = when (state) {
        is TabState.Connecting -> "Connessione a ${tab.host.hostname}…" to MaterialTheme.colorScheme.surfaceVariant
        is TabState.Reconnecting ->
            "Riconnessione (tentativo ${state.attempt}, tra ${state.delaySeconds}s)…" to
                MaterialTheme.colorScheme.tertiaryContainer
        is TabState.Failed -> state.message to MaterialTheme.colorScheme.errorContainer
        is TabState.Closed -> (state.message ?: "Sessione chiusa") to MaterialTheme.colorScheme.surfaceVariant
        else -> return
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        // Nessuna riconnessione automatica: solo un pulsante manuale.
        if (state is TabState.Failed || state is TabState.Closed) {
            TextButton(onClick = { tab.retry() }) { Text("Riconnetti") }
        }
    }
}

@Composable
private fun HostKeyDialogs(tab: SshTerminalTab, state: TabState) {
    val awaiting = state as? TabState.AwaitingHostKey ?: return
    val info = awaiting.info
    val isMismatch = awaiting.storedFingerprint != null

    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                if (isMismatch) "⚠️ CHIAVE HOST CAMBIATA!" else "Verifica chiave host",
                color = if (isMismatch) MaterialTheme.colorScheme.error else Color.Unspecified,
                fontWeight = if (isMismatch) FontWeight.Bold else null,
            )
        },
        text = {
            Column {
                if (isMismatch) {
                    Text(
                        "La chiave presentata da ${info.hostname}:${info.port} NON corrisponde " +
                            "a quella salvata. Potrebbe trattarsi di un attacco man-in-the-middle. " +
                            "Continua solo se sai che il server è stato reinstallato.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text("\nFingerprint salvato:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        awaiting.storedFingerprint ?: "",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "Prima connessione a ${info.hostname}:${info.port}. " +
                            "Verifica che il fingerprint corrisponda a quello del server."
                    )
                }
                Text("\nTipo: ${info.keyType}", style = MaterialTheme.typography.labelMedium)
                Text("Fingerprint ricevuto:", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(
                        info.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { tab.acceptHostKey(info, replaceExisting = isMismatch) }) {
                Text(
                    if (isMismatch) "SOSTITUISCI (rischioso)" else "Accetta e connetti",
                    color = if (isMismatch) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { tab.rejectHostKey() }) { Text("Rifiuta") }
        },
    )
}

@Composable
private fun PasswordDialog(prompt: ConnectPrompt.Password) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { prompt.deferred.complete(null) },
        title = {
            Text(if (prompt.isPassphrase) "Passphrase per ${prompt.hostName}" else "Password per ${prompt.hostName}")
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(if (prompt.isPassphrase) "Passphrase" else "Password") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            TextButton(onClick = { prompt.deferred.complete(value.toCharArray()) }) {
                Text("Connetti")
            }
        },
        dismissButton = {
            TextButton(onClick = { prompt.deferred.complete(null) }) { Text("Annulla") }
        },
    )
}

@Composable
private fun ScrollbackSearchBar(
    tab: SshTerminalTab,
    terminalView: TerminalView?,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var current by remember { mutableStateOf(-1) }

    fun search() {
        val emulator = tab.terminalSession.emulator ?: return
        val transcript = emulator.screen.transcriptText
        if (query.isBlank() || transcript.isNullOrEmpty()) {
            matches = emptyList(); current = -1
            return
        }
        val lines = transcript.lines()
        matches = lines.withIndex()
            .filter { (_, line) -> line.contains(query, ignoreCase = true) }
            .map { it.index }
        current = if (matches.isEmpty()) -1 else matches.lastIndex
        if (current >= 0) jumpToLine(matches[current], tab, terminalView)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Cerca nello scrollback") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { search() }) { Text("Cerca") }
        TextButton(
            enabled = matches.isNotEmpty(),
            onClick = {
                if (matches.isNotEmpty()) {
                    current = (current - 1 + matches.size) % matches.size
                    jumpToLine(matches[current], tab, terminalView)
                }
            },
        ) { Text("↑") }
        TextButton(
            enabled = matches.isNotEmpty(),
            onClick = {
                if (matches.isNotEmpty()) {
                    current = (current + 1) % matches.size
                    jumpToLine(matches[current], tab, terminalView)
                }
            },
        ) { Text("↓") }
        Text(
            if (matches.isEmpty()) "0/0" else "${current + 1}/${matches.size}",
            style = MaterialTheme.typography.labelMedium,
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Chiudi ricerca")
        }
    }

}

/**
 * Porta in vista la riga [lineIndex] del transcript: la riga i-esima (contata
 * dall'inizio dello scrollback) corrisponde a topRow = i - activeTranscriptRows
 * (0 = schermo attuale, valori negativi = scrollback).
 */
private fun jumpToLine(lineIndex: Int, tab: SshTerminalTab, view: TerminalView?) {
    if (lineIndex < 0 || view == null) return
    val emulator = tab.terminalSession.emulator ?: return
    val transcriptRows = emulator.screen.activeTranscriptRows
    view.topRow = (lineIndex - transcriptRows).coerceIn(-transcriptRows, 0)
    view.invalidate()
}
