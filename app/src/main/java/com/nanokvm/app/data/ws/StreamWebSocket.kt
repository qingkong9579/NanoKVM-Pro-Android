package com.nanokvm.app.data.ws

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow
import com.nanokvm.app.data.ws.StreamFrameParser.VideoFrame

/**
 * Direct video stream WebSocket — `/api/stream/{h264|h265}/direct`.
 *
 * Frames are parsed by [StreamFrameParser]; raw frames are handed to the decoder in
 * the media layer (task 4). Reconnect mirrors [HidWebSocket] (backoff, caps at 8).
 *
 * Keyframe-starvation watchdog: the server only begins emitting once the WS connects,
 * and produces no keyframes while nobody watches. If a connected socket receives no
 * keyframe within [STARVATION_TIMEOUT_MS], we tear it down to force a fresh stream
 * (a keyframe is guaranteed on reconnect — truly lost streams restart cleanly).
 */
class StreamWebSocket(
    private val scope: CoroutineScope,
    private val okHttp: OkHttpClient,
    host: String,
    private val codec: String, // "h264" | "h265"
    private val token: () -> String?,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onFrame(frame: VideoFrame)
        fun onOpened()
        fun onReconnect(attempt: Int)
        fun onError(cause: Throwable)
        val isActive: Boolean
    }

    companion object {
        const val STARVATION_TIMEOUT_MS = 8_000L
        private const val MAX_RECONNECT = 8
        private const val BASE_DELAY_MS = 1_000L
    }

    private val url = "wss://$host/api/stream/$codec/direct"
    private var webSocket: WebSocket? = null
    private var watchdogJob: Job? = null
    private var reconnectAttempts = 0
    private var requestedStop = false
    private var lastKeyframeAt = 0L

    @Synchronized
    fun connect() {
        requestedStop = false
        reconnectAttempts = 0
        lastKeyframeAt = 0L
        openSocket()
    }

    @Synchronized
    fun stop() {
        requestedStop = true
        watchdogJob?.cancel()
        webSocket?.close(1000, "client stop")
        webSocket = null
        reconnectAttempts = 0
    }

    private fun openSocket() {
        val jwt = token() ?: run {
            callbacks.onError(RuntimeException("not authenticated"))
            return
        }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "nano-kvm-token=$jwt")
            .build()
        webSocket = okHttp.newWebSocket(request, listener)
    }

    private fun scheduleReconnect(failure: Throwable?) {
        if (requestedStop) return
        if (reconnectAttempts >= MAX_RECONNECT) {
            callbacks.onError(failure ?: RuntimeException("reconnect attempts exhausted"))
            return
        }
        reconnectAttempts++
        callbacks.onReconnect(reconnectAttempts)
        val delayMs = (BASE_DELAY_MS * 2.0.pow(reconnectAttempts - 1)).toLong()
        scope.launch {
            delay(delayMs)
            if (!requestedStop && callbacks.isActive) openSocket()
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && !requestedStop) {
                delay(1_000)
                if (lastKeyframeAt != 0L && System.currentTimeMillis() - lastKeyframeAt > STARVATION_TIMEOUT_MS) {
                    // No fresh keyframe — recycle the stream so a new one starts on key.
                    webSocket?.close(1000, "keyframe starvation")
                }
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            Log.i("NanokvmStream", "video WS opened: ${response.request.url}")
            callbacks.onOpened()
            startWatchdog()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = StreamFrameParser.parse(bytes.toByteArray()) ?: return
            if (frame.keyframe) lastKeyframeAt = System.currentTimeMillis()
            callbacks.onFrame(frame)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w("NanokvmStream", "video WS closed code=$code reason=$reason")
            watchdogJob?.cancel()
            if (!requestedStop) scheduleReconnect(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("NanokvmStream", "video WS failure: ${t.message} (http=${response?.code})")
            watchdogJob?.cancel()
            if (!requestedStop) scheduleReconnect(t)
        }
    }
}