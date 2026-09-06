package com.nanokvm.app.ui.console

import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.nanokvm.app.data.api.HidMouseMode
import com.nanokvm.app.data.hid.MouseButton
import com.nanokvm.app.media.WebRtcEnv
import com.nanokvm.app.ui.components.SegmentedButtons
import com.nanokvm.app.ui.components.StatusChip
import com.nanokvm.app.ui.theme.DotGridBackground
import com.nanokvm.app.ui.theme.StatusTone
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.math.abs

/** Main remote-desktop console: top status bar + action bar + video stage + keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    viewModel: ConsoleViewModel,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    onOpenTerminal: (com.nanokvm.app.ui.terminal.TerminalRequest) -> Unit = {},
    onOpenAssistant: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val stats by viewModel.stats.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.start()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 底色必须铺到状态栏后面:只 padding 不铺底,深色主题会露出白色窗口底
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        TopBar(state, isDark, onToggleTheme, onBack, viewModel)
        ActionBar(state, viewModel)
        Box(modifier = Modifier.weight(1f)) {
            VideoStage(state, viewModel)
            StageOverlays(state, viewModel)
            if (state.statsVisible) StatsOverlay(state, viewModel, stats)
        }
        if (state.vkbVisible) {
            VirtualKeyboard(
                onKeyDown = viewModel::vkbKeyDown,
                onKeyUp = viewModel::vkbKeyUp,
                onModifierToggle = viewModel::vkbModifierToggle,
                onAction = viewModel::vkbAction,
            )
        }
    }

    if (state.settingsSheetOpen) {
        SettingsSheet(state, viewModel)
    }
    if (state.toolsSheetOpen) {
        ConsoleToolsSheet(state, viewModel, onOpenTerminal, onOpenAssistant)
    }
}

@Composable
private fun TopBar(
    state: ConsoleUiState,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    viewModel: ConsoleViewModel,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 左侧状态组放在“weight 容器”内(weight 之后的兄弟行内件在本机不渲染,见双栏同源坑)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        // 画面:分辨率;分阶段给可扫读文案与状态色
        val picValue = when {
            state.phase == Phase.ERROR -> "无画面"
            state.videoFormatKnown && state.phase == Phase.STREAMING -> "${state.videoWidth}×${state.videoHeight}"
            state.phase == Phase.STREAMING && !state.videoFormatKnown -> "等待首帧…"
            else -> "连接中…"
        }
        val picTone = when {
            state.phase == Phase.ERROR -> StatusTone.Error
            state.phase == Phase.STREAMING && state.videoFormatKnown -> StatusTone.Ok
            else -> StatusTone.Connecting
        }
        StatusChip(
            label = "画面",
            value = picValue,
            tone = picTone,
            monospace = state.videoFormatKnown,
            modifier = Modifier.height(28.dp),
        )
        // 会话:编码 · 传输 + 健康状态(重连/错误时取代编码信息,避免两 chip 各说一半)
        val codec = if (state.streamMode.startsWith("h265")) "H.265" else "H.264"
        val transport = if (state.streamMode.endsWith("webrtc")) "WebRTC" else "直连"
        val sessValue = when {
            state.phase == Phase.ERROR -> "错误"
            state.reconnecting != null && state.reconnecting!! > 0 -> "重连中 ${state.reconnecting}"
            state.phase == Phase.CONNECTING -> "连接中…"
            else -> "$codec · $transport"
        }
        val sessTone = when {
            state.phase == Phase.ERROR -> StatusTone.Error
            state.reconnecting != null && state.reconnecting!! > 0 -> StatusTone.Warning
            state.phase == Phase.CONNECTING -> StatusTone.Connecting
            else -> StatusTone.Ok
        }
        StatusChip(
            label = "会话",
            value = sessValue,
            tone = sessTone,
            monospace = !sessValue.contains("…") && sessValue != "错误",
            modifier = Modifier.height(28.dp),
        )
        // 鼠标:操控模式
        StatusChip(
            label = "鼠标",
            value = if (state.mouseMode == HidMouseMode.ABSOLUTE) "绝对" else "相对",
            tone = StatusTone.Ok,
            modifier = Modifier.height(28.dp),
        )
        }
        IconButton(onClick = onToggleTheme, modifier = Modifier.size(40.dp)) {
            Icon(
                if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                contentDescription = if (isDark) "切换浅色" else "切换深色",
                modifier = Modifier.size(20.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "菜单", modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("断开连接", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ExitToApp, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; viewModel.disconnect() },
                )
                DropdownMenuItem(
                    text = { Text("返回") },
                    onClick = { menuOpen = false; onBack() },
                )
            }
        }
    }
}

@Composable
private fun ActionBar(state: ConsoleUiState, viewModel: ConsoleViewModel) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ActionIcon(Icons.Outlined.Settings, "设置(视频流/鼠标模式)", "设置") { viewModel.toggleSettingsSheet() }
            ActionIcon(Icons.Outlined.Mouse, "鼠标模式", "鼠标") {
                viewModel.setMouseMode(if (state.mouseMode == HidMouseMode.ABSOLUTE) HidMouseMode.RELATIVE else HidMouseMode.ABSOLUTE)
            }
            ActionIcon(Icons.Outlined.Keyboard, "虚拟键盘", "键盘") { viewModel.toggleVirtualKeyboard() }
            ActionIcon(Icons.Outlined.Handyman, "工具箱", "工具箱") { viewModel.toggleToolsSheet() }
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            // Neutral vertical hairline between the two action groups.
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            ActionIcon(Icons.Outlined.BarChart, "性能", "性能") { viewModel.toggleStats() }
            ActionIcon(Icons.Outlined.Refresh, "重新连接", "重连") { viewModel.reconnect() }
        }
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    caption: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 40.dp),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** The video stage: dot-grid letterbox + fitted black video rect + touch mouse. */
@Composable
private fun VideoStage(
    state: ConsoleUiState,
    viewModel: ConsoleViewModel,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        var stagePx by remember { mutableStateOf(IntSize(0, 0)) }
        DotGridBackground(Modifier.fillMaxSize())

        // Letterbox math in Dp (BoxWithConstraints exposes maxWidth/maxHeight as Dp).
        // Rotation/zoom are client-side (web video-transform). SurfaceView-based
        // transports (WebRTC) cannot be view-transformed, so transforms apply only
        // to the direct TextureView path; quarter turns swap the fitted aspect and
        // the media box is laid out transposed then rotated.
        val outerW = maxWidth.value
        val outerH = maxHeight.value
        val webrtc = state.streamMode.endsWith("webrtc")
        val effRotation = if (webrtc) 0 else state.videoRotation
        val effScale = if (webrtc) 1f else state.videoScale
        val quarter = effRotation == 90 || effRotation == 270
        val vw = if (quarter) state.videoHeight.toFloat() else state.videoWidth.toFloat()
        val vh = if (quarter) state.videoWidth.toFloat() else state.videoHeight.toFloat()

        val fw: Float
        val fh: Float
        if (state.videoFormatKnown && state.videoWidth > 0 && state.videoHeight > 0 && outerW > 0 && outerH > 0) {
            val ratio = vw / vh
            val containerRatio = outerW / outerH
            if (ratio >= containerRatio) {
                fw = outerW
                fh = outerW / ratio
            } else {
                fh = outerH
                fw = outerH * ratio
            }
        } else {
            fw = outerW
            fh = outerH
        }
        val offXDp = (outerW - fw) / 2f
        val offYDp = (outerH - fh) / 2f

        Box(
            modifier = Modifier
                .offset(x = offXDp.dp, y = offYDp.dp)
                .size(width = fw.dp, height = fh.dp)
                .background(Color.Black)
                .clipToBounds()
                .onSizeChanged { stagePx = it },
        ) {
            // Media box in UNROTATED orientation (transposed for quarter turns), so
            // rotating it by `effRotation` fills the fitted rect exactly; uniform
            // scale implements the web's zoom (crop around the center).
            val hostW = if (quarter) fh else fw
            val hostH = if (quarter) fw else fh
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .size(width = hostW.dp, height = hostH.dp)
                        .graphicsLayer {
                            rotationZ = effRotation.toFloat()
                            scaleX = effScale
                            scaleY = effScale
                        },
                ) {
                    if (webrtc) {
                        WebRtcViewHost(viewModel)
                    } else {
                        DirectTextureHost(viewModel)
                    }
                }
            }
            // Touch mouse only when we know the frame geometry.
            if (state.videoFormatKnown && state.phase == Phase.STREAMING) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(state.mouseMode, effRotation, effScale, state.wheelDir) {
                            touchMouseGestures(
                                viewModel = viewModel,
                                boxPx = stagePx,
                                mode = state.mouseMode,
                                rotation = effRotation,
                                scale = effScale,
                                wheelDir = state.wheelDir,
                            )
                        },
                )
            }
        }
        // Crosshair follows the fitted rect center.
        if (state.videoFormatKnown) {
            Crosshair(
                modifier = Modifier
                    .offset(x = offXDp.dp, y = offYDp.dp)
                    .size(width = fw.dp, height = fh.dp),
            )
        }
    }
}

/**
 * Overlay-normalized (0..1 over the fitted rect) → remote-normalized (0..1):
 * zoom-crop inverse, then the web's `inverseRotatePoint` for the display rotation.
 */
private fun mapToRemote(nx: Float, ny: Float, rotation: Int, scale: Float): Pair<Float, Float> {
    var x = nx
    var y = ny
    if (scale != 1f) {
        x = 0.5f + (x - 0.5f) / scale
        y = 0.5f + (y - 0.5f) / scale
    }
    return when (rotation) {
        90 -> (y to 1f - x)
        180 -> (1f - x to 1f - y)
        270 -> (1f - y to x)
        else -> x to y
    }.let { (rx, ry) -> rx.coerceIn(0f, 1f) to ry.coerceIn(0f, 1f) }
}

/** Touch delta (screen px) → remote delta for the display rotation (inverseRotateDelta). */
private fun mapDeltaToRemote(dx: Float, dy: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
    90 -> dy to -dx
    180 -> -dx to -dy
    270 -> -dy to dx
    else -> dx to dy
}

/** WebRTC video host — `org.webrtc.SurfaceViewRenderer` bound to the session. */
@Composable
private fun WebRtcViewHost(viewModel: ConsoleViewModel) {
    val appContext = LocalContext.current.applicationContext
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebRtcEnv.ensure(appContext)
            SurfaceViewRenderer(ctx).apply {
                setMirror(false)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                init(WebRtcEnv.eglContext(), null)
                viewModel.bindRenderer(this)
            }
        },
        onRelease = { renderer ->
            viewModel.unbindRenderer(renderer)
            renderer.release()
        },
    )
}

/** Direct-mode video host — TextureView (MediaCodec renders into its SurfaceTexture),
 *  the only transformable (rotation/zoom) video surface path. */
@Composable
private fun DirectTextureHost(viewModel: ConsoleViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                isFocusable = true
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        viewModel.bindTexture(st)
                        requestFocus()
                    }

                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                        viewModel.unbindTexture()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                }
                setOnKeyListener { _, keyCode, event ->
                    when (event.action) {
                        android.view.KeyEvent.ACTION_DOWN -> viewModel.physicalKeyDown(keyCode)
                        android.view.KeyEvent.ACTION_UP -> viewModel.physicalKeyUp(keyCode)
                        else -> false
                    }
                }
            }
        },
    )
}

/** One-KVM crosshair: thin cross with black outer + white inner stroke. */
@Composable
private fun Crosshair(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val len = 11.5f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, strokeWidth: Float, color: Color) {
            drawLine(color, androidx.compose.ui.geometry.Offset(x1, y1), androidx.compose.ui.geometry.Offset(x2, y2), strokeWidth, StrokeCap.Round)
        }
        line(cx - len, cy, cx + len, cy, 4f, Color.Black)
        line(cx, cy - len, cx, cy + len, 4f, Color.Black)
        line(cx - len, cy, cx + len, cy, 2f, Color.White)
        line(cx, cy - len, cx, cy + len, 2f, Color.White)
    }
}

@Composable
private fun StageOverlays(state: ConsoleUiState, viewModel: ConsoleViewModel) {
    var noSignal by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase, state.videoFormatKnown) {
        noSignal = false
        if (state.phase == Phase.STREAMING && !state.videoFormatKnown) {
            delay(10_000)
            noSignal = true
        }
    }

    when {
        state.phase == Phase.CONNECTING || (state.phase == Phase.STREAMING && !state.videoFormatKnown && !noSignal) -> {
            LoadingOverlay(text = state.stageText, reconnecting = state.reconnecting)
        }
        state.phase == Phase.ERROR && state.error != null -> {
            ErrorOverlay(message = state.error!!, onRetry = { viewModel.reconnect() })
        }
        state.phase == Phase.STREAMING && noSignal -> {
            NoSignalOverlay(onRefresh = { noSignal = false; viewModel.reconnect() })
        }
    }
}

/** NanoKVM-style diagnosis panel: metrics grid + time-series curves. */
@Composable
private fun BoxScope.StatsOverlay(state: ConsoleUiState, viewModel: ConsoleViewModel, stats: StatsUi) {
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
            .width(276.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatsChip("${stats.codec} · ${stats.transport}")
            Spacer(Modifier.weight(1f))
            StatsChip(if (state.videoFormatKnown) "${state.videoWidth}×${state.videoHeight}" else "—")
            IconButton(onClick = { viewModel.toggleStats() }) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭统计",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        StatsGrid(stats)
        Sparkline("实时帧率", stats.historyFps) { "%.0f fps".format(it) }
        Sparkline("码率", stats.historyKbps) { "%.0f kbps".format(it) }
        Sparkline("总测延迟", stats.historyMs) { "%.0f ms".format(it) }
    }
}

@Composable
private fun StatsGrid(stats: StatsUi) {
    @Composable
    fun tileRow(one: Pair<String, String>, two: Pair<String, String>? = null) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile(one.first, one.second)
            if (two != null) StatTile(two.first, two.second)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (stats.transport == "WebRTC") {
            // WebRTC: network + jitter-buffer metrics from RTCStatsReport (decode
            // happens inside the SDK, so there is no local decode-latency probe).
            tileRow("码率" to if (stats.bitrateKbps > 0) "%.0f kbps".format(stats.bitrateKbps) else "—",
                "实时帧率" to if (stats.fps > 0) "%.0f fps".format(stats.fps) else "—")
            tileRow("抖动缓冲" to if (stats.jitterMs > 0) "%.1f ms".format(stats.jitterMs) else "—",
                "ICE RTT" to if (stats.rttMs > 0) "%.1f ms".format(stats.rttMs) else "—")
            tileRow("丢包" to if (stats.packetsLost > 0) "${stats.packetsLost}" else "0",
                "总测延迟" to "${stats.totalMs} ms")
        } else {
            // Direct: local MediaCodec path metrics (no ICE/jitter-buffer of its
            // own — 抖动缓冲 is the decoder queue backlog estimate).
            tileRow("码率" to if (stats.bitrateKbps > 0) "%.0f kbps".format(stats.bitrateKbps) else "—",
                "实时帧率" to if (stats.fps > 0) "%.0f fps".format(stats.fps) else "—")
            tileRow("解码延迟" to if (stats.decodeMs > 0) "%.1f ms".format(stats.decodeMs) else "—",
                "抖动缓冲" to if (stats.fps > 0) "%.1f ms".format(stats.jitterMs) else "—")
            tileRow("总测延迟" to "${stats.totalMs} ms")
        }
    }
}

@Composable
private fun RowScope.StatTile(label: String, value: String) {
    // Threshold alert: 总测延迟 > 250 ms or 丢包 > 0 turns the value red.
    val alert = when (label) {
        "总测延迟" -> (value.removeSuffix(" ms").toFloatOrNull() ?: 0f) > 250f
        "丢包" -> value != "0"
        else -> false
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (alert) Color(0xFFF87171) else Color.White,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatsChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.09f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.92f),
    )
}

/**
 * uPlot-style sparkline — mirrors One-KVM's StatsSheet chart look: blue #3b82f6
 * 1.5px line over a translucent gradient fill, faint horizontal grid, muted axis-text
 * live value (the web's palette is identical for every chart, so all three curves
 * share it). A glow dot on the newest sample marks the live edge.
 */
private val OneKvmChartLine = Color(0xFF3B82F6)
private val OneKvmChartGrid = Color(0x1A94A3B8)   // rgba(148,163,184,0.10)
private val OneKvmChartText = Color(0xFF94A3B8)

/** Label + trailing muted value + a uPlot-style area line of recent samples. */
@Composable
private fun Sparkline(label: String, values: List<Float>, fmt: (Float) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (values.isNotEmpty()) fmt(values.last()) else "—",
                style = MaterialTheme.typography.labelMedium,
                color = OneKvmChartText,
                fontFamily = FontFamily.Monospace,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
        ) {
            val w = size.width
            val h = size.height
            // Faint horizontal grid across the plot (uPlot y-axis splits, x off),
            // under the series.
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(OneKvmChartGrid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            if (values.size >= 2) {
                val maxV = values.maxOrNull() ?: 0f
                val minV = values.minOrNull() ?: 0f
                val span = (maxV - minV).coerceAtLeast(1f)
                val stepX = w / (values.size - 1)
                val line = Path()
                val area = Path()
                var lastX = 0f
                var lastY = 0f
                values.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = h - 2f - ((v - minV) / span) * (h - 4f)
                    if (i == 0) {
                        line.moveTo(x, y)
                        area.moveTo(x, h)
                        area.lineTo(x, y)
                    } else {
                        line.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                    lastX = x
                    lastY = y
                }
                area.lineTo(w, h)
                area.close()
                drawPath(
                    area,
                    brush = Brush.verticalGradient(
                        colors = listOf(OneKvmChartLine.copy(alpha = 0.22f), OneKvmChartLine.copy(alpha = 0.02f)),
                        startY = 0f,
                        endY = h,
                    ),
                )
                drawPath(
                    line,
                    OneKvmChartLine,
                    style = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                // Live edge: solid dot + faint halo on the newest sample.
                drawCircle(OneKvmChartLine.copy(alpha = 0.25f), radius = 4.5f, center = Offset(lastX, lastY))
                drawCircle(OneKvmChartLine, radius = 2.2f, center = Offset(lastX, lastY))
            }
        }
    }
}

@Composable
private fun LoadingOverlay(text: String, reconnecting: Int?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            if (reconnecting != null) {
                Text(
                    text = "第 $reconnecting 次重连…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun NoSignalOverlay(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = Color.White.copy(alpha = 0.9f),
            )
            Text(
                text = "无信号",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = Color.White,
            )
            Text(
                text = "被控机可能已关闭显示器输出",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRefresh) {
                Text("刷新", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "连接失败",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 260.dp)
                    .height(40.dp),
            ) {
                Text("重连", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Settings bottom sheet: stream codec, transport, mouse mode, reconnect. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(state: ConsoleUiState, viewModel: ConsoleViewModel) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = { viewModel.toggleSettingsSheet() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Text("视频流", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Two independent axes compose the firmware mode string (h264|h265)-(direct|webrtc).
            val mode = state.streamMode
            val codec = if (mode.startsWith("h265")) "h265" else "h264"
            val transport = if (mode.endsWith("webrtc")) "webrtc" else "direct"
            SegmentedButtons(
                options = listOf("H.264" to "h264", "H.265" to "h265"),
                selected = codec,
                onSelect = { viewModel.setStreamMode("$it-$transport") },
            )
            SegmentedButtons(
                options = listOf("直连" to "direct", "WebRTC" to "webrtc"),
                selected = transport,
                onSelect = { viewModel.setStreamMode("$codec-$it") },
            )
            Text("鼠标模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedButtons(
                options = listOf("绝对" to HidMouseMode.ABSOLUTE, "相对" to HidMouseMode.RELATIVE),
                selected = state.mouseMode,
                onSelect = viewModel::setMouseMode,
            )
            Button(
                onClick = { viewModel.toggleSettingsSheet(); viewModel.reconnect() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("应用并重连", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Touch mouse over the fitted video rect — mirrors the web mouse layer.
 *
 * Absolute mode: normalized points (rotation/zoom compensated) move the remote
 * cursor; tap = left click, double-tap = right click, long-press+drag = left drag,
 * two-finger swipe = wheel.
 *
 * Relative mode (web `relative.tsx`): the remote cursor stays wherever it is;
 * drag deltas are sent as ±127-clamped 4-byte reports (small moves ×2), taps are
 * plain clicks, wheel behaves the same.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.touchMouseGestures(
    viewModel: ConsoleViewModel,
    boxPx: IntSize,
    mode: String,
    rotation: Int,
    scale: Float,
    wheelDir: Int,
) {
    val relative = mode == HidMouseMode.RELATIVE
    var lastTapAt = 0L
    while (true) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val primary = down.id
                val downAt = down.uptimeMillis
                var downPos = down.position
                var curPos = down.position
                var prevPos = down.position
                @Suppress("BoringName") var longPressed = false
                var moved = false
                var leftHeld = false
                var wheelAccum = 0f
                var secondLastY: Float? = null

                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.isEmpty()) break
                    var primaryReleased = false
                    for (change in event.changes) {
                        if (change.id == primary) {
                            if (!change.pressed) {
                                primaryReleased = true
                                continue
                            }
                            curPos = change.position
                            val dx = curPos.x - downPos.x
                            val dy = curPos.y - downPos.y
                            val slop = viewConfiguration.touchSlop
                            if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                            if (moved) {
                                if (relative) {
                                    val (rdx, rdy) = mapDeltaToRemote(curPos.x - prevPos.x, curPos.y - prevPos.y, rotation)
                                    viewModel.mouseRelativeMove(rdx, rdy)
                                } else if (boxPx.width > 0) {
                                    val (nx, ny) = mapToRemote((curPos.x / boxPx.width), (curPos.y / boxPx.height), rotation, scale)
                                    viewModel.mouseMove(nx, ny)
                                }
                                prevPos = curPos
                            }
                            if (!longPressed && !moved && change.uptimeMillis - downAt > 600) {
                                longPressed = true
                                if (!relative && boxPx.width > 0) {
                                    val (nx, ny) = mapToRemote((curPos.x / boxPx.width), (curPos.y / boxPx.height), rotation, scale)
                                    viewModel.mouseMove(nx, ny)
                                }
                                viewModel.mouseButton(MouseButton.LEFT, true)
                                leftHeld = true
                            }
                        } else if (change.pressed) {
                            // second finger → two-finger vertical swipe = wheel
                            if (!longPressed && !moved) {
                                val y = change.position.y
                                val dy = secondLastY?.let { y - it } ?: 0f
                                secondLastY = y
                                wheelAccum += dy
                                if (abs(wheelAccum) > 48f) {
                                    viewModel.mouseWheel(if (wheelAccum > 0) -1 else 1, wheelDir)
                                    wheelAccum = 0f
                                }
                            }
                        } else {
                            secondLastY = null
                        }
                    }
                    if (primaryReleased) break
                }
                // Release phase.
                when {
                    leftHeld -> {
                        if (!relative && boxPx.width > 0) {
                            val (nx, ny) = mapToRemote((curPos.x / boxPx.width), (curPos.y / boxPx.height), rotation, scale)
                            viewModel.mouseMove(nx, ny)
                        }
                        viewModel.mouseButton(MouseButton.LEFT, false)
                    }
                    !moved -> {
                        if (!relative && boxPx.width > 0) {
                            val (nx, ny) = mapToRemote((curPos.x / boxPx.width), (curPos.y / boxPx.height), rotation, scale)
                            viewModel.mouseMove(nx, ny)
                        }
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt < 320L && now - downAt < 400L) {
                            lastTapAt = 0L
                            viewModel.mouseClick(MouseButton.RIGHT)
                        } else {
                            lastTapAt = now
                            viewModel.mouseClick(MouseButton.LEFT)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // gesture coroutine cancelled; continue to next gesture
        }
    }
}