package com.jacklugano.termvault

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jacklugano.termvault.debug.CrashReporter
import com.jacklugano.termvault.ui.nav.TermVaultNavHost
import com.jacklugano.termvault.ui.theme.TermVaultTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nel terminale e nei form possono transitare credenziali: niente screenshot
        // né anteprime nel task switcher.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        val crashReport = CrashReporter.consumeReport(this)
        enableEdgeToEdge()
        setContent {
            TermVaultTheme {
                TermVaultNavHost()
                crashReport?.let { CrashReportDialog(it) }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun CrashReportDialog(report: String) {
        var visible by remember { mutableStateOf(true) }
        if (!visible) return
        AlertDialog(
            onDismissRequest = { visible = false },
            title = { Text("L'app si è chiusa in modo anomalo") },
            text = {
                SelectionContainer(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        report,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "TermVault crash report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    startActivity(Intent.createChooser(share, "Condividi report crash"))
                }) { Text("Condividi") }
            },
            dismissButton = {
                TextButton(onClick = { visible = false }) { Text("Chiudi") }
            },
        )
    }
}
