package com.nanokvm.app.ui.console

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.sp
import com.nanokvm.app.data.api.NanoKvmApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设备设置 — web 设置(about/device/network)中未移植项的组合对话框:
 * 主机名 / SSH / mDNS / HDMI 采集与直通 / 静态 IP / Wi-Fi。
 * 全部走设备 REST,只读当前值 + 切换/保存;破坏性提示就地给出。
 */
@Composable
fun DeviceSettingsDialog(rest: NanoKvmApi, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var hostname by remember { mutableStateOf("") }
    var hostnameOk by remember { mutableStateOf(true) }
    var sshOn by remember { mutableStateOf(false) }
    var mdnsOn by remember { mutableStateOf(false) }
    var capOn by remember { mutableStateOf(false) }
    var passOn by remember { mutableStateOf(false) }
    var staticEnabled by remember { mutableStateOf(false) }
    var staticIp by remember { mutableStateOf("") }
    var staticOk by remember { mutableStateOf(true) }

    var wifiSupported by remember { mutableStateOf(false) }
    var wifiApMode by remember { mutableStateOf(false) }
    var wifiConnected by remember { mutableStateOf(false) }
    var wifiSsid by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf<List<com.nanokvm.app.data.api.WifiInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanNote by remember { mutableStateOf<String?>(null) }
    var pickSsid by remember { mutableStateOf<String?>(null) }
    var pickPwd by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    suspend fun refreshAll() {
        suspend fun <T> attempt(block: suspend () -> T): T? = try {
            block()
        } catch (e: Exception) {
            android.util.Log.w("DeviceSettings", "fetch failed: ${e.message}")
            null
        }
        attempt { hostname = rest.getHostname(); hostnameOk = true } ?: run { hostnameOk = false }
        attempt { sshOn = rest.getSshState() }
        attempt { mdnsOn = rest.getMdnsState() }
        attempt { capOn = rest.getHdmiCapture() }
        attempt { passOn = rest.getHdmiPassthrough() }
        val sip = attempt { rest.getStaticIp() }
        if (sip != null) {
            staticOk = true
            staticEnabled = sip.enabled
            staticIp = sip.ip
        } else {
            staticOk = false
        }
        val w = attempt { rest.getWifiState() }
        if (w != null) {
            wifiSupported = w.supported
            wifiApMode = w.apMode
            wifiConnected = w.connected
            wifiSsid = w.wifi?.ssid.orEmpty()
        }
        err = null
    }

    suspend fun guard(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        try {
            action()
            err = null
        } catch (e: Exception) {
            err = e.message ?: "操作失败"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("设备设置", style = MaterialTheme.typography.titleMedium)
                Text("主机名 · 服务 · 网络(改动即时下发)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(
                Modifier
                    .verticalScroll(scrollState)
                    .fillMaxWidth(),
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

                GroupLabel("主机名")
                if (hostnameOk) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hostname,
                            onValueChange = { hostname = it },
                            singleLine = true,
                            label = { Text("主机名") },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            enabled = hostname.isNotBlank() && !busy,
                            onClick = { scope.launch { guard { rest.setHostname(hostname.trim()) } } },
                            modifier = Modifier.height(48.dp),
                        ) { Text("保存") }
                    }
                    Text("重启后生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "固件不支持修改主机名",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                GroupLabel("服务")
                ToggleRow("SSH", "允许 SSH 登录设备", sshOn) { on ->
                    scope.launch { guard { rest.setSshState(on); sshOn = rest.getSshState() } }
                }
                ToggleRow("mDNS", "局域网设备发现(nanokvm.local)", mdnsOn) { on ->
                    scope.launch { guard { rest.setMdnsState(on); mdnsOn = rest.getMdnsState() } }
                }
                ToggleRow("HDMI 采集", "从 HDMI 输入采集画面", capOn) { on ->
                    scope.launch { guard { rest.setHdmiCapture(on); capOn = rest.getHdmiCapture() } }
                }
                ToggleRow("HDMI 直通", "输入直通到 HDMI 输出", passOn) { on ->
                    scope.launch { guard { rest.setHdmiPassthrough(on); passOn = rest.getHdmiPassthrough() } }
                }

                GroupLabel("网络")
                if (!staticOk) {
                    Text(
                        "固件不支持静态 IP 设置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("静态 IP", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = staticEnabled,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            scope.launch {
                                guard {
                                    rest.setStaticIp(if (on) staticIp else "")
                                    val s = rest.getStaticIp()
                                    staticEnabled = s.enabled
                                    staticIp = s.ip
                                }
                            }
                        },
                    )
                }
                if (staticEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = staticIp,
                            onValueChange = { staticIp = it },
                            singleLine = true,
                            label = { Text("IP 地址") },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            enabled = staticIp.isNotBlank() && !busy,
                            onClick = {
                                scope.launch {
                                    guard {
                                        rest.setStaticIp(staticIp.trim())
                                        val s = rest.getStaticIp()
                                        staticEnabled = s.enabled
                                        staticIp = s.ip
                                    }
                                }
                            },
                            modifier = Modifier.height(48.dp),
                        ) { Text("应用") }
                    }
                    Text("改错地址可能导致设备失联,请确认与本机同网段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                }

                // Wi-Fi
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (wifiConnected) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                        null,
                        tint = if (wifiConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            !wifiSupported -> "本机不支持 Wi-Fi"
                            wifiApMode -> "处于 AP 热点模式"
                            wifiConnected -> "已连接 $wifiSsid"
                            else -> "未连接 Wi-Fi"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (wifiSupported && !wifiApMode && wifiConnected) {
                        TextButton(onClick = { scope.launch { guard { rest.disconnectWifi(); refreshAll() } } }, enabled = !busy) {
                            Text("断开")
                        }
                    }
                    if (wifiSupported && !wifiApMode) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    scanning = true
                                    scanNote = null
                                    guard {
                                        val list = rest.scanWifi().sortedByDescending { it.signal }
                                        scan = list
                                        if (list.isEmpty()) scanNote = "未扫描到 Wi-Fi 网络"
                                    }
                                    scanning = false
                                    if (scan.isNotEmpty()) {
                                        // 等列表入帧后滚到底部,保证结果可见
                                        delay(120)
                                        scrollState.animateScrollTo(scrollState.maxValue)
                                    }
                                }
                            },
                            enabled = !busy && !scanning,
                            modifier = Modifier.height(40.dp),
                        ) {
                            if (scanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Text("扫描")
                        }
                    }
                }
                if (scanNote != null) {
                    Text(
                        scanNote.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (scan.isNotEmpty()) {
                    Text(
                        "扫描到 ${scan.size} 个网络,点击「连接」输入密码",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    scan.forEach { net ->
                        val isCurrent = wifiConnected && net.ssid == wifiSsid
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                net.ssid.ifBlank { "(隐藏网络)" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (isCurrent) {
                                Text("已连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            val secured = net.security.isNotBlank() && net.security != "NONE"
                            Text(
                                (if (secured) "🔒 " else "") +
                                    when {
                                        net.signal >= -60 -> "信号强"
                                        net.signal >= -70 -> "信号中"
                                        else -> "信号弱"
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { pickSsid = net.ssid; pickPwd = "" }) {
                                Text(if (isCurrent) "重连" else "连接")
                            }
                        }
                        if (pickSsid == net.ssid) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = pickPwd,
                                    onValueChange = { pickPwd = it },
                                    singleLine = true,
                                    label = { Text("密码(开放网络可留空)") },
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            guard {
                                                rest.connectWifi(net.ssid, pickPwd)
                                                pickSsid = null
                                                pickPwd = ""
                                                scan = emptyList()
                                                refreshAll()
                                            }
                                        }
                                    },
                                    modifier = Modifier.height(44.dp),
                                ) { Text("连接") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose, enabled = !busy) { Text("关闭") }
        },
    )
}

@Composable
private fun GroupLabel(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(
            Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
