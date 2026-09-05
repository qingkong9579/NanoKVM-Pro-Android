package com.nanokvm.app.ui.console

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanokvm.app.data.api.DeviceStats
import com.nanokvm.app.ui.components.SegmentedButtons
import com.nanokvm.app.data.api.NanoKvmApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private const val MAX_POINTS = 75 // 固定滚动窗口:满窗后左出右进,不再延伸

/** 设备健康监控 — CPU/内存/温度/负载,2s 采样,实时数字 + 曲线。 */
@Composable
fun DeviceMonitorDialog(rest: NanoKvmApi, onClose: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    var cpuNow by remember { mutableStateOf(0f) }
    var memPct by remember { mutableStateOf(0f) }
    var memText by remember { mutableStateOf("—") }
    var tempText by remember { mutableStateOf("—") }
    var loadText by remember { mutableStateOf("—") }
    val cpuHist = remember { mutableListOf<Float>() }
    val memHist = remember { mutableListOf<Float>() }
    val tempHist = remember { mutableListOf<Float>() }
    var lastCpu by remember { mutableStateOf<DeviceStats?>(null) }
    var samples by remember { mutableStateOf(0) }
    var intervalMs by remember { mutableStateOf(2000) }
    val context = LocalContext.current
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(intervalMs) {
        if (!firstRun) {
            android.widget.Toast.makeText(context, "采样间隔已更新:${intervalMs / 1000}s", android.widget.Toast.LENGTH_SHORT).show()
        }
        firstRun = false
    }

    fun push(list: MutableList<Float>, v: Float) {
        list.add(v)
        if (list.size > MAX_POINTS) list.removeAt(0)
    }

    LaunchedEffect(intervalMs) {
        while (isActive) {
            try {
                val s = rest.fetchDeviceStats()
                if (s != null) {
                    lastCpu?.let { prev ->
                        val dTotal = (s.cpuTotal - prev.cpuTotal).toFloat()
                        val dIdle = (s.cpuIdle - prev.cpuIdle).toFloat()
                        if (dTotal > 0) cpuNow = ((1f - dIdle / dTotal) * 100f).coerceIn(0f, 100f)
                    }
                    lastCpu = s
                    if (s.memTotalKb > 0) {
                        memPct = ((s.memTotalKb - s.memAvailKb).toFloat() / s.memTotalKb * 100f).coerceIn(0f, 100f)
                        memText = String.format(Locale.US, "%.1f / %.1f GB", (s.memTotalKb - s.memAvailKb) / 1048576f, s.memTotalKb / 1048576f)
                    }
                    tempText = if (s.tempC.isNaN()) "—" else String.format(Locale.US, "%.0f °C", s.tempC)
                    loadText = if (s.load1.isNaN()) "—" else String.format(Locale.US, "%.2f", s.load1)
                    push(cpuHist, cpuNow)
                    push(memHist, memPct)
                    if (!s.tempC.isNaN()) push(tempHist, s.tempC)
                    samples++
                    err = null
                }
            } catch (e: Exception) {
                err = e.message ?: "采样失败"
            }
            delay(intervalMs.toLong())
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("设备监控", style = MaterialTheme.typography.titleMedium)
                Text(
                    "间隔 ${intervalMs / 1000}s · 已采 $samples 次 · 窗口 ${intervalMs * MAX_POINTS / 1000 / 60} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                err?.let {
                    Text(
                        it,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("CPU", String.format(Locale.US, "%.0f%%", cpuNow), if (cpuNow > 85) Color(0xFFF87171) else null, Modifier.weight(1f))
                    StatCard("内存", String.format(Locale.US, "%.0f%%", memPct), if (memPct > 85) Color(0xFFF87171) else null, Modifier.weight(1f))
                    StatCard("温度", tempText, null, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("负载(1m)", loadText, null, Modifier.weight(1f))
                    StatCard("内存占用", memText, null, Modifier.weight(1f))
                    StatCard("采样", "${intervalMs / 1000} s", null, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("采样间隔", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    SegmentedButtons(
                        options = listOf("1s" to 1000, "2s" to 2000, "5s" to 5000, "10s" to 10000),
                        selected = intervalMs,
                        onSelect = { intervalMs = it },
                    )
                }
                MiniChart("CPU 使用率(%)", cpuHist.toList(), Color(0xFF8B5CF6), 0f, 100f, MAX_POINTS, { v: Float -> String.format(Locale.US, "%.0f%%", v) })
                MiniChart("内存使用率(%)", memHist.toList(), Color(0xFF22C55E), 0f, 100f, MAX_POINTS, { v: Float -> String.format(Locale.US, "%.0f%%", v) })
                MiniChart("温度(°C)", tempHist.toList(), Color(0xFFF97316), Float.NaN, Float.NaN, MAX_POINTS, { v: Float -> String.format(Locale.US, "%.0f°C", v) })
                Text(
                    "数据读取自 /proc/stat · /proc/meminfo · thermal_zone,实时反映设备本体(非被控机)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

@Composable
private fun StatCard(label: String, value: String, warn: Color?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            color = warn ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MiniChart(
    title: String,
    values: List<Float>,
    color: Color,
    fixedMin: Float,
    fixedMax: Float,
    window: Int,
    fmt: (Float) -> String,
) {
    val valid = (values.filter { it.isFinite() }).takeLast(window)
    val last = valid.lastOrNull()
    // 与「性能」面板同款 uPlot 风:细线 + 10% 同色填充 + 细网格,数值灰
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (last != null) fmt(last) else "—",
                style = MaterialTheme.typography.labelMedium,
                color = OneKvmMonitorText,
                fontFamily = FontFamily.Monospace,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
        ) {
            val w = size.width
            val h = size.height
            val minV: Float
            val maxV: Float
            if (fixedMin.isFinite()) {
                minV = fixedMin
                maxV = fixedMax
            } else if (valid.size >= 2) {
                val mn = valid.minOrNull() ?: 0f
                val mx = valid.maxOrNull() ?: 1f
                val pad = ((mx - mn).coerceAtLeast(1f)) * 0.15f
                minV = (mn - pad).coerceAtLeast(0f)
                maxV = mx + pad
            } else {
                minV = 0f
                maxV = 1f
            }
            val span = (maxV - minV).coerceAtLeast(0.001f)
            fun yOf(v: Float) = h - 1.5f - ((v - minV) / span) * (h - 3f)

            // 细网格(画布四等分,与性能面板一致),无数据也显示
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(OneKvmMonitorGrid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            if (valid.size >= 2) {
                // 恒定步距、最新点在右缘:未满窗前点靠右排布,满窗后整体左移滚动
                val stepX = w / (window - 1)
                val line = Path()
                val area = Path()
                valid.forEachIndexed { i, v ->
                    val x = w - (valid.size - 1 - i) * stepX
                    val y = yOf(v)
                    if (i == 0) {
                        line.moveTo(x, y)
                        area.moveTo(x, h)
                        area.lineTo(x, y)
                    } else {
                        line.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                }
                area.lineTo(w, h)
                area.close()
                drawPath(area, color.copy(alpha = 0.10f))
                drawPath(line, color, style = Stroke(width = 1.5f))
            }
        }
    }
}

private val OneKvmMonitorText = Color(0xFF94A3B8)
private val OneKvmMonitorGrid = Color(0x1A94A3B8)
