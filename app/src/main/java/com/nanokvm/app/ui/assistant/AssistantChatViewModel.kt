package com.nanokvm.app.ui.assistant

import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 跨进程/重启保存助手 token(单用户会话:服务重启前 App 重启后仍可用)。 */
object AssistSession {
    private const val PREFS = "nanokvm_assist"
    private const val KEY_TOKEN = "token"

    @Volatile
    var appContext: android.content.Context? = null

    fun token(): String? = prefs()?.getString(KEY_TOKEN, null)

    fun save(token: String?) {
        val p = prefs() ?: return
        p.edit().putString(KEY_TOKEN, token).apply()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
}

/** 聊天消息(web assistant.html 的 updateUI 对应物)。 */
data class AssistantMsg(
    val kind: Kind,
    val text: String,
    val imageB64: String? = null, // 每轮截图
) {
    enum class Kind { USER, THOUGHT, ACTION, IMAGE, SYSTEM, ERROR }
}

/** 任务状态机(web: isTaskRunning / task_update status)。 */
enum class TaskState { IDLE, RUNNING, WAITING_USER, PAUSED, DONE, ERROR }

data class AssistantSettings(
    val apiType: String = "DashScope",
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val imgKeepN: Int = 3,
    val maxRounds: Int = 20,
    val initialPrompt: String = "",
)

data class AssistantUiState(
    val messages: List<AssistantMsg> = emptyList(),
    val taskState: TaskState = TaskState.IDLE,
    val serviceUp: Boolean = false,
    val busying: Boolean = false,
    val error: String? = null,
)

/**
 * 设备端智能助手(App 内对话,替代 web 的 http://host:5000 页面)。
 *
 * 服务:`/kvmapp/cua/cua_webapp.py --auth 1`(由 `POST /api/extensions/assistant/start` 拉起)。
 * - 首次 GET / 时服务生成单用户 token(可能内嵌页面,尽力解析;无 token 时按无鉴权访问);
 * - REST:`/settings` GET/POST、`/start_task {task_desc}`、`/pause_task`、
 *   `/resume_task {user_input}`、`/reset_task`、`/get_status`;
 * - 推送:Socket.IO 事件 `task_update`,payload = CUA 每步结果
 *   {status, screenshot_base64, llm_thoughts[], executed_command, notify_input, error_message, log}。
 */
class AssistantChatViewModel(
    private val host: String,
) : ViewModel() {

    companion object {
        private const val TAG = "NanokvmAssistant"
    }

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var authToken: String? = null
    private var socket: Socket? = null
    private var socketConnected = false

    /** 用户最后一次主动连接的时间戳:接管动作只在这个窗口内有效(10s),防后台重连风暴互踢。 */
    @Volatile
    private var takeoverIntentAt = 0L

    private fun effectiveAllow() = System.currentTimeMillis() - takeoverIntentAt < 10_000L

    private fun base() = "http://$host:5000"

    /** 幂等:探测服务 + 拿 token + 连推送 + 读任务状态。
     *  [allowRelease]=true 表示这次连接来自用户主动操作(进入页面/点重连):
     *  遇到"会话被旧实例占用"时允许客户端侧接管(杀旧 cua 起新会话);
     *  后台自动重连一律 false,防止多端互踢。 */
    fun connect(allowRelease: Boolean = false) {
        if (allowRelease) takeoverIntentAt = System.currentTimeMillis()
        viewModelScope.launch {
            _state.value = _state.value.copy(busying = true, error = null)
            try {
                bootstrapToken(effectiveAllow())
                _state.value = _state.value.copy(serviceUp = true)
                connectSocket()
                refreshStatus()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    serviceUp = false,
                    taskState = TaskState.IDLE,
                    error = if ((e.message ?: "").contains("会话被占用")) {
                        "助手会话被其他客户端占用,已尝试接管失败——确认旧会话关闭后点右上角刷新"
                    } else {
                        "助手服务不可达(请先在设备上 安装依赖→启动): ${e.message}"
                    },
                )
            } finally {
                _state.value = _state.value.copy(busying = false)
            }
        }
    }

    private suspend fun bootstrapToken(allowRelease: Boolean) {
        val cached = AssistSession.token()
        if (cached != null) {
            // 单用户会话:服务未重启时,缓存的 token 直接可用。
            authToken = cached
            Log.i(TAG, "assistant using cached token")
            return
        }
        while (true) {
            val body = withContext(Dispatchers.IO) {
                okHttp.newCall(Request.Builder().url("${base()}/").build()).execute().use { it.body?.string().orEmpty() }
            }
            // --auth 模式下首次 GET / 返回页面并生成 token;页面内嵌 const AUTH_TOKEN = "uuid"。
            val m = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").find(body)
            val token = m?.value
            if (token != null) {
                authToken = token
                AssistSession.save(token)
                val hits = body.lineSequence().mapIndexedNotNull { i, l ->
                    if (Regex("AUTH|X-Auth|auth|TOKEN").containsMatchIn(l)) (i + 1).toString() + ": " + l.trim().take(160) else null
                }.take(10).joinToString(" | ")
                Log.i(TAG, "assistant / reachable len=${body.length} token=true lines=[$hits]")
                return
            }
            // 服务在跑但拿不到 token = 单用户会话被占用(旧实例僵死或别人在用)。
            if (!allowRelease) throw IllegalStateException("会话被占用")
            val claimed = CuaSessionGate.releaseStaleSession(host)
            if (claimed != null) {
                // gate 已认领并落缓存:直接使用,进入下一阶段
                authToken = claimed
                Log.i(TAG, "assistant session claimed via gate token=$claimed")
                return
            }
            throw IllegalStateException("会话被占用(释放失败)")
        }
    }

    private fun connectSocket() {
        runCatching { socket?.disconnect() }
        val opts = IO.Options()
        opts.reconnection = true
        opts.reconnectionAttempts = 10
        opts.timeout = 6000
        val headers = HashMap<String, List<String>>()
        authToken?.let { headers["X-Auth-Token"] = listOf(it) }
        if (headers.isNotEmpty()) {
            opts.extraHeaders = headers
        }
        val s = IO.socket(base(), opts)
        s.on(Socket.EVENT_CONNECT) {
            socketConnected = true
            Log.i(TAG, "assistant socketio connected")
        }
        s.on("task_update") { args ->
            val payload = args.firstOrNull() as? JSONObject
            if (payload != null) handleTaskUpdate(payload)
        }
        s.on(Socket.EVENT_DISCONNECT) { socketConnected = false }
        s.on(Socket.EVENT_CONNECT_ERROR) { err ->
            Log.w(TAG, "socketio error: ${err?.firstOrNull()}")
            socketConnected = false
            // 单用户会话被换(服务重启/他人首访)会拒绝连接:清缓存后重试一次重新领取。
            if (AssistSession.token() != null && (err?.firstOrNull()?.toString() ?: "").contains("reject", ignoreCase = true)) {
                AssistSession.save(null)
                authToken = null
                viewModelScope.launch {
                    delay(800)
                    connect(effectiveAllow())
                }
            }
        }
        s.connect()
        socket = s
    }

    private fun handleTaskUpdate(payload: JSONObject) {
        val status = payload.optString("status", "running")
        val thoughts = (payload.opt("llm_thoughts") as? JSONArray)?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }.orEmpty()
        val cmd = payload.optString("executed_command")
        val notify = payload.optString("notify_input")
        val errMsg = payload.optString("error_message")
        val img = payload.optString("screenshot_base64").takeIf { it.isNotBlank() && it != "null" }
        val log = payload.optString("log")

        val list = _state.value.messages.toMutableList()
        thoughts.filter { it.isNotBlank() }.forEach { list += AssistantMsg(AssistantMsg.Kind.THOUGHT, it) }
        if (cmd.isNotBlank()) list += AssistantMsg(AssistantMsg.Kind.ACTION, cmd)
        if (errMsg.isNotBlank()) list += AssistantMsg(AssistantMsg.Kind.ERROR, errMsg)
        if (img != null && img != "0") {
            list += AssistantMsg(AssistantMsg.Kind.IMAGE, "屏幕", img)
        }
        if (notify.isNotBlank()) {
            list += AssistantMsg(AssistantMsg.Kind.SYSTEM, "助手需要你提供: $notify")
        }
        val state = when (status) {
            "running" -> TaskState.RUNNING
            "paused" -> TaskState.PAUSED
            "waiting_for_user" -> TaskState.WAITING_USER
            "done" -> {
                list += AssistantMsg(AssistantMsg.Kind.SYSTEM, "任务完成 ✓")
                TaskState.DONE
            }
            "error" -> {
                list += AssistantMsg(AssistantMsg.Kind.ERROR, log.substringAfterLast("-> ").ifBlank { log })
                TaskState.ERROR
            }
            else -> TaskState.RUNNING
        }
        _state.value = _state.value.copy(messages = list, taskState = state)
    }

    /** 用户发消息:空闲=新任务;运行中/等待=暂停后带输入恢复(web pauseAndSendInput)。 */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _state.value = _state.value.copy(
            messages = _state.value.messages + AssistantMsg(AssistantMsg.Kind.USER, t),
            busying = true,
            error = null,
        )
        viewModelScope.launch {
            try {
                when (_state.value.taskState) {
                    TaskState.IDLE, TaskState.DONE, TaskState.ERROR -> post("/start_task", mapOf("task_desc" to t))
                    TaskState.RUNNING, TaskState.WAITING_USER, TaskState.PAUSED -> {
                        post("/pause_task", emptyMap<String, String>())
                        post("/resume_task", mapOf("user_input" to t))
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "发送失败")
            } finally {
                _state.value = _state.value.copy(busying = false)
            }
        }
    }

    fun pause() {
        viewModelScope.launch { runCatching { post("/pause_task", emptyMap()) } }
    }

    fun reset() {
        viewModelScope.launch {
            runCatching { post("/reset_task", emptyMap()) }
            _state.value = AssistantUiState(serviceUp = true, taskState = TaskState.IDLE)
        }
    }

    fun saveSettings(s: AssistantSettings, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(try {
                post("/settings", mapOf(
                    "api_type" to s.apiType,
                    "api_key" to s.apiKey,
                    "base_url" to s.baseUrl,
                    "model_name" to s.modelName,
                    "img_keep_n" to s.imgKeepN,
                    "max_rounds" to s.maxRounds,
                    "initial_prompt" to s.initialPrompt,
                ))
                null
            } catch (e: Exception) {
                e.message
            })
        }
    }

    fun loadSettings(onDone: (AssistantSettings?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val obj = JSONObject(get("/settings"))
                onDone(
                    AssistantSettings(
                        apiType = obj.optString("api_type", "DashScope"),
                        apiKey = obj.optString("api_key"),
                        baseUrl = obj.optString("base_url"),
                        modelName = obj.optString("model_name"),
                        imgKeepN = obj.optInt("img_keep_n", 3),
                        maxRounds = obj.optInt("max_rounds", 20),
                        initialPrompt = obj.optString("initial_prompt"),
                    ), null,
                )
            } catch (e: Exception) {
                onDone(null, e.message)
            }
        }
    }

    private suspend fun refreshStatus() {
        runCatching {
            val obj = JSONObject(get("/get_status"))
            _state.value = _state.value.copy(
                taskState = if (obj.optBoolean("done", false)) TaskState.DONE else TaskState.IDLE,
            )
        }
    }

    private suspend fun post(path: String, body: Map<String, Any>): JSONObject {
        val req = Request.Builder()
            .url(base() + path)
            .addHeader("Accept", "application/json")
            .header("X-Auth-Token", authToken.orEmpty())
            .post(JSONObject(body).toString().toRequestBody(jsonMedia))
            .build()
        return withContext(Dispatchers.IO) {
            okHttp.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} $path: $text")
                JSONObject(text)
            }
        }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(base() + path)
            .addHeader("Accept", "application/json")
            .header("X-Auth-Token", authToken.orEmpty())
            .build()
        okHttp.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} $path: $text")
            text
        }
    }

    override fun onCleared() {
        runCatching { socket?.disconnect() }
        super.onCleared()
    }

    /** 供图片解码:base64 → bytes */
    fun decodeImage(b64: String): ByteArray? =
        runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
}
