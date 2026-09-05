package com.nanokvm.app.data.hid

import android.view.KeyEvent

/**
 * HID usage codes and modifier bits, ported 1:1 from the NanoKVM-Pro web client
 * (`web/src/lib/keymap.ts`). The web maps browser `event.code` (physical key) to a
 * HID usage; on Android we map `KeyEvent.keyCode` (also physical, layout-stable) to
 * the same usage table. Symbol positions (0x1e..0x38) are US layout, matching the
 * device's HID firmware; the host OS keyboard layout decides the printed character.
 */
object HidKeymap {
    // Modifier bits — high bit of an 8-byte report.
    const val MOD_LCTRL = 0x01
    const val MOD_LSHIFT = 0x02
    const val MOD_LALT = 0x04
    const val MOD_LMETA = 0x08
    const val MOD_RCTRL = 0x10
    const val MOD_RSHIFT = 0x20
    const val MOD_RALT = 0x40
    const val MOD_RMETA = 0x80

    // ---- HID usage codes (quad 1 keyboards) ----
    // Letters A..Z = 0x04..0x1d
    // Digits 1..0 (top row) = 0x1e..0x27
    const val HID_DIGIT_0 = 0x27
    const val HID_ENTER = 0x28
    const val HID_ESCAPE = 0x29
    const val HID_BACKSPACE = 0x2a
    const val HID_TAB = 0x2b
    const val HID_SPACE = 0x2c
    const val HID_MINUS = 0x2d
    const val HID_EQUAL = 0x2e
    const val HID_BRACKET_LEFT = 0x2f
    const val HID_BRACKET_RIGHT = 0x30
    const val HID_BACKSLASH = 0x31
    const val HID_SEMICOLON = 0x33
    const val HID_QUOTE = 0x34
    const val HID_GRAVE = 0x35
    const val HID_COMMA = 0x36
    const val HID_PERIOD = 0x37
    const val HID_SLASH = 0x38
    const val HID_CAPS_LOCK = 0x39
    // F1..F12
    const val HID_F1 = 0x3a
    const val HID_F4 = 0x3d
    const val HID_F5 = 0x3e
    const val HID_F8 = 0x41
    const val HID_F9 = 0x42
    const val HID_F10 = 0x43
    const val HID_F11 = 0x44
    const val HID_F12 = 0x45
    const val HID_PRINT_SCREEN = 0x46
    const val HID_SCROLL_LOCK = 0x47
    const val HID_PAUSE = 0x48
    const val HID_INSERT = 0x49
    const val HID_HOME = 0x4a
    const val HID_PAGE_UP = 0x4b
    const val HID_DELETE = 0x4c
    const val HID_END = 0x4d
    const val HID_PAGE_DOWN = 0x4e
    const val HID_ARROW_RIGHT = 0x4f
    const val HID_ARROW_LEFT = 0x50
    const val HID_ARROW_DOWN = 0x51
    const val HID_ARROW_UP = 0x52
    const val HID_NUM_LOCK = 0x53
    const val HID_NUMPAD_DIVIDE = 0x54
    const val HID_NUMPAD_MULTIPLY = 0x55
    const val HID_NUMPAD_SUBTRACT = 0x56
    const val HID_NUMPAD_ADD = 0x57
    const val HID_NUMPAD_ENTER = 0x58
    const val HID_NUMPAD_1 = 0x59
    const val HID_NUMPAD_4 = 0x5c
    const val HID_NUMPAD_7 = 0x5f
    const val HID_NUMPAD_0 = 0x62
    const val HID_NUMPAD_DECIMAL = 0x63
    const val HID_CONTEXT_MENU = 0x65
    const val HID_VOLUME_MUTE = 0x7f
    const val HID_VOLUME_UP = 0x80
    const val HID_VOLUME_DOWN = 0x81
    const val HID_MEDIA_PLAY_PAUSE = 0xe8
    const val HID_MEDIA_STOP = 0xe9
    const val HID_MEDIA_PREVIOUS = 0xea
    const val HID_MEDIA_NEXT = 0xeb
    const val HID_EJECT = 0xec

    /** Modifier bit for physical modifier keys, else 0. */
    fun modifierBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT -> MOD_LCTRL
        KeyEvent.KEYCODE_CTRL_RIGHT -> MOD_RCTRL
        KeyEvent.KEYCODE_SHIFT_LEFT -> MOD_LSHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> MOD_RSHIFT
        KeyEvent.KEYCODE_ALT_LEFT -> MOD_LALT
        KeyEvent.KEYCODE_ALT_RIGHT -> MOD_RALT
        KeyEvent.KEYCODE_META_LEFT -> MOD_LMETA
        KeyEvent.KEYCODE_META_RIGHT -> MOD_RMETA
        else -> 0
    }

    /**
     * HID usage for a physical (non-modifier) key, or null for unmapped codes.
     * Letters and top-row digits are contiguous ranges.
     */
    fun hidUsage(keyCode: Int): Int? = when {
        keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            0x04 + (keyCode - KeyEvent.KEYCODE_A)
        keyCode == KeyEvent.KEYCODE_0 ->
            0x27
        keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 ->
            0x1e + (keyCode - KeyEvent.KEYCODE_1)
        keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            0x3a + (keyCode - KeyEvent.KEYCODE_F1)
        keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
            0x59 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        else -> when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> HID_ENTER
            KeyEvent.KEYCODE_ESCAPE -> HID_ESCAPE
            KeyEvent.KEYCODE_DEL -> HID_BACKSPACE
            KeyEvent.KEYCODE_TAB -> HID_TAB
            KeyEvent.KEYCODE_SPACE -> HID_SPACE
            KeyEvent.KEYCODE_MINUS -> HID_MINUS
            KeyEvent.KEYCODE_EQUALS -> HID_EQUAL
            KeyEvent.KEYCODE_LEFT_BRACKET -> HID_BRACKET_LEFT
            KeyEvent.KEYCODE_RIGHT_BRACKET -> HID_BRACKET_RIGHT
            KeyEvent.KEYCODE_BACKSLASH -> HID_BACKSLASH
            KeyEvent.KEYCODE_SEMICOLON -> HID_SEMICOLON
            KeyEvent.KEYCODE_APOSTROPHE -> HID_QUOTE
            KeyEvent.KEYCODE_GRAVE -> HID_GRAVE
            KeyEvent.KEYCODE_COMMA -> HID_COMMA
            KeyEvent.KEYCODE_PERIOD -> HID_PERIOD
            KeyEvent.KEYCODE_SLASH -> HID_SLASH
            KeyEvent.KEYCODE_CAPS_LOCK -> HID_CAPS_LOCK
            KeyEvent.KEYCODE_SYSRQ -> HID_PRINT_SCREEN
            KeyEvent.KEYCODE_SCROLL_LOCK -> HID_SCROLL_LOCK
            KeyEvent.KEYCODE_BREAK -> HID_PAUSE
            KeyEvent.KEYCODE_INSERT -> HID_INSERT
            KeyEvent.KEYCODE_HOME -> HID_HOME
            KeyEvent.KEYCODE_PAGE_UP -> HID_PAGE_UP
            KeyEvent.KEYCODE_FORWARD_DEL -> HID_DELETE
            KeyEvent.KEYCODE_MOVE_END -> HID_END
            KeyEvent.KEYCODE_PAGE_DOWN -> HID_PAGE_DOWN
            KeyEvent.KEYCODE_DPAD_RIGHT -> HID_ARROW_RIGHT
            KeyEvent.KEYCODE_DPAD_LEFT -> HID_ARROW_LEFT
            KeyEvent.KEYCODE_DPAD_DOWN -> HID_ARROW_DOWN
            KeyEvent.KEYCODE_DPAD_UP -> HID_ARROW_UP
            KeyEvent.KEYCODE_NUM_LOCK -> HID_NUM_LOCK
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> HID_NUMPAD_DIVIDE
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> HID_NUMPAD_MULTIPLY
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> HID_NUMPAD_SUBTRACT
            KeyEvent.KEYCODE_NUMPAD_ADD -> HID_NUMPAD_ADD
            KeyEvent.KEYCODE_NUMPAD_ENTER -> HID_NUMPAD_ENTER
            KeyEvent.KEYCODE_NUMPAD_DOT -> HID_NUMPAD_DECIMAL
            KeyEvent.KEYCODE_MENU -> HID_CONTEXT_MENU
            KeyEvent.KEYCODE_MUTE -> HID_VOLUME_MUTE
            KeyEvent.KEYCODE_VOLUME_UP -> HID_VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> HID_VOLUME_DOWN
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> HID_MEDIA_PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_STOP -> HID_MEDIA_STOP
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> HID_MEDIA_PREVIOUS
            KeyEvent.KEYCODE_MEDIA_NEXT -> HID_MEDIA_NEXT
            KeyEvent.KEYCODE_MEDIA_EJECT -> HID_EJECT
            else -> null
        }
    }
}