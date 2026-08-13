package com.jacklugano.termvault.ui.session

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacklugano.termvault.ssh.SshTerminalTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.OpenMode
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

private data class SftpEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
)

private fun parentOf(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    val parent = trimmed.substringBeforeLast('/', "")
    return if (parent.isEmpty()) "/" else parent
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GiB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

/** Copia con callback di avanzamento ogni ~256 KiB. */
private fun copyStream(
    input: InputStream,
    output: OutputStream,
    onProgress: (Long) -> Unit,
) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var sinceReport = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        output.write(buffer, 0, read)
        total += read
        sinceReport += read
        if (sinceReport >= 256 * 1024) {
            sinceReport = 0
            onProgress(total)
        }
    }
    output.flush()
    onProgress(total)
}

private fun displayNameOf(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
}

/**
 * Browser SFTP della sessione: naviga le directory remote, scarica un file
 * toccandolo (selettore di sistema per la destinazione) e carica file dal
 * telefono nella directory corrente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpBrowserSheet(
    tab: SshTerminalTab,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<SftpEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var transferLabel by remember { mutableStateOf<String?>(null) }
    var transferProgress by remember { mutableStateOf<Float?>(null) }
    var pendingDownload by remember { mutableStateOf<SftpEntry?>(null) }

    fun load(target: String?) {
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val sftp = tab.sftp()
                    val base = target ?: sftp.canonicalize(".")
                    val list = sftp.ls(base).map { info ->
                        SftpEntry(
                            name = info.name,
                            path = info.path,
                            isDir = info.isDirectory,
                            size = info.attributes.size,
                        )
                    }.sortedWith(
                        compareByDescending<SftpEntry> { it.isDir }
                            .thenBy { it.name.lowercase() }
                    )
                    base to list
                }
            }.onSuccess { (base, list) ->
                path = base
                entries = list
            }.onFailure { e ->
                error = e.message ?: "Errore SFTP"
            }
            loading = false
        }
    }

    LaunchedEffect(tab.id) { load(null) }

    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferLabel = "Scarico ${entry.name}…"
            transferProgress = if (entry.size > 0) 0f else null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    tab.sftp().open(entry.path).use { remote ->
                        remote.RemoteFileInputStream().use { input ->
                            context.contentResolver.openOutputStream(uri)!!.use { out ->
                                copyStream(input, out) { done ->
                                    if (entry.size > 0) {
                                        transferProgress =
                                            (done.toFloat() / entry.size).coerceIn(0f, 1f)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            transferLabel = null
            transferProgress = null
            error = result.exceptionOrNull()?.let { "Download fallito: ${it.message}" }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val base = path
        if (uri == null || base == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = displayNameOf(context, uri)
            val total = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            transferLabel = "Carico $name…"
            transferProgress = if (total > 0) 0f else null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val remotePath = base.trimEnd('/') + "/" + name
                    tab.sftp().open(
                        remotePath,
                        EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
                    ).use { remote ->
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            remote.RemoteFileOutputStream().use { out ->
                                copyStream(input, out) { done ->
                                    if (total > 0) {
                                        transferProgress =
                                            (done.toFloat() / total).coerceIn(0f, 1f)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            transferLabel = null
            transferProgress = null
            error = result.exceptionOrNull()?.let { "Upload fallito: ${it.message}" }
            if (result.isSuccess) load(base)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        modifier = Modifier.fillMaxHeight(0.92f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "File — ${tab.host.name}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                    enabled = path != null && transferLabel == null,
                ) {
                    Icon(Icons.Default.Upload, contentDescription = "Carica file qui")
                }
                IconButton(onClick = { load(path) }, enabled = transferLabel == null) {
                    Icon(Icons.Default.Refresh, contentDescription = "Ricarica")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Chiudi")
                }
            }

            Text(
                path ?: "…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            transferLabel?.let { label ->
                Text(label, style = MaterialTheme.typography.labelMedium)
                val p = transferProgress
                if (p != null) {
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            if (loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val current = path
                    if (current != null && current != "/") {
                        item(key = "..") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = transferLabel == null) {
                                        load(parentOf(current))
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null)
                                Text("  ..", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    items(entries, key = { it.path }) { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = transferLabel == null) {
                                    if (entry.isDir) {
                                        load(entry.path)
                                    } else {
                                        pendingDownload = entry
                                        downloadLauncher.launch(entry.name)
                                    }
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Icon(
                                if (entry.isDir) Icons.Default.Folder
                                else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                "  ${entry.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!entry.isDir) {
                                Text(
                                    humanSize(entry.size),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
