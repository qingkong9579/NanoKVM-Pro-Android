package com.nanokvm.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Material3 slot mapping of the One-KVM OKLCH palette.
 * Mirrors the shadcn-vue convention: light theme uses a dark primary button,
 * dark theme uses a light primary button.
 */
private val LightColors: ColorScheme = lightColorScheme(
    primary = OneKvmColors.LightPrimary,
    onPrimary = OneKvmColors.White,
    primaryContainer = OneKvmColors.LightSurface,
    onPrimaryContainer = OneKvmColors.LightPrimary,
    secondary = OneKvmColors.LightSurface,
    onSecondary = OneKvmColors.LightPrimary,
    background = OneKvmColors.LightBackground,
    onBackground = OneKvmColors.NearBlack,
    surface = OneKvmColors.LightBackground,
    onSurface = OneKvmColors.NearBlack,
    surfaceVariant = OneKvmColors.LightSurface,
    onSurfaceVariant = OneKvmColors.LightMutedForeground,
    error = OneKvmColors.Destructive,
    onError = OneKvmColors.White,
    outline = OneKvmColors.LightBorder,
    outlineVariant = OneKvmColors.LightBorder,
    surfaceContainer = OneKvmColors.LightSurface,
    surfaceContainerHigh = OneKvmColors.LightBorder,
    surfaceContainerLow = OneKvmColors.LightBackground,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = OneKvmColors.DarkPrimary,
    onPrimary = OneKvmColors.NearBlack,
    primaryContainer = OneKvmColors.DarkSurface,
    onPrimaryContainer = OneKvmColors.DarkPrimary,
    secondary = OneKvmColors.DarkSurface,
    onSecondary = OneKvmColors.DarkPrimary,
    background = OneKvmColors.DarkBackground,
    onBackground = OneKvmColors.White,
    surface = OneKvmColors.DarkBackground,
    onSurface = OneKvmColors.White,
    surfaceVariant = OneKvmColors.DarkSurface,
    onSurfaceVariant = OneKvmColors.DarkMutedForeground,
    error = OneKvmColors.Destructive,
    onError = OneKvmColors.NearBlack,
    outline = OneKvmColors.DarkBorder,
    outlineVariant = OneKvmColors.DarkBorder,
    surfaceContainer = OneKvmColors.DarkSurface,
    surfaceContainerHigh = OneKvmColors.DarkBorder,
    surfaceContainerLow = OneKvmColors.DarkBackground,
)

/** Component shapes: --radius 10dp for cards, 6dp for controls. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

@Composable
fun NanoKvmProTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TypographyDefaults,
        shapes = AppShapes,
        content = content,
    )
}

// System default typography is fine; mirrored here for explicit override later.
private val TypographyDefaults: Typography = Typography()