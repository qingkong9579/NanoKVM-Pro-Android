package com.nanokvm.app.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanokvm.app.data.hid.HidKeymap

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
            KKey.HID("⇥", HidKeymap.HID_TAB),
            KKey.Action("Esc", ActionKind.ESC),
        ),
        listOf(
            KKey.HID("1", 0x1e), KKey.HID("2", 0x1f), KKey.HID("3", 0x20), KKey.HID("4", 0x21),
            KKey.HID("5", 0x22), KKey.HID("6", 0x23), KKey.HID("7", 0x24), KKey.HID("8", 0x25),
            KKey.HID("9", 0x26), KKey.HID("0", 0x27), KKey.HID("-", 0x2d), KKey.HID("=", 0x2e),
        ),
        listOf(
            KKey.HID("q", 0x14), KKey.HID("w", 0x1a), KKey.HID("e", 0x08), KKey.HID("r", 0x15),
            KKey.HID("t", 0x17), KKey.HID("y", 0x1c), KKey.HID("u", 0x18), KKey.HID("i", 0x0c),
            KKey.HID("o", 0x12), KKey.HID("p", 0x13), KKey.HID("[", 0x2f), KKey.HID("]", 0x30),
        ),
        listOf(
            KKey.HID("a", 0x04), KKey.HID("s", 0x16), KKey.HID("d", 0x07), KKey.HID("f", 0x09),
            KKey.HID("g", 0x0a), KKey.HID("h", 0x0b), KKey.HID("j", 0x0d), KKey.HID("k", 0x0e),
            KKey.HID("l", 0x0f), KKey.HID(";", 0x33), KKey.HID("'", 0x34), KKey.HID("\\", 0x31),
        ),
        listOf(
            KKey.HID("z", 0x1d), KKey.HID("x", 0x1b), KKey.HID("c", 0x06), KKey.HID("v", 0x19),
            KKey.HID("b", 0x05), KKey.HID("n", 0x11), KKey.HID("m", 0x10), KKey.HID(",", 0x36),
            KKey.HID(".", 0x37), KKey.HID("/", 0x38), KKey.HID("`", 0x35),
        ),
        listOf(
            KKey.Action("←", ActionKind.ARROW_LEFT),
            KKey.Action("↑", ActionKind.ARROW_UP),
            KKey.Action("↓", ActionKind.ARROW_DOWN),
            KKey.Action("→", ActionKind.ARROW_RIGHT),
            KKey.Action("⌫", ActionKind.BACKSPACE, 1.4f),
            KKey.HID("␣", HidKeymap.HID_SPACE, 2.2f),
            KKey.Action("⏎", ActionKind.ENTER, 1.4f),
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
    onKeyDown: (KKey.HID) -> Unit,
    onKeyUp: (KKey.HID) -> Unit,
    onModifierToggle: (KKey.Mod) -> Unit,
    onAction: (ActionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // Wide screens (tablets / desktop windows): keep a keyboard-sized column instead
    // of stretching one row of keys across the whole display.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = modifier
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
                    BoxHolder(key = KKey.HID(label, usage), weight = 1f, onKeyDown = onKeyDown, onKeyUp = onKeyUp, onModifierToggle = onModifierToggle, onAction = onAction)
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
    onKeyDown: (KKey.HID) -> Unit,
    onKeyUp: (KKey.HID) -> Unit,
    onModifierToggle: (KKey.Mod) -> Unit,
    onAction: (ActionKind) -> Unit,
) {
    // 键帽铺满整个按键格(等宽块状),命中区即整格;修饰键加粗区分。
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
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (key is KKey.Mod) FontWeight.SemiBold else FontWeight.Normal,
            // 长标签(F10/CLR)降一号字并收窄水平留白,避免等宽键帽下被裁剪。
            fontSize = if (key.label.length > 2) 12.sp else 14.sp,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (key is KKey.Mod) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(horizontal = 2.dp, vertical = 10.dp),
            textAlign = TextAlign.Center,
        )
    }
}