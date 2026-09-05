package com.nanokvm.app.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
 * 虚拟设备 — 虚拟U盘(sd/emmc)/虚拟网卡/虚拟麦克风。
 * 切换由设备端以 HID 锁执行(键鼠会短暂重连);写后回读。
 */
@Composable
fun VirtualDevDialog(rest: NanoKvmApi, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var netOn by remember { mutableStateOf(false) }
    var micOn by remember { mutableStateOf(false) }
    var mountedDisk by remember { mutableStateOf("") }
    var emmcExist by remember { mutableStateOf(false) }
    var sdExist by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try {
            val s = rest.getVirtualDevices()
            netOn = s.isNetworkEnabled
            micOn = s.isMicEnabled
            mountedDisk = s.mountedDisk
            emmcExist = s.isEmmcExist
            sdExist = s.isSdCardExist
            err = null
        } catch (e: Exception) {
            err = e.message ?: "读取失败"
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
                Text("虚拟设备", style = MaterialTheme.typography.titleMedium)
                Text("虚拟U盘 / 网卡 / 麦克风(经 USB 虚拟到被控机)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(
                    "切换会短暂重启 USB 键鼠通道,属正常现象",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                GroupLabel3("虚拟 U 盘")
                when {
                    mountedDisk.isNotBlank() -> {
                        Text("已挂载:$mountedDisk", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    guard {
                                        rest.toggleVirtualDevice("disk", mountedDisk)
                                        refresh()
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("卸载") }
                    }
                    sdExist || emmcExist -> {
                        Text("选择要挂载到被控机的镜像(先上传到镜像管理)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (sdExist) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            guard {
                                                rest.toggleVirtualDevice("disk", "sdcard")
                                                refresh()
                                            }
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text("挂载 SD 卡镜像") }
                            }
                            if (emmcExist) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            guard {
                                                rest.toggleVirtualDevice("disk", "emmc")
                                                refresh()
                                            }
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text("挂载 eMMC 镜像") }
                            }
                        }
                    }
                    else -> Text("无可用镜像(sd/emmc 均不存在)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                GroupLabel3("虚拟网卡 / 麦克风")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("虚拟网卡(RNDIS)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = netOn,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            scope.launch {
                                guard {
                                    rest.toggleVirtualDevice("network")
                                    refresh()
                                }
                            }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("虚拟麦克风(UAC)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = micOn,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            scope.launch {
                                guard {
                                    rest.toggleVirtualDevice("mic")
                                    refresh()
                                }
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("关闭") } },
    )
}

@Composable
private fun GroupLabel3(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.height(2.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(
            Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
    }
}
