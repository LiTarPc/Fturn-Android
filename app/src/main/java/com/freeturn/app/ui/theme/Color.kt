package com.freeturn.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Shared brand tokens: warm neutrals, deep wine actions and cream lettering. */
object WinePalette {
    val Primary = Color(0xFF65182C)
    val Highlight = Color(0xFF8C3047)
    val Pressed = Color(0xFF3B1020)
    val Cream = Color(0xFFFFF7ED)
    val Background = Color(0xFFFCFAF6)
    val Card = Color(0xFFF4F0E9)
    val Text = Color(0xFF30272B)
    val Muted = Color(0xFF756B6E)
    val Border = Color(0xFFE5DEDA)
}

val primaryLight = WinePalette.Primary
val onPrimaryLight = WinePalette.Cream
val primaryContainerLight = WinePalette.Card
val onPrimaryContainerLight = WinePalette.Primary
val secondaryLight = WinePalette.Primary
val onSecondaryLight = WinePalette.Cream
val secondaryContainerLight = WinePalette.Primary
val onSecondaryContainerLight = WinePalette.Cream
val tertiaryLight = WinePalette.Highlight
val onTertiaryLight = WinePalette.Cream
val tertiaryContainerLight = Color(0xFFF0E3E4)
val onTertiaryContainerLight = WinePalette.Pressed
val errorLight = Color(0xFF9F3946)
val onErrorLight = WinePalette.Cream
val errorContainerLight = Color(0xFFF5E4E3)
val onErrorContainerLight = Color(0xFF712331)
val backgroundLight = WinePalette.Background
val onBackgroundLight = WinePalette.Text
val surfaceLight = WinePalette.Background
val onSurfaceLight = WinePalette.Text
val surfaceVariantLight = WinePalette.Card
val onSurfaceVariantLight = WinePalette.Muted
val outlineLight = WinePalette.Border
val outlineVariantLight = WinePalette.Border
val scrimLight = WinePalette.Pressed
val inverseSurfaceLight = WinePalette.Text
val inverseOnSurfaceLight = WinePalette.Cream
val inversePrimaryLight = Color(0xFFE6B2BE)
val surfaceDimLight = Color(0xFFEBE5DE)
val surfaceBrightLight = WinePalette.Background
val surfaceContainerLowestLight = Color(0xFFFFFDF9)
val surfaceContainerLowLight = WinePalette.Card
val surfaceContainerLight = WinePalette.Card
val surfaceContainerHighLight = Color(0xFFEFE9E1)
val surfaceContainerHighestLight = Color(0xFFEAE2DA)

val primaryDark = Color(0xFFE4A5B5)
val onPrimaryDark = WinePalette.Pressed
val primaryContainerDark = Color(0xFF4D2332)
val onPrimaryContainerDark = WinePalette.Cream
val secondaryDark = primaryDark
val onSecondaryDark = WinePalette.Pressed
val secondaryContainerDark = WinePalette.Primary
val onSecondaryContainerDark = WinePalette.Cream
val tertiaryDark = Color(0xFFD8B6C0)
val onTertiaryDark = Color(0xFF38242D)
val tertiaryContainerDark = Color(0xFF422D36)
val onTertiaryContainerDark = Color(0xFFF0D8DE)
val errorDark = Color(0xFFF1A8B1)
val onErrorDark = Color(0xFF571E28)
val errorContainerDark = Color(0xFF592731)
val onErrorContainerDark = Color(0xFFFFD9DC)
val backgroundDark = Color(0xFF1B1519)
val onBackgroundDark = Color(0xFFF4EDE6)
val surfaceDark = backgroundDark
val onSurfaceDark = onBackgroundDark
val surfaceVariantDark = Color(0xFF281F24)
val onSurfaceVariantDark = Color(0xFFC8BABC)
val outlineDark = Color(0xFF6B555F)
val outlineVariantDark = Color(0xFF4D3A42)
val scrimDark = Color(0xFF100A0E)
val inverseSurfaceDark = WinePalette.Card
val inverseOnSurfaceDark = WinePalette.Text
val inversePrimaryDark = WinePalette.Primary
val surfaceDimDark = backgroundDark
val surfaceBrightDark = Color(0xFF3B2E35)
val surfaceContainerLowestDark = Color(0xFF151013)
val surfaceContainerLowDark = Color(0xFF241B20)
val surfaceContainerDark = Color(0xFF281F24)
val surfaceContainerHighDark = Color(0xFF32262D)
val surfaceContainerHighestDark = Color(0xFF3C2F36)
