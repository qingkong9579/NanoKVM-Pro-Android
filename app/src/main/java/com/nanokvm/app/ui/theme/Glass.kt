package com.nanokvm.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/**
 * 磨砂玻璃全局样式(持久化于 SettingsStore,设置 Sheet 可实时调节):
 *  - blurRadiusDp:高斯模糊半径(dp)
 *  - tintAlpha:半透明 tint 强度(0..1),与模糊叠加决定面板的"实度"
 */
object GlassPrefs {
    var blurRadiusDp by mutableFloatStateOf(16f)
    var tintAlpha by mutableFloatStateOf(0.24f)
}

/** 面板顶缘折射高光条;放在 Box 内用 modifier.align(Alignment.TopCenter)。 */
@Composable
fun RefractionHighlight(modifier: Modifier = Modifier, isDark: Boolean) {
    Box(
        modifier = modifier
            .padding(horizontal = 0.dp)
            .fillMaxWidth(0.86f)
            .height(4.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, GlassTokens.highlight(isDark), Color.Transparent),
                ),
            ),
    )
}

/**
 * 磨砂玻璃面板(haze):实时高斯模糊背景层 + 半透明 tint + 噪点。
 * - hazeState 为空(跨弹窗窗口等无法采样的场景)→ 退化为半透明平色。
 * - API <31 时 haze 自身退化为 fallbackTint 半透明色。
 * 面板底色垫底,内容绘制在其上。
 */
@Composable
fun GlassPanel(
    isDark: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    tint: Color = if (isDark) Color(0xFF0E0F11) else Color.White,
    tintAlphaOverride: Float? = null,
    blurOverride: Dp? = null,
    hazeState: HazeState? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val tintAlpha = tintAlphaOverride ?: GlassPrefs.tintAlpha
    val blurDp = blurOverride ?: GlassPrefs.blurRadiusDp.dp
    val fallback = tint.copy(alpha = (tintAlpha * 1.6f).coerceAtMost(0.92f))
    Box(
        modifier = modifier
            .clip(shape)
            .background(fallback)
            .border(1.dp, GlassTokens.hairline(isDark), shape),
    ) {
        if (hazeState != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .hazeChild(
                        hazeState,
                        style = HazeStyle(
                            backgroundColor = tint,
                            tints = listOf(HazeTint(tint.copy(alpha = tintAlpha))),
                            blurRadius = blurDp,
                            noiseFactor = 0.08f,
                            fallbackTint = HazeTint(fallback),
                        ),
                    ),
            )
        }
        content()
    }
}

/** 顶栏/工具条磨砂(悬浮在视频上时使用);hazeState 为空则退化为实色表面。 */
@Composable
fun Modifier.glassFrost(hazeState: HazeState?, isDark: Boolean): Modifier {
    if (hazeState == null) return this.background(MaterialTheme.colorScheme.surface)
    val tint = if (isDark) Color(0xFF0E0F11) else Color.White
    val a = (GlassPrefs.tintAlpha * 1.3f).coerceAtMost(0.85f)
    return this.hazeChild(
        hazeState,
        style = HazeStyle(
            backgroundColor = tint,
            tints = listOf(HazeTint(tint.copy(alpha = a))),
            blurRadius = GlassPrefs.blurRadiusDp.dp,
            noiseFactor = 0.06f,
            fallbackTint = HazeTint(tint.copy(alpha = a.coerceAtMost(0.9f))),
        ),
    )
}

/** 玻璃样式令牌。 */
object GlassTokens {
    fun hairline(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)

    fun hairlineStrong(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.12f)

    fun highlight(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.95f)

    /** 虚拟键盘键帽 */
    fun keyBg(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.74f)

    fun keyBorder(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.08f)
}

/** 圆角速取(与 design-v2 令牌一致)。 */
object GlassShapes {
    val card = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    val dock = RoundedCornerShape(16.dp)
    val input = RoundedCornerShape(8.dp)
}
