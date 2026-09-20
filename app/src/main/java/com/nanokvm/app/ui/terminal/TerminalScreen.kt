package com.nanokvm.app.ui.terminal

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 终端页 — WebView 承载 xterm(assets/terminal),原生管 pty WS。
 * 极简顶栏(标题/状态/退出)+ 全黑沉浸终端区;WS 401/403 → SSH Basic 对话框。
 */
@Composable
fun TerminalScreen(
    request: TerminalRequest,
    host: String,
    username: String,
    password: String,
    onExit: () -> Unit,
) {
    val vm: TerminalViewModel = viewModel(
        key = "term-${request.kind}-${request.port}-${request.baud}",
        factory = TerminalViewModel.factory(host, username, password, request),
    )
    val event by vm.state.collectAsState()
    var authOpen by remember { mutableStateOf(false) }
    var authErr by remember { mutableStateOf<String?>(null) }

    var authUser by remember { mutableStateOf("") }
    var authPass by remember { mutableStateOf("") }

    BackHandler(onBack = { vm.stop(); onExit() })

    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 极简顶栏:标题(按入口类型)+ 状态标签 + 退出;48dp,surface 底 + hairline
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (request.kind) {
                    TerminalKind.SHELL -> "Shell"
                    TerminalKind.SERIAL -> "串口"
                    TerminalKind.ASSISTANT_INSTALL -> "助手安装"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 状态标签(design S10):绿=已连接,黄=重连,红=错误
            val (tagText, tagColor) = when (event) {
                is TerminalEvent.Reconnecting -> "重连中" to MaterialTheme.colorScheme.onSurfaceVariant
                is TerminalEvent.Error -> "错误" to MaterialTheme.colorScheme.error
                is TerminalEvent.AuthRequired -> "需 SSH 校验" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> "已连接" to MaterialTheme.colorScheme.primary
            }
            Text(
                tagText,
                style = MaterialTheme.typography.labelSmall,
                color = tagColor,
                modifier = Modifier
                    .border(1.dp, tagColor.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            IconButton(onClick = { vm.stop(); onExit() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ExitToApp, "退出终端", Modifier.size(20.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // 终端区:全黑沉浸,无内边距/圆角/边框;重连时顶部退避提示条
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            TerminalWebView(vm)
            if (event is TerminalEvent.Reconnecting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(14.dp)
                        .background(Color(0xFF2A2410), RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text("⚠", color = Color(0xFFF2B33D))
                    Text(
                        when (val e = event) {
                            is TerminalEvent.Reconnecting ->
                                "连接断开 · 指数退避第 ${(e as TerminalEvent.Reconnecting).attempt} 次,自动重试中"
                            is TerminalEvent.Error -> "错误:${(e as TerminalEvent.Error).message}"
                            else -> "连接中断"
                        },
                        color = Color(0xFFF2B33D),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (event == null || event is TerminalEvent.Reconnecting) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 3.dp,
                )
            }
        }
    }

    if (event is TerminalEvent.AuthRequired) authOpen = true
    if (authOpen) {
        AlertDialog(
            onDismissRequest = { authOpen = false },
            title = { Text("SSH 权限校验") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("终端需设备 SSH 账号验证(root 或已启用的 SSH 用户)。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = authUser, onValueChange = { authUser = it }, singleLine = true, label = { Text("用户名") })
                    OutlinedTextField(
                        value = authPass,
                        onValueChange = { authPass = it },
                        singleLine = true,
                        label = { Text("密码") },
                    )
                    authErr?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    authErr = null
                    vm.retryAfterAuth(authUser.trim(), authPass) { err ->
                        if (err == null) authOpen = false else authErr = err
                    }
                }) { Text("验证并连接") }
            },
            dismissButton = { TextButton(onClick = { authOpen = false }) { Text("取消") } },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
private fun TerminalWebView(vm: TerminalViewModel) {
    val ctx = LocalContext.current.applicationContext
    val bridge = remember {
        object {
            private var wv: WebView? = null

            @JavascriptInterface
            fun onData(data: String) = vm.onTermData(data)

            @JavascriptInterface
            fun onSize(rows: Int, cols: Int) = vm.onTermSize(rows, cols)

            @JavascriptInterface
            fun showIme() {
                wv?.let { v ->
                    val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }

            fun attach(view: WebView) {
                wv = view
            }
        }
    }
    AndroidView(
        factory = { c ->
            val wv = WebView(c)
            wv.isFocusableInTouchMode = true
            wv.requestFocus()
            wv.apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = true
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        vm.onPageLoaded()
                    }
                }
                addJavascriptInterface(bridge, "Android")
            }
            vm.attachBridge(
                onPush = { b64 -> wv.post { wv.evaluateJavascript("nanoTerm.push('$b64')", null) } },
                onResize = { wv.post { wv.evaluateJavascript("nanoTerm.resize()", null) } },
            )
            bridge.attach(wv)
            wv.loadUrl("file:///android_asset/terminal/index.html")
            wv
        },
        modifier = Modifier.fillMaxSize(),
        update = {},
    )
}
