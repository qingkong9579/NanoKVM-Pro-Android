package com.nanokvm.app.data.ws

import android.content.Context
import android.util.Log
import com.nanokvm.app.media.WebRtcEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import kotlin.math.pow

/**
 * WebRTC video transport — signaling WebSocket + Pion peer connection.
 *
 * Mirrors the firmware's browser client (NanoKVM-Pro/web …/screen/h264-webrtc.tsx):
 *   1. open `/api/stream/{h264|h265}/webrtc` (Cookie `nano-kvm-token`);
 *   2. create a PeerConnection (STUN stun.l.google.com) and add a **recvonly**
 *      video transceiver;
 *   3. createOffer → send `video-offer` (JSON-stringified SDP), trickle local ICE
 *      candidates via `video-candidate`; server answers `video-answer`, sends its
 *      own `video-candidate`s and `video-status` (1 = streaming, -1 = no signal,
 *      -4 = mode mismatch);
 *   4. `heartbeat` both ways every 60 s.
 *
 * Server quirks (verified against NanoKVM-Server/service/stream/{h264,h265}/webrtc):
 *   - h265 negotiation is H265-only on the server side, so the offer must carry an
 *     H265 m-line (only devices that advertise H.265 decode get a working h265-webrtc);
 *   - media starts only after ICE connects, and the stream is a shared GOP stream —
 *     a fresh peer waits for the next keyframe, no periodic SPS/PPS needed.
 *
 * Lifecycle parity with [StreamWebSocket]: exponential-backoff reconnects cap at 8,
 * the watchdog recycles a connection that never delivers frames (ICE timeout /
 * starvation), and a `video-status == -1` suspends the watchdog (no-signal is
 * legitimate — the UI shows the no-signal overlay instead of a reconnect storm).
 * Every reconnect is a full re-signaling cycle (the old WS + PC are torn down first).
 */
class WebRtcVideoSource(
    private val scope: CoroutineScope,
    private val okHttp: OkHttpClient,
    host: String,
    private val codecName: String, // "h264" | "h265"
    private val appContext: Context,
    private val token: () -> String?,
    private val rendererProvider: () -> SurfaceViewRenderer?,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onOpened()                              // signaling WS open
        fun onIceConnected()                        // media path live
        fun onStatus(status: Int)                   // server video-status
        fun onFormatChanged(width: Int, height: Int)
        fun onReconnect(attempt: Int)
        fun onError(cause: Throwable)
        val isActive: Boolean
    }

    companion object {
        private const val TAG = "NanokvmWebRtc"
        private const val STUN_URL = "stun:stun.l.google.com:19302"
        private const val HEARTBEAT_MS = 60_000L
        private const val STATS_POLL_MS = 1_000L
        private const val MAX_RECONNECT = 8
        private const val BASE_DELAY_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val STALL_TIMEOUT_MS = 8_000L

        /** Stats-report stats objects of interest. */
        private const val TYPE_INBOUND_RTP = "inbound-rtp"
        private const val TYPE_CANDIDATE_PAIR = "candidate-pair"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val url = "wss://$host/api/stream/$codecName/webrtc"

    // ---- live telemetry (read by the stats overlay) ----
    @Volatile
    var jitterBufferMs: Float = 0f
        private set
    @Volatile
    var iceRttMs: Float = 0f
        private set
    @Volatile
    var fps: Float = 0f
        private set
    @Volatile
    var bitrateKbps: Float = 0f
        private set

    /** Cumulative lost RTP packets (inbound-rtp `packetsLost`). */
    @Volatile
    var packetsLost: Long = 0
        private set

    private var heartbeatJob: Job? = null
    private var statsJob: Job? = null
    private var watchdogJob: Job? = null

    /** Attempt serial — callbacks from a torn-down attempt are ignored. */
    @Volatile
    private var attempt = 0L

    @Volatile
    private var requestedStop = false
    private var reconnectAttempts = 0

    /** Watchdog-driven retry: when set, the watchdog waits until [retryAtMs]. */
    private var retryAtMs = 0L

    // ---- current attempt state (owned by the signaling thread) ----
    private var ws: WebSocket? = null
    private var pc: PeerConnection? = null
    @Volatile
    private var iceConnected = false
    @Volatile
    private var signaledStatus = 0

    // frame activity (updated from the frame sink / stats poller)
    private var lastFrameAt = 0L
    private var attemptStartedAt = 0L
    private var reportedWidth = 0
    private var reportedHeight = 0

    // renderer sink (the SurfaceViewRenderer lives in the UI; attach lazily)
    private val frameCounterSink = FrameCounterSink()
    private var videoTrack: VideoTrack? = null
    private var attachedRenderer: SurfaceViewRenderer? = null

    private var offerSent = false
    private val pendingRemoteCandidates = ArrayList<IceCandidate>()
    private val remoteCandidatesLock = Any()

    fun start() {
        requestedStop = false
        reconnectAttempts = 0
        try {
            WebRtcEnv.ensure(appContext)
        } catch (t: Throwable) {
            callbacks.onError(t)
            return
        }
        watchdogJob?.cancel()
        watchdogJob = scope.launch { watchdogLoop() }
        openAttempt()
    }

    fun stop() {
        val toClose: List<Any>
        synchronized(this) {
            requestedStop = true
            heartbeatJob?.cancel()
            statsJob?.cancel()
            watchdogJob?.cancel()
            toClose = teardownLocked()
            resetTelemetry()
        }
        // Closes happen outside the monitor: PeerConnection.close() waits for the
        // signaling thread, which may itself be blocked on this object's monitor.
        toClose.forEach { c ->
            runCatching {
                when (c) {
                    is WebSocket -> c.close(1000, "client stop")
                    is PeerConnection -> c.close()
                }
            }
        }
    }

    /** New renderer instance (recompose) — attach the live video track to it. */
    @Synchronized
    fun attachRenderer(renderer: SurfaceViewRenderer) {
        if (attachedRenderer === renderer) return
        detachRendererSinks()
        attachedRenderer = renderer
        videoTrack?.addSink(renderer)
    }

    /** Renderer instance leaving the UI (back nav / mode switch). */
    @Synchronized
    fun detachRenderer(renderer: SurfaceViewRenderer) {
        if (attachedRenderer !== renderer) return
        detachRendererSinks()
    }

    private fun detachRendererSinks() {
        val r = attachedRenderer ?: return
        videoTrack?.removeSink(r)
        attachedRenderer = null
    }

    // ---- attempt lifecycle ----

    private fun openAttempt() {
        if (requestedStop) return
        val jwt = token() ?: run {
            callbacks.onError(RuntimeException("not authenticated"))
            return
        }
        retryAtMs = 0L
        attempt++
        offerSent = false
        iceConnected = false
        signaledStatus = 0
        lastFrameAt = 0L
        attemptStartedAt = System.currentTimeMillis()
        val myAttempt = attempt

        val request = Request.Builder()
            .url(url)
            .header("Cookie", "nano-kvm-token=$jwt")
            .build()
        ws = okHttp.newWebSocket(request, createWsListener(myAttempt))
        Log.i(TAG, "signaling WS opening ($myAttempt): $url")
    }

    /**
     * Detaches current-attempt state under the monitor. MUST NOT close the sockets
     * here — `PeerConnection.close()` synchronously waits on the signaling thread,
     * which can be blocked on this monitor (observer callbacks) → deadlock (ANR).
     * Returns the handles the caller must close outside the lock.
     */
    @Synchronized
    private fun teardownLocked(): List<Any> {
        val old = mutableListOf<Any>()
        ws?.let { old += it }
        ws = null
        pc?.let { old += it }
        pc = null
        videoTrack?.removeSink(frameCounterSink)
        detachRendererSinks()
        videoTrack = null
        iceConnected = false
        offerSent = false
        pendingRemoteCandidates.clear()
        return old
    }

    private fun recycleAttempt(reason: String) {
        if (requestedStop) return
        val toClose: List<Any>
        synchronized(this) {
            if (retryAtMs != 0L) return // retry already scheduled by an earlier recycle
            if (pc == null && ws == null) return
            Log.w(TAG, "recycling attempt: $reason")
            toClose = teardownLocked()
            if (reconnectAttempts >= MAX_RECONNECT) {
                callbacks.onError(RuntimeException("重连失败: $reason"))
                return
            }
            reconnectAttempts++
            callbacks.onReconnect(reconnectAttempts)
            retryAtMs = System.currentTimeMillis() + (BASE_DELAY_MS * 2.0.pow(reconnectAttempts - 1)).toLong()
        }
        toClose.forEach { c ->
            runCatching {
                when (c) {
                    is WebSocket -> c.close(1000, "client stop")
                    is PeerConnection -> c.close()
                }
            }
        }
    }

    // ---- signaling websocket ----

    private fun createWsListener(myAttempt: Long): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (attempt != myAttempt || requestedStop) return
            Log.i(TAG, "signaling WS opened ($myAttempt)")
            callbacks.onOpened()
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (!requestedStop && attempt == myAttempt) {
                    delay(HEARTBEAT_MS)
                    sendMessage("heartbeat", "")
                }
            }
            startPeerConnection(myAttempt)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (attempt != myAttempt || requestedStop) return
            handleSignal(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "signaling WS closed code=$code reason=$reason")
            if (attempt != myAttempt || requestedStop) return
            // Server dropped us mid-session → full re-signaling cycle. A close before
            // the PC existed (server refused after accept) is also recycled.
            recycleAttempt("signaling WS closed ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "signaling WS failure: ${t.message} (http=${response?.code})")
            if (attempt != myAttempt || requestedStop) return
            recycleAttempt("signaling WS failure: ${t.message}")
        }
    }

    private fun handleSignal(text: String) {
        val msg = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "unparseable signal: $text")
            return
        }
        val event = (msg["event"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        val data = (msg["data"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        when (event) {
            "video-answer" -> handleAnswer(data)
            "video-candidate" -> handleRemoteCandidate(data)
            "video-status" -> handleVideoStatus(data)
            "heartbeat" -> Unit // server keeps the WS alive; ignore
            "audio-answer", "audio-candidate" -> Unit // mic/audio is out of scope
            else -> Log.d(TAG, "unhandled signal event=$event")
        }
    }

    private fun handleAnswer(sdpJson: String) {
        val pc = pc ?: return
        val sdp = try {
            val obj = json.parseToJsonElement(sdpJson).jsonObject
            SessionDescription(SessionDescription.Type.ANSWER, obj["sdp"]?.jsonPrimitive?.content.orEmpty())
        } catch (e: Exception) {
            recycleAttempt("bad video-answer: ${e.message}")
            return
        }
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.w(TAG, "answer while signalingState=${pc.signalingState()} — ignoring")
            offerSent = false
            return
        }
        pc.setRemoteDescription(answerObserver, sdp)
    }

    private val answerObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetSuccess() {
            Log.i(TAG, "remote answer set")
            offerSent = false
            flushRemoteCandidates()
        }

        override fun onSetFailure(error: String) {
            Log.w(TAG, "setRemoteDescription failed: $error")
            recycleAttempt("answer rejected: $error")
        }
    }

    private fun flushRemoteCandidates() {
        val pc = pc ?: return
        synchronized(remoteCandidatesLock) {
            val queued = pendingRemoteCandidates.toList()
            pendingRemoteCandidates.clear()
            for (c in queued) {
                if (!pc.addIceCandidate(c)) Log.w(TAG, "queued candidate add failed")
            }
        }
    }

    private fun handleRemoteCandidate(candidateJson: String) {
        val pc = pc ?: return
        val c = try {
            val obj = json.parseToJsonElement(candidateJson).jsonObject
            val candidate = obj["candidate"]?.jsonPrimitive?.content ?: ""
            if (candidate.isEmpty()) return
            IceCandidate(
                obj["sdpMid"]?.jsonPrimitive?.content.orEmpty(),
                obj["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0,
                candidate,
            )
        } catch (e: Exception) {
            Log.w(TAG, "bad video-candidate: ${e.message}")
            return
        }
        if (pc.remoteDescription == null) {
            synchronized(remoteCandidatesLock) { pendingRemoteCandidates.add(c) }
        } else {
            if (!pc.addIceCandidate(c)) Log.w(TAG, "remote candidate add failed")
        }
    }

    private fun handleVideoStatus(data: String) {
        val status = data.trim().toIntOrNull() ?: return
        signaledStatus = status
        Log.i(TAG, "video-status=$status")
        callbacks.onStatus(status)
    }

    private fun sendMessage(event: String, data: String) {
        ws?.send(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("event", event); put("data", data) }))
    }

    // ---- peer connection ----

    private fun startPeerConnection(myAttempt: Long) {
        try {
            val config = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer(STUN_URL)))
            val pc = WebRtcEnv.peerConnectionFactory().createPeerConnection(config, pcObserver(myAttempt))
                ?: throw IllegalStateException("createPeerConnection returned null")
            this.pc = pc

            // recvonly transceiver — a dummy local track satisfies the API; the
            // transceiver direction keeps the m-line a=recvonly.
            val factory = WebRtcEnv.peerConnectionFactory()
            val dummy = factory.createVideoTrack("nanokvm-video", factory.createVideoSource(false))
            videoTrack = dummy
            pc.addTransceiver(dummy, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))

            pc.createOffer(offerObserver, MediaConstraints())
        } catch (t: Throwable) {
            Log.e(TAG, "PC setup failed", t)
            recycleAttempt("PC setup failed: ${t.message}")
        }
    }

    private val offerObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {
            val pc = pc ?: return
            offerSent = true
            // The local-set observer sends the video-offer once the description is live.
            pc.setLocalDescription(localOfferObserver, desc)
            Log.i(TAG, "offer created: ${codecName}\n${desc.description.lineSequence().filter { it.startsWith("a=rtpmap") || it.startsWith("m=") }.joinToString("\n")}")
        }

        override fun onCreateFailure(error: String) {
            Log.w(TAG, "createOffer failed: $error")
            recycleAttempt("createOffer failed: $error")
        }

        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String) = Unit
    }

    /** Local-set completion for the offer — this is where the offer goes out. */
    private val localOfferObserver = object : SdpObserver {
        override fun onSetSuccess() {
            Log.i(TAG, "local offer set — sending video-offer")
            val desc = pc?.localDescription ?: return
            val payload = buildJsonObject {
                put("type", when (desc.type) {
                    SessionDescription.Type.OFFER -> "offer"
                    SessionDescription.Type.ANSWER -> "answer"
                    SessionDescription.Type.PRANSWER -> "pranswer"
                    SessionDescription.Type.ROLLBACK -> "rollback"
                })
                put("sdp", desc.description)
            }
            sendMessage("video-offer", payload.toString())
        }

        override fun onSetFailure(error: String) {
            Log.w(TAG, "setLocalDescription failed: $error")
            recycleAttempt("setLocalDescription failed: $error")
        }

        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
    }

    private fun pcObserver(myAttempt: Long) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (attempt != myAttempt || requestedStop) return
            val payload = buildJsonObject {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            sendMessage("video-candidate", payload.toString())
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (attempt != myAttempt) return
            Log.i(TAG, "ice state=$state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> {
                    if (!iceConnected) {
                        iceConnected = true
                        startStatsPoller(myAttempt)
                        callbacks.onIceConnected()
                    }
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    if (!requestedStop) recycleAttempt("ICE failed")
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    if (!requestedStop && pc != null) recycleAttempt("ICE closed")
                }
                else -> Unit
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            if (attempt != myAttempt || requestedStop) return
            val track = transceiver.receiver.track() as? VideoTrack
            if (track == null) {
                Log.w(TAG, "onTrack without a video track")
                return
            }
            Log.i(TAG, "remote video track: ${track.id()}")
            videoTrack = track
            videoTrack?.addSink(frameCounterSink)
            // The UI renderer may arrive after the track (mode switch recompose); the
            // UI later calls attachRenderer() when that happens. If it is already up,
            // attach now — a second call is a no-op.
            rendererProvider()?.let { attachRenderer(it) }
            videoTrack?.setEnabled(true)
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connection state=$state")
        }

        // --- unused callbacks ---
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.d(TAG, "signaling state=$state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) = Unit
        override fun onRemoveTrack(receiver: RtpReceiver) = Unit
    }

    // ---- frame sink: activity heartbeat + first-frame geometry ----

    private inner class FrameCounterSink : VideoSink {
        private var formatReported = false

        override fun onFrame(frame: VideoFrame) {
            lastFrameAt = System.currentTimeMillis()
            if (!formatReported || frame.rotatedWidth != reportedWidth || frame.rotatedHeight != reportedHeight) {
                formatReported = true
                reportedWidth = frame.rotatedWidth
                reportedHeight = frame.rotatedHeight
                callbacks.onFormatChanged(reportedWidth, reportedHeight)
            }
        }
    }

    // ---- stats poller (1 Hz over RTCStatsReport) ----

    private fun startStatsPoller(myAttempt: Long) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var prevFrames = 0L
            var prevBytes = 0L
            var prevAt = System.currentTimeMillis()
            while (isActive && !requestedStop && attempt == myAttempt) {
                delay(STATS_POLL_MS)
                val p = pc ?: continue
                p.getStats(object : RTCStatsCollectorCallback {
                    override fun onStatsDelivered(report: RTCStatsReport) {
                        if (attempt != myAttempt) return
                        val now = System.currentTimeMillis()
                        var frames = prevFrames
                        var bytes = prevBytes
                        var jitterBufferAvgMs = 0f
                        var rttMs = 0f
                        var lost = packetsLost
                        for (stats in report.statsMap.values) {
                            when (stats.type) {
                                TYPE_INBOUND_RTP -> {
                                    val kind = stats.members["kind"] as? String ?: continue
                                    if (kind != "video") continue
                                    frames = (stats.members["framesDecoded"] as? Number)?.toLong() ?: frames
                                    bytes = (stats.members["bytesReceived"] as? Number)?.toLong() ?: bytes
                                    lost = (stats.members["packetsLost"] as? Number)?.toLong() ?: lost
                                    val delay = (stats.members["jitterBufferDelay"] as? Number)?.toDouble() ?: 0.0
                                    val emitted = (stats.members["jitterBufferEmittedCount"] as? Number)?.toLong() ?: 0L
                                    if (emitted > 0) jitterBufferAvgMs = (delay / emitted * 1000.0).toFloat()
                                }
                                TYPE_CANDIDATE_PAIR -> {
                                    if ((stats.members["state"] as? String) != "succeeded") continue
                                    val rtt = (stats.members["currentRoundTripTime"] as? Number)?.toDouble() ?: 0.0
                                    if (rtt > 0) rttMs = (rtt * 1000.0).toFloat()
                                }
                            }
                        }
                        val dt = ((now - prevAt).coerceAtLeast(1L)) / 1000.0
                        if (frames >= prevFrames && bytes >= prevBytes) {
                            fps = if (dt > 0) ((frames - prevFrames) / dt).toFloat() else 0f
                            bitrateKbps = ((bytes - prevBytes) * 8.0 / 1000.0 / dt).toFloat()
                            if (frames > prevFrames) lastFrameAt = now
                        }
                        prevFrames = frames
                        prevBytes = bytes
                        prevAt = now
                        jitterBufferMs = jitterBufferAvgMs
                        iceRttMs = rttMs
                        packetsLost = lost
                    }
                })
            }
        }
    }

    // ---- watchdog: connect timeout + post-connect starvation ----

    private suspend fun watchdogLoop() {
        var ticks = 0
        while (true) {
            try {
                delay(250)
                if (requestedStop) return
                val now = System.currentTimeMillis()
                if (++ticks % 200 == 1) {
                    Log.i(TAG, "watchdog tick #$ticks retryAt=${if (retryAtMs == 0L) 0 else retryAtMs - now}ms pc=${pc != null} ice=$iceConnected status=$signaledStatus lastFrame=${lastFrameAt - now}ms reqStop=$requestedStop active=${callbacks.isActive}")
                }
                if (retryAtMs != 0L) {
                    // Between attempts: wait out the backoff, then re-signal.
                    if (now >= retryAtMs && callbacks.isActive) {
                        Log.i(TAG, "watchdog: opening retry attempt")
                        openAttempt()
                    }
                    continue
                }
                if (attemptStartedAt == 0L) continue
                when {
                    pc == null && now - attemptStartedAt > CONNECT_TIMEOUT_MS ->
                        recycleAttempt("信令 ${CONNECT_TIMEOUT_MS / 1000}s 未建立")
                    pc == null -> Unit
                    !iceConnected && now - attemptStartedAt > CONNECT_TIMEOUT_MS ->
                        recycleAttempt("ICE 未在 ${CONNECT_TIMEOUT_MS / 1000}s 内连通")
                    signaledStatus == -1 -> Unit // no signal — legitimate, UI shows overlay
                    lastFrameAt == 0L && now - attemptStartedAt > CONNECT_TIMEOUT_MS ->
                        recycleAttempt("连接后 ${CONNECT_TIMEOUT_MS / 1000}s 无视频帧")
                    lastFrameAt != 0L && now - lastFrameAt > STALL_TIMEOUT_MS ->
                        recycleAttempt("视频停滞 ${STALL_TIMEOUT_MS / 1000}s")
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t // job cancelled (stop()) — clean exit, not an error
            } catch (t: Throwable) {
                Log.e(TAG, "watchdog died", t)
                return
            }
        }
    }

    private fun resetTelemetry() {
        jitterBufferMs = 0f
        iceRttMs = 0f
        fps = 0f
        bitrateKbps = 0f
        packetsLost = 0L
    }
}
