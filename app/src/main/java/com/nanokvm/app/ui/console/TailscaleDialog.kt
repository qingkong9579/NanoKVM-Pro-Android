package com.nanokvm.app.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.nanokvm.app.data.api.NanoKvmApi
import kotlinx.coroutines.launch

private fun tsLabel(state: String): String = when (state) {
    "running" -> "运行中"
    "notInstall" -> "未安装"
    "notRunning" -> "未运行"
    "notLogin" -> "已安装未登录"
    "stopped" -> "已停止"
    else -> state
}

/** Tailscale — 状态 + 生命周期操作(安装/启动/登录/断开/停止/卸载)。 */
@Composable
fun TailscaleDialog(rest: NanoKvmApi, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var supported by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf("notInstall") }
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }

    suspend fun refresh() {
        try {
            val s = rest.getTailscale()
            supported = true
            state = s.state
            name = s.name
            ip = s.ip
            account = s.account
            err = null
        } catch (e: Exception) {
            supported = false
            err = "设备固件未提供 Tailscale 支持(需升级设备固件)"
        }
    }

    suspend fun act(action: String, finish: String) {
        if (busy) return
        busy = true
        try {
            val url = rest.tailscaleAction(action)
            if (url.isNullOrBlank()) {
                err = finish
            } else {
                loginUrl = url
                err = "请复制登录链接并在浏览器中打开授权;授权后点「刷新状态」"
            }
            refresh()
        } catch (e: Exception) {
            err = e.message ?: "操作失败"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Tailscale", style = MaterialTheme.typography.titleMedium)
                Text("把设备加入你的 Tailnet,随时从外网访问", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state == "running") Icons.Outlined.Cloud else Icons.Outlined.CloudOff,
                        null,
                        tint = if (state == "running") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        "状态:${tsLabel(state)}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { scope.launch { refresh() } },
                        enabled = !busy,
                        modifier = Modifier.height(36.dp),
                    ) { Text("刷新") }
                }
                if (name.isNotBlank() || ip.isNotBlank() || account.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (name.isNotBlank()) Text("节点:${name}", style = MaterialTheme.typography.bodySmall)
                        if (ip.isNotBlank()) Text("Tailscale IP:${ip}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (account.isNotBlank()) Text("账号:${account}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                loginUrl?.let { url ->
                    Text(
                        "登录链接(复制到浏览器打开授权):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(url))
                            err = "已复制登录链接"
                        },
                    ) { Text("复制链接") }
                }
                if (supported) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state == "notInstall") {
                        OutlinedButton(
                            onClick = { scope.launch { act("install", "安装完成,点刷新查看状态") } },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("安装") }
                    } else {
                        OutlinedButton(
                            onClick = { scope.launch { act("start", "已启动服务") } },
                            enabled = !busy && state == "stopped",
                            modifier = Modifier.weight(1f),
                        ) { Text("启动") }
                        OutlinedButton(
                            onClick = { scope.launch { act("login", "登录流程已发起") } },
                            enabled = !busy && (state == "notLogin" || state == "notRunning"),
                            modifier = Modifier.weight(1f),
                        ) { Text("登录/上线") }
                        OutlinedButton(
                            onClick = { scope.launch { act("down", "已断开") } },
                            enabled = !busy && state == "running",
                            modifier = Modifier.weight(1f),
                        ) { Text("断开") }
                    }
                }
                if (state != "notInstall") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { act("stop", "已停止服务") } },
                            enabled = !busy && state != "notRunning" && state != "notInstall",
                            modifier = Modifier.weight(1f),
                        ) { Text("停止") }
                        OutlinedButton(
                            onClick = { scope.launch { act("restart", "已重启") } },
                            enabled = !busy && state == "running",
                            modifier = Modifier.weight(1f),
                        ) { Text("重启") }
                        OutlinedButton(
                            onClick = { scope.launch { act("uninstall", "已卸载") } },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("卸载") }
                    }
                }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("关闭") } },
    )
}
