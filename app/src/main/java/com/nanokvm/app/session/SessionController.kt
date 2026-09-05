package com.nanokvm.app.session

import android.content.Context
import android.view.Surface
import com.nanokvm.app.data.api.ApiException
import com.nanokvm.app.data.api.HidMouseMode
import com.nanokvm.app.data.api.NanoKvmApi
import com.nanokvm.app.data.ws.HidWebSocket
import com.nanokvm.app.data.ws.StreamFrameParser.VideoFrame
import com.nanokvm.app.data.ws.StreamWebSocket
import com.nanokvm.app.data.ws.WebRtcVideoSource
import com.nanokvm.app.media.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.webrtc.SurfaceViewRenderer

/**
 * Session coordinator — the connection state-machine glue.
 *
 * Sequence (mirrors the web client): login → `POST /api/stream/mode` (+ plane
 * control/quality/gop/fps) → `POST /api/hid/mode` → open the transport: either the
 * direct Annex-B WebSocket + MediaCodec, or the WebRTC signaling socket + Pion
 * peer connection (`streamMode` suffix decides). HID (input) is transport-agnostic.
 * The coordinator owns the REST api, both sockets/transports and the decoder; the
 * UI layer only talks to [SessionEvent]s and the input passthroughs.
 *
 * Reconnects happen at the transport level (exponential backoff, capped). A session
 * stays live across them; only a failed login or exhausted retries surface an
 * error/authentication event to the UI.
 */
class SessionController(
    private val scope: CoroutineScope,
    private val host: String,
    private val username: String,
    private val password: String,
    private val appContext: Context,
    private val okHttp: OkHttpClient,
    private val onEvent: (SessionEvent) -> Unit,
) {
    private val api = NanoKvmApi("https://$host", okHttp)

    /**
     * REST surface for the console tools (power/storage/script/wol/…). Shares the
     * session's JWT; the tools layer must run calls off the main thread.
     */
    val restApi: NanoKvmApi get() = api

    /** Dispatches session events to the UI on the main (viewModel) scope. */
    private fun emit(event: SessionEvent) {
        scope.launch { onEvent(event) }
    }

    private val codec = VideoDecoder(
        surfaceProvider = { surfaceProvider?.invoke() },
        onFormatChanged = { w, h -> emit(SessionEvent.FormatChanged(w, h)) },
    )

    private var streamSocket: StreamWebSocket? = null
    private var hidSocket: HidWebSocket? = null

    /** Active WebRTC transport (null while in direct mode). */
    private var webrtcSource: WebRtcVideoSource? = null
    private var connectJob: Job? = null

    @Volatile
    private var active = false
    @Volatile
    private var isWebrtc = false
    @Volatile
    private var currentHevc = false

    /** Set by the UI once the video SurfaceView is ready (direct mode). */
    @Volatile
    var surfaceProvider: (() -> Surface?)? = null

    /** Set by the UI once the WebRTC SurfaceViewRenderer is ready (webrtc mode). */
    @Volatile
    var webrtcRendererProvider: (() -> SurfaceViewRenderer?)? = null

    val isActive: Boolean get() = active

    /** Direct-mode decoder telemetry access for the stats overlay. */
    val decoder: VideoDecoder get() = codec

    /** True while the current transport is WebRTC. */
    val isWebRtc: Boolean get() = isWebrtc

    /** Unified counters for the stats overlay — routed by transport. */
    fun videoStats(): SessionVideoStats {
        val s = webrtcSource ?: return codec.snapshot().let {
            SessionVideoStats(frames = it.frames, bytes = it.bytes, queueSize = it.queueSize)
        }
        return SessionVideoStats(
            frames = 0,
            bytes = 0,
            queueSize = 0,
            jitterMs = s.jitterBufferMs,
            rttMs = s.iceRttMs,
            fps = s.fps,
            kbps = s.bitrateKbps,
            packetsLost = s.packetsLost,
        )
    }

    /** Decode-loop latency, ms (direct only; WebRTC decoding is inside the SDK). */
    fun decodeLatencyMs(): Double = if (isWebrtc) 0.0 else codec.decodeLatencyMs()

    // ---- stream parameters (persist in-process; connect() replays them) ----
    private val streamParams = StreamParams()

    /**
     * 应用流参数并重启视频传输,使编码器以新参数重新初始化。
     * 固件对 quality/bitrate 只写内存字段、运行中的编码器不读;rate-control/GOP/FPS
     * 有原生 setter(实时),重启统一保证全部生效。返回 null=成功 / 错误消息。
     */
    suspend fun applyStreamParams(
        rateControl: String? = null,
        bitrateKbps: Int? = null,
        gop: Int? = null,
        fps: Int? = null,
    ): String? {
        try {
            if (rateControl != null && rateControl != streamParams.rateControl) {
                api.setRateControl(rateControl)
                streamParams.rateControl = rateControl
            }
            if (bitrateKbps != null && bitrateKbps != streamParams.bitrateKbps) {
                api.setQuality(bitrateKbps)
                streamParams.bitrateKbps = bitrateKbps
            }
            if (gop != null && gop != streamParams.gop) {
                api.setGop(gop)
                streamParams.gop = gop
            }
            if (fps != null && fps != streamParams.fps) {
                api.setFps(fps)
                streamParams.fps = fps
            }
            restartVideoTransport()
            return null
        } catch (e: Exception) {
            return e.message ?: "设置失败"
        }
    }

    private suspend fun restartVideoTransport() {
        if (!active) return
        val codecName = if (currentHevc) "h265" else "h264"
        when {
            isWebrtc -> {
                runCatching { webrtcSource?.stop() }
                webrtcSource = WebRtcVideoSource(
                    scope, okHttp, host, codecName, appContext, { api.token },
                    { webrtcRendererProvider?.invoke() }, webrtcCallbacks,
                ).also { it.start() }
            }
            else -> {
                codec.start(currentHevc)
                runCatching { streamSocket?.stop() }
                streamSocket = StreamWebSocket(
                    scope, okHttp, host, codecName, { api.token }, streamCallbacks,
                ).also { it.connect() }
            }
        }
    }

    /** WebRTC renderer lifecycle (UI side). */
    fun bindRenderer(renderer: SurfaceViewRenderer) {
        webrtcRendererProvider = { renderer }
        webrtcSource?.attachRenderer(renderer)
    }

    fun unbindRenderer(renderer: SurfaceViewRenderer) {
        if (webrtcRendererProvider?.invoke() === renderer) webrtcRendererProvider = null
        webrtcSource?.detachRenderer(renderer)
    }

    fun connect(streamMode: String, mouseMode: String = HidMouseMode.ABSOLUTE) {
        disconnect()
        active = true
        isWebrtc = streamMode.endsWith("webrtc")
        currentHevc = streamMode.startsWith("h265")
        connectJob = scope.launch {
            emit(SessionEvent.Authenticating)
            try {
                val token = api.login(username, password)
                require(token.isNotEmpty()) { "empty token" }
                emit(SessionEvent.Configured("authenticated"))

                // Mode must precede opening the matching route or the streamer loops
                // forever on a type mismatch (the classic permanent black screen).
                configureStream(streamMode)
                emit(SessionEvent.Configured(streamMode))

                // NOTE: mouse absolute/relative is a client-side preference (the web's
                // mouse.ts never calls the server). DO NOT post it to /api/hid/mode —
                // that endpoint switches the USB gadget profile (normal/hid-only).
                openVideoTransport()
                hidSocket = HidWebSocket(scope, okHttp, host, { api.token }, hidCallbacks).also { it.connect() }
            } catch (e: ApiException) {
                active = false
                emit(SessionEvent.AuthenticationFailed(e.message ?: "auth failed"))
            } catch (e: Exception) {
                active = false
                emit(SessionEvent.Error(e.message ?: "connect failed"))
            }
        }
    }

    /** Applies mode + the user's current stream parameters (defaults until changed). */
    private suspend fun configureStream(streamMode: String) {
        api.setStreamMode(streamMode)
        api.setRateControl(streamParams.rateControl)
        api.setQuality(streamParams.bitrateKbps)
        api.setGop(streamParams.gop)
        api.setFps(streamParams.fps)
    }

    private fun openVideoTransport() {
        if (isWebrtc) {
            webrtcSource = WebRtcVideoSource(
                scope = scope,
                okHttp = okHttp,
                host = host,
                codecName = if (currentHevc) "h265" else "h264",
                appContext = appContext,
                token = { api.token },
                rendererProvider = { webrtcRendererProvider?.invoke() },
                callbacks = webrtcCallbacks,
            ).also { it.start() }
        } else {
            codec.start(currentHevc)
            streamSocket = StreamWebSocket(
                scope, okHttp, host, if (currentHevc) "h265" else "h264", { api.token }, streamCallbacks,
            ).also { it.connect() }
        }
    }

    fun disconnect() {
        active = false
        connectJob?.cancel()
        runCatching { streamSocket?.stop() }
        streamSocket = null
        runCatching { webrtcSource?.stop() }
        webrtcSource = null
        runCatching { hidSocket?.stop() }
        hidSocket = null
        runCatching { codec.stop() }
    }

    // ---- input passthrough ----
    fun sendKeyboard(report: ByteArray) {
        if (report.isNotEmpty()) android.util.Log.i("NanokvmHid", "kbd ${report.joinToString("") { String.format("%02x", it) }}")
        hidSocket?.sendKeyboard(report)
    }

    fun sendMouse(report: ByteArray) {
        if (report.isNotEmpty()) android.util.Log.i("NanokvmHid", "mouse ${report.joinToString("") { String.format("%02x", it) }}")
        hidSocket?.sendMouse(report)
    }

    /** Clears every key (empty 8-byte report) — unsuck stuck modifiers. */
    fun clearKeyboard() {
        hidSocket?.sendKeyboard(ByteArray(8))
    }

    /**
     * Mirrors the web client's USB-churn dance (`client.close()` → REST call →
     * `client.connect()`): releases every key, stops the input WS, runs [block]
     * (device restarts the USB HID gadget, e.g. hid/reset, image mount, relative
     * mouse switch), then reopens the input WS if the session is still active.
     */
    suspend fun withHidCycle(block: suspend () -> Unit) {
        runCatching { hidSocket?.stop() }
        hidSocket = null
        try {
            block()
        } finally {
            if (active) {
                hidSocket = HidWebSocket(scope, okHttp, host, { api.token }, hidCallbacks).also { it.connect() }
            }
        }
    }

    /** 快捷键组合:修饰键位图 + 1..6 个 usage,按下保持后整体释放(web shortcut.tsx replay)。 */
    fun sendCombo(modifiers: Int, usages: IntArray) {
        val codec = com.nanokvm.app.data.hid.KeyboardCodec()
        if (modifiers != 0) {
            // bits are individual modifiers — emulate via keyboard-style ids per bit
            for (b in 0..7) {
                val bit = 1 shl b
                if (modifiers and bit != 0) hidSocket?.sendKeyboard(codec.keyDown("combo-mod-$b", bit, null))
            }
        }
        usages.forEachIndexed { i, usage ->
            hidSocket?.sendKeyboard(codec.keyDown("combo-key-$i", 0, usage))
        }
        hidSocket?.sendKeyboard(codec.reset())
    }

    fun reconnectHid() {
        if (!active) return
        scope.launch { withHidCycle { } }
    }

    // ---- internals ----

    private val streamCallbacks = object : StreamWebSocket.Callbacks {
        override val isActive get() = active
        private var counter = 0
        private var lastKey = false

        override fun onFrame(frame: VideoFrame) {
            counter++
            if (frame.keyframe) lastKey = true
            if (counter % 30 == 1) {
                android.util.Log.i("NanokvmSession", "frames=$counter keySeen=$lastKey key=${frame.keyframe} size=${frame.payload.size}")
            }
            codec.submit(frame)
        }

        override fun onOpened() {
            emit(SessionEvent.StreamConnected)
        }

        override fun onReconnect(attempt: Int) {
            emit(SessionEvent.Reconnecting(attempt))
        }

        override fun onError(cause: Throwable) {
            emit(SessionEvent.Error(cause.message ?: "stream error"))
        }
    }

    private val webrtcCallbacks = object : WebRtcVideoSource.Callbacks {
        override val isActive get() = active

        override fun onOpened() {
            emit(SessionEvent.StreamConnected)
        }

        override fun onIceConnected() {
            android.util.Log.i("NanokvmSession", "webrtc ICE connected — media flowing")
        }

        override fun onStatus(status: Int) {
            when (status) {
                -4 -> emit(SessionEvent.Error("视频模式不一致 (-4): 设备流正被其他客户端占用"))
                else -> android.util.Log.i("NanokvmSession", "webrtc video-status=$status")
            }
        }

        override fun onFormatChanged(width: Int, height: Int) {
            emit(SessionEvent.FormatChanged(width, height))
        }

        override fun onReconnect(attempt: Int) {
            emit(SessionEvent.Reconnecting(attempt))
        }

        override fun onError(cause: Throwable) {
            emit(SessionEvent.Error(cause.message ?: "webrtc error"))
        }
    }

    private val hidCallbacks = object : HidWebSocket.Callbacks {
        override val isActive get() = active

        override fun onConnected() = Unit
        override fun onClosed(reason: String?) = Unit
        override fun onReconnect(attempt: Int) = Unit
        override fun onError(cause: Throwable) = Unit
    }
}

/** UI-facing session lifecycle events. */
sealed interface SessionEvent {
    data object Authenticating : SessionEvent
    data class Configured(val what: String) : SessionEvent
    data object StreamConnected : SessionEvent
    data class FormatChanged(val width: Int, val height: Int) : SessionEvent
    data class Reconnecting(val attempt: Int) : SessionEvent
    data class AuthenticationFailed(val reason: String) : SessionEvent
    data class Error(val message: String) : SessionEvent
}

/**
 * User stream parameters, mirrored on every connect (the web replays them from
 * localStorage; we keep them in-process). Initial values = web defaults.
 */
class StreamParams(
    @Volatile var rateControl: String = "vbr",
    @Volatile var bitrateKbps: Int = 8000,
    @Volatile var gop: Int = 50,
    @Volatile var fps: Int = 0,
)

/**
 * Transport-agnostic counters for the stats overlay.
 *
 * Direct mode fills [frames]/[bytes]/[queueSize] (MediaCodec feed counters); WebRTC
 * mode fills [jitterMs]/[rttMs]/[fps]/[kbps]/[packetsLost] (1 Hz RTCStatsReport
 * poll — the SDK decodes internally, so per-frame counters are not observable).
 * The UI shows transport-specific tile sets from these fields.
 */
data class SessionVideoStats(
    val frames: Long = 0,
    val bytes: Long = 0,
    val queueSize: Int = 0,
    val jitterMs: Float = 0f,
    val rttMs: Float = 0f,
    val fps: Float = 0f,
    val kbps: Float = 0f,
    val packetsLost: Long = 0,
)