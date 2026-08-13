package com.jacklugano.termvault.ui.snippets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacklugano.termvault.data.db.SnippetEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(
    onBack: () -> Unit,
    viewModel: SnippetsViewModel = hiltViewModel(),
) {
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SnippetEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snippet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = SnippetEntity(name = "", command = "")
            }) {
                Icon(Icons.Default.Add, contentDescription = "Nuovo snippet")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            items(snippets, key = { it.id }) { snippet ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(snippet.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                snippet.command,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                            )
                        }
                        TextButton(onClick = { editing = snippet }) { Text("Modifica") }
                        IconButton(onClick = { viewModel.delete(snippet) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Elimina")
                        }
                    }
                }
            }
        }
    }

    editing?.let { snippet ->
        var name by remember(snippet) { mutableStateOf(snippet.name) }
        var command by remember(snippet) { mutableStateOf(snippet.command) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (snippet.id == 0L) "Nuovo snippet" else "Modifica snippet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Comando") },
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.save(snippet.copy(name = name.trim(), command = command))
                        editing = null
                    },
                    enabled = name.isNotBlank() && command.isNotBlank(),
                ) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Annulla") }
            },
        )
    }
}
