package com.nanokvm.app.data.hid

import java.util.LinkedHashMap

/**
 * 8-byte HID keyboard report builder, ported from `web/src/lib/keyboard.ts`.
 *
 *    byte 0        modifier bitmap
 *    byte 1        reserved (0x00)
 *    bytes 2..7    up to 6 HID usages (6-key rollover; the 7th simultaneous key
 *                  is dropped like the web client's `pressedKeys.size < MAX_KEYS`)
 *
 * Keys are addressed by a caller-supplied stable id (e.g. first key down wins the
 * frame) so a press/release pair always hits the same slot.
 */
class KeyboardCodec {
    companion object {
        const val MAX_KEYS = 6
    }

    private var modifier = 0

    /** id -> (usage). Modifier keys are not in this list; they live in [modifier]. */
    private val pressedKeys: LinkedHashMap<Any, Int> = LinkedHashMap()

    /** Report for a key-down. Returns the *previous* report untouched if it would
     *  exceed rollover (the key is simply not inserted, matching the web). */
    fun keyDown(key: Any, modifierBit: Int, usage: Int?): ByteArray {
        if (modifierBit != 0) {
            modifier = modifier or modifierBit
            return buildReport()
        }
        val code = usage ?: return buildReport()
        if (pressedKeys.containsKey(key) || pressedKeys.size < MAX_KEYS) {
            pressedKeys[key] = code
        }
        return buildReport()
    }

    fun keyUp(key: Any): ByteArray {
        pressedKeys.remove(key)
        return buildReport()
    }

    /** Releases a modifier bit explicitly. */
    fun modifierUp(modifierBit: Int): ByteArray {
        modifier = modifier and modifierBit.inv()
        return buildReport()
    }

    /** Clears all state — used to unstick keys. */
    fun reset(): ByteArray {
        modifier = 0
        pressedKeys.clear()
        return buildReport()
    }

    val isDirtyState: Boolean
        get() = modifier != 0 || pressedKeys.isNotEmpty()

    private fun buildReport(): ByteArray {
        val report = ByteArray(8)
        report[0] = modifier.toByte()
        report[1] = 0x00
        var i = 2
        for (usage in pressedKeys.values) {
            if (i >= 8) break
            report[i++] = usage.toByte()
        }
        return report
    }
}