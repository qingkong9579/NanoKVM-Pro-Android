package com.nanokvm.app.ui.assistant


import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 智能助手对话页 — 响应式:
 * 竖屏:顶栏 / 实时桌面缩略图(条状) / 消息流 / 控制 / 输入,纵向堆叠。
 * 横屏:左侧 实时画面(大) + 右侧 消息/控制/输入 双栏。
 * 均含 实时桌面(MJPEG `/desktop-snapshot`)缩略图。
 */
@Composable
fun AssistantChatScreen(
    host: String,
    onExit: () -> Unit,
) {
    val vm: AssistantChatViewModel = viewModel(
        key = "assistant-$host",
        factory = viewModelFactory {
            initializer { AssistantChatViewModel(host) }
        },
    )
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var retryTick by remember { mutableIntStateOf(0) }

    val cfg = LocalConfiguration.current
    // 双栏只给"真正的横屏宽窗"(平板横屏/大屏):宽≥600dp、高≥480dp 且 宽>高。
    // 平板竖屏与手机一律走上下堆叠布局。
    val twoPane = cfg.screenWidthDp >= 600 && cfg.screenHeightDp >= 480 && cfg.screenWidthDp > cfg.screenHeightDp

    DisposableEffect(Unit) {
        vm.connect(true)
        onDispose { }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        TopBar(state, host, twoPane, onRefresh = { retryTick++; vm.connect(true) }, onSettings = { showSettings = true }, onExit = onExit)
        state.error?.let {
            Text(
                it,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        if (twoPane) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().fillMaxHeight()) {
                val w = maxWidth
                Row(Modifier.fillMaxSize()) {
                    // 左:大画面 + 底部状态(显式宽度,不依赖 Row 内 weight)
                    Column(
                        Modifier
                            .width(w * 0.45f)
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LiveDesktopThumb(
                            host = host,
                            retryTick = retryTick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    // 右:消息 + 控制 + 输入
                    Column(
                        Modifier
                            .width(w * 0.55f)
                            .fillMaxHeight(),
                    ) {
                        MessagesPane(state, vm, listState, Modifier.weight(1f))
                        TaskStateRow(state, vm)
                        InputRow(state, text, { text = it }, { vm.send(text); text = "" })
                    }
                }
            }
        } else {
            // 上下堆叠(手机任意方向 + 平板竖屏):顶栏 → 缩略图条 → 消息 → 控制/输入
            // 宽高都够大的窗口(平板竖屏等)画面条按屏宽 38% 自适应(240–400dp),
            // 手机保持 190dp 固定条。
            val thumbH = if (cfg.screenWidthDp >= 600 && cfg.screenHeightDp >= 600) {
                (cfg.screenWidthDp * 0.38f).dp.coerceIn(240.dp, 400.dp)
            } else {
                190.dp
            }
            LiveDesktopThumb(
                host = host,
                retryTick = retryTick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbH)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            MessagesPane(state, vm, listState, Modifier.weight(1f))
            TaskStateRow(state, vm)
            InputRow(state, text, { text = it }, { vm.send(text); text = "" })
        }
    }

    if (showSettings) AssistantSettingsDialog(vm, onClose = { showSettings = false })
}

@Composable
private fun TopBar(
    state: AssistantUiState,
    host: String,
    twoPane: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (twoPane) "智能助手" else "智能助手 · $host:5000",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            when {
                !state.serviceUp -> "未连接"
                state.taskState == TaskState.RUNNING -> "执行中"
                state.taskState == TaskState.WAITING_USER -> "等待输入"
                else -> "在线"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusDot(state)
        IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Refresh, "重连", Modifier.size(20.dp))
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Settings, "设置", Modifier.size(20.dp))
        }
        IconButton(onClick = onExit, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ExitToApp, "退出", Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StatusDot(state: AssistantUiState) {
    val color = when {
        !state.serviceUp -> MaterialTheme.colorScheme.error
        state.taskState == TaskState.RUNNING || state.taskState == TaskState.WAITING_USER -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
    )
}

@Composable
private fun MessagesPane(
    state: AssistantUiState,
    vm: AssistantChatViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    if (state.messages.isEmpty()) {
        // 冷启动:不显示空列表,说人话引导(finesse-brief 空态=引导建第一个)
        val hint = if (state.serviceUp) {
            if (state.taskState == TaskState.IDLE) "描述一个任务,AI 会看着这台电脑的实时画面一步步执行\n画面区就是它现在看到的桌面"
            else "任务执行中…首个结果马上出现"
        } else "助手服务未连接\n先点右上角刷新,或到设备端确认 cua 服务已启动"
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        items(state.messages, key = { it.hashCode() }) { msg ->
            MessageBubble(msg, vm)
        }
        if (state.taskState == TaskState.RUNNING) {
            item {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("助手正在操作桌面…", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun TaskStateRow(state: AssistantUiState, vm: AssistantChatViewModel) {
    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.serviceUp) {
            when (state.taskState) {
                TaskState.RUNNING, TaskState.WAITING_USER, TaskState.PAUSED -> {
                    TextButton(onClick = { vm.pause() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text(if (state.taskState == TaskState.PAUSED) "恢复(输入继续)" else "暂停", style = MaterialTheme.typography.labelMedium)
                    }
                }
                else -> {}
            }
            TextButton(onClick = { vm.reset() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                Text("重置", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            when (state.taskState) {
                TaskState.RUNNING -> {
                    val last = state.messages.lastOrNull { it.kind == AssistantMsg.Kind.ACTION }?.text
                    if (last != null) "执行中 · ${last.take(26)}${if (last.length > 26) "…" else ""}"
                    else "执行中…"
                }
                TaskState.WAITING_USER -> "等待你补充信息"
                TaskState.PAUSED -> "已暂停"
                TaskState.DONE -> "完成"
                TaskState.ERROR -> "出错"
                TaskState.IDLE -> "空闲:输入目标开始"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun InputRow(
    state: AssistantUiState,
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = text.isNotBlank() && !state.busying
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    when (state.taskState) {
                        TaskState.WAITING_USER -> "回答助手的问题(补充后继续执行)…"
                        else -> "描述你想在远程电脑上完成的任务…"
                    },
                    maxLines = 2,
                )
            },
            shape = RoundedCornerShape(14.dp),
            maxLines = 4,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
        )
        Button(
            enabled = canSend,
            onClick = onSend,
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Text("发送", style = MaterialTheme.typography.labelLarge, fontSize = 14.sp)
        }
    }
}

@Composable
private fun MessageBubble(msg: AssistantMsg, vm: AssistantChatViewModel) {
    val isUser = msg.kind == AssistantMsg.Kind.USER
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val bg = when (msg.kind) {
            AssistantMsg.Kind.USER -> MaterialTheme.colorScheme.primaryContainer
            AssistantMsg.Kind.ERROR -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Column(
            Modifier
                .widthIn(max = if (isUser) 300.dp else 340.dp)
                .clip(RoundedCornerShape(if (isUser) 14.dp else 12.dp))
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (msg.kind) {
                AssistantMsg.Kind.THOUGHT -> Text("💭 ${msg.text}", style = MaterialTheme.typography.bodyMedium)
                AssistantMsg.Kind.ACTION -> Text(msg.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, style = MaterialTheme.typography.bodyMedium)
                AssistantMsg.Kind.SYSTEM -> Text("🤖 ${msg.text}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                AssistantMsg.Kind.ERROR -> Text("⚠ ${msg.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                AssistantMsg.Kind.USER -> Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                AssistantMsg.Kind.IMAGE -> {
                    val bytes = vm.decodeImage(msg.imageB64 ?: "")
                    val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "桌面截图",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantSettingsDialog(vm: AssistantChatViewModel, onClose: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var apiType by remember { mutableStateOf("OpenAI") }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var imgKeepN by remember { mutableStateOf("3") }
    var maxRounds by remember { mutableStateOf("20") }

    LaunchedEffect(Unit) {
        vm.loadSettings { loaded, e ->
            if (loaded != null) {
                apiType = loaded.apiType
                apiKey = loaded.apiKey
                baseUrl = loaded.baseUrl
                modelName = loaded.modelName
                imgKeepN = loaded.imgKeepN.toString()
                maxRounds = loaded.maxRounds.toString()
            } else {
                err = e
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("模型设置", style = MaterialTheme.typography.titleMedium)
                Text("写入设备 /etc/kvm/cua_cfg.json", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                SettingsGroupLabel("模型")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = apiType,
                        onValueChange = { apiType = it },
                        singleLine = true,
                        label = { Text("API 类型") },
                        placeholder = { Text("OpenAI") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        singleLine = true,
                        label = { Text("模型") },
                        placeholder = { Text("deepseek-v4-flash-vision-exp") },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    singleLine = true,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://opencode.ai/zen/go/v1") },
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsGroupLabel("密钥")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-…") },
                    minLines = 1,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsGroupLabel("会话")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = imgKeepN,
                        onValueChange = { imgKeepN = it },
                        singleLine = true,
                        label = { Text("保留截图数") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxRounds,
                        onValueChange = { maxRounds = it },
                        singleLine = true,
                        label = { Text("最大轮数") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    vm.saveSettings(
                        AssistantSettings(
                            apiType = apiType,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            modelName = modelName,
                            imgKeepN = imgKeepN.toIntOrNull() ?: 3,
                            maxRounds = maxRounds.toIntOrNull() ?: 20,
                        ),
                    ) { e -> busy = false; if (e == null) onClose() else err = e }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("保存中…")
                } else {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !busy) { Text("取消") }
        },
    )
}

/** 弹窗内分组小标题:12sp 次要色 + 分隔细线,与 spec 弹窗令牌一致。 */
@Composable
private fun SettingsGroupLabel(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
    }
}
