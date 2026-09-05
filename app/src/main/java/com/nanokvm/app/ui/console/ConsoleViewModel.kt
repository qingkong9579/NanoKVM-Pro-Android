package com.nanokvm.app.ui.console

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanokvm.app.data.api.HidMouseMode
import com.nanokvm.app.data.api.NanoKvmApi
import com.nanokvm.app.data.api.StreamMode
import com.nanokvm.app.data.hid.HidKeymap
import com.nanokvm.app.data.hid.KeyboardCodec
import com.nanokvm.app.data.hid.MouseCodec
import com.nanokvm.app.session.SessionController
import com.nanokvm.app.session.SessionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import okhttp3.OkHttpClient
import kotlin.math.abs

/** Console phase — drives the loading/no-signal/error overlays. */
enum class Phase { IDLE, CONNECTING, STREAMING, ERROR }

data class ConsoleUiState(
    val phase: Phase = Phase.IDLE,
    val stageText: String = "连接中…",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val reconnecting: Int? = null,
    val error: String? = null,
    val vkbVisible: Boolean = false,
    val mouseMode: String = HidMouseMode.ABSOLUTE,
    val streamMode: String = StreamMode.H264_DIRECT,
    val settingsSheetOpen: Boolean = false,
    val toolsSheetOpen: Boolean = false,
    val statsVisible: Boolean = false,
    val videoRotation: Int = 0,       // 0/90/180/270 (web rotation menu; client-side view)
    val videoScale: Float = 1f,       // 0.5..2 zoom (web scale menu; client-side view)
    val wheelDir: Int = 1,            // 滚轮方向乘数 (web direction ±1)
) {
    val videoFormatKnown: Boolean get() = videoWidth > 0 && videoHeight > 0
}

/** Live streaming stats for the One-KVM / NanoKVM-style diagnosis panel. */
data class StatsUi(
    val fps: Float = 0f,              // 实时帧率
    val bitrateKbps: Float = 0f,      // 码率 (kbps)
    val jitterMs: Float = 0f,         // 抖动缓冲: Direct=解码队列积压估计; WebRTC=jitter buffer 真值
    val decodeMs: Float = 0f,         // 解码延迟 (Direct MediaCodec 实测; WebRTC 不可测 → 不显示)
    val rttMs: Float = 0f,            // ICE RTT (WebRTC: candidate-pair; Direct 无 → 不显示)
    val packetsLost: Long = 0,        // 累计丢包 (WebRTC inbound-rtp; Direct 无 → 不显示)
    val totalMs: Int = 0,             // 总测延迟
    val codec: String = "H.264",      // 编码格式
    val transport: String = "Direct", // 连接协议
    val reconnectCount: Int = 0,
    val historyFps: List<Float> = emptyList(),
    val historyKbps: List<Float> = emptyList(),
    val historyMs: List<Float> = emptyList(),
)

/**
 * Console state + HID host. The gestures layer reports normalized (0..1) touch
 * points over the letterboxed video rect; the HID host converts them to absolute
 * device coordinates and forwards every change as a full 6-byte report (matching
 * the web: buttons are re-stated with each move).
 */
class ConsoleViewModel(
    host: String,
    private val username: String,
    private val password: String,
    okHttp: OkHttpClient,
    appContext: android.content.Context,
) : ViewModel() {

    companion object {
        private const val MAX_HISTORY = 40

        fun factory(
            host: String,
            username: String,
            password: String,
            okHttp: OkHttpClient,
            appContext: android.content.Context,
        ): androidx.lifecycle.ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ConsoleViewModel(host, username, password, okHttp, appContext) }
            }
    }

    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(StatsUi())
    val stats: StateFlow<StatsUi> = _stats.asStateFlow()
    private var statsJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var reconnectTicks = 0

    private val session = SessionController(viewModelScope, host, username, password, appContext, okHttp) { event ->
        handleSessionEvent(event)
    }

    /** Wired to the direct-mode TextureView's SurfaceTexture once it's ready. */
    fun bindTexture(st: android.graphics.SurfaceTexture) {
        session.surfaceProvider = { Surface(st) }
    }

    /** TextureView leaving the composition — detach the decoder surface. */
    fun unbindTexture() {
        session.surfaceProvider = null
    }

    /** Wired to the WebRTC renderer view once it's ready (webrtc mode). */
    fun bindRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        session.bindRenderer(renderer)
    }

    /** Renderer leaving the composition — detach so frames do not leak into it. */
    fun unbindRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        session.unbindRenderer(renderer)
    }

    // ---- lifecycle ----
    fun start() {
        val prev = _state.value
        if (prev.phase == Phase.CONNECTING || prev.phase == Phase.STREAMING) return
        // Preserve the user's stream/mouse settings across reconnects.
        _state.value = prev.copy(
            phase = Phase.CONNECTING,
            stageText = "连接中…",
            reconnecting = null,
            error = null,
        )
        session.connect(prev.streamMode, prev.mouseMode)
        startStatsTicker()
    }

    fun disconnect() {
        statsJob?.cancel()
        session.disconnect()
        _state.value = _state.value.copy(phase = Phase.IDLE, reconnecting = null)
    }

    /** Toggles the One-KVM-style performance overlay. */
    fun toggleStats() {
        _state.value = _state.value.copy(statsVisible = !_state.value.statsVisible)
    }

    /** 1 Hz sampling of transport counters → fps / bitrate / latency + history curves. */
    private fun startStatsTicker() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            var prevFrames = 0L
            var prevBytes = 0L
            var prevAt = System.currentTimeMillis()
            val hFps = ArrayDeque<Float>()
            val hKbps = ArrayDeque<Float>()
            val hMs = ArrayDeque<Float>()
            while (true) {
                delay(1000)
                val t = session.videoStats()
                val now = System.currentTimeMillis()
                val dt = ((now - prevAt).coerceAtLeast(1L)) / 1000.0
                val webrtc = session.isWebRtc
                val fps: Float
                val kbps: Float
                val jitter: Float
                val decode: Float
                val rtt: Float
                val lost: Long
                if (webrtc) {
                    // RTCStatsReport polled at ~1 Hz by the transport — no deltas here.
                    fps = t.fps
                    kbps = t.kbps
                    jitter = t.jitterMs
                    decode = 0f
                    rtt = t.rttMs
                    lost = t.packetsLost
                } else {
                    val df = (t.frames - prevFrames).coerceAtLeast(0L)
                    val db = (t.bytes - prevBytes).coerceAtLeast(0L)
                    fps = if (dt > 0) (df / dt).toFloat() else 0f
                    kbps = (db * 8.0 / 1_000.0 / dt).toFloat()
                    jitter = if (fps > 1f) (t.queueSize * (1000f / fps)).coerceAtMost(500f) else 0f
                    decode = session.decodeLatencyMs().toFloat()
                    rtt = 0f
                    lost = 0L
                    prevFrames = t.frames
                    prevBytes = t.bytes
                    prevAt = now
                }
                // End-to-end estimate: buffering + decode + (network round-trip)/2 +
                // one frame interval. WebRTC decode happens inside the SDK (no probe);
                // its decode share is folded into the jitter-buffer figure.
                val interval = if (fps > 1f) 1000f / fps else 0f
                val total = (jitter + decode + rtt / 2f + interval).toInt()
                val mode = _state.value.streamMode
                val codec = if (mode.startsWith("h265")) "H.265" else "H.264"
                val transport = if (webrtc) "WebRTC" else "Direct"

                hFps.addLast(fps)
                hKbps.addLast(kbps)
                hMs.addLast(total.toFloat())
                while (hFps.size > MAX_HISTORY) hFps.removeFirst()
                while (hKbps.size > MAX_HISTORY) hKbps.removeFirst()
                while (hMs.size > MAX_HISTORY) hMs.removeFirst()

                _stats.value = StatsUi(
                    fps = fps,
                    bitrateKbps = kbps,
                    jitterMs = jitter,
                    decodeMs = decode,
                    rttMs = rtt,
                    packetsLost = lost,
                    totalMs = total,
                    codec = codec,
                    transport = transport,
                    reconnectCount = reconnectTicks,
                    historyFps = hFps.toList(),
                    historyKbps = hKbps.toList(),
                    historyMs = hMs.toList(),
                )
            }
        }
    }

    /** Re-applies current settings (stream/mouse mode) with a fresh connection. */
    fun reconnect() {
        disconnect()
        start()
    }

    override fun onCleared() {
        session.disconnect()
        super.onCleared()
    }

    // ---- settings ----
    fun setStreamMode(mode: String) {
        _state.value = _state.value.copy(streamMode = mode)
    }

    fun setMouseMode(mode: String) {
        if (_state.value.mouseMode == mode) return
        _state.value = _state.value.copy(mouseMode = mode)
        // 相对模式走相对 HID 报文(hidg1),固件侧按会话重建(web:切换后 client 重连)。
        if (mode == HidMouseMode.RELATIVE && session.isActive) session.reconnectHid()
    }

    fun setVideoRotation(rotation: Int) {
        _state.value = _state.value.copy(videoRotation = rotation)
    }

    fun setVideoScale(scale: Float) {
        _state.value = _state.value.copy(videoScale = scale.coerceIn(0.5f, 2f))
    }

    fun setWheelDir(dir: Int) {
        _state.value = _state.value.copy(wheelDir = if (dir < 0) -1 else 1)
    }

    fun toggleToolsSheet() {
        _state.value = _state.value.copy(toolsSheetOpen = !_state.value.toolsSheetOpen)
    }

    fun toggleSettingsSheet() {
        _state.value = _state.value.copy(settingsSheetOpen = !_state.value.settingsSheetOpen)
    }

    fun toggleVirtualKeyboard() {
        _state.value = _state.value.copy(vkbVisible = !_state.value.vkbVisible)
    }

    // ---- virtual keyboard callbacks ----
    fun vkbKeyDown(key: KKey.HID) = hidHost?.pressHid(key.keyId(), key.usage)
    fun vkbKeyUp(key: KKey.HID) = hidHost?.releaseHid(key.keyId())
    fun vkbModifierToggle(key: KKey.Mod) = hidHost?.toggleModifier(key.keyId(), key.bit)
    fun vkbAction(action: ActionKind) = hidHost?.action(action)

    // ---- physical keyboard (Android KeyEvent) ----
    fun physicalKeyDown(keyCode: Int): Boolean {
        val bit = HidKeymap.modifierBit(keyCode)
        val usage = HidKeymap.hidUsage(keyCode)
        if (bit != 0) return hidHost?.pressModifier(keyCode, bit) ?: false
        if (usage != null) return hidHost?.pressHid(keyCode, usage) ?: false
        return false
    }

    fun physicalKeyUp(keyCode: Int): Boolean {
        val bit = HidKeymap.modifierBit(keyCode)
        if (bit != 0) return hidHost?.releaseModifier(keyCode, bit) ?: false
        val usage = HidKeymap.hidUsage(keyCode)
        if (usage != null) return hidHost?.releaseHid(keyCode) ?: false
        return false
    }

    // ---- touch mouse (normalized over the video rect) ----
    fun mouseMove(nx: Float, ny: Float) = hidHost?.mouseMove(nx, ny)
    fun mouseButton(button: Int, down: Boolean) = hidHost?.mouseButton(button, down)
    fun mouseClick(button: Int) = hidHost?.mouseClick(button)
    fun mouseWheel(ticks: Int, dir: Int = _state.value.wheelDir) = hidHost?.mouseWheel(ticks * dir)

    // ---- console tools (web menu parity) ----
    /** REST surface shared with the tools sheet (token = session JWT). */
    val toolsRest: NanoKvmApi get() = session.restApi

    /** 挂载/卸载镜像(web:断输入 WS→mount→重连,USB gadget 重启)。 */
    fun mountImage(file: String, cdrom: Boolean, readOnly: Boolean, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(try {
                session.withHidCycle { session.restApi.storageMount(file, cdrom, readOnly) }
                null
            } catch (e: Exception) {
                e.message ?: "mount failed"
            })
        }
    }

    /** 应用流参数并重启视频传输(web:参数在下次建流生效;此处立即重建)。 */
    fun applyStreamParam(
        rateControl: String? = null,
        bitrateKbps: Int? = null,
        gop: Int? = null,
        fps: Int? = null,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(try {
                session.applyStreamParams(rateControl, bitrateKbps, gop, fps)
                null
            } catch (e: Exception) {
                e.message ?: "设置失败"
            })
        }
    }

    // ---- internals ----

    private var hidHost: HidHost? = null
        get() {
            if (field == null) field = HidHost(session) { _state.value.mouseMode }
            return field
        }

    // ---- relative mouse & quick combos (web parity) ----
    fun mouseRelativeMove(dxPx: Float, dyPx: Float) = hidHost?.mouseRelativeMove(dxPx, dyPx)
    fun sendCombo(modifiers: Int, usages: List<Int>) = hidHost?.combo(modifiers, usages)

    /** 设备端逐字符粘贴(web paste:POST /api/hid/paste,≤1024)。 */
    fun pasteRemote(text: String, onDone: (String?) -> Unit) {
        if (text.isBlank()) return
        viewModelScope.launch {
            onDone(try {
                session.restApi.hidPaste(text.take(1024))
                null
            } catch (e: Exception) {
                e.message ?: "paste failed"
            })
        }
    }

    /** 重置 USB HID(web reset-hid:全释放→断输入 WS→POST /api/hid/reset→重连)。 */
    fun resetHidDevice(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(try {
                session.clearKeyboard()
                session.withHidCycle { session.restApi.hidReset() }
                null
            } catch (e: Exception) {
                e.message ?: "reset hid failed"
            })
        }
    }

    private fun handleSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Authenticating -> _state.value = _state.value.copy(stageText = "正在鉴权…")
            is SessionEvent.Configured -> _state.value = _state.value.copy(stageText = when (event.what) {
                "authenticated" -> "已登录"
                else -> "配置视频流…"
            })
            is SessionEvent.StreamConnected -> {
                reconnectTicks = 0
                _state.value = _state.value.copy(stageText = "等待首帧…", reconnecting = null)
            }
            is SessionEvent.FormatChanged -> _state.value = _state.value.copy(
                videoWidth = event.width,
                videoHeight = event.height,
                stageText = "已连接",
                phase = Phase.STREAMING,
            )
            is SessionEvent.Reconnecting -> {
                reconnectTicks++
                _state.value = _state.value.copy(reconnecting = event.attempt)
            }
            is SessionEvent.AuthenticationFailed -> _state.value = _state.value.copy(
                phase = Phase.ERROR,
                error = "认证失败: ${event.reason}",
            )
            is SessionEvent.Error -> _state.value = _state.value.copy(phase = Phase.ERROR, error = event.message)
        }
    }
}

/** Stable identity for any virtual keyboard key (used for the rollover map). */
private fun KKey.keyId(): Any = when (this) {
    is KKey.HID -> "vkb-hid:$usage"
    is KKey.Mod -> "vkb-mod:$bit"
    is KKey.Action -> "vkb-action:$action"
}

/**
 * HID interaction host — owns keyboard rollover + mouse state and forwards
 * keyboard/mouse frames to the session. Frame type follows the client-side mouse
 * mode (web: absolute = 6-byte hidg2 reports, relative = 4-byte hidg1 reports),
 * so every report goes out in the active mode's format.
 */
private class HidHost(
    private val session: SessionController,
    private val mouseMode: () -> String,
) {
    private val kb = KeyboardCodec()
    private val stickyMods = mutableSetOf<Any>()
    private val heldPhysicalMods = mutableMapOf<Int, Int>() // keyCode -> bit

    private var buttons = 0
    private var lastX = 0
    private var lastY = 0
    private var relAccX = 0f
    private var relAccY = 0f

    private val isRelative: Boolean get() = mouseMode() == HidMouseMode.RELATIVE

    fun pressHid(id: Any, usage: Int): Boolean {
        session.sendKeyboard(kb.keyDown(id, 0, usage))
        return true
    }

    fun releaseHid(id: Any): Boolean {
        session.sendKeyboard(kb.keyUp(id))
        return true
    }

    fun toggleModifier(id: Any, bit: Int): Boolean {
        if (!stickyMods.remove(id)) {
            stickyMods += id
            session.sendKeyboard(kb.keyDown(id, bit, null))
        } else {
            session.sendKeyboard(kb.modifierUp(bit))
        }
        return true
    }

    fun pressModifier(keyCode: Int, bit: Int): Boolean {
        heldPhysicalMods[keyCode] = bit
        session.sendKeyboard(kb.keyDown("phys-mod:$keyCode", bit, null))
        return true
    }

    fun releaseModifier(keyCode: Int, bit: Int): Boolean {
        heldPhysicalMods.remove(keyCode)
        session.sendKeyboard(kb.modifierUp(bit))
        return true
    }

    fun action(kind: ActionKind): Boolean {
        val usage = when (kind) {
            ActionKind.BACKSPACE -> HidKeymap.HID_BACKSPACE
            ActionKind.ENTER -> HidKeymap.HID_ENTER
            ActionKind.TAB -> HidKeymap.HID_TAB
            ActionKind.ESC -> HidKeymap.HID_ESCAPE
            ActionKind.DELETE -> HidKeymap.HID_DELETE
            ActionKind.ARROW_LEFT -> HidKeymap.HID_ARROW_LEFT
            ActionKind.ARROW_DOWN -> HidKeymap.HID_ARROW_DOWN
            ActionKind.ARROW_UP -> HidKeymap.HID_ARROW_UP
            ActionKind.ARROW_RIGHT -> HidKeymap.HID_ARROW_RIGHT
            ActionKind.CLEAR -> {
                session.sendKeyboard(kb.reset())
                return true
            }
        }
        val id = "action:$usage"
        session.sendKeyboard(kb.keyDown(id, 0, usage))
        session.sendKeyboard(kb.keyUp(id))
        return true
    }

    /** 快捷键组合:修饰键 + 1..6 键 usage,整体按下后整体释放(web shortcut replay)。 */
    fun combo(modifiers: Int, usages: List<Int>) {
        for (b in 0..7) {
            val bit = 1 shl b
            if (modifiers and bit != 0) kb.keyDown("combo-mod:$bit", bit, null).let { session.sendKeyboard(it) }
        }
        usages.forEachIndexed { i, usage ->
            kb.keyDown("combo-key:$i", 0, usage).let { session.sendKeyboard(it) }
        }
        session.sendKeyboard(kb.reset())
    }

    /** Absolute move (absolute mode only). */
    fun mouseMove(nx: Float, ny: Float) {
        if (isRelative) return
        val (x, y) = MouseCodec.normalize(nx, ny)
        lastX = x
        lastY = y
        session.sendMouse(MouseCodec.absolute(buttons, x, y, 0))
    }

    /**
     * Relative move — touch drag deltas in px, web-style: small moves (< 10px)
     * are doubled, deltas accumulate to avoid dropping sub-pixel drift, each frame
     * clamps to ±127.
     */
    fun mouseRelativeMove(dxPx: Float, dyPx: Float) {
        if (!isRelative) return
        var dx = dxPx
        var dy = dyPx
        if (abs(dx) < 10f && abs(dy) < 10f) {
            dx *= 2f
            dy *= 2f
        }
        relAccX += dx
        relAccY += dy
        val ix = relAccX.toInt().coerceIn(-127, 127)
        val iy = relAccY.toInt().coerceIn(-127, 127)
        if (ix == 0 && iy == 0) return
        relAccX -= ix
        relAccY -= iy
        session.sendMouse(MouseCodec.relative(buttons, ix, iy, 0))
    }

    fun mouseButton(button: Int, down: Boolean) {
        buttons = if (down) buttons or button else buttons and button.inv()
        sendMouseFrame(wheel = 0)
    }

    fun mouseClick(button: Int) {
        mouseButton(button, true)
        mouseButton(button, false)
    }

    /** wheel: signed tick (-1 up / +1 down by client convention). */
    fun mouseWheel(ticks: Int) {
        val t = ticks.coerceIn(-127, 127)
        sendMouseFrame(wheel = t)
        sendMouseFrame(wheel = 0)
    }

    private fun sendMouseFrame(wheel: Int) {
        if (isRelative) {
            session.sendMouse(MouseCodec.relative(buttons, 0, 0, wheel))
        } else {
            session.sendMouse(MouseCodec.absolute(buttons, lastX, lastY, wheel))
        }
    }
}