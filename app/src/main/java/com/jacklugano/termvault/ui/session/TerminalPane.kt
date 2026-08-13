package com.jacklugano.termvault.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.jacklugano.termvault.ssh.SshTerminalTab
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** Stato dei modificatori sticky della tastiera ausiliaria. */
class ModifierKeysState {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)
    var fn by mutableStateOf(false)

    fun keyMod(): Int {
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (alt) mod = mod or KeyHandler.KEYMOD_ALT
        if (shift) mod = mod or KeyHandler.KEYMOD_SHIFT
        return mod
    }

    /** I modificatori sticky si consumano dopo un uso. */
    fun consume() {
        ctrl = false
        alt = false
        shift = false
        fn = false
    }
}

@Composable
fun TerminalPane(
    tab: SshTerminalTab,
    fontSizeSp: Int,
    modifierKeys: ModifierKeysState,
    onFontSizeChange: (Int) -> Unit,
    onViewReady: (TerminalView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.scaledDensity

    val view = remember(tab.id) {
        TerminalView(context, null)
    }

    val client = remember(tab.id) {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                if (scale < 0.9f || scale > 1.1f) {
                    onFontSizeChange(fontSizeSp + (if (scale > 1f) 1 else -1))
                    return 1.0f
                }
                return scale
            }

            override fun onSingleTapUp(e: MotionEvent?) {
                showKeyboard(context, view)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) = Unit

            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
            override fun onLongPress(event: MotionEvent?): Boolean = false

            override fun readControlKey(): Boolean = modifierKeys.ctrl
            override fun readAltKey(): Boolean = modifierKeys.alt
            override fun readShiftKey(): Boolean = modifierKeys.shift
            override fun readFnKey(): Boolean = modifierKeys.fn

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
                // FN + cifra => tasto funzione (1->F1 ... 9->F9, 0->F10).
                if (modifierKeys.fn && session != null) {
                    val fKeyCode = when (codePoint.toChar()) {
                        in '1'..'9' -> KeyEvent.KEYCODE_F1 + (codePoint - '1'.code)
                        '0' -> KeyEvent.KEYCODE_F10
                        else -> -1
                    }
                    if (fKeyCode != -1) {
                        val emulator = session.emulator
                        val code = KeyHandler.getCode(
                            fKeyCode, 0,
                            emulator?.isCursorKeysApplicationMode ?: false,
                            emulator?.isKeypadApplicationMode ?: false,
                        )
                        if (code != null) session.write(code)
                        modifierKeys.consume()
                        return true
                    }
                }
                // Modificatori sticky consumati dal prossimo carattere.
                if (modifierKeys.ctrl || modifierKeys.alt || modifierKeys.shift || modifierKeys.fn) {
                    // Lascia che TerminalView applichi ctrl/alt, poi rilascia.
                    view.post { modifierKeys.consume() }
                }
                return false
            }

            override fun onEmulatorSet() = Unit

            override fun logError(tag: String?, message: String?) = Unit
            override fun logWarn(tag: String?, message: String?) = Unit
            override fun logInfo(tag: String?, message: String?) = Unit
            override fun logDebug(tag: String?, message: String?) = Unit
            override fun logVerbose(tag: String?, message: String?) = Unit
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
            override fun logStackTrace(tag: String?, e: Exception?) = Unit
        }
    }

    DisposableEffect(tab.id) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        tab.onTextChanged = { view.onScreenUpdated() }
        tab.onCopyToClipboard = { text ->
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        tab.onPasteFromClipboard = {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }
        onDispose {
            tab.onTextChanged = null
            tab.onCopyToClipboard = null
            tab.onPasteFromClipboard = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            view.setTerminalViewClient(client)
            // TerminalView di termux non si rende focusable da sola: senza questi
            // flag requestFocus() fallisce e la tastiera software non appare mai.
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            // L'ordine conta: setTextSize crea il TerminalRenderer interno, che
            // setTypeface poi legge. Invertendoli, mRenderer è null -> NPE.
            view.setTextSize((fontSizeSp * density).toInt())
            view.setTypeface(Typeface.MONOSPACE)
            view.keepScreenOn = true
            view.attachSession(tab.terminalSession)
            onViewReady(view)
            view
        },
        update = { v ->
            v.setTextSize((fontSizeSp * density).toInt())
        },
    )
}

/** Dà il focus al terminale e forza l'apertura della tastiera software. */
fun showKeyboard(context: Context, view: android.view.View) {
    view.requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}
