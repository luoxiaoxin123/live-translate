package com.livetranslate.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

// "Booth blue" — simultaneous-interpretation desk aesthetic
object Booth {
    val Accent = Color(0xFF2F6BFF)
    val AccentSoft = Color(0xFFDCE8FF)
    val Ink = Color(0xFF0F172A)
    val Mist = Color(0xFFF3F6FB)
    val Success = Color(0xFF0F9F6E)
    val Danger = Color(0xFFE11D48)
    val Warning = Color(0xFFD97706)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            primary = Booth.Accent,
            onPrimary = Color.White,
            primaryVariant = Booth.Accent,
        )
    } else {
        lightColorScheme(
            primary = Booth.Accent,
            onPrimary = Color.White,
            primaryVariant = Booth.Accent,
            background = Booth.Mist,
            onBackground = Booth.Ink,
            surface = Color.White,
            onSurface = Booth.Ink,
        )
    }
    MiuixTheme(colors = colors, content = content)
}
