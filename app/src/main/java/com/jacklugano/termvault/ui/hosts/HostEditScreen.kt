package com.jacklugano.termvault.ui.hosts

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacklugano.termvault.data.db.AuthMode
import com.jacklugano.termvault.data.db.ForwardType
import com.jacklugano.termvault.kp2a.Kp2aFields
import keepass2android.pluginsdk.Kp2aControl

private val HostColors = listOf(
    0xFF00E676, 0xFF40C4FF, 0xFFFFD740, 0xFFFF6E40,
    0xFFE040FB, 0xFFFF4081, 0xFF69F0AE, 0xFFB0BEC5,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    onDone: () -> Unit,
    viewModel: HostEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val otherHosts by viewModel.otherHosts.collectAsStateWithLifecycle()

    // Derivato dallo stato osservato così Compose riabilita il pulsante mentre si digita.
    val canSave = state.name.isNotBlank() && state.hostname.isNotBlank() &&
        (state.port.toIntOrNull() ?: 0) in 1..65535

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.id == 0L) "Nuovo host" else "Modifica host") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.save(onDone) },
                        enabled = canSave,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Salva")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!canSave) {
                Text(
                    "Compila Nome e Hostname per abilitare il salvataggio (✓ in alto a destra).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text("Nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.hostname,
                    onValueChange = { v -> viewModel.update { it.copy(hostname = v) } },
                    label = { Text("Hostname / IP") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = { v -> viewModel.update { it.copy(port = v.filter(Char::isDigit).take(5)) } },
                    label = { Text("Porta") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = state.username,
                onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                label = { Text("Utente (vuoto = da KP2A)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.tags,
                onValueChange = { v -> viewModel.update { it.copy(tags = v) } },
                label = { Text("Tag (separati da virgola)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Colore", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HostColors.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(c), CircleShape)
                            .then(
                                if (state.color == c) Modifier.border(
                                    3.dp, MaterialTheme.colorScheme.onBackground, CircleShape
                                ) else Modifier
                            )
                            .clickable { viewModel.update { it.copy(color = c) } },
                    )
                }
            }

            JumpHostSelector(
                currentId = state.jumpHostId,
                candidates = otherHosts.map { it.id to it.name },
                onSelect = { id -> viewModel.update { it.copy(jumpHostId = id) } },
            )

            Text("Autenticazione", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.authMode == AuthMode.KP2A,
                    onClick = { viewModel.update { it.copy(authMode = AuthMode.KP2A) } },
                    label = { Text("Keepass2Android") },
                )
                FilterChip(
                    selected = state.authMode == AuthMode.LOCAL_KEY,
                    onClick = { viewModel.update { it.copy(authMode = AuthMode.LOCAL_KEY) } },
                    label = { Text("Chiave locale") },
                )
                FilterChip(
                    selected = state.authMode == AuthMode.PROMPT,
                    onClick = { viewModel.update { it.copy(authMode = AuthMode.PROMPT) } },
                    label = { Text("Password") },
                )
            }

            if (state.authMode == AuthMode.KP2A ||
                (state.authMode == AuthMode.LOCAL_KEY && state.kp2aForPassphrase)
            ) {
                OutlinedTextField(
                    value = state.kp2aQuery,
                    onValueChange = { v -> viewModel.update { it.copy(kp2aQuery = v) } },
                    label = { Text("Query KP2A (es. ssh://nome-host)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("Deve corrispondere al campo URL dell'entry KeePass")
                    },
                )
            }

            if (state.authMode == AuthMode.LOCAL_KEY) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Passphrase da KP2A")
                    Switch(
                        checked = state.kp2aForPassphrase,
                        onCheckedChange = { v -> viewModel.update { it.copy(kp2aForPassphrase = v) } },
                    )
                }
                Text(
                    "La chiave ed25519 viene generata in-app alla prima connessione e " +
                        "protetta dal Keystore. Esporta la chiave pubblica dal menu della sessione.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.authMode == AuthMode.KP2A) {
                val context = LocalContext.current
                var kp2aMissing by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Kp2aControl.getAddEntryIntent(
                                    Kp2aFields.addEntryFields(
                                        title = state.name.ifBlank { state.hostname },
                                        query = state.kp2aQuery.ifBlank { "ssh://${state.hostname}" },
                                        username = state.username,
                                        port = state.port.toIntOrNull(),
                                    ),
                                    Kp2aFields.protectedFields(),
                                )
                            )
                        } catch (_: ActivityNotFoundException) {
                            kp2aMissing = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Crea entry in Keepass2Android")
                }
                Text(
                    "Crea un'entry con URL = query e i campi SSH-PrivateKey / Password " +
                        "protetti, da completare in KP2A.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (kp2aMissing) {
                    AlertDialog(
                        onDismissRequest = { kp2aMissing = false },
                        title = { Text("Keepass2Android non installato") },
                        text = { Text("Installa Keepass2Android per usare questa funzione.") },
                        confirmButton = {
                            TextButton(onClick = { kp2aMissing = false }) { Text("OK") }
                        },
                    )
                }
            }

            if (state.id != 0L) {
                PortForwardSection(viewModel)
            } else {
                Text(
                    "Salva l'host per configurare i port forwarding.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PortForwardSection(viewModel: HostEditViewModel) {
    val forwards by viewModel.forwards.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Text("Port forwarding", style = MaterialTheme.typography.labelLarge)
    forwards.forEach { fwd ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val desc = when (fwd.type) {
                ForwardType.LOCAL -> "L ${fwd.bindHost}:${fwd.bindPort} → ${fwd.targetHost}:${fwd.targetPort}"
                ForwardType.REMOTE -> "R ${fwd.bindHost}:${fwd.bindPort} ← ${fwd.targetHost}:${fwd.targetPort}"
            }
            Text(desc, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { viewModel.deleteForward(fwd) }) { Text("Rimuovi") }
        }
    }
    OutlinedButton(onClick = { showAdd = true }) { Text("Aggiungi forwarding") }

    if (showAdd) {
        var type by remember { mutableStateOf(ForwardType.LOCAL) }
        var bindPort by remember { mutableStateOf("") }
        var targetHost by remember { mutableStateOf("127.0.0.1") }
        var targetPort by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Nuovo forwarding") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = type == ForwardType.LOCAL,
                            onClick = { type = ForwardType.LOCAL },
                            label = { Text("Locale (-L)") },
                        )
                        FilterChip(
                            selected = type == ForwardType.REMOTE,
                            onClick = { type = ForwardType.REMOTE },
                            label = { Text("Remoto (-R)") },
                        )
                    }
                    OutlinedTextField(
                        value = bindPort,
                        onValueChange = { bindPort = it.filter(Char::isDigit).take(5) },
                        label = { Text(if (type == ForwardType.LOCAL) "Porta locale" else "Porta remota") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = targetHost,
                        onValueChange = { targetHost = it },
                        label = { Text("Host destinazione") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = targetPort,
                        onValueChange = { targetPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Porta destinazione") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = (bindPort.toIntOrNull() ?: 0) in 1..65535 &&
                        (targetPort.toIntOrNull() ?: 0) in 1..65535,
                    onClick = {
                        viewModel.addForward(
                            type,
                            bindPort.toInt(),
                            targetHost.trim(),
                            targetPort.toInt(),
                        )
                        showAdd = false
                    },
                ) { Text("Aggiungi") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Annulla") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JumpHostSelector(
    currentId: Long?,
    candidates: List<Pair<Long, String>>,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = candidates.firstOrNull { it.first == currentId }?.second ?: "Nessuno"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = currentName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Jump host") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Nessuno") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            candidates.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}
