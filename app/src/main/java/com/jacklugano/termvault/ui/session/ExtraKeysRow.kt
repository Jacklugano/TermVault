package com.jacklugano.termvault.ui.session

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.termux.view.TerminalView

/**
 * Tastiera ausiliaria: Ctrl/Alt/Shift/Fn sono toggle sticky (si consumano al
 * tasto successivo), gli altri inviano subito il keycode al terminale.
 */
@Composable
fun ExtraKeysRow(
    modifierKeys: ModifierKeysState,
    terminalView: TerminalView?,
    onPaste: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun sendKey(keyCode: Int) {
        terminalView?.handleKeyCode(keyCode, modifierKeys.keyMod())
        modifierKeys.consume()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ActionKey("⌨") { onToggleKeyboard() }
        ToggleKey("CTRL", modifierKeys.ctrl) { modifierKeys.ctrl = !modifierKeys.ctrl }
        ToggleKey("ALT", modifierKeys.alt) { modifierKeys.alt = !modifierKeys.alt }
        ToggleKey("FN", modifierKeys.fn) { modifierKeys.fn = !modifierKeys.fn }
        ActionKey("ESC") { sendKey(KeyEvent.KEYCODE_ESCAPE) }
        ActionKey("TAB") { sendKey(KeyEvent.KEYCODE_TAB) }
        ActionKey("◀") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        ActionKey("▼") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        ActionKey("▲") { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        ActionKey("▶") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        ActionKey("PgUp") { sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        ActionKey("PgDn") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        ActionKey("HOME") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        ActionKey("END") { sendKey(KeyEvent.KEYCODE_MOVE_END) }
        ActionKey("COPIA") { onCopy() }
        ActionKey("INCOLLA") { onPaste() }
    }
}

@Composable
private fun ToggleKey(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontFamily = FontFamily.Monospace) },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

@Composable
private fun ActionKey(label: String, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, fontFamily = FontFamily.Monospace) },
    )
}
