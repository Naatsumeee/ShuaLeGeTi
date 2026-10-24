package com.examhelper.shua.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GreenPrimary = Color(0xFF1B6E53)
private val GreenLight = Color(0xFF52A87F)
private val GreenContainer = Color(0xFFBDEFD6)

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    secondary = Color(0xFF4C6358),
    tertiary = Color(0xFF3D6373),
    primaryContainer = GreenContainer,
    onPrimaryContainer = Color(0xFF00251A)
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    secondary = Color(0xFFB5CCBD),
    tertiary = Color(0xFFA5CADF),
    primaryContainer = Color(0xFF00523B),
    onPrimaryContainer = GreenContainer
)

@Composable
fun ShuaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
