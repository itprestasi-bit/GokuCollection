package com.collectionfield.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Indigo500,
    onPrimary = PaperWhite,
    primaryContainer = Indigo500.copy(alpha = 0.1f),
    onPrimaryContainer = Indigo500,
    surface = PaperWhite,
    onSurface = Slate800,
    background = SoftGray50,
    onBackground = Slate800,
    outline = SoftGray100,
    outlineVariant = SoftGray50,
    error = Rose500,
    secondary = Slate500,
    onSecondary = PaperWhite,
    surfaceVariant = SoftGray50,
    onSurfaceVariant = Slate600
)

private val DarkColorScheme = darkColorScheme(
    primary = Indigo500,
    onPrimary = PaperWhite,
    surface = Slate900,
    onSurface = PaperWhite,
    background = Color.Black,
    onBackground = PaperWhite,
    outline = Slate600,
    error = Rose500
)

@Composable
fun CollectionFieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
