package com.jacklugano.termvault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TerminalGreen = Color(0xFF00E676)
private val TerminalGreenDim = Color(0xFF00A854)
private val DarkBg = Color(0xFF101418)
private val DarkSurface = Color(0xFF171C22)

private val DarkColors = darkColorScheme(
    primary = TerminalGreen,
    onPrimary = Color.Black,
    secondary = TerminalGreenDim,
    onSecondary = Color.Black,
    background = DarkBg,
    surface = DarkSurface,
    error = Color(0xFFFF5252),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006D3B),
    secondary = TerminalGreenDim,
)

@Composable
fun TermVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
