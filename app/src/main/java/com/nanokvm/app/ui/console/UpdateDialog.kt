package com.nanokvm.app.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.nanokvm.app.data.api.NanoKvmApi
import kotlinx.coroutines.launch

/**
 * 系统更新 — 检查稳定/预览通道版本,确认后在线下载安装(下载+安装可能数分钟,
 * 期间设备会重启服务)。真实更新动作由用户二次确认触发。
 */
@Composable
fun UpdateDialog(rest: NanoKvmApi, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("—") }
    var latest by remember { mutableStateOf("—") }
    var preview by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }

    suspend fun refresh() {
        busy = true
        try {
            val v = rest.appVersion()
            current = v.current.ifBlank { "—" }
            latest = v.latest.ifBlank { "—" }
            preview = rest.appPreviewEnabled()
            confirmed = false
            err = null
        } catch (e: Exception) {
            err = e.message ?: "读取版本失败"
        } finally {
            busy = false
        }
    }

    suspend fun guard(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        try {
            block()
            err = null
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
                Text("系统更新", style = MaterialTheme.typography.titleMedium)
                Text("在线下载并安装(需设备能访问更新服务器)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Column(Modifier.weight(1f)) {
                        Text("当前版本:$current", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (latest == current && latest != "—") "已是最新" else "最新版本:$latest",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (latest != "—" && latest != current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !busy, modifier = Modifier.height(40.dp)) {
                        Text("检查更新")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("预览(测试)通道", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("尝鲜版,稳定性较低", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(
                        checked = preview,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            scope.launch { guard { rest.setAppPreview(on); preview = rest.appPreviewEnabled(); refresh() } }
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                if (!confirmed) {
                    Button(
                        enabled = !busy && latest != "—",
                        onClick = { confirmed = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始更新") }
                } else {
                    Text(
                        "⚠ 二次确认:将下载并安装 $latest。过程中设备服务会重启,切勿断电;完成后 App 需重新连接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    guard {
                                        rest.updateApplication()
                                        err = "更新流程已触发,设备可能在重启;稍后重新连接查看版本"
                                        confirmed = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("确认更新") }
                        OutlinedButton(onClick = { confirmed = false }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text("取消")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("关闭") } },
    )
}
