package com.nanokvm.app.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanokvm.app.data.api.DeviceInfo
import com.nanokvm.app.data.api.HidMouseMode
import com.nanokvm.app.data.api.MountedImage
import com.nanokvm.app.data.api.NanoKvmApi
import com.nanokvm.app.data.hid.HidKeymap
import com.nanokvm.app.ui.components.SegmentedButtons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 工具箱 — web 端桌面菜单(电源/屏幕/键盘/鼠标/镜像/脚本/WOL/信息)的移动端移植。
 * 所有 REST 走 session 的共享 JWT;破坏性操作(电源键/重启/挂载/删除)均需确认。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConsoleToolsSheet(
    state: ConsoleUiState,
    viewModel: ConsoleViewModel,
    onOpenTerminal: (com.nanokvm.app.ui.terminal.TerminalRequest) -> Unit = {},
    onOpenAssistant: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val rest = viewModel.toolsRest
    var confirmAction by remember { mutableStateOf<(suspend () -> String?)?>(null) }
    var confirmTitle by remember { mutableStateOf("") }
    var busyText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var showDeviceSettings by remember { mutableStateOf(false) }
    var showDevExtras by remember { mutableStateOf(false) }
    var showVirtualDev by remember { mutableStateOf(false) }
    var showTailscale by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var showMonitor by remember { mutableStateOf(false) }
    var managePage by remember { mutableStateOf(false) }
    // 行内参数选择(跨分页切换保持)
    var bitrateSel by remember { mutableIntStateOf(0) }
    var fpsSel by remember { mutableIntStateOf(0) }
    var gopSel by remember { mutableIntStateOf(50) }
    var longSeconds by remember { mutableIntStateOf(8) }
    var showPaste by remember { mutableStateOf(false) }
    var showEdid by remember { mutableStateOf(false) }
    var showImages by remember { mutableStateOf(false) }
    var showScripts by remember { mutableStateOf(false) }
    var showWol by remember { mutableStateOf(false) }
    var showSerial by remember { mutableStateOf(false) }
    var showAssistant by remember { mutableStateOf(false) }

    suspend fun run(action: suspend () -> String?) {
        if (busyText != null) return
        busyText = "执行中…"
        try {
            val err = action()
            android.util.Log.i("NanokvmTools", "action ok: $err")
            if (err != null) errorText = err
        } catch (e: Exception) {
            android.util.Log.e("NanokvmTools", "action failed", e)
            errorText = e.message ?: "操作失败"
        } finally {
            busyText = null
        }
    }

    fun confirm(title: String, action: suspend () -> String?) {
        confirmTitle = title
        confirmAction = { action() }
    }

    fun busyOrError(): String? = busyText ?: errorText

    ModalBottomSheet(onDismissRequest = { viewModel.toggleToolsSheet() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("工具箱", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.toggleToolsSheet() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }
            busyOrError()?.let { msg ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (busyText != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    if (busyText != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SegmentedButtons(
                    options = listOf("操作" to false, "设备管理" to true),
                    selected = managePage,
                    onSelect = { managePage = it },
                )
            }
            if (!managePage) {
                // ============ 操作:会话内操控 ============
                SectionHeader("常用")
                ToolRow(
                    icon = Icons.Outlined.Terminal,
                    title = "NanoKVM 终端",
                    subtitle = "进入设备命令行",
                    onClick = {
                        scope.launch { onOpenTerminal(com.nanokvm.app.ui.terminal.TerminalRequest(com.nanokvm.app.ui.terminal.TerminalKind.SHELL)) }
                    },
                )
                ToolRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "智能助手",
                    subtitle = "AI 看着屏幕执行键鼠任务",
                    onClick = { scope.launch { showAssistant = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.Usb,
                    title = "串口终端",
                    subtitle = "picocom 串口调试会话",
                    onClick = { scope.launch { showSerial = true } },
                )
                SectionHeader("输入")
                ToolRow(
                    icon = Icons.Outlined.ContentPaste,
                    title = "粘贴文本",
                    subtitle = "逐字符输入到被控机(≤1024)",
                    onClick = { scope.launch { showPaste = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.Keyboard,
                    title = "常用快捷键",
                    subtitle = "组合键立即发送",
                )
                FlowRow(
                    modifier = Modifier.padding(start = 44.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "Ctrl+Alt+Del" to ((HidKeymap.MOD_LCTRL or HidKeymap.MOD_LALT) to listOf(HidKeymap.HID_DELETE)),
                        "Alt+Tab" to (HidKeymap.MOD_LALT to listOf(HidKeymap.HID_TAB)),
                        "Win+Tab" to (HidKeymap.MOD_LMETA to listOf(HidKeymap.HID_TAB)),
                        "Win+D" to (HidKeymap.MOD_LMETA to listOf(0x07)), // HID_D
                        "Win+E" to (HidKeymap.MOD_LMETA to listOf(0x08)), // HID_E
                        "Ctrl+Shift+Esc" to ((HidKeymap.MOD_LCTRL or HidKeymap.MOD_LSHIFT) to listOf(HidKeymap.HID_ESCAPE)),
                    ).forEach { (label, combo) ->
                        val (mods, usages) = combo
                        ActionChip(label) { viewModel.sendCombo(mods, usages) }
                    }
                }
                SectionHeader("画面与鼠标")
                val transformLocked = state.streamMode.endsWith("webrtc")
                ToolRow(
                    icon = Icons.Outlined.ScreenRotation,
                    title = "旋转",
                    subtitle = if (transformLocked) "仅直连可用(WebRTC 渲染层不支持)" else "仅改变本端显示",
                )
                Row(Modifier.padding(start = 44.dp, end = 4.dp)) {
                    SegmentedButtons(
                        options = listOf("0°" to 0, "90°" to 90, "180°" to 180, "270°" to 270),
                        selected = state.videoRotation,
                        onSelect = { if (!transformLocked) viewModel.setVideoRotation(it) },
                    )
                }
                ToolRow(
                    icon = Icons.Outlined.ZoomIn,
                    title = "缩放",
                    subtitle = if (transformLocked) "仅直连可用(WebRTC 渲染层不支持)" else "本端画面缩放",
                )
                Row(Modifier.padding(start = 44.dp, end = 4.dp)) {
                    SegmentedButtons(
                        options = listOf("50%" to 0.5f, "75%" to 0.75f, "100%" to 1f, "150%" to 1.5f, "200%" to 2f),
                        selected = state.videoScale,
                        onSelect = { if (!transformLocked) viewModel.setVideoScale(it) },
                    )
                }
                ToolRow(
                    icon = Icons.Outlined.BarChart,
                    title = "码率",
                    subtitle = "修改后立即重建视频流生效",
                )
                Row(Modifier.padding(start = 44.dp, end = 4.dp)) {
                    SegmentedButtons(
                        options = listOf("自动" to 0, "无损" to 10000, "高" to 5000, "中" to 3000, "低" to 1000),
                        selected = bitrateSel,
                        onSelect = { v ->
                            if (v == bitrateSel) return@SegmentedButtons
                            val prev = bitrateSel
                            bitrateSel = v
                            scope.launch {
                                run {
                                    var done = false
                                    var msg: String? = null
                                    viewModel.applyStreamParam(
                                        rateControl = if (v == 0) "vbr" else "cbr",
                                        bitrateKbps = if (v == 0) 8000 else v,
                                    ) { msg = it; done = true }
                                    while (!done) kotlinx.coroutines.delay(50)
                                    if (msg != null) bitrateSel = prev
                                    msg
                                }
                            }
                        },
                    )
                }
                ToolRow(
                    icon = Icons.Outlined.Speed,
                    title = "帧率",
                    subtitle = if (fpsSel == 0) "自动(源帧率)" else "$fpsSel fps",
                )
                Row(Modifier.padding(start = 44.dp, end = 4.dp)) {
                    SegmentedButtons(
                        options = listOf("自动" to 0, "30" to 30, "60" to 60),
                        selected = fpsSel,
                        onSelect = { v ->
                            if (v == fpsSel) return@SegmentedButtons
                            val prev = fpsSel
                            fpsSel = v
                            scope.launch {
                                run {
                                    var done = false
                                    var msg: String? = null
                                    viewModel.applyStreamParam(fps = v) { msg = it; done = true }
                                    while (!done) kotlinx.coroutines.delay(50)
                                    if (msg != null) fpsSel = prev
                                    msg
                                }
                            }
                        },
                    )
                }
                ToolRow(
                    icon = Icons.Outlined.Timeline,
                    title = "关键帧间隔 GOP",
                    subtitle = "每 $gopSel 帧一个关键帧",
                )
                Row(Modifier.padding(start = 44.dp, end = 4.dp)) {
                    SegmentedButtons(
                        options = listOf("30" to 30, "50" to 50, "100" to 100, "200" to 200),
                        selected = gopSel,
                        onSelect = { v ->
                            if (v == gopSel) return@SegmentedButtons
                            val prev = gopSel
                            gopSel = v
                            scope.launch {
                                run {
                                    var done = false
                                    var msg: String? = null
                                    viewModel.applyStreamParam(gop = v) { msg = it; done = true }
                                    while (!done) kotlinx.coroutines.delay(50)
                                    if (msg != null) gopSel = prev
                                    msg
                                }
                            }
                        },
                    )
                }
                ToolRow(
                    icon = Icons.Outlined.Mouse,
                    title = "鼠标模式",
                    subtitle = if (state.mouseMode == HidMouseMode.ABSOLUTE) "绝对模式(点哪指哪)" else "相对模式(拖动控制)",
                )
                Row(Modifier.padding(start = 44.dp, end = 4.dp)) {
                    SegmentedButtons(
                        options = listOf("绝对" to HidMouseMode.ABSOLUTE, "相对" to HidMouseMode.RELATIVE),
                        selected = state.mouseMode,
                        onSelect = viewModel::setMouseMode,
                    )
                }
                ToolRow(
                    icon = Icons.Outlined.SwapVert,
                    title = "滚轮方向",
                    subtitle = if (state.wheelDir > 0) "正常(下滚=向下)" else "反向",
                )
                Row(Modifier.padding(start = 44.dp, end = 4.dp)) {
                    SegmentedButtons(
                        options = listOf("正常" to 1, "反向" to -1),
                        selected = state.wheelDir,
                        onSelect = viewModel::setWheelDir,
                    )
                }
                ToolRow(
                    icon = Icons.Outlined.Mouse,
                    title = "鼠标抖动",
                    subtitle = "防止远程主机休眠",
                    trailing = {
                        JigglerPicker(rest) { err -> if (err != null) errorText = err }
                    },
                )
                ToolRow(
                    icon = Icons.Outlined.Refresh,
                    title = "重置 HID",
                    subtitle = "键鼠无响应时复位 USB HID",
                    onClick = {
                        confirm("重置 HID?约 3 秒内键鼠不可用") {
                            var done = false
                            var msg: String? = null
                            viewModel.resetHidDevice { msg = it; done = true }
                            var waited = 0
                            while (!done && waited < 60) { kotlinx.coroutines.delay(200); waited++ }
                            msg
                        }
                    },
                )
                SectionHeader("电源")
                ToolRow(
                    icon = Icons.Outlined.PowerSettingsNew,
                    title = "电源键短按",
                    subtitle = "模拟一次电源键短按(800ms)",
                    danger = true,
                    onClick = {
                        confirm("执行电源短按") { rest.gpioPower("power", 800).let { null } }
                    },
                )
                ToolRow(
                    icon = Icons.Outlined.PowerSettingsNew,
                    title = "电源键长按",
                    subtitle = "长按 ${longSeconds}s(强制关机)",
                    danger = true,
                    trailing = {
                        Column(Modifier.width(120.dp)) {
                            Slider(
                                value = longSeconds.toFloat(), onValueChange = { longSeconds = it.roundToInt() },
                                valueRange = 1f..30f,
                            )
                            Text("${longSeconds}s", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    onClick = {
                        confirm("电源键长按 ${longSeconds}s?") { rest.gpioPower("power", longSeconds * 1000).let { null } }
                    },
                )
                ToolRow(
                    icon = Icons.Outlined.Refresh,
                    title = "重启(被控机复位)",
                    subtitle = "reset 键按下 800ms 复位主机",
                    danger = true,
                    onClick = {
                        confirm("重启被控机") { rest.gpioPower("reset", 800).let { null } }
                    },
                )
            } else {
                // ============ 设备管理:设备级配置 ============
                SectionHeader("信息与网络")
                ToolRow(
                    icon = Icons.Outlined.Info,
                    title = "设备信息",
                    subtitle = "固件版本 / IP / 架构",
                    onClick = { scope.launch { showInfo = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.BarChart,
                    title = "设备监控",
                    subtitle = "CPU / 内存 / 温度 实时曲线",
                    onClick = { scope.launch { showMonitor = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.Settings,
                    title = "设备设置",
                    subtitle = "主机名 / SSH / mDNS / HDMI / 网络",
                    onClick = { scope.launch { showDeviceSettings = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Tailscale",
                    subtitle = "异地组网访问设备",
                    onClick = { scope.launch { showTailscale = true } },
                )
                SectionHeader("外设与存储")
                ToolRow(
                    icon = Icons.Outlined.Tune,
                    title = "显示 · 时间 · 账户",
                    subtitle = "OLED / LCD / LED / 同步 / 改密",
                    onClick = { scope.launch { showDevExtras = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.Usb,
                    title = "虚拟设备",
                    subtitle = "虚拟U盘 / 网卡 / 麦克风",
                    onClick = { scope.launch { showVirtualDev = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.Monitor,
                    title = "EDID(显示器参数)",
                    subtitle = "被控端识别分辨率 / 自定义上传",
                    onClick = { scope.launch { showEdid = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.DriveFileMove,
                    title = "镜像",
                    subtitle = "挂载 / 上传 / 校验 / 删除",
                    onClick = { scope.launch { showImages = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.Code,
                    title = "脚本",
                    subtitle = "运行 / 后台运行",
                    onClick = { scope.launch { showScripts = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.Wifi,
                    title = "Wake-on-LAN",
                    subtitle = "保存 MAC,从网络唤醒被控机",
                    onClick = { scope.launch { showWol = true } },
                )
                SectionHeader("系统")
                ToolRow(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "系统更新",
                    subtitle = "检查并在线升级固件",
                    onClick = { scope.launch { showUpdate = true } },
                )
                ToolRow(
                    icon = Icons.Outlined.PowerSettingsNew,
                    title = "重启 NanoKVM 系统",
                    subtitle = "设备本体重启,连接将断开",
                    danger = true,
                    onClick = {
                        confirm("重启 NanoKVM 设备?连接将中断") { rest.rebootSystem().let { null } }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        }
    }

    // ---- nested dialogs ----
    if (confirmAction != null) {
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text("确认操作", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)) },
            text = { Text(confirmTitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)) },
            confirmButton = {
                TextButton(onClick = {
                    val a = confirmAction
                    confirmAction = null
                    scope.launch { run { a?.invoke() } }
                }) { Text("执行") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("取消") }
            },
        )
    }
    if (showInfo) InfoDialog(rest, onClose = { showInfo = false })
    if (showDeviceSettings) DeviceSettingsDialog(rest, onClose = { showDeviceSettings = false })
    if (showDevExtras) DevExtrasDialog(rest, onClose = { showDevExtras = false })
    if (showVirtualDev) VirtualDevDialog(rest, onClose = { showVirtualDev = false })
    if (showTailscale) TailscaleDialog(rest, onClose = { showTailscale = false })
    if (showUpdate) UpdateDialog(rest, onClose = { showUpdate = false })
    if (showMonitor) DeviceMonitorDialog(rest, onClose = { showMonitor = false })
    if (showPaste) PasteDialog(viewModel, onClose = { showPaste = false })
    if (showEdid) EdidDialog(rest, onClose = { showEdid = false }, onError = { errorText = it })
    if (showImages) ImagesDialog(rest, viewModel, onClose = { showImages = false }, onError = { errorText = it })
    if (showScripts) ScriptsDialog(rest, onClose = { showScripts = false }, onError = { errorText = it })
    if (showWol) WolDialog(rest, onClose = { showWol = false }, onError = { errorText = it })
    if (showSerial) SerialDialog(
        onOpenTerminal = { req ->
            showSerial = false
            onOpenTerminal(req)
        },
        onClose = { showSerial = false },
    )
    if (showAssistant) AssistantDialog(
        rest = rest,
        host = com.nanokvm.app.ui.AppSession.host,
        onOpenTerminal = onOpenTerminal,
        onOpenAssistant = onOpenAssistant,
        onClose = { showAssistant = false },
    )
}

// ---------- shared bits ----------

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun ToolRow(
    title: String,
    subtitle: String = "",
    icon: ImageVector? = null,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        if (icon != null) {
            // 图标底座:32dp 圆角浅色块,与 MiniStat 同语言;危险项染错误色。
            val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = tint)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 32.dp)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun ToolDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)) { content() }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

private fun fmtDevInfo(i: DeviceInfo): List<Pair<String, String>> {
    val ip = i.ips?.joinToString("\n") { "${it.name} ${it.addr}" } ?: "—"
    return listOf(
        "IP" to ip,
        "mDNS" to (i.mdns ?: "—"),
        "固件" to ((i.application ?: "—").trim()),
        "镜像" to ((i.image ?: "—").trim()),
        "架构" to (i.arch ?: "—"),
        "设备号" to (i.deviceKey ?: "—"),
        "型号" to (i.pn ?: "—"),
    )
}

// ---------- device info ----------
@Composable
private fun InfoDialog(rest: com.nanokvm.app.data.api.NanoKvmApi, onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            val info = rest.deviceInfo()
            val hw = runCatching { rest.account() }.getOrNull()
            rows = fmtDevInfo(info) + ("账户" to (hw?.username ?: "—"))
        } catch (e: Exception) {
            err = e.message
        }
    }
    ToolDialog("设备信息", onClose) {
        when {
            err != null -> Text(
                err!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            rows == null -> CircularProgressIndicator()
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows!!.forEach { (k, v) ->
                    Row(Modifier.defaultMinSize(minHeight = 48.dp)) {
                        Text(k, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

// ---------- paste ----------
@Composable
private fun PasteDialog(viewModel: ConsoleViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val hasNonAscii = text.any { it.code > 127 }
    ToolDialog("粘贴文本(≤1024)", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(1024) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                placeholder = { Text("请输入内容…") },
                isError = hasNonAscii,
                supportingText = { if (hasNonAscii) Text("仅支持标准键盘的字母和符号") },
            )
            err?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Button(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    viewModel.pasteRemote(text) { e ->
                        busy = false
                        if (e == null) onClose() else err = e
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("粘贴到远程主机") }
        }
    }
}

// ---------- EDID ----------
private val EdidPresets = listOf(
    "E18-4K30FPS" to "3840×2160 30Hz",
    "E48-4K39FPS" to "3840×2160 39Hz",
    "E56-2K60FPS" to "2560×1440 60Hz",
    "E54-1080P60FPS" to "1920×1080 60Hz",
    "E58-4K16-10" to "3840×2400 30Hz",
    "E63-Ultrawide" to "3440×1440 60Hz",
)

@Composable
private fun EdidDialog(
    rest: com.nanokvm.app.data.api.NanoKvmApi,
    onClose: () -> Unit,
    onError: (String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var uploadMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val uploadLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            uploadMsg = null
            try {
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/')
                if (name == null || !name.lowercase().endsWith(".bin")) {
                    uploadMsg = "请选择 .bin 文件"
                    return@launch
                }
                val tmp = java.io.File(context.cacheDir, "edid_upload.bin")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                rest.uploadEdidFile(tmp)
                tmp.delete()
                custom = rest.edidCustomList().edidList.orEmpty()
                uploadMsg = "已上传 $name,点它即可切换"
            } catch (e: Exception) {
                uploadMsg = e.message ?: "上传失败"
            } finally {
                busy = false
            }
        }
    }
    fun pick(target: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                rest.switchEdid(target)
                current = target
            } catch (e: Exception) {
                onError(e.message ?: "EDID 切换失败")
            } finally {
                busy = false
            }
        }
    }
    fun remove(target: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                rest.edidDelete(target)
                custom = rest.edidCustomList().edidList.orEmpty()
            } catch (e: Exception) {
                onError(e.message ?: "删除失败")
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            current = rest.edidCurrent().edid
            custom = rest.edidCustomList().edidList.orEmpty()
        } catch (e: Exception) {
            onError(e.message ?: "EDID 读取失败")
        }
    }
    ToolDialog("EDID 显示器参数", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EdidPresets.forEach { (key, desc) ->
                EdidRow(
                    label = "$desc · $key",
                    value = key,
                    current = current,
                    busy = busy,
                    onPick = ::pick,
                )
            }
            custom?.forEach { file ->
                EdidRow(
                    label = file.removeSuffix(".bin"),
                    value = file,
                    current = current,
                    busy = busy,
                    onPick = ::pick,
                    onDelete = { remove(file) },
                )
            }
            OutlinedButton(
                onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("上传自定义 EDID(.bin)") }
            uploadMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("切换后显示器需重新协商,被控端分辨率随之变化", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EdidRow(
    label: String,
    value: String,
    current: String,
    busy: Boolean,
    onPick: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val selected = current == value
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = !busy && !selected) { onPick(value) }
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (selected) Text("使用中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (!selected && onDelete != null) {
            TextButton(
                onClick = onDelete,
                enabled = !busy,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ---------- images ----------
@Composable
private fun ImagesDialog(
    rest: com.nanokvm.app.data.api.NanoKvmApi,
    viewModel: ConsoleViewModel,
    onClose: () -> Unit,
    onError: (String) -> Unit,
) {
    var files by remember { mutableStateOf<List<String>?>(null) }
    var mounted by remember { mutableStateOf<MountedImage?>(null) }
    var modeCdrom by remember { mutableStateOf(false) }
    var readOnly by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var opMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun refresh() {
        scope.launch {
            try {
                files = rest.storageImages().files.orEmpty()
                mounted = rest.storageMounted()
            } catch (e: Exception) {
                err = e.message
            }
        }
    }
    val uploadLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            opMsg = "准备上传…"
            try {
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/')
                val tmp = java.io.File(context.cacheDir, name ?: "upload.img")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                rest.uploadImageChunked(tmp) { done, total -> opMsg = "上传中 $done/$total 块…" }
                tmp.delete()
                opMsg = "上传完成:${name}"
                refresh()
            } catch (e: Exception) {
                opMsg = "上传失败[${e.javaClass.simpleName}]:${e.message}"
            } finally {
                busy = false
            }
        }
    }
    fun checksum(f: String) {
        scope.launch {
            busy = true
            opMsg = "校验中…"
            try {
                val hash = rest.storageChecksum(f, "sha256")
                opMsg = "SHA256:${hash?.replace("\"", "").orEmpty().take(80)}"
            } catch (e: Exception) {
                opMsg = if ((e.message ?: "").contains("404")) "此固件不支持在线校验(升级设备后可用)" else "校验失败:${e.message}"
            } finally {
                busy = false
            }
        }
    }
    fun remove(f: String) {
        scope.launch {
            busy = true
            opMsg = "删除中…"
            try {
                rest.storageDeleteImage(f)
                opMsg = "已删除"
                refresh()
            } catch (e: Exception) {
                opMsg = "删除失败:${e.message}"
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    ToolDialog("镜像(/data)", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            err?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SegmentedButtons(
                    options = listOf("硬盘" to false, "CD-ROM" to true),
                    selected = modeCdrom,
                    onSelect = { modeCdrom = it; if (it) readOnly = true },
                )
                Spacer(Modifier.weight(1f))
                Text("只读", style = MaterialTheme.typography.labelMedium)
                Switch(checked = readOnly, onCheckedChange = { if (!modeCdrom) readOnly = it }, enabled = mounted == null)
            }
            OutlinedButton(
                onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy && (opMsg?.contains("上传") == true)) opMsg!! else "上传镜像(.img / .iso,自动分片)") }
            opMsg?.let {
                if (!(busy && it.contains("上传"))) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (files == null) {
                CircularProgressIndicator()
            } else if (files!!.isEmpty()) {
                Text("无镜像文件(点下方上传 .img/.iso;大文件走分片)", style = MaterialTheme.typography.bodySmall)
            } else {
                files!!.forEach { f ->
                    val name = f.substringAfterLast('/')
                    val isMounted = mounted?.file == f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isMounted) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable(enabled = !busy) {
                                busy = true
                                viewModel.mountImage(if (isMounted) "" else f, modeCdrom, readOnly) { e ->
                                    busy = false
                                    if (e != null) err = e
                                    refresh()
                                }
                            }
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(if (isMounted) "已挂载·点按卸载" else "点按挂载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!isMounted) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 8.dp),
                        ) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { checksum(f) }, enabled = !busy, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                                Text("SHA256 校验", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = { remove(f) }, enabled = !busy, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                                Text("删除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            mounted?.file?.let { m ->
                Text("当前: ${m.substringAfterLast('/')} · ${if (mounted?.cdrom == true) "CD-ROM" else "硬盘"}${if (mounted?.readOnly == true) " · 只读" else ""}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------- scripts ----------
@Composable
private fun ScriptsDialog(
    rest: com.nanokvm.app.data.api.NanoKvmApi,
    onClose: () -> Unit,
    onError: (String) -> Unit,
) {
    var files by remember { mutableStateOf<List<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<Pair<String, String>?>(null) } // (name, output)
    val scope = rememberCoroutineScope()
    suspend fun load() {
        try {
            files = rest.scripts().files.orEmpty()
        } catch (e: com.nanokvm.app.data.api.ApiException) {
            // 设备在目录为空/不存在时返回 code=-1 — 等同空列表(web 行为)。
            files = emptyList()
        } catch (e: Exception) {
            err = e.message
        }
    }
    LaunchedEffect(Unit) { load() }
    ToolDialog("脚本(/etc/kvm/scripts)", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            err?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            if (files == null) CircularProgressIndicator()
            else if (files!!.isEmpty()) Text("无脚本(web 端可上传 .sh/.py)", style = MaterialTheme.typography.bodySmall)
            else files!!.forEach { f ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(f.substringAfterLast('/'), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                log = f.substringAfterLast('/') to (rest.runScript(f, "foreground")?.log ?: "")
                            } catch (e: Exception) {
                                err = e.message
                            }
                        }
                    }) { Text("运行") }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                rest.runScript(f, "background")
                            } catch (e: Exception) {
                                err = e.message
                            }
                        }
                    }) { Text("后台") }
                }
            }
        }
    }
    log?.let { (name, out) ->
        AlertDialog(
            onDismissRequest = { log = null },
            title = { Text("运行 $name", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)) },
            text = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(Color(0xFF101418), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    Text(
                        out.ifEmpty { "(无输出)" },
                        color = Color(0xFFB9C2CB),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { log = null }) { Text("关闭") } },
        )
    }
}

// ---------- WOL ----------
@Composable
private fun WolDialog(
    rest: com.nanokvm.app.data.api.NanoKvmApi,
    onClose: () -> Unit,
    onError: (String) -> Unit,
) {
    var macs by remember { mutableStateOf<List<String>?>(null) }
    var input by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        try {
            macs = rest.wolMacs().macs.orEmpty()
        } catch (e: Exception) {
            macs = emptyList()
            err = null // file missing = empty history (live device: open file error)
        }
    }
    LaunchedEffect(Unit) { load() }
    ToolDialog("Wake-on-LAN", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            err?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("MAC 地址") },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Button(
                    enabled = input.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                rest.wolWake(input.trim())
                                input = ""
                                load()
                            } catch (e: Exception) {
                                err = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("唤醒") }
            }
            if (macs != null && macs!!.isNotEmpty()) {
                macs!!.forEach { line ->
                    val parts = line.split(" ")
                    val mac = parts[0]
                    val name = parts.getOrNull(1) ?: ""
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(if (name.isNotEmpty()) name else mac, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = if (name.isEmpty()) FontFamily.Monospace else null)
                        if (name.isNotEmpty()) Text(mac, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            editing = mac
                            editName = name
                        }) { Text("改名", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    rest.wolWake(mac)
                                } catch (e: Exception) {
                                    err = e.message
                                }
                            }
                        }) { Text("唤醒", style = MaterialTheme.typography.labelSmall) }
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    rest.wolDelete(mac)
                                    load()
                                } catch (e: Exception) {
                                    err = e.message
                                }
                            }
                        }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Delete, "删除", Modifier.size(20.dp))
                        }
                    }
                }
            } else if (macs != null) {
                Text("暂无历史记录,唤醒成功后自动保存", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("重命名 ${editing}", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)) },
            text = {
                OutlinedTextField(value = editName, onValueChange = { editName = it }, singleLine = true, label = { Text("名字(不含空格)") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = editing
                    editing = null
                    scope.launch {
                        try {
                            rest.wolRename(m ?: "", editName.trim())
                            load()
                        } catch (e: Exception) {
                            err = e.message
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}

// ---------- serial terminal config (web serial-port.tsx) ----------
@Composable
private fun SerialDialog(
    onOpenTerminal: (com.nanokvm.app.ui.terminal.TerminalRequest) -> Unit,
    onClose: () -> Unit,
) {
    var port by remember { mutableStateOf("/dev/ttyS1") }
    var baud by remember { mutableStateOf("115200") }
    var parity by remember { mutableStateOf("none") }
    var flow by remember { mutableStateOf("none") }
    var dataBits by remember { mutableStateOf("8") }
    var stopBits by remember { mutableStateOf("1") }
    ToolDialog("串口终端", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    modifier = Modifier.weight(1f), singleLine = true, label = { Text("串口") },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                ActionChip("ttyS1") { port = "/dev/ttyS1" }
                ActionChip("ttyS2") { port = "/dev/ttyS2" }
            }
            OutlinedTextField(
                value = baud, onValueChange = { baud = it },
                singleLine = true, label = { Text("波特率") },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            ToolRow("校验位", parityLabel(parity))
            Row(Modifier.padding(horizontal = 4.dp)) {
                SegmentedButtons(
                    options = listOf("无" to "none", "偶" to "even", "奇" to "odd"),
                    selected = parity,
                    onSelect = { parity = it },
                )
            }
            ToolRow("流控", flowLabel(flow))
            Row(Modifier.padding(horizontal = 4.dp)) {
                SegmentedButtons(
                    options = listOf("无" to "none", "软" to "soft", "硬" to "hard"),
                    selected = flow,
                    onSelect = { flow = it },
                )
            }
            ToolRow("数据位", dataBits)
            Row(Modifier.padding(horizontal = 4.dp)) {
                SegmentedButtons(
                    options = listOf("5" to "5", "6" to "6", "7" to "7", "8" to "8"),
                    selected = dataBits,
                    onSelect = { dataBits = it },
                )
            }
            ToolRow("停止位", stopBits)
            Row(Modifier.padding(horizontal = 4.dp)) {
                SegmentedButtons(
                    options = listOf("1" to "1", "2" to "2"),
                    selected = stopBits,
                    onSelect = { stopBits = it },
                )
            }
            Button(
                enabled = port.isNotBlank() && baud.isNotBlank(),
                onClick = {
                    onOpenTerminal(
                        com.nanokvm.app.ui.terminal.TerminalRequest(
                            kind = com.nanokvm.app.ui.terminal.TerminalKind.SERIAL,
                            port = port.trim(),
                            baud = baud.trim(),
                            parity = parity,
                            flow = flow,
                            dataBits = dataBits,
                            stopBits = stopBits,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("连接串口") }
        }
    }
}

private fun parityLabel(v: String): String = when (v) {
    "even" -> "偶校验"
    "odd" -> "奇校验"
    else -> "无校验"
}

private fun flowLabel(v: String): String = when (v) {
    "soft" -> "软件流控"
    "hard" -> "硬件流控"
    else -> "无流控"
}

// ---------- smart assistant (web menu/assistant → App 内对话) ----------
@Composable
private fun AssistantDialog(
    rest: NanoKvmApi,
    host: String,
    onOpenTerminal: (com.nanokvm.app.ui.terminal.TerminalRequest) -> Unit,
    onOpenAssistant: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    ToolDialog("智能助手(设备端 AI)", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "助手运行在被控设备上(cua,端口 5000)。启动后,你在这里描述任务,它会看着被控电脑的屏幕执行键鼠操作;对话页会同步显示实时画面,你可以随时观察进度、叫停或纠正。首次使用请先「安装依赖」再「启动并进入对话」(安装进度在终端页查看,依赖需联网 pip 安装)。",
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text("⚠ 助手会真实操控被控电脑(键鼠/输入),请确认任务描述安全。", color = MaterialTheme.colorScheme.error)
            err?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Button(
                enabled = busy.isEmpty(),
                onClick = {
                    busy = "install"
                    err = null
                    scope.launch {
                        try {
                            rest.assistantInstall()
                            onOpenTerminal(com.nanokvm.app.ui.terminal.TerminalRequest(kind = com.nanokvm.app.ui.terminal.TerminalKind.ASSISTANT_INSTALL))
                        } catch (e: Exception) {
                            err = e.message ?: "安装失败"
                        } finally {
                            busy = ""
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy == "install") "正在准备…" else "安装依赖(终端看进度)") }
            Button(
                enabled = busy.isEmpty(),
                onClick = {
                    busy = "start"
                    err = null
                    scope.launch {
                        try {
                            rest.assistantStart()
                            onOpenAssistant()
                        } catch (e: Exception) {
                            err = e.message ?: "启动失败(可能未安装依赖)"
                        } finally {
                            busy = ""
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy == "start") "正在启动…" else "启动并进入对话") }
            Text(
                "模型 API Key 在对话页「设置」中配置。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- mouse jiggler ----------
@Composable
private fun JigglerPicker(rest: com.nanokvm.app.data.api.NanoKvmApi, onError: (String) -> Unit) {
    var value by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            val j = rest.mouseJiggler()
            value = if (j.enabled) j.mode ?: "relative" else "disable"
        } catch (_: Exception) {
            // 读取失败保持 null(禁用控件)
        }
    }
    if (value != null) {
        SegmentedButtons(
            options = listOf("关闭" to "disable", "相对" to "relative", "绝对" to "absolute"),
            selected = value!!,
            onSelect = { v ->
                if (busy) return@SegmentedButtons
                busy = true
                scope.launch {
                    try {
                        rest.setMouseJiggler(v != "disable", if (v == "disable") "relative" else v)
                        value = v
                    } catch (e: Exception) {
                        onError(e.message ?: "鼠标抖动设置失败")
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}
