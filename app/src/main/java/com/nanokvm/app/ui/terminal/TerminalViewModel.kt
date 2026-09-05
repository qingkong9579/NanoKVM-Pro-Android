package com.nanokvm.app.ui.terminal

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nanokvm.app.data.api.NanoKvmApi
import com.nanokvm.app.data.net.Tls
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.math.pow

/** 终端启动形态(web `#terminal` 的 query 等价物)。 */
enum class TerminalKind { SHELL, SERIAL, ASSISTANT_INSTALL }

data class TerminalRequest(
    val kind: TerminalKind = TerminalKind.SHELL,
    // serial(web serial-port.tsx)
    val port: String = "/dev/ttyS1",
    val baud: String = "115200",
    val parity: String = "none",   // none|even|odd
    val flow: String = "none",     // none|soft|hard
    val dataBits: String = "8",
    val stopBits: String = "1",
) {
    val picocomCommand: String
        get() = "picocom $port --baud $baud --parity $parity --flow $flow --databits $dataBits --stopbits $stopBits\r"
}

sealed interface TerminalEvent {
    data object Connected : TerminalEvent
    data class Reconnecting(val attempt: Int) : TerminalEvent
    data class AuthRequired(val reason: String) : TerminalEvent
    data class Error(val message: String) : TerminalEvent
}

/**
 * Web terminal session — mirrors web `pages/terminal/index.tsx` + server
 * `vm/terminal.go`:
 *  - REST login (token) → OkHttp WS `/api/vm/terminal` (Cookie nano-kvm-token);
 *  - client → server: binary frame JSON {rows,cols} = resize; text = stdin;
 *  - server → client: binary pty output (UTF-8, chunked → decoded streaming in JS);
 *  - first resize ack triggers serial (`picocom …`) or assistant (`pip install …`) cmd;
 *  - WS 401/403 → SSH Basic dialog (`GET /api/vm/terminal` w/ Basic, token included).
 */
class TerminalViewModel(
    private val host: String,
    private val username: String,
    private val password: String,
    request: TerminalRequest,
) : ViewModel() {

    companion object {
        private const val TAG = "NanokvmTerm"
        private const val MAX_RECONNECT = 8
        private const val BASE_DELAY_MS = 800L

        fun factory(host: String, username: String, password: String, request: TerminalRequest) =
            viewModelFactory {
                initializer { TerminalViewModel(host, username, password, request) }
            }
    }

    val request: TerminalRequest = request

    private val _state = MutableStateFlow<TerminalEvent?>(null)
    val state: StateFlow<TerminalEvent?> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private lateinit var api: NanoKvmApi
    private val okHttp: OkHttpClient = Tls.okHttpBuilder().build()
    private var ws: WebSocket? = null
    private var reconnectAttempts = 0
    private var requestedStop = false
    private var cmdSent = false
    private var retryJob: Job? = null

    /** JS 桥(WebView) */
    private var jsPush: ((String) -> Unit)? = null
    private var jsResize: (() -> Unit)? = null
    private var pageReady = false
    private var lastRows = 24
    private var lastCols = 80
    private var pendingRows = 24
    private var pendingCols = 80
    private var wsOpen = false

    fun attachBridge(onPush: (String) -> Unit, onResize: () -> Unit) {
        jsPush = onPush
        jsResize = onResize
    }

    fun onPageLoaded() {
        pageReady = true
        // ask JS to measure and report size (it will call back onTermSize)
        jsResize?.invoke()
    }

    /** JS 上报的终端尺寸 → pty resize 帧。 */
    fun onTermSize(rows: Int, cols: Int) {
        pendingRows = rows.coerceIn(5, 200)
        pendingCols = cols.coerceIn(10, 400)
        if (pageReady && wsOpen) sendResize(pendingRows, pendingCols)
    }

    fun onTermData(data: String) {
        if (wsOpen) ws?.send(data)
    }

    /** JS 可用后的桥:主线程 evaluate 两个通道。 */
    fun start() {
        requestedStop = false
        retryJob = viewModelScope.launch {
            try {
                api = NanoKvmApi("https://$host", okHttp)
                api.login(username, password)
                openSocket()
            } catch (e: Exception) {
                if (!requestedStop) _state.value = TerminalEvent.Error(e.message ?: "终端登录失败")
            }
        }
    }

    fun stop() {
        requestedStop = true
        retryJob?.cancel()
        ws?.close(1000, "client stop")
        ws = null
        wsOpen = false
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private fun openSocket() {
        val token = api.token ?: return
        val requestBuilder = Request.Builder()
            .url("wss://$host/api/vm/terminal")
            .header("Cookie", "nano-kvm-token=$token")
            .build()
        ws = okHttp.newWebSocket(requestBuilder, listener)
        Log.i(TAG, "terminal WS opening")
    }

    private fun scheduleReconnect(failure: Throwable?, http: Int?) {
        if (requestedStop) return
        if (http == 401 || http == 403) {
            _state.value = TerminalEvent.AuthRequired("终端需要 SSH 权限校验")
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT) {
            _state.value = TerminalEvent.Error(failure?.message ?: "终端连接失败")
            return
        }
        reconnectAttempts++
        _state.value = TerminalEvent.Reconnecting(reconnectAttempts)
        val delayMs = (BASE_DELAY_MS * 2.0.pow(reconnectAttempts - 1)).toLong()
        retryJob = viewModelScope.launch {
            delay(delayMs)
            if (!requestedStop) openSocket()
        }
    }

    /** SSH Basic 校验通过后重开 WS(web:modal submit → authed 翻转 → effect 重建)。 */
    fun retryAfterAuth(user: String, pass: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val err = try {
                api.terminalAuthBasic(user, pass)
                null
            } catch (e: Exception) {
                e.message ?: "SSH 校验失败"
            }
            if (err == null) {
                _state.value = null
                reconnectAttempts = 0
                openSocket()
            }
            onDone(err)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "terminal WS opened (http=${response.code})")
            reconnectAttempts = 0
            wsOpen = true
            _state.value = TerminalEvent.Connected
            main.post { sendResize(pendingRows, pendingCols) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // pty 输出(二进制) — JS 端 TextDecoder 流式解码防多字节切裂
            val b64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            main.post { jsPush?.invoke(b64) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post { jsPush?.invoke(Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "terminal WS closed $code $reason")
            wsOpen = false
            if (!requestedStop) scheduleReconnect(null, code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "terminal WS failure: ${t.message} http=${response?.code}")
            wsOpen = false
            if (!requestedStop) scheduleReconnect(t, response?.code)
        }
    }

    private fun sendResize(rows: Int, cols: Int) {
        if (!wsOpen) return
        lastRows = rows
        lastCols = cols
        // 服务端按“二进制帧=resize JSON”区分 stdin:文本帧会被写入 pty。
        ws?.send(okio.ByteString.of(*"{\"rows\":$rows,\"cols\":$cols}".toByteArray(Charsets.UTF_8)))
        // 首个尺寸确认后注入串口/助手命令(web 300ms 延迟)
        if (!cmdSent) {
            cmdSent = true
            viewModelScope.launch {
                delay(300)
                when (request.kind) {
                    TerminalKind.SERIAL -> ws?.send(request.picocomCommand)
                    TerminalKind.ASSISTANT_INSTALL -> ws?.send("pip install -r /tmp/requirements.txt\r")
                    TerminalKind.SHELL -> Unit
                }
            }
        }
    }
}

/** 跨路由传递的启动参数(与 AppSession 同风格)。 */
object TerminalLauncher {
    @Volatile
    var request: TerminalRequest = TerminalRequest()
}
