package com.freeturn.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic status colors with readable variants for both wine themes. */
@Immutable
data class ExtendedColorScheme(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

internal val WineExtendedColorScheme = ExtendedColorScheme(
    success = Color(0xFF28643C),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFCFEDD6),
    onSuccessContainer = Color(0xFF0B381B),
    warning = Color(0xFF8A602F),
    onWarning = WinePalette.Cream,
    warningContainer = Color(0xFFF3E8D9),
    onWarningContainer = Color(0xFF65471F),
    info = WinePalette.Highlight,
    onInfo = WinePalette.Cream,
    infoContainer = WinePalette.Card,
    onInfoContainer = WinePalette.Primary,
)

internal val WineExtendedDarkColorScheme = ExtendedColorScheme(
    success = Color(0xFF90D5A3), onSuccess = Color(0xFF003919),
    successContainer = Color(0xFF174F2C), onSuccessContainer = Color(0xFFCFEDD6),
    warning = Color(0xFFD5B57D), onWarning = Color(0xFF2E2217),
    warningContainer = Color(0xFF423420), onWarningContainer = Color(0xFFF4DCB4),
    info = primaryDark, onInfo = WinePalette.Pressed,
    infoContainer = primaryContainerDark, onInfoContainer = onPrimaryContainerDark,
)

internal val LocalExtendedColorScheme = staticCompositionLocalOf { WineExtendedColorScheme }

@Suppress("UnusedReceiverParameter")
val MaterialTheme.extendedColorScheme: ExtendedColorScheme
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColorScheme.current
