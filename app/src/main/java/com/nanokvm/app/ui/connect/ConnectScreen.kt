package com.nanokvm.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanokvm.app.ui.AppSession
import com.nanokvm.app.ui.theme.DotGridBackground
import com.nanokvm.app.ui.theme.OneKvmColors

/**
 * One-KVM login style: centered card on the dotted backdrop, built as two visual
 * layers — a single-line brand header (48dp icon + title with inline subtitle)
 * followed by the form: host / username / password, inline error block, primary
 * connect button and a first-run hint. On success the credentials are stashed in
 * [AppSession] and the console opens. Visual tokens per finesse-brief: 12dp card
 * corners, 10dp field/button corners, 12sp labels, 44dp primary action, textual
 * busy/error feedback instead of icon-only states.
 */
@Composable
fun ConnectScreen(
    viewModel: ConnectViewModel,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onConnected: (host: String, user: String, pass: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val error = state.error
    val fieldsEnabled = !state.busy

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        DotGridBackground(Modifier.fillMaxSize())
        // 右上角日夜切换(全局主题开关入口,置于底纹之上)
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            IconButton(onClick = onToggleTheme) {
                Icon(
                    if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    contentDescription = if (isDark) "切换浅色" else "切换深色",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Brand: one row — 48dp icon tile (12dp corners) + title 15sp/500 with
            // the 12sp subtitle inline to its right (single visual layer).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(OneKvmColors.NearBlack, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = OneKvmColors.SuccessBright,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) { append("NanoKVM Pro") }
                        append("  ")
                        withStyle(
                            SpanStyle(
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { append("局域网 · 远程桌面") }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Credential fields: uniform outlined fields, 10dp corners, 10dp apart,
            // 12sp labels, disabled while a connection attempt is in flight.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::onHostChange,
                    enabled = fieldsEnabled,
                    label = { Text("主机地址", style = MaterialTheme.typography.labelMedium) },
                    placeholder = { Text("192.168.5.47") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    enabled = fieldsEnabled,
                    label = { Text("用户名", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    enabled = fieldsEnabled,
                    label = { Text("密码", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.togglePasswordVisibility() }) {
                            Icon(
                                if (state.showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (state.showPassword) "隐藏密码" else "显示密码",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    visualTransformation = if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Inline error: light error-tinted block, 8dp corners, leading icon.
            if (error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Primary action: 44dp tall, 10dp corners; busy state spells out the
            // target host instead of spinning alone.
            Button(
                onClick = {
                    viewModel.connect {
                        AppSession.set(state.host.trim(), state.username.trim(), state.password)
                        onConnected(state.host.trim(), state.username.trim(), state.password)
                    }
                },
                enabled = !state.busy,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在连接 ${state.host.trim().ifEmpty { "目标设备" }}…")
                } else {
                    Text("连接")
                }
            }

            // First-run hint: only while there is no error to report.
            if (error == null) {
                Text(
                    text = "连上后即可看到并操控这台电脑的桌面",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
