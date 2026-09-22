package com.nanokvm.app.ui.console

import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.nanokvm.app.ui.theme.GlassShapes
import com.nanokvm.app.ui.theme.GlassPanel
import com.nanokvm.app.ui.theme.GlassTokens
import com.nanokvm.app.ui.theme.RefractionHighlight
import com.nanokvm.app.ui.theme.glassFrost
import com.nanokvm.app.ui.theme.StatusTone
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.math.abs
import kotlin.math.roundToInt

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

    // 电源状态灯(web 电源按钮同源):建流后每 5s 轮询 /api/vm/gpio 的 pwr,悬浮徽标展示
    LaunchedEffect(state.phase == Phase.STREAMING) {
        if (state.phase == Phase.STREAMING) {
            viewModel.refreshPowerState()
            while (true) {
                kotlinx.coroutines.delay(5000)
                viewModel.refreshPowerState()
            }
        }
    }

    val hazeState = remember { HazeState() }
    var statsDock by rememberSaveable { mutableStateOf(false) }
    // S03 结构(design-v2):顶部 chrome / 中部视频(haze 采样源)/ 底部键盘,三段分明
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        TopBar(state, isDark, onToggleTheme, onBack, viewModel)
        ActionBar(state, viewModel, isDark)
        Box(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.fillMaxSize().haze(hazeState)) {
                VideoStage(state, viewModel)
            }
            StageOverlays(state, viewModel)
            PowerBadge(
                powerOn = state.powerOn,
                isDark = isDark,
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 10.dp, start = 12.dp),
            )
            if (state.phase == Phase.CONNECTING) FlowLine(Modifier.align(Alignment.TopCenter).fillMaxWidth())
            if (state.statsVisible && !statsDock) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 12.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    StatsStrip(state, stats) { statsDock = true }
                }
            }
            if (state.statsVisible && statsDock) {
                StatsDock(state, stats, hazeState) { statsDock = false }
            }
        }
        if (state.vkbVisible) {
            VirtualKeyboard(
                isDark = isDark,
                activeModifiers = state.activeModifiers,
                hazeState = hazeState,
                modifier = Modifier.navigationBarsPadding(),
                onKeyDown = viewModel::vkbKeyDown,
                onKeyUp = viewModel::vkbKeyUp,
                onModifierToggle = viewModel::vkbModifierToggle,
                onAction = viewModel::vkbAction,
            )
        }
        if (state.touchpadVisible) {
            TouchpadPanel(
                isDark = isDark,
                hazeState = hazeState,
                modifier = Modifier.navigationBarsPadding(),
                onMove = viewModel::touchpadMove,
                onWheel = viewModel::touchpadWheel,
                onLeftDown = { viewModel.touchpadButton(MouseButton.LEFT, true) },
                onLeftUp = { viewModel.touchpadButton(MouseButton.LEFT, false) },
                onRightDown = { viewModel.touchpadButton(MouseButton.RIGHT, true) },
                onRightUp = { viewModel.touchpadButton(MouseButton.RIGHT, false) },
            )
        }
        if (state.settingsSheetOpen) {
            Box(Modifier.fillMaxSize()) {
                SettingsSheet(state, viewModel, isDark, hazeState)
            }
        }
        if (state.toolsSheetOpen) {
            Box(Modifier.fillMaxSize()) {
                ConsoleToolsSheet(state, viewModel, isDark, hazeState, onOpenTerminal, onOpenAssistant)
            }
        }
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
            if (state.phase == Phase.ERROR && state.error != null) {
                // v2:会话异常时三 chip 合并为一条告警条,重连随手可及
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFDE3B32).copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFDE3B32).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFF87171),
                    )
                    Text(
                        text = "会话错误 · ${state.error}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF87171),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(
                        onClick = { viewModel.reconnect() },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text("重连", style = MaterialTheme.typography.labelMedium, color = Color(0xFFF87171))
                    }
                }
            } else {
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

/** 电源状态徽标:圆角矩形悬浮于工具栏下方,LED 点 绿=开机 红=关机 灰=未知(GET /api/vm/gpio 的 pwr)。 */
@Composable
private fun PowerBadge(
    powerOn: Boolean?,
    isDark: Boolean,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val led = when (powerOn) {
        true -> Color(0xFF16A34A)   // web text-green-600
        false -> Color(0xFFDC2626)  // 关机红
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .glassFrost(hazeState, isDark)
            .border(1.dp, GlassTokens.hairline(isDark), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(led))
        Text(
            when (powerOn) {
                true -> "已开机"
                false -> "已关机"
                null -> "电源状态…"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionBar(state: ConsoleUiState, viewModel: ConsoleViewModel, isDark: Boolean) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
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
                .horizontalScroll(scroll)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 磨砂胶囊(design S03):圆角矩形,激活态青色高亮;互斥面板不堆叠
            ActionCapsule(icon = Icons.Outlined.Settings, "设置", isDark = isDark, active = state.settingsSheetOpen) {
                viewModel.activatePanel(if (state.settingsSheetOpen) null else ConsolePanel.SETTINGS)
            }
            ActionCapsule(icon = Icons.Outlined.Mouse, "触控板", isDark = isDark, active = state.touchpadVisible) {
                viewModel.activatePanel(if (state.touchpadVisible) null else ConsolePanel.TOUCHPAD)
            }
            ActionCapsule(icon = Icons.Outlined.Keyboard, "键盘", isDark = isDark, active = state.vkbVisible) {
                viewModel.activatePanel(if (state.vkbVisible) null else ConsolePanel.KEYBOARD)
            }
            ActionCapsule(icon = Icons.Outlined.Handyman, "工具箱", isDark = isDark, active = state.toolsSheetOpen) {
                viewModel.activatePanel(if (state.toolsSheetOpen) null else ConsolePanel.TOOLS)
            }
            ActionCapsule(icon = Icons.Outlined.BarChart, "性能", isDark = isDark, active = state.statsVisible) {
                viewModel.activatePanel(if (state.statsVisible) null else ConsolePanel.STATS)
            }
            ActionCapsule(icon = Icons.Outlined.Refresh, "重连", isDark = isDark) { viewModel.reconnect() }
        }
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/** 圆角矩形磨砂胶囊:图标 + 文字横排;激活态青色高亮(dark)/主色高亮(light)。 */
@Composable
private fun ActionCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isDark: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = if (isDark) Color(0xFF7BE7FF) else MaterialTheme.colorScheme.primary
    val bg = if (active) {
        accent.copy(alpha = if (isDark) 0.14f else 0.12f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.06f else 0.05f)
    }
    val border = if (active) {
        accent.copy(alpha = if (isDark) 0.45f else 0.35f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.10f else 0.08f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(17.dp),
            tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 收起态性能细条(玻璃胶囊,悬在工具条正下方)。 */
@Composable
private fun StatsStrip(state: ConsoleUiState, stats: StatsUi, onExpand: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (state.phase == Phase.STREAMING) Color(0xFF45E07A) else Color(0xFFF2B33D)),
        )
        Text(
            text = buildString {
                append(stats.codec)
                append(" · ")
                append(stats.transport)
                if (stats.fps > 0) {
                    append(" · ")
                    append("%.0f fps".format(stats.fps))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFE8EAED),
        )
        Text(
            "▾",
            color = Color(0xFFB9C2CB),
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 2.dp),
        )
    }
}

/** 展开态性能抽屉(底部磨砂,不遮挡画面主体)。 */
@Composable
private fun BoxScope.StatsDock(
    state: ConsoleUiState,
    stats: StatsUi,
    hazeState: HazeState,
    onCollapse: () -> Unit,
) {
    GlassPanel(
        isDark = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(8.dp)
            .fillMaxWidth(),
        tint = Color(0xFF050607),
        tintAlphaOverride = 0.55f,
        blurOverride = 18.dp,
        hazeState = hazeState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        RefractionHighlight(Modifier.align(Alignment.CenterHorizontally), isDark = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("性能", style = MaterialTheme.typography.titleSmall, color = Color.White)
            StatsChip("${stats.codec} · ${stats.transport}")
            if (state.videoFormatKnown) StatsChip("${state.videoWidth}×${state.videoHeight}")
            Spacer(Modifier.weight(1f))
            if (stats.fps > 0) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF45E07A).copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        "%.0f fps".format(stats.fps),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF45E07A),
                    )
                }
            }
            IconButton(onClick = onCollapse, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "收起性能",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        StatsGrid(stats, if (state.videoFormatKnown) "${state.videoWidth}×${state.videoHeight}" else "—")
        Sparkline("实时帧率", stats.historyFps) { "%.0f fps".format(it) }
        Sparkline("码率", stats.historyKbps) { "%.0f kbps".format(it) }
        Sparkline("总测延迟", stats.historyMs) { "%.0f ms".format(it) }
        }
    }
}

@Composable
private fun StatsGrid(stats: StatsUi, resolution: String) {
    @Composable
    fun tileRow3(one: Pair<String, String>, two: Pair<String, String>, three: Pair<String, String>) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile(one.first, one.second)
            StatTile(two.first, two.second)
            StatTile(three.first, three.second)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (stats.transport == "WebRTC") {
            // WebRTC: network + jitter-buffer metrics from RTCStatsReport (decode
            // happens inside the SDK, so there is no local decode-latency probe).
            tileRow3(
                "码率" to if (stats.bitrateKbps > 0) "%.0f kbps".format(stats.bitrateKbps) else "—",
                "实时帧率" to if (stats.fps > 0) "%.0f fps".format(stats.fps) else "—",
                "抖动缓冲" to if (stats.jitterMs > 0) "%.1f ms".format(stats.jitterMs) else "—",
            )
            tileRow3(
                "ICE RTT" to if (stats.rttMs > 0) "%.1f ms".format(stats.rttMs) else "—",
                "丢包" to if (stats.packetsLost > 0) "${stats.packetsLost}" else "0",
                "总测延迟" to "${stats.totalMs} ms",
            )
        } else {
            // Direct: local MediaCodec path metrics (no ICE/jitter-buffer of its
            // own — 抖动缓冲 is the decoder queue backlog estimate).
            tileRow3(
                "码率" to if (stats.bitrateKbps > 0) "%.0f kbps".format(stats.bitrateKbps) else "—",
                "实时帧率" to if (stats.fps > 0) "%.0f fps".format(stats.fps) else "—",
                "解码延迟" to if (stats.decodeMs > 0) "%.1f ms".format(stats.decodeMs) else "—",
            )
            tileRow3(
                "抖动缓冲" to if (stats.fps > 0) "%.1f ms".format(stats.jitterMs) else "—",
                "总测延迟" to "${stats.totalMs} ms",
                "分辨率" to resolution,
            )
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

/** 连接中:顶缘流光进度线(青→绿渐变横扫),体感“活着”。 */
@Composable
private fun FlowLine(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "flow")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "flowx",
    )
    Box(
        modifier = modifier
            .height(2.dp)
            .drawBehind {
                val band = 360f
                val start = Offset((size.width + band) * x - band, 0f)
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color(0xFF7BE7FF), Color(0xFF45E07A), Color.Transparent),
                        start = start,
                        end = start.copy(x = start.x + band),
                    ),
                )
            },
    )
}

/** 模拟触控板(design):滑动=相对移动光标,双指=滚轮;下方物理左/右键(支持按住拖拽)。 */
@Composable
private fun TouchpadPanel(
    isDark: Boolean,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    onMove: (Float, Float) -> Unit,
    onWheel: (Int) -> Unit,
    onLeftDown: () -> Unit,
    onLeftUp: () -> Unit,
    onRightDown: () -> Unit,
    onRightUp: () -> Unit,
) {
    GlassPanel(
        isDark = isDark,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        modifier = modifier.fillMaxWidth(),
        tint = if (isDark) Color(0xFF0E0F11) else Color(0xFFF0F1F3),
        tintAlphaOverride = if (isDark) 0.55f else 0.75f,
        hazeState = hazeState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 触控面:单指滑动=移动光标,双指滑动=滚轮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.05f else 0.06f))
                    .border(1.dp, GlassTokens.hairline(isDark), RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var wheelAcc = 0f
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        // 双指竖滑 = 滚轮
                                        val p = pressed[1]
                                        val dy = p.position.y - p.previousPosition.y
                                        wheelAcc += dy
                                        if (abs(wheelAcc) >= 48f) {
                                            val steps = (wheelAcc / 48f).toInt()
                                            onWheel(-steps)
                                            wheelAcc -= steps * 48f
                                        }
                                        pressed.forEach { it.consume() }
                                    } else {
                                        // 单指滑动 = 相对移动光标
                                        val c = pressed[0]
                                        val dx = c.position.x - c.previousPosition.x
                                        val dy = c.position.y - c.previousPosition.y
                                        if (dx != 0f || dy != 0f) {
                                            onMove(dx, dy)
                                            c.consume()
                                        }
                                    }
                                }
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "触控区 · 滑动移动光标,双指滚动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
            // 物理左/右键:按住不放 + 触控面滑动 = 拖拽
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton(
                    label = "左键",
                    isDark = isDark,
                    onPress = onLeftDown,
                    onRelease = onLeftUp,
                    modifier = Modifier.weight(1.6f),
                )
                PadButton(
                    label = "右键",
                    isDark = isDark,
                    onPress = onRightDown,
                    onRelease = onRightUp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 触控板物理按键:按下=键按下,松开=键抬起(配合触控面滑动即拖拽)。 */
@Composable
private fun PadButton(
    label: String,
    isDark: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            )
            .border(1.dp, GlassTokens.hairline(isDark), RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPress()
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.none { it.pressed }) break
                        }
                    } finally {
                        pressed = false
                        onRelease()
                    }
                }
            },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** 设置面板(树内磨砂,非弹窗):调模糊度/透明度滑杆时面板自身即实时预览。 */
@Composable
private fun BoxScope.SettingsSheet(
    state: ConsoleUiState,
    viewModel: ConsoleViewModel,
    isDark: Boolean,
    hazeState: HazeState,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.40f))
            .clickable { viewModel.toggleSettingsSheet() },
    )
    GlassPanel(
        isDark = isDark,
        shape = GlassShapes.sheet,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        tint = if (isDark) Color(0xFF0E0F11) else Color.White,
        hazeState = hazeState,
    ) {
        RefractionHighlight(Modifier.align(Alignment.TopCenter), isDark = isDark)
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DragHandle(isDark)
            Text("视频流", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Two independent axes compose the firmware mode string (h264|h265)-(direct|webrtc).
            val mode = state.streamMode
            val codec = if (mode.startsWith("h265")) "h265" else "h264"
            val transport = if (mode.endsWith("webrtc")) "webrtc" else "direct"
            Text("编码", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            SegmentedButtons(
                options = listOf("H.264" to "h264", "H.265" to "h265"),
                selected = codec,
                onSelect = { viewModel.setStreamMode("$it-$transport") },
            )
            Text("传输", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            SegmentedButtons(
                options = listOf("直连" to "direct", "WebRTC" to "webrtc"),
                selected = transport,
                onSelect = { viewModel.setStreamMode("$codec-$it") },
            )
            Text("磨砂玻璃", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("模糊度", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
                Slider(
                    value = state.blurRadiusDp.toFloat(),
                    onValueChange = { viewModel.setGlassBlur(it.roundToInt()) },
                    onValueChangeFinished = viewModel::commitGlassStyle,
                    valueRange = 4f..40f,
                    modifier = Modifier.weight(1f).height(28.dp),
                )
                Text(
                    "${state.blurRadiusDp}dp",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(40.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("透明度", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
                Slider(
                    value = state.glassAlphaPct.toFloat(),
                    onValueChange = { viewModel.setGlassAlpha(it.roundToInt()) },
                    onValueChangeFinished = viewModel::commitGlassStyle,
                    valueRange = 5f..80f,
                    modifier = Modifier.weight(1f).height(28.dp),
                )
                Text(
                    "${state.glassAlphaPct}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(40.dp),
                )
            }
            Text("鼠标模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedButtons(
                options = listOf("绝对 · 点哪指哪" to HidMouseMode.ABSOLUTE, "相对 · 拖动控制" to HidMouseMode.RELATIVE),
                selected = state.mouseMode,
                onSelect = viewModel::setMouseMode,
            )
            Text("切换编码 / 传输将重建视频流,约 2–4 秒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** 玻璃抽屉顶部拖拽指示条(design-v2)。 */
@Composable
private fun DragHandle(isDark: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 4.dp)
            .background(
                if (isDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.18f),
                RoundedCornerShape(2.dp),
            ),
    )
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 协程被取消(如弹层 scrim 掐断指针流):必须上抛,否则 while(true) 会在
            // 已取消的协程上立即重入、非挂起自旋,主线程 100% 触发 ANR(实测)。
            throw e
        } catch (_: Throwable) {
            // gesture error; continue to next gesture
        }
    }
}