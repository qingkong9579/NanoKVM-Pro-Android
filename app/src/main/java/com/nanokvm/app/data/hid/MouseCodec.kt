package com.nanokvm.app.data.hid

/**
 * Mouse report encoders, ported from `web/src/lib/mouse.ts`.
 * The report bytes are appended after the `type=2` prefix by the input WebSocket.
 */
object MouseButton {
    const val LEFT = 1 shl 0
    const val RIGHT = 1 shl 1
    const val MIDDLE = 1 shl 2
    const val BACK = 1 shl 3
    const val FORWARD = 1 shl 4
}

object MouseCodec {
    private const val MAX_SIGNED_8 = 127

    /**
     * Relative report (4 bytes): buttons, dx, dy, wheel — each signed, clamped to
     * [-127, 127] by the web client's `clamp(Math.round(v), -127, 127)`.
     */
    fun relative(buttons: Int, deltaX: Int, deltaY: Int, wheel: Int = 0): ByteArray {
        val report = ByteArray(4)
        report[0] = buttons.toByte()
        report[1] = clampS8(deltaX)
        report[2] = clampS8(deltaY)
        report[3] = clampS8(wheel)
        return report
    }

    /**
     * Absolute report (6 bytes): buttons, X low/high (LE 0..0x7fff), Y low/high (LE),
     * wheel. Inputs are already the device-space coordinates from [normalize].
     */
    fun absolute(buttons: Int, x: Int, y: Int, wheel: Int = 0): ByteArray {
        val report = ByteArray(6)
        report[0] = buttons.toByte()
        report[1] = (x and 0xff).toByte()
        report[2] = ((x shr 8) and 0xff).toByte()
        report[3] = (y and 0xff).toByte()
        report[4] = ((y shr 8) and 0xff).toByte()
        report[5] = clampS8(wheel)
        return report
    }

    /**
     * Normalized 0..1 touch point -> device absolute coordinates, matching the web:
     * `floor(0x7fff * clamped) + 1`.
     */
    fun normalize(nx: Float, ny: Float): Pair<Int, Int> {
        val x = nx.coerceIn(0f, 1f)
        val y = ny.coerceIn(0f, 1f)
        val px = (32767 * x).toInt() /* floor for positive */ + 1
        val py = (32767 * y).toInt() + 1
        return px to py
    }

    private fun clampS8(v: Int): Byte {
        val c = v.coerceIn(-MAX_SIGNED_8, MAX_SIGNED_8)
        return c.toByte()
    }
}