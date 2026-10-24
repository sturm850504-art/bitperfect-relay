package com.bitperfect.relay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Fixed brand palette (no Material You dynamic colors).
// dynamicLight/DarkColorScheme on Samsung One UI can yield an onPrimary with
// almost no contrast against primary, which made filled-button text invisible
// (S21 / Android 16, reported 2026-10-09). A fixed scheme guarantees readable
// contrast on every device.

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = DarkBlue,
    secondary = Accent,
    onSecondary = DarkBlue,
    background = DarkBlue,
    surface = DarkGray,
    error = ErrorRed,
)

private val LightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = DarkBlue,
    secondary = Accent,
    onSecondary = DarkBlue,
    error = ErrorRed,
)

@Composable
fun BitPerfectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // Paint the scheme background: the XML window theme is always light, so in night mode the
    // page stayed white under light status-bar icons (S25 FE, 2026-10-24).
    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = colorScheme.background, content = content)
    }
}
