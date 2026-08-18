package com.collectionfield.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Derived from the Prestasi Group mark, which contains exactly two colours:
 * crimson #dc214c and a warm near-black #1f1a17 (sampled from the logo file).
 * The admin dashboard is built on the same two anchors, so a supervisor moving
 * between the web Command Center and a collector's phone sees one product.
 *
 * Every neutral below is that warm ink mixed toward white rather than a stock
 * cool grey — which is why the greys sit with the logo instead of beside it.
 *
 * Contrast on white, measured: Ink 17.2:1 · InkSecondary 9.1:1 · InkMuted 5.5:1
 * · Brand 4.8:1. Brand clears AA on white both as text and as a fill under white
 * text, so one value covers buttons and labels alike.
 */

// Brand
val Brand = Color(0xFFDC214C)
val BrandStrong = Color(0xFFC22045)
val BrandTint = Color(0xFFFDF4F6)
val BrandTint2 = Color(0xFFFBE4EA)
val BrandBorder = Color(0xFFF6C5D0)
/** Brand as *text on a brand tint*: plain Brand only measures 4.46:1 there. */
val BrandOnTint = Color(0xFFC22045)

// Surfaces — warm off-white page, pure white cards.
val PageWhite = Color(0xFFFAF9F8)
val PaperWhite = Color(0xFFFFFFFF)
val SurfaceAlt = Color(0xFFF4F3F2)
val BorderHairline = Color(0xFFE5E3E1)
val BorderStrong = Color(0xFFD3D0CE)

// Ink
val Ink = Color(0xFF1F1A17)
val InkSecondary = Color(0xFF4C4845)
val InkMuted = Color(0xFF6B6866)
val InkFaint = Color(0xFF74716F)

// Status — paired with tints, each verified as text on its own tint.
val Success = Color(0xFF047857)
val SuccessTint = Color(0xFFECFDF5)
val Warning = Color(0xFFB45309)
val WarningTint = Color(0xFFFFFBEB)
val Critical = Color(0xFFC22045)
val CriticalTint = Color(0xFFFDF4F6)

// Dark theme — warm, not blue-black, so it stays in the same family.
val DarkBg = Color(0xFF17140F)
val DarkSurface = Color(0xFF201C17)
val DarkSurfaceAlt = Color(0xFF2A2521)
val DarkBorder = Color(0xFF302B26)
val DarkInk = Color(0xFFFAF9F8)
val DarkInkSecondary = Color(0xFFDDD8D3)
val DarkInkMuted = Color(0xFFB0A9A3)
/** #dc214c only reaches ~3.6:1 on the dark surface, so text there uses this. */
val BrandOnDark = Color(0xFFFF5C81)
val BrandTintDark = Color(0xFF2A1519)
val SuccessOnDark = Color(0xFF34D399)
val WarningOnDark = Color(0xFFFBBF24)
