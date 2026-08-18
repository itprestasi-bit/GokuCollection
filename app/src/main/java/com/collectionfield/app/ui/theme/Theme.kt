package com.collectionfield.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Brand,
    onPrimary = PaperWhite,
    primaryContainer = BrandTint,
    onPrimaryContainer = BrandOnTint,

    secondary = InkSecondary,
    onSecondary = PaperWhite,
    secondaryContainer = SurfaceAlt,
    onSecondaryContainer = Ink,

    tertiary = Success,
    onTertiary = PaperWhite,
    tertiaryContainer = SuccessTint,
    onTertiaryContainer = Success,

    background = PageWhite,
    onBackground = Ink,
    surface = PaperWhite,
    onSurface = Ink,
    surfaceVariant = SurfaceAlt,
    onSurfaceVariant = InkSecondary,

    outline = BorderStrong,
    outlineVariant = BorderHairline,

    error = Critical,
    onError = PaperWhite,
    errorContainer = CriticalTint,
    onErrorContainer = Critical,
)

private val DarkColorScheme = darkColorScheme(
    primary = Brand,
    onPrimary = PaperWhite,
    primaryContainer = BrandTintDark,
    onPrimaryContainer = BrandOnDark,

    secondary = DarkInkMuted,
    onSecondary = DarkBg,
    secondaryContainer = DarkSurfaceAlt,
    onSecondaryContainer = DarkInk,

    tertiary = SuccessOnDark,
    onTertiary = DarkBg,

    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceVariant = DarkInkSecondary,

    outline = DarkBorder,
    outlineVariant = DarkBorder,

    error = BrandOnDark,
    onError = DarkBg,
)

/**
 * Light is the product's primary appearance, matching the admin dashboard.
 * [darkTheme] is driven by the in-app switch (ThemePreferences), deliberately not
 * by `isSystemInDarkTheme()` — a collector who set the app to light shouldn't have
 * it flip because their phone entered night mode mid-shift.
 */
@Composable
fun CollectionFieldTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
