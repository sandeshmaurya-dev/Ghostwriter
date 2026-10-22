package com.musheer360.swiftslate.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Ghostwriter dark palette — deep space blue-black base with neon accents:
// cyan for identity/keys, green for growth, purple for AI, rose/orange for action.
private val DarkColorScheme = darkColorScheme(
    background = Color(0xFF0A0B10),
    surface = Color(0xFF12141D),
    surfaceVariant = Color(0xFF181B27),
    surfaceContainerHigh = Color(0xFF1D2130),
    onBackground = Color(0xFFF2F4F8),
    onSurface = Color(0xFFF2F4F8),
    onSurfaceVariant = Color(0xFF8B93A7),
    outline = Color(0xFF232838),
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF04141A),
    primaryContainer = Color(0xFF0E2A31),
    onPrimaryContainer = Color(0xFF67E8F9),
    secondary = Color(0xFF7C5CFC),
    secondaryContainer = Color(0xFF241D4D),
    onSecondary = Color(0xFF0E0A2A),
    onSecondaryContainer = Color(0xFFC4B5FD),
    error = Color(0xFFF43F5E),
    errorContainer = Color(0xFF3D1220),
    tertiary = Color(0xFF10B981),
    tertiaryContainer = Color(0xFF0E3B2D),
    onTertiary = Color(0xFF06251A),
    onTertiaryContainer = Color(0xFF6EE7B7)
)

private val LightColorScheme = lightColorScheme(
    background = Color(0xFFFCFCFC),
    surface = Color(0xFFF2F2F4),
    surfaceVariant = Color(0xFFE8E8EA),
    surfaceContainerHigh = Color(0xFFDFDFE1),
    onBackground = Color(0xFF1A1A1E),
    onSurface = Color(0xFF1A1A1E),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFD5D5DA),
    primary = Color(0xFF1A1A1E),
    onPrimary = Color(0xFFFCFCFC),
    primaryContainer = Color(0xFFEEEEF0),
    onPrimaryContainer = Color(0xFF1A1A1E),
    error = Color(0xFFFF3B30),
    tertiary = Color(0xFF34C759),
    tertiaryContainer = Color(0xFFFF9500)
)

@Composable
fun SwiftSlateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
