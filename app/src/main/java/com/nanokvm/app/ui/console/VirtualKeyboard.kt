package com.nanokvm.app.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanokvm.app.data.hid.HidKeymap
import com.nanokvm.app.ui.theme.GlassTokens
import com.nanokvm.app.ui.theme.GlassPanel
import dev.chrisbanes.haze.HazeState

/** A single virtual keyboard key. */
sealed interface KKey {
    val label: String
    val weight: Float

    data class HID(override val label: String, val usage: Int, override val weight: Float = 1f) : KKey
    data class Mod(override val label: String, val bit: Int, override val weight: Float = 1.4f) : KKey
    data class Action(override val label: String, val action: ActionKind, override val weight: Float = 1f) : KKey
}

enum class ActionKind { BACKSPACE, ENTER, TAB, ESC, DELETE, ARROW_LEFT, ARROW_DOWN, ARROW_UP, ARROW_RIGHT, CLEAR }

/** One-KVM inspired compact layout — rows of physical HID keys. */
object VirtualKeyboardLayout {
    val rows: List<List<KKey>> = listOf(
        listOf(
            KKey.Mod("Ctrl", HidKeymap.MOD_LCTRL),
            KKey.Mod("Alt", HidKeymap.MOD_LALT),
            KKey.Mod("Shift", HidKeymap.MOD_LSHIFT, 1.2f),
            KKey.Mod("Win", HidKeymap.MOD_LMETA),
            KKey.HID("Tab", HidKeymap.HID_TAB),
            KKey.Action("Esc", ActionKind.ESC),
        ),
        listOf(
            KKey.HID("1", 0x1e), KKey.HID("2", 0x1f), KKey.HID("3", 0x20), KKey.HID("4", 0x21),
            KKey.HID("5", 0x22), KKey.HID("6", 0x23), KKey.HID("7", 0x24), KKey.HID("8", 0x25),
            KKey.HID("9", 0x26), KKey.HID("0", 0x27), KKey.HID("-", 0x2d), KKey.HID("=", 0x2e),
        ),
        listOf(
            KKey.HID("Q", 0x14), KKey.HID("W", 0x1a), KKey.HID("E", 0x08), KKey.HID("R", 0x15),
            KKey.HID("T", 0x17), KKey.HID("Y", 0x1c), KKey.HID("U", 0x18), KKey.HID("I", 0x0c),
            KKey.HID("O", 0x12), KKey.HID("P", 0x13),
        ),
        listOf(
            KKey.HID("A", 0x04), KKey.HID("S", 0x16), KKey.HID("D", 0x07), KKey.HID("F", 0x09),
            KKey.HID("G", 0x0a), KKey.HID("H", 0x0b), KKey.HID("J", 0x0d), KKey.HID("K", 0x0e),
            KKey.HID("L", 0x0f),
        ),
        listOf(
            KKey.HID("Z", 0x1d), KKey.HID("X", 0x1b), KKey.HID("C", 0x06), KKey.HID("V", 0x19),
            KKey.HID("B", 0x05), KKey.HID("N", 0x11), KKey.HID("M", 0x10),
        ),
        listOf(
            KKey.Action("←", ActionKind.ARROW_LEFT),
            KKey.Action("↑", ActionKind.ARROW_UP),
            KKey.Action("↓", ActionKind.ARROW_DOWN),
            KKey.Action("→", ActionKind.ARROW_RIGHT),
            KKey.Action("⌫", ActionKind.BACKSPACE, 1.3f),
            KKey.HID("空格", HidKeymap.HID_SPACE, 2.2f),
            KKey.Action("⏎", ActionKind.ENTER, 1.3f),
            KKey.Action("CLR", ActionKind.CLEAR, 1f),
        ),
    )
}

/**
 * Bottom virtual keyboard. Keys report press/release upward; the ViewModel owns the
 * HID state so on-screen keys and a physical keyboard share one stream. Modifier
 * keys act as sticky toggles (tap once to hold, tap again to release).
 */
@Composable
fun VirtualKeyboard(
    isDark: Boolean,
    activeModifiers: Int = 0,
    hazeState: HazeState? = null,
    onKeyDown: (KKey.HID) -> Unit,
    onKeyUp: (KKey.HID) -> Unit,
    onModifierToggle: (KKey.Mod) -> Unit,
    onAction: (ActionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // Wide screens (tablets / desktop windows): keep a keyboard-sized column instead
    // of stretching one row of keys across the whole display.
    GlassPanel(
        isDark = isDark,
        shape = RectangleShape,
        modifier = Modifier
            .fillMaxWidth(),
        tint = if (isDark) Color(0xFF0E0F11) else Color(0xFFF0F1F3),
        tintAlphaOverride = if (isDark) 0.30f else 0.55f,
        hazeState = hazeState,
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(GlassTokens.hairline(isDark)),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(modifier)
                .widthIn(max = 960.dp)
                .fillMaxWidth()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                (HidKeymap.HID_F1..HidKeymap.HID_F12).forEach { usage ->
                    val label = "F${usage - 0x3a + 1}"
                    BoxHolder(key = KKey.HID(label, usage), weight = 1f, isDark = isDark, active = false, onKeyDown = onKeyDown, onKeyUp = onKeyUp, onModifierToggle = onModifierToggle, onAction = onAction)
                }
            }
            VirtualKeyboardLayout.rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    row.forEach { key ->
                        BoxHolder(
                            key = key,
                            weight = key.weight,
                            isDark = isDark,
                            active = key is KKey.Mod && (activeModifiers and key.bit) != 0,
                            onKeyDown = onKeyDown,
                            onKeyUp = onKeyUp,
                            onModifierToggle = onModifierToggle,
                            onAction = onAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.BoxHolder(
    key: KKey,
    weight: Float,
    isDark: Boolean,
    active: Boolean,
    onKeyDown: (KKey.HID) -> Unit,
    onKeyUp: (KKey.HID) -> Unit,
    onModifierToggle: (KKey.Mod) -> Unit,
    onAction: (ActionKind) -> Unit,
) {
    // 键帽铺满整个按键格(等宽块状),命中区即整格;修饰键加粗、粘滞激活时青色高亮。
    val modKey = key is KKey.Mod
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxWidth()
            .pointerInput(key) {
                when (key) {
                    is KKey.HID -> detectTapGestures(
                        onPress = {
                            onKeyDown(key)
                            tryAwaitRelease()
                            onKeyUp(key)
                        },
                    )
                    is KKey.Mod -> detectTapGestures(onTap = { onModifierToggle(key) })
                    is KKey.Action -> detectTapGestures(onTap = { onAction(key.action) })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) Color(0xFF7BE7FF) else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (modKey) FontWeight.SemiBold else FontWeight.Normal,
            // 长标签(F10/CLR)降一号字并收窄水平留白,避免等宽键帽下被裁剪。
            fontSize = if (key.label.length > 2) 12.sp else 14.sp,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        active -> Color(0xFF7BE7FF).copy(alpha = 0.16f)
                        modKey -> GlassTokens.keyBg(isDark).copy(alpha = 0.6f)
                        else -> GlassTokens.keyBg(isDark)
                    },
                )
                .border(
                    1.dp,
                    if (active) Color(0xFF7BE7FF).copy(alpha = 0.4f) else GlassTokens.keyBorder(isDark),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 2.dp, vertical = 10.dp),
            textAlign = TextAlign.Center,
        )
    }
}