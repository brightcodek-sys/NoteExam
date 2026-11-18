package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = MidnightIndigo,
    onPrimary = Color(0xFF0A192F),
    primaryContainer = MidnightIndigoContainer,
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = MidnightTeal,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = MidnightTealContainer,
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = MidnightAmber,
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = MidnightAmberContainer,
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = MidnightBg,
    onBackground = MidnightText,
    surface = MidnightSurface,
    onSurface = MidnightText,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = MidnightMuted,
    outline = MidnightOutline,
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)

private val LightColorScheme = lightColorScheme(
    primary = InkIndigo,
    onPrimary = Color.White,
    primaryContainer = InkIndigoContainer,
    onPrimaryContainer = OnInkIndigoContainer,
    secondary = ChalkboardTeal,
    onSecondary = Color.White,
    secondaryContainer = ChalkboardTealContainer,
    onSecondaryContainer = OnChalkboardTealContainer,
    tertiary = HighlighterAmber,
    onTertiary = Color.White,
    tertiaryContainer = HighlighterAmberContainer,
    onTertiaryContainer = OnHighlighterAmberContainer,
    background = ParchmentBg,
    onBackground = InkDark,
    surface = PaperWhite,
    onSurface = InkDark,
    surfaceVariant = PaperWarmVariant,
    onSurfaceVariant = InkMuted,
    outline = RuleLine,
    error = CrimsonError,
    onError = Color.White,
    errorContainer = CrimsonErrorContainer,
    onErrorContainer = Color(0xFF7F1D1D)
)

val NoteExamShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun NoteExamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = NoteExamShapes,
        content = content
    )
}
