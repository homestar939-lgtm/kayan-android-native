package com.kayan.x.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Brand palette ────────────────────────────────────────────────────────────
private val KayanTeal    = Color(0xFF00BFA5)
private val KayanTealDim = Color(0xFF00897B)
private val KayanBg      = Color(0xFF0D1117)
private val KayanSurface = Color(0xFF161B22)
private val KayanOnBg    = Color(0xFFE6EDF3)

private val DarkColors = darkColorScheme(
    primary          = KayanTeal,
    onPrimary        = Color.Black,
    primaryContainer = KayanTealDim,
    background       = KayanBg,
    surface          = KayanSurface,
    onBackground     = KayanOnBg,
    onSurface        = KayanOnBg,
    secondary        = Color(0xFF58A6FF),
    error            = Color(0xFFF85149)
)

private val LightColors = lightColorScheme(
    primary          = KayanTealDim,
    onPrimary        = Color.White,
    background       = Color(0xFFF6F8FA),
    surface          = Color.White,
    onBackground     = Color(0xFF24292F),
    onSurface        = Color(0xFF24292F),
    secondary        = Color(0xFF0969DA),
    error            = Color(0xFFCF222E)
)

@Composable
fun KayanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography  = KayanTypography,
        content     = content
    )
}
