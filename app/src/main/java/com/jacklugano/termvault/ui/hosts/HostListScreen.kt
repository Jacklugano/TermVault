package com.jacklugano.termvault.ui.hosts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacklugano.termvault.data.db.HostEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSnippets: () -> Unit,
    viewModel: HostListViewModel = hiltViewModel(),
) {
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val pings by viewModel.pings.collectAsStateWithLifecycle()
    val monitored by viewModel.monitored.collectAsStateWithLifecycle()
    val sessions by viewModel.activeSessions.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val sessionsVersion by viewModel.sessionsVersion.collectAsStateWithLifecycle()
    var hostToDelete by remember { mutableStateOf<HostEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TermVault") },
                actions = {
                    IconButton(onClick = onOpenSnippets) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Snippet")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHost) {
                Icon(Icons.Default.Add, contentDescription = "Aggiungi host")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            if (sessions.isNotEmpty()) {
                item(key = "sessions-header") {
                    Text(
                        "Sessioni attive",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                items(sessions, key = { "session-${it.id}" }) { tab ->
                    ActiveSessionCard(
                        tab = tab,
                        onOpen = {
                            viewModel.focusSession(tab.id)
                            onOpenSessions()
                        },
                        onClose = { viewModel.closeSession(tab.id) },
                    )
                }
                item(key = "hosts-header") {
                    Text(
                        "Host",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
            }

            if (hosts.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Nessun host.\nTocca + per aggiungerne uno.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                items(hosts, key = { "host-${it.id}" }) { host ->
                    HostCard(
                        host = host,
                        ping = pings[host.id],
                        monitorAttempts = monitored[host.id],
                        onClick = { onConnect(host.id) },
                        onEdit = { onEditHost(host.id) },
                        onDelete = { hostToDelete = host },
                        onPing = { viewModel.ping(host) },
                        onToggleMonitor = { viewModel.toggleMonitor(host) },
                    )
                }
            }
        }
    }

    hostToDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { hostToDelete = null },
            title = { Text("Eliminare ${host.name}?") },
            text = { Text("Verranno rimossi anche i port forwarding associati.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(host)
                    hostToDelete = null
                }) { Text("Elimina") }
            },
            dismissButton = {
                TextButton(onClick = { hostToDelete = null }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun ActiveSessionCard(
    tab: com.jacklugano.termvault.ssh.SshTerminalTab,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val title by tab.title.collectAsStateWithLifecycle()
    val state by tab.state.collectAsStateWithLifecycle()
    val (label, dotColor) = when (state) {
        is com.jacklugano.termvault.ssh.TabState.Connected -> "connesso" to Color(0xFF00E676)
        is com.jacklugano.termvault.ssh.TabState.Connecting -> "connessione…" to Color(0xFFFFD740)
        is com.jacklugano.termvault.ssh.TabState.Reconnecting -> "riconnessione…" to Color(0xFFFF6E40)
        is com.jacklugano.termvault.ssh.TabState.AwaitingHostKey -> "verifica chiave" to Color(0xFFFF6E40)
        is com.jacklugano.termvault.ssh.TabState.Failed -> "errore" to Color(0xFFFF5252)
        is com.jacklugano.termvault.ssh.TabState.Closed -> "chiusa" to Color(0xFFB0BEC5)
        else -> "…" to Color(0xFFB0BEC5)
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Chiudi sessione")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: HostEntity,
    ping: PingState?,
    monitorAttempts: Int?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPing: () -> Unit,
    onToggleMonitor: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(Color(host.color), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    host.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val target = buildString {
                    if (host.username.isNotBlank()) append("${host.username}@")
                    append(host.hostname)
                    if (host.port != 22) append(":${host.port}")
                }
                Text(
                    target,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PingResultLabel(ping, monitorAttempts)
                if (host.tagList.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        host.tagList.take(3).forEach { tag ->
                            AssistChip(onClick = {}, label = { Text(tag) })
                        }
                    }
                }
            }
            val monitoring = monitorAttempts != null
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(
                        onClick = onPing,
                        onLongClick = onToggleMonitor,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (monitoring || ping is PingState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = if (monitoring) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    if (monitoring) Icons.Default.Sensors else Icons.Default.NetworkCheck,
                    contentDescription = if (monitoring) "Ferma monitoraggio" else "Ping (tieni premuto per monitorare)",
                    tint = if (monitoring) MaterialTheme.colorScheme.tertiary
                    else LocalContentColor.current,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Modifica")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Elimina")
            }
            Icon(Icons.Default.Terminal, contentDescription = null)
        }
    }
}

@Composable
private fun PingResultLabel(ping: PingState?, monitorAttempts: Int?) {
    val suffix = if (monitorAttempts != null) " · monitor #$monitorAttempts" else ""
    when (ping) {
        null -> {
            if (monitorAttempts != null) {
                Text("monitoraggio…", style = MaterialTheme.typography.labelSmall)
            }
        }
        is PingState.Checking ->
            Text("ping…$suffix", style = MaterialTheme.typography.labelSmall)
        is PingState.Reachable -> Text(
            "● raggiungibile · ${ping.millis} ms$suffix",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF00A854),
        )
        is PingState.Unreachable -> Text(
            "● irraggiungibile · ${ping.reason}$suffix",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
