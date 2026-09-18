package ru.profstroyservices.armdriver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Общий фирменный стиль студии (тот же, что в web/realtor-cabinet/src/theme.ts →
// CABINET_THEME) — чёрные кнопки на белом, оранжевый акцент, приглушённый серый
// для второстепенного текста. Заведён явно, а не через один seed-цвет: дефолтная
// генерация Material3 из одного primary даёт собственный лиловатый фон/поверхности,
// которые с этим брендом не совпадают.
private val Accent = Color(0xFFE86C2F)
private val TextDark = Color(0xFF1F2228)
private val TextMuted = Color(0xFF6B7280)
private val BorderLight = Color(0xFFEAEAEA)

private val LightColors = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Accent,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Accent,
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = TextDark,
    surface = Color(0xFFFFFFFF),
    onSurface = TextDark,
    surfaceVariant = Color(0xFFF7F7F8),
    onSurfaceVariant = TextMuted,
    outline = BorderLight,
    outlineVariant = BorderLight
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    secondary = Accent,
    onSecondary = Color(0xFF1F2228),
    tertiary = Accent,
    onTertiary = Color(0xFF1F2228),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF262628),
    onSurfaceVariant = Color(0xFFA8ACB4),
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF3A3A3C)
)

// Крупные, просторные размеры — экран читается на ходу и на солнце, не мелкий
// плотный текст для настольного использования.
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 18.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp),
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, color = TextMuted),
        labelLarge = base.labelLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    )
}

@Composable
fun ArmDriverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
