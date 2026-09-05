package com.nanokvm.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * One-KVM palette, ported from `One-KVM/web/src/style.css`
 * (OKLCH tokens approximated to sRGB hex, hue/chroma meaning preserved).
 */
object OneKvmColors {
    // Neutral scale (both themes)
    val White = Color(0xFFFBFBFA)
    val NearBlack = Color(0xFF17181B)

    // Light mode
    val LightBackground = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF5F5F6)
    val LightMutedForeground = Color(0xFF898A8E)
    val LightBorder = Color(0xFFEAE9EB)
    val LightPrimary = Color(0xFF202124)
    val LightRing = Color(0xFFB4B3B7)

    // Dark mode
    val DarkBackground = Color(0xFF17181A)
    val DarkSurface = Color(0xFF2A2B2E)
    val DarkMutedForeground = Color(0xFFB4B3B7)
    val DarkBorder = Color(0xFF26272A)
    val DarkPrimary = Color(0xFFFBFBFA)
    val DarkRing = Color(0xFFC9D0E0)

    // Semantic status colors (CSS: success / status-active / warning / destructive / info)
    val Success = Color(0xFF1FA865)
    val SuccessBright = Color(0xFF45E07A)
    val Warning = Color(0xFFD98A1F)
    val WarningBright = Color(0xFFF2B33D)
    val Destructive = Color(0xFFDE3B32)
    val Info = Color(0xFF2F6FED)
    val InfoBright = Color(0xFF6C9CF8)
}

/** Semantic status color used by StatusChip / status dots. */
enum class StatusTone { Ok, Warning, Error, Neutral, Connecting }

fun statusColor(tone: StatusTone, isDark: Boolean): Color = when (tone) {
    StatusTone.Ok -> OneKvmColors.SuccessBright
    StatusTone.Warning -> if (isDark) OneKvmColors.WarningBright else OneKvmColors.Warning
    StatusTone.Error -> OneKvmColors.Destructive
    StatusTone.Neutral -> if (isDark) OneKvmColors.DarkMutedForeground else OneKvmColors.LightMutedForeground
    StatusTone.Connecting -> if (isDark) OneKvmColors.WarningBright else OneKvmColors.Warning
}