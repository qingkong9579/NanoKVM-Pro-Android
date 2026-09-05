package com.nanokvm.app.data.ws

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * HID WebSocket (`/api/ws`) with the device's input protocol.
 *
 * Wire framing (verified against the server & web client):
 *   - heartbeat               = `[0x00]` every 10 s while connected
 *   - keyboard (type 1)       = `[0x01] + 8-byte report`  (sendKeyboard)
 *   - mouse (type 2)          = `[0x02] + 4/6-byte report` (sendMouse)
 *
 * Reconnect uses exponential backoff (1s, 2s, … capped at 8 attempts total) and
 * stops when [stop] is called. The device enforces the 10 s heartbeat; the server
 * only prunes idle clients, but staying live keeps the NAT mapping / UI state fresh.
 */
class HidWebSocket(
    private val scope: CoroutineScope,
    private val okHttp: OkHttpClient,
    host: String,
    private val token: () -> String?,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onConnected()
        fun onClosed(reason: String?)
        fun onReconnect(attempt: Int)
        fun onError(cause: Throwable)
        val isActive: Boolean
    }

    private val url = "wss://$host/api/ws"
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempts = 0
    private var requestedStop = false

    private val maxReconnectAttempts = 8
    private val baseDelayMs = 1_000L

    @Synchronized
    fun connect() {
        requestedStop = false
        reconnectAttempts = 0
        openSocket()
    }

    @Synchronized
    fun stop() {
        requestedStop = true
        heartbeatJob?.cancel()
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

    /** Sends the 10 s heartbeat. Returns false if the socket is not open. */
    fun sendHeartbeat(): Boolean = webSocket?.send(ByteString.of(0x00)) ?: false

    fun sendKeyboard(report: ByteArray): Boolean {
        val frame = prefixed(0x01, report)
        return webSocket?.send(ByteString.of(*frame)) ?: false
    }

    fun sendMouse(report: ByteArray): Boolean {
        val frame = prefixed(0x02, report)
        return webSocket?.send(ByteString.of(*frame)) ?: false
    }

    private fun prefixed(type: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(payload.size + 1)
        frame[0] = type.toByte()
        payload.copyInto(frame, 1)
        return frame
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (!requestedStop) {
                delay(10_000)
                if (callbacks.isActive) sendHeartbeat()
            }
        }
    }

    private fun scheduleReconnect(failure: Throwable?) {
        if (requestedStop) return
        if (reconnectAttempts >= maxReconnectAttempts) {
            callbacks.onError(failure ?: RuntimeException("reconnect attempts exhausted"))
            return
        }
        reconnectAttempts++
        callbacks.onReconnect(reconnectAttempts)
        val delayMs = (baseDelayMs * 2.0.pow(reconnectAttempts - 1)).toLong()
        scope.launch {
            delay(delayMs)
            if (!requestedStop && callbacks.isActive) openSocket()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            startHeartbeat()
            callbacks.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The device does not send binary messages to the app on /api/ws; ignore.
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            if (!requestedStop) {
                callbacks.onClosed(reason)
                scheduleReconnect(null)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            heartbeatJob?.cancel()
            if (!requestedStop) {
                scheduleReconnect(t)
            }
        }
    }
}