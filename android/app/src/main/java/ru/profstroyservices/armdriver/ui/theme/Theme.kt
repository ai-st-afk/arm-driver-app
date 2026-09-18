package ru.profstroyservices.armdriver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PrimaryGreen = Color(0xFF1B5E20)
private val PrimaryGreenDark = Color(0xFF4C8C4A)

private val LightColors = lightColorScheme(primary = PrimaryGreen)
private val DarkColors = darkColorScheme(primary = PrimaryGreenDark)

@Composable
fun ArmDriverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
