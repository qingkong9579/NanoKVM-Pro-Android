package com.nanokvm.app.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.nanokvm.app.data.ws.H264Sps
import com.nanokvm.app.data.ws.H265Sps
import com.nanokvm.app.data.ws.StreamFrameParser
import com.nanokvm.app.data.ws.StreamFrameParser.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MediaCodec H.264/H.265 decoder rendering to a Surface.
 *
 * Startup mirrors the web client's `direct.worker.ts`:
 *   1. drop everything until the first keyframe (the server never sends periodic
 *      SPS/PPS — they travel inside keyframes);
 *   2. extract csd from that keyframe and `configure()`/`start()` the codec there;
 *   3. feed every subsequent frame, tagging keyframes with BUFFER_FLAG_KEY_FRAME.
 *
 * A single decode thread owns the codec (configure/dequeue/queue/release are all
 * blocking calls on that thread). The WS callback thread only feeds the bounded
 * queue, so keyframes are never lost: submit() always makes room for one.
 */
class VideoDecoder(
    private val surfaceProvider: () -> Surface?,
    private val onFormatChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "NanokvmDecoder"
        private const val QUEUE_CAPACITY = 16
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    private val queue = ArrayBlockingQueue<VideoFrame>(QUEUE_CAPACITY)

    private val frameCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val byteCounter = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    private var lastDecodeMs = 0.0

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var isHevc = false
    @Volatile
    private var codec: MediaCodec? = null

    /** True once a keyframe has initialized the codec. */
    @Volatile
    var started: Boolean = false
        private set

    @Volatile
    private var reportedWidth = 0
    @Volatile
    private var reportedHeight = 0

    /** Current decoded output size (from CODE output format), or 0x0 if unknown. */
    val videoSize: Pair<Int, Int> get() = reportedWidth to reportedHeight

    fun start(isHevc: Boolean) {
        stop()
        this.isHevc = isHevc
        started = false
        reportedWidth = 0
        reportedHeight = 0
        queue.clear()
        running = true
        thread = Thread(::decodeLoop, "nanokvm-decode").also { it.start() }
    }

    fun stop() {
        running = false
        queue.clear()
        thread?.interrupt()
        thread?.join(500)
        thread = null
        releaseCodec()
        started = false
    }

    /**
     * Called from the WS caller. Keyframes always get in (a stale delta is evicted);
     * deltas before the first keyframe and beyond the queue capacity are dropped.
     */
    fun submit(frame: VideoFrame) {
        if (frame.keyframe) {
            while (!queue.offer(frame)) queue.poll()
        } else {
            if (started) queue.offer(frame)
        }
        frameCounter.incrementAndGet()
        byteCounter.addAndGet(frame.payload.size.toLong())
    }

    /** Telemetry for the stats overlay (measured in real time). */
    data class Telemetry(
        val frames: Long,
        val bytes: Long,
        val queueSize: Int,
    )

    fun snapshot(): Telemetry = Telemetry(frameCounter.get(), byteCounter.get(), queue.size)

    private fun decodeLoop() {
        while (running) {
            val frame = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (frame == null) continue

            if (frame.keyframe && codec == null) {
                if (!initCodec(frame)) continue // retry on the next keyframe
            }
            val c = codec ?: continue
            val t0 = System.nanoTime()
            try {
                feed(c, frame)
                drain(c)
            } catch (e: MediaCodec.CodecException) {
                Log.w(TAG, "codec feed error: ${e.diagnosticInfo ?: e.message} — releasing, wait keyframe")
                releaseCodec()
            } catch (_: IllegalStateException) {
                releaseCodec()
            } finally {
                lastDecodeMs = (System.nanoTime() - t0) / 1_000_000.0
            }
        }
    }

    /** Average decode-loop iteration cost (feed + drain), ms. */
    fun decodeLatencyMs(): Double = lastDecodeMs

    /** configure+start on the first keyframe; extracts csd from the same bytes. */
    private fun initCodec(frame: VideoFrame): Boolean {
        val surface = surfaceProvider() ?: run {
            Log.i(TAG, "initCodec skipped: surface not ready yet")
            return false
        }
        if (frame.payload.isEmpty()) return false

        val mime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        return try {
            val decoder = MediaCodec.createDecoderByType(mime)
            val csds = extractCsds(frame.payload)

            val fmt = MediaFormat()
            fmt.setString(MediaFormat.KEY_MIME, mime)
            if (!csds.isNullOrEmpty()) {
                // MediaCodec needs explicit dimensions + csd (it will not infer them).
                val dims = if (isHevc) {
                    StreamFrameParser.extractHevcSps(frame.payload)?.let { H265Sps.parseWidthHeight(it) }
                } else {
                    H264Sps.parseWidthHeight(csds[0])
                }
                if (dims != null && dims.first != reportedWidth) {
                    fmt.setInteger(MediaFormat.KEY_WIDTH, dims.first)
                    fmt.setInteger(MediaFormat.KEY_HEIGHT, dims.second)
                    onFormatChanged(dims.first, dims.second)
                } else if (dims == null) {
                    Log.i(TAG, "no dimensions parsed; ${if (isHevc) "hevc" else "avc"} configure may fail")
                }
                csds.forEachIndexed { i, nal -> fmt.setByteBuffer("csd-$i", ByteBuffer.wrap(nal)) }
            }
            applyCommonKeys(fmt)

            val ok = try {
                decoder.configure(fmt, surface, null, 0)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "decoder configure failed (${t.javaClass.simpleName}: ${t.message}; dims=${fmt.getInteger(MediaFormat.KEY_WIDTH, 0)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT, 0)})")
                runCatching { decoder.release() }
                false
            }
            if (!ok) return false

            decoder.start()
            codec = decoder
            started = true
            Log.i(TAG, "codec started: $mime ${fmt.getInteger(MediaFormat.KEY_WIDTH, -1)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT, -1)} with ${csds?.size ?: 0} csd")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "initCodec failed: ${t.javaClass.simpleName}: ${t.message}")
            releaseCodec()
            false
        }
    }

    private fun applyCommonKeys(fmt: MediaFormat) {
        if (Build.VERSION.SDK_INT >= 30) {
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
    }

    /** One buffer per frame; splits into per-NAL buffers if the codec's buffer is too small. */
    private fun feed(codec: MediaCodec, frame: VideoFrame) {
        val pts = frame.timestampUs
        val keyFlags = if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return
        val buf = codec.getInputBuffer(index) ?: return
        if (buf.capacity() >= frame.payload.size) {
            buf.clear()
            buf.put(frame.payload)
            codec.queueInputBuffer(index, 0, frame.payload.size, pts, keyFlags)
            return
        }
        // Oversized frame — release the held buffer empty, then split into NALs.
        codec.queueInputBuffer(index, 0, 0, pts, 0)
        var first = true
        for (nal in StreamFrameParser.splitNals(frame.payload)) {
            val idx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (idx < 0) return
            val b = codec.getInputBuffer(idx) ?: return
            if (b.capacity() < nal.size) {
                codec.queueInputBuffer(idx, 0, 0, pts, 0)
                continue
            }
            b.clear()
            b.put(nal)
            codec.queueInputBuffer(idx, 0, nal.size, pts, if (first) keyFlags else 0)
            first = false
        }
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var served = 0
        while (served < 4) {
            when (val index = codec.dequeueOutputBuffer(info, if (served == 0) DEQUEUE_TIMEOUT_US else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateSize(codec.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    codec.releaseOutputBuffer(index, true)
                    served++
                }
            }
        }
    }

    private fun updateSize(format: MediaFormat) {
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (w != reportedWidth || h != reportedHeight) {
            reportedWidth = w
            reportedHeight = h
            onFormatChanged(w, h)
        }
    }

    private fun extractCsds(payload: ByteArray): List<ByteArray>? =
        if (isHevc) {
            StreamFrameParser.extractHevcCsd(payload)?.let { listOf(it) }
        } else {
            StreamFrameParser.extractH264Csd(payload).ifEmpty { null }
        }

    private fun releaseCodec() {
        codec?.let { c ->
            try {
                c.stop()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        started = false
    }
}