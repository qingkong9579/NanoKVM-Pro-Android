package com.nanokvm.app.data.ws

/**
 * Parser for the NanoKVM-Pro direct video stream frames.
 *
 * Every binary WS message from `/api/stream/h264/direct` (and `/h265/direct`) is one
 * video frame:
 *
 *    byte 0        keyframe flag: 1 = key, otherwise delta
 *    bytes 1..8    presentation timestamp, uint64 little-endian (µs since encoder start)
 *    bytes 9..     Annex-B elementary stream (4-byte start codes, a.k.a. "dummy bytes" 00 00 00 01)
 *
 * The server never sends periodic parameter sets — SPS/PPS (H264) or VPS/SPS/PPS
 * (H265) travel inside the keyframes. The client must drop every frame until the
 * first keyframe (web client does the same in `direct.worker.ts`).
 */
object StreamFrameParser {
    const val HEADER_LEN = 9

    data class VideoFrame(
        val keyframe: Boolean,
        val timestampUs: Long,
        val payload: ByteArray,
    )

    fun parse(message: ByteArray): VideoFrame? {
        if (message.size < HEADER_LEN) return null
        val keyframe = message[0].toInt() == 1
        var ts = 0L
        for (i in 8 downTo 1) {
            ts = (ts shl 8) or (message[i].toLong() and 0xff)
        }
        val payload = message.copyOfRange(HEADER_LEN, message.size)
        return VideoFrame(keyframe, ts, payload)
    }

    /**
     * Extracts the H.264 parameter sets from a keyframe's Annex-B payload.
     * Returns keeps entire NAL units (start-code prefix included) for `csd-0`/`csd-1`,
     * preserving the exact bytes the decoder will see in-band.
     */
    fun extractH264Csd(payload: ByteArray): List<ByteArray> {
        val found = mutableListOf<ByteArray>()
        var i = 0
        while (i < payload.size) {
            val sc = startCodeLengthAt(payload, i)
            if (sc > 0) {
                val type = payload[i + sc].toInt() and 0x1f
                var end = i + sc + 1
                while (end < payload.size && startCodeLengthAt(payload, end) == 0) end++
                val raw = payload.copyOfRange(i + sc, end)
                if ((type == 7 || type == 8) && found.none { it.contentEquals(raw) }) found.add(stripEmulationPrevention(raw))
                if (found.size == 2) break
                i = end
            } else {
                i++
            }
        }
        return found
    }

    /**
     * Extracts the H.265 parameter sets (VPS=32, SPS=33, PPS=34) and concatenates
     * the raw units into a single `csd-0` (the common HEVC MediaCodec config).
     */
    fun extractHevcCsd(payload: ByteArray): ByteArray? {
        val units = mutableListOf<ByteArray>()
        var i = 0
        while (i < payload.size) {
            val sc = startCodeLengthAt(payload, i)
            if (sc > 0) {
                val type = ((payload[i + sc].toInt() and 0x7f)) shr 1
                var end = i + sc + 1
                while (end < payload.size && startCodeLengthAt(payload, end) == 0) end++
                val raw = payload.copyOfRange(i + sc, end)
                if ((type == 32 || type == 33 || type == 34) && units.none { it.contentEquals(raw) }) units.add(stripEmulationPrevention(raw))
                i = end
            } else {
                i++
            }
        }
        if (units.isEmpty()) return null
        return units.reduce { acc, nal ->
            ByteArray(acc.size + nal.size).also { out ->
                acc.copyInto(out, 0)
                nal.copyInto(out, acc.size)
            }
        }
    }

    /** Returns the raw HEVC SPS NAL (2-byte header included, type 33), or null. */
    fun extractHevcSps(payload: ByteArray): ByteArray? {
        var i = 0
        while (i < payload.size) {
            val sc = startCodeLengthAt(payload, i)
            if (sc > 0) {
                val type = ((payload[i + sc].toInt() and 0x7f)) shr 1
                var end = i + sc + 1
                while (end < payload.size && startCodeLengthAt(payload, end) == 0) end++
                val raw = payload.copyOfRange(i + sc, end)
                if (type == 33) return stripEmulationPrevention(raw)
                i = end
            } else {
                i++
            }
        }
        return null
    }

    /** Removes H.264/HEVC emulation-prevention bytes (`00 00 03` → `00 00`). */
    fun stripEmulationPrevention(input: ByteArray): ByteArray {
        if (input.size < 3) return input
        val out = ByteArray(input.size)
        var o = 0
        var i = 0
        while (i < input.size) {
            val b = input[i]
            // drop the 0x03 inserted after two consecutive zeros (00 00 03)
            if (b == 3.toByte() && i >= 2 && input[i - 1] == 0.toByte() && input[i - 2] == 0.toByte()) {
                i++
                continue
            }
            out[o++] = b
            i++
        }
        return if (o == input.size) input else out.copyOf(o)
    }

    /** 4 for `00 00 00 01`, 3 for a bare `00 00 01`, 0 elsewhere. */
    private fun startCodeLengthAt(p: ByteArray, k: Int): Int {
        if (k + 3 < p.size && p[k] == 0.toByte() && p[k + 1] == 0.toByte() && p[k + 2] == 0.toByte() && p[k + 3] == 1.toByte()) return 4
        if (k + 2 < p.size && p[k] == 0.toByte() && p[k + 1] == 0.toByte() && p[k + 2] == 1.toByte() &&
            (k + 3 >= p.size || p[k + 3] != 0.toByte())
        ) return 3
        return 0
    }

    /** Splits an Annex-B payload into whole NAL units (each still start-coded). */
    fun splitNals(payload: ByteArray): List<ByteArray> = nals(payload)

    private fun nals(payload: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var i = 0
        val n = payload.size
        // find first start code
        var scan = 0
        while (scan + 3 < n && !isStartCode(payload, scan)) scan++
        if (scan + 3 >= n) return result
        var start = scan
        var cur = scan + 4
        while (cur < n) {
            if (isStartCode(payload, cur)) {
                result.add(payload.copyOfRange(start, cur))
                start = cur
                cur += 4
            } else {
                cur++
            }
        }
        if (start < n) result.add(payload.copyOfRange(start, n))
        return result
    }

    private fun isStartCode(payload: ByteArray, at: Int): Boolean {
        if (at + 3 >= payload.size) return false
        if (payload[at] == 0x00.toByte() && payload[at + 1] == 0x00.toByte() &&
            payload[at + 2] == 0x00.toByte() && payload[at + 3] == 0x01.toByte()
        ) return true
        // tolerate the 3-byte 00 00 01 prefix
        return payload[at] == 0x00.toByte() && payload[at + 1] == 0x00.toByte() &&
            payload[at + 2] == 0x01.toByte()
    }
}