package com.nanokvm.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanokvm.app.ui.theme.StatusTone
import com.nanokvm.app.ui.theme.statusColor

/**
 * One-KVM style status chip: colored dot + label + value, optionally tappable
 * (the web wraps these in a hover card/popover for details).
 */
@Composable
fun StatusChip(
    label: String,
    value: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    monospace: Boolean = false,
) {
    val isDark = isSystemInDarkTheme()
    val chipModifier = modifier
        .clip(RoundedCornerShape(999.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
        .padding(horizontal = 10.dp, vertical = 5.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)

    Row(
        modifier = chipModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor(tone, isDark)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Small status stat: colored icon chip + label + value. */
@Composable
fun MiniStat(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(statusColor(tone, isDark).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * One-KVM segmented control: filled segment for the selected option, outline for
 * the rest (used for video mode / mouse mode / bitrate presets).
 */
@Composable
fun <T> SegmentedButtons(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (label, value) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}