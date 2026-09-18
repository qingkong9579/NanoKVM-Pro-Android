package com.nanokvm.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
import com.nanokvm.app.ui.theme.GlassShapes
import com.nanokvm.app.ui.theme.GlassPanel
import com.nanokvm.app.ui.theme.GlassTokens
import com.nanokvm.app.ui.theme.OneKvmColors
import com.nanokvm.app.ui.theme.RefractionHighlight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

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
    val hazeState = remember { HazeState() }

    // 点阵底纹必须铺满整屏(edge-to-edge 含系统栏):内边距只会内缩画布、
    // 露出窗口底色形成四周一圈;呼吸边距改由卡片外层 padding 承担(对应 web 的 p-4)。
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // 背景层 = haze 采样源(只含底纹;磨砂卡片与其同级,避免自引用)
        Box(Modifier.matchParentSize().haze(hazeState)) {
            DotGridBackground(Modifier.fillMaxSize())
        }
        // 全屏磨砂玻璃(design-v2):整屏一层玻璃,表单居中其上。
        GlassPanel(
            isDark = isDark,
            shape = RectangleShape,
            modifier = Modifier.matchParentSize(),
            tint = if (isDark) Color(0xFF0E0F11) else Color.White,
            tintAlphaOverride = if (isDark) 0.42f else 0.62f,
            hazeState = hazeState,
        ) {
            RefractionHighlight(Modifier.align(Alignment.TopCenter), isDark = isDark)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            // 品牌:居中竖排(经典登录构图);已保存 chip 显示当前记忆状态。
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(OneKvmColors.NearBlack, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = OneKvmColors.SuccessBright,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "NanoKVM Pro",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "局域网 · 远程桌面",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.host.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .background(GlassTokens.keyBg(isDark), RoundedCornerShape(999.dp))
                            .border(1.dp, GlassTokens.hairline(isDark), RoundedCornerShape(999.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(OneKvmColors.SuccessBright),
                        )
                        Text(
                            "已保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(GlassTokens.hairline(isDark)),
            )

            // Credential fields: 外浮标签 + 玻璃容器(无描边),48dp 视觉、56dp 触达。
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassField(
                    label = "主机地址",
                    isDark = isDark,
                    value = state.host,
                    onValueChange = viewModel::onHostChange,
                    enabled = fieldsEnabled,
                    placeholder = "192.168.5.47",
                    leading = Icons.Outlined.Computer,
                )
                GlassField(
                    label = "用户名",
                    isDark = isDark,
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    enabled = fieldsEnabled,
                    placeholder = "admin",
                    leading = Icons.Outlined.Person,
                )
                GlassField(
                    label = "密码",
                    isDark = isDark,
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    enabled = fieldsEnabled,
                    placeholder = "••••••••",
                    leading = Icons.Outlined.Lock,
                    visualTransformation = if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = { viewModel.togglePasswordVisibility() }) {
                            Icon(
                                if (state.showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (state.showPassword) "隐藏密码" else "显示密码",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
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
                    Text(if (error != null) "重试连接" else "连接")
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
        // 右上角日夜切换(置于玻璃之上)
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
    }
}

/** 外浮标签玻璃输入框:标签在框外,容器为玻璃填充、无边框线。 */
@Composable
private fun GlassField(
    label: String,
    isDark: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    leading: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            placeholder = {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leadingIcon = {
                Icon(leading, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = trailing,
            visualTransformation = visualTransformation,
            singleLine = true,
            shape = GlassShapes.input,
            colors = OutlinedTextFieldDefaults.colors(
                // 显式配色:深色下文字/占位符/光标不再依赖默认值(曾出现灰字灰底)
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.08f else 0.06f),
                unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.04f else 0.03f),
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
                focusedBorderColor = if (isDark) Color.White.copy(alpha = 0.30f) else Color.Black.copy(alpha = 0.28f),
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
