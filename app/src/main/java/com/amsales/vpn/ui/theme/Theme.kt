package com.amsales.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Star палитра — фиолетово-красная.
// Имена переменных оставлены AmXxx чтобы не пересобирать все Compose-экраны.
val AmAccent  = Color(0xFFC44BFF)   // фиолетовый акцент (как звезда)
val AmAccent2 = Color(0xFFFF3D6E)   // розово-красный (вторичный)
val AmTextHi  = Color(0xFFF5EAFF)
val AmTextLo  = Color(0xFF9080A0)
val AmBgTop   = Color(0xFF1A0820)
val AmBgMid   = Color(0xFF0B0410)
val AmBgBot   = Color(0xFF16040E)

private val DarkColors = darkColorScheme(
    primary = AmAccent,
    onPrimary = AmBgMid,
    secondary = AmAccent2,
    onSecondary = AmBgMid,
    background = AmBgMid,
    onBackground = AmTextHi,
    surface = AmBgMid,
    onSurface = AmTextHi,
    surfaceVariant = AmBgTop,
    onSurfaceVariant = AmTextLo,
)

@Composable
fun AmSalesTheme(
    darkTheme: Boolean = true,   // всегда тёмная
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
