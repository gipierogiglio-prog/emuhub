package com.emuhub.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val EmuHubRed = Color(0xFFE31E24)
val EmuHubRedDark = Color(0xFF8C1216)
val EmuHubBlack = Color(0xFF0A0A0A)
val EmuHubSurface = Color(0xFF161616)
val EmuHubSurfaceHigh = Color(0xFF222222)
val EmuHubWhite = Color(0xFFF2F2F2)
val EmuHubGray = Color(0xFF9E9E9E)

private val DarkScheme = darkColorScheme(
    primary = EmuHubRed,
    onPrimary = EmuHubWhite,
    secondary = EmuHubRedDark,
    background = EmuHubBlack,
    onBackground = EmuHubWhite,
    surface = EmuHubSurface,
    onSurface = EmuHubWhite,
    surfaceVariant = EmuHubSurfaceHigh,
    onSurfaceVariant = EmuHubGray,
)

@Composable
fun EmuHubTheme(content: @Composable () -> Unit) {
    // O EmuHub é sempre escuro, como um frontend de console.
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
