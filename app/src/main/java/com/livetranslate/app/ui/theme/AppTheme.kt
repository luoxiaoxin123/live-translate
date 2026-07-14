package com.livetranslate.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * MIUIX light defaults:
 * - Scaffold page bg: colorScheme.surface = #F7F7F7 (gray)
 * - Card fill: colorScheme.surfaceContainer = White
 * Matches HyperOS/MIUI settings: gray page + white rounded groups.
 */
object Booth {
    val Accent = Color(0xFF3482FF) // MIUIX primary default
    val AccentSoft = Color(0xFFEAF2FF)
    val Success = Color(0xFF0F9F6E)
    val Danger = Color(0xFFE94634)
    val Warning = Color(0xFFD97706)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme()
    } else {
        // Keep MIUIX gray surface + white surfaceContainer; only accent stays brand blue.
        lightColorScheme(
            primary = Booth.Accent,
            onPrimary = Color.White,
            primaryVariant = Booth.Accent,
            onTertiaryContainer = Booth.Accent,
        )
    }
    MiuixTheme(colors = colors, content = content)
}
