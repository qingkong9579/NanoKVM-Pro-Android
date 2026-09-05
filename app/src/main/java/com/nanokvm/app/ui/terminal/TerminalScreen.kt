package com.nanokvm.app.ui.terminal

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
        // 极简顶栏:左标题(终端 · host)、右退出;48dp,surface 底 + hairline
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
                if (host.isBlank()) "终端" else "终端 · $host",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when (event) {
                is TerminalEvent.Reconnecting -> Text(
                    "重连中 ${(event as TerminalEvent.Reconnecting).attempt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is TerminalEvent.Error -> Text(
                    "连接错误",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                is TerminalEvent.AuthRequired -> Text(
                    "需 SSH 校验",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {}
            }
            IconButton(onClick = { vm.stop(); onExit() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ExitToApp, "退出终端", Modifier.size(20.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // 终端区:全黑沉浸,无内边距/圆角/边框
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            TerminalWebView(vm)
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
