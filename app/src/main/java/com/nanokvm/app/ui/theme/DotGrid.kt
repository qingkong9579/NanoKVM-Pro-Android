package com.nanokvm.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nanokvm.app.ui.theme.OneKvmColors

/**
 * One-KVM dotted backdrop: 1px dots on a 20px grid over the muted background.
 * Ported from `One-KVM/web/src/style.css` `.dot-grid-bg`
 * (dot color is 35%-alpha muted-foreground).
 */
@Composable
fun DotGridBackground(
    modifier: Modifier = Modifier,
    step: Dp = 20.dp,
    dotColor: Color = OneKvmColors.LightMutedForeground,
    background: Color = OneKvmColors.LightSurface,
) {
    val stepPx = step.value * LocalDensity.current.density
    Canvas(modifier = modifier) {
        drawRect(color = background, size = size)
        val r = stepPx.coerceAtMost(1.2f) * 0.045f
        var x = stepPx / 2
        while (x < size.width) {
            var y = stepPx / 2
            while (y < size.height) {
                drawCircle(color = dotColor.copy(alpha = 0.35f), radius = r, center = Offset(x, y))
                y += stepPx
            }
            x += stepPx
        }
    }
}