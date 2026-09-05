package com.nanokvm.app.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanokvm.app.data.api.NanoKvmApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 显示 · 时间 · 账户 — web 设置剩余项的组合对话框:
 * OLED 息屏 / LCD 时间格式 / LCD 定时熄屏 / LED 灯带 / 低功耗 / 时间同步与状态 / 时区 / 账户改密。
 * 与 DeviceSettingsDialog 相同:逐项容错、写后回读。
 */
@Composable
fun DevExtrasDialog(rest: NanoKvmApi, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // OLED
    var oledExist by remember { mutableStateOf(false) }
    var oledType by remember { mutableStateOf("") }
    var oledSleep by remember { mutableStateOf("") }

    // LCD
    var lcdFormat by remember { mutableStateOf("") }
    var soffSupported by remember { mutableStateOf(false) }
    var soffEnabled by remember { mutableStateOf(false) }
    var soffStart by remember { mutableStateOf("") }
    var soffEnd by remember { mutableStateOf("") }

    // LED strip
    var ledOn by remember { mutableStateOf(false) }
    var ledBright by remember { mutableIntStateOf(0) }

    // Low power
    var lowPower by remember { mutableStateOf(false) }

    // Time
    var timezone by remember { mutableStateOf("") }
    var timezoneOk by remember { mutableStateOf(true) }
    var timeSynced by remember { mutableStateOf(false) }
    var lastSync by remember { mutableStateOf("—") }

    // Account
    var account by remember { mutableStateOf("") }
    var accountOk by remember { mutableStateOf(true) }
    var newPwd by remember { mutableStateOf("") }
    var newPwd2 by remember { mutableStateOf("") }

    suspend fun refreshAll() {
        suspend fun <T> attempt(block: suspend () -> T): T? = try {
            block()
        } catch (e: Exception) {
            android.util.Log.w("DevExtras", "fetch failed: ${e.message}")
            null
        }
        val oled = attempt { rest.getOled() }
        if (oled != null) {
            oledExist = oled.exist
            oledType = oled.type
            oledSleep = oled.sleep.toString()
        }
        attempt { lcdFormat = rest.getLcdTimeFormat() }
        val off = attempt { rest.getLcdScreenOff() }
        if (off != null) {
            soffSupported = off.supported
            soffEnabled = off.enabled
            soffStart = String.format("%02d:%02d", off.startMinute / 60, off.startMinute % 60)
            soffEnd = String.format("%02d:%02d", off.endMinute / 60, off.endMinute % 60)
        }
        val led = attempt { rest.getLedConfig() }
        if (led != null) {
            ledOn = led.on
            ledBright = led.brightness
        }
        attempt { lowPower = rest.getLowPower() }
        val tz = attempt { rest.getTimeZone() }
        if (tz != null) {
            timezoneOk = true
            timezone = tz
        } else {
            timezoneOk = false
        }
        val ts = attempt { rest.getTimeStatus() }
        if (ts != null) {
            timeSynced = ts.isSynchronized
            lastSync = if (ts.lastSyncTime > 0) {
                val ms = if (ts.lastSyncTime > 1_000_000_000_000L) ts.lastSyncTime else ts.lastSyncTime * 1000
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
            } else "从未"
        }
        val acc = attempt { rest.getAccount() }
        if (acc != null) {
            accountOk = true
            account = acc
        } else {
            accountOk = false
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
                Text("显示 · 时间 · 账户", style = MaterialTheme.typography.titleMedium)
                Text("OLED / LCD / LED / 时间 / 改密", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
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

                GroupLabel2("显示")
                if (oledExist) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("OLED 息屏秒数($oledType)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = oledSleep,
                            onValueChange = { oledSleep = it.filter { c -> c.isDigit() }.take(5) },
                            singleLine = true,
                            modifier = Modifier.width(92.dp),
                        )
                        TextButton(
                            enabled = oledSleep.toIntOrNull() != null && !busy,
                            onClick = { scope.launch { guard { rest.setOledSleep(oledSleep.toInt()); refreshAll() } } },
                        ) { Text("应用") }
                    }
                } else {
                    Text("未检测到 OLED", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LCD 时间格式", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    val is24 = lcdFormat.startsWith("24")
                    TextButton(
                        enabled = lcdFormat.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                guard {
                                    rest.setLcdTimeFormat(if (is24) "12h" else "24h")
                                    refreshAll()
                                }
                            }
                        },
                    ) { Text("切换 ${if (is24) "12 小时" else "24 小时"}") }
                }
                if (soffSupported) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LCD 定时熄屏", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = soffEnabled,
                            enabled = !busy,
                            onCheckedChange = { on ->
                                scope.launch {
                                    guard {
                                        val s = soffStart.split(":").let { (it[0].toIntOrNull() ?: 0) * 60 + (it[1].toIntOrNull() ?: 0) }
                                        val e = soffEnd.split(":").let { (it[0].toIntOrNull() ?: 0) * 60 + (it[1].toIntOrNull() ?: 0) }
                                        rest.setLcdScreenOff(on, s, e)
                                        refreshAll()
                                    }
                                }
                            },
                        )
                    }
                    if (soffEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = soffStart,
                                onValueChange = { soffStart = it },
                                singleLine = true,
                                label = { Text("开始(HH:mm)") },
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = soffEnd,
                                onValueChange = { soffEnd = it },
                                singleLine = true,
                                label = { Text("结束(HH:mm)") },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !busy && Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(soffStart) && Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(soffEnd),
                                onClick = {
                                    scope.launch {
                                        guard {
                                            val s = soffStart.split(":")[0].toInt() * 60 + soffStart.split(":")[1].toInt()
                                            val e = soffEnd.split(":")[0].toInt() * 60 + soffEnd.split(":")[1].toInt()
                                            rest.setLcdScreenOff(true, s, e)
                                            refreshAll()
                                        }
                                    }
                                },
                            ) { Text("保存") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("低功耗模式", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("自动关闭屏幕/外设省电", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(
                        checked = lowPower,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            scope.launch {
                                guard {
                                    rest.setLowPower(on)
                                    lowPower = rest.getLowPower()
                                }
                            }
                        },
                    )
                }

                GroupLabel2("LED 灯带")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("灯带", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = ledOn,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            scope.launch {
                                guard {
                                    rest.setLedConfig(on, 1, 1, if (on) ledBright.coerceAtLeast(1) else ledBright)
                                    val l = rest.getLedConfig()
                                    ledOn = l.on
                                    ledBright = l.brightness
                                }
                            }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("亮度 ${ledBright}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = ledBright.toFloat(),
                        onValueChange = { ledBright = it.toInt() },
                        onValueChangeFinished = {
                            scope.launch {
                                guard {
                                    rest.setLedConfig(ledOn, 1, 1, ledBright)
                                    val l = rest.getLedConfig()
                                    ledBright = l.brightness
                                    ledOn = l.on
                                }
                            }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                }

                GroupLabel2("时间")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("时间同步", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (timeSynced) "已同步 · 上次 $lastSync" else "未同步($lastSync)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { scope.launch { guard { rest.syncTime(); refreshAll() } } },
                        modifier = Modifier.height(40.dp),
                    ) { Text("立即同步") }
                }
                if (timezoneOk) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = timezone,
                            onValueChange = { timezone = it },
                            singleLine = true,
                            label = { Text("时区(IANA)") },
                            placeholder = { Text("Asia/Shanghai") },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            enabled = timezone.isNotBlank() && !busy,
                            onClick = { scope.launch { guard { rest.setTimeZone(timezone.trim()); refreshAll() } } },
                            modifier = Modifier.height(48.dp),
                        ) { Text("保存") }
                    }
                } else {
                    Text("固件不支持时区设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                GroupLabel2("账户")
                if (accountOk) {
                    Text("当前用户名:$account", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = newPwd,
                        onValueChange = { newPwd = it },
                        singleLine = true,
                        label = { Text("新密码") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newPwd2,
                        onValueChange = { newPwd2 = it },
                        singleLine = true,
                        label = { Text("确认新密码") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "⚠ 修改后 App 与 Web 均需使用新密码登录;本 App 下次连接请改回凭据",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        enabled = newPwd.length >= 6 && newPwd == newPwd2 && !busy,
                        onClick = {
                            scope.launch {
                                guard {
                                    rest.changePassword(account, newPwd)
                                    newPwd = ""
                                    newPwd2 = ""
                                    err = "密码已修改,请牢记新密码"
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) { Text("修改密码") }
                } else {
                    Text("固件不支持账户设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose, enabled = !busy) { Text("关闭") }
        },
    )
}

@Composable
private fun GroupLabel2(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(
            Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
    }
}
