package com.nanokvm.app.data.api

import kotlinx.serialization.Serializable

// --- Auth ---
@Serializable
data class AuthRequest(val username: String, val password: String)

@Serializable
data class AuthResponse(val token: String)

// --- Stream configuration ---
@Serializable
data class SetModeRequest(val mode: String)

@Serializable
data class RateControlRequest(val mode: String)

@Serializable
data class QualityRequest(val quality: Int)

@Serializable
data class GopRequest(val gop: Int)

@Serializable
data class FpsRequest(val fps: Int)

// --- HID ---
@Serializable
data class HidModeRequest(val mode: String)

/**
 * Uniform response envelope for every NanoKVM-Pro endpoint.
 * `code == 0` means success; `data` varies per endpoint.
 */
@Serializable
data class ApiEnvelope<T>(
    val code: Int = -1,
    val msg: String? = null,
    val data: T? = null,
)

/** Stream modes accepted by `POST /api/stream/mode`. */
object StreamMode {
    const val H264_DIRECT = "h264-direct"
    const val H265_DIRECT = "h265-direct"
    const val H264_WEBRTC = "h264-webrtc"
    const val H265_WEBRTC = "h265-webrtc"
    const val MJPEG = "mjpeg"
}

/** Mouse positioning modes for `GET/POST /api/hid/mode`. */
object HidMouseMode {
    const val ABSOLUTE = "absolute"
    const val RELATIVE = "relative"
}

/** Default web stream parameters (mirrors the web client's entry sequence). */
object StreamDefaults {
    const val RATE_CONTROL = "vbr"
    const val BITRATE = 8000
    const val GOP = 50
    const val FPS = 0
}

// --- Console tools (ported from the web menus) ---

@Serializable
data class IpAddr(val name: String = "", val addr: String = "", val version: String = "", val type: String = "")

@Serializable
data class DeviceInfo(
    val ips: List<IpAddr>? = null,
    val mdns: String? = null,
    val image: String? = null,
    val application: String? = null,
    val deviceKey: String? = null,
    val pn: String? = null,
    val arch: String? = null,
)

@Serializable
data class GpioState(val pwr: Boolean = false, val hdd: Boolean = false)

@Serializable
data class HidModeState(val mode: String = "")

@Serializable
data class MountedImage(val file: String = "", val cdrom: Boolean = false, val readOnly: Boolean = false)

@Serializable
data class FileList(val files: List<String>? = null)

@Serializable
data class WolMacList(val macs: List<String>? = null)

@Serializable
data class ScriptRunResult(val log: String? = null)

@Serializable
data class EdidState(val edid: String = "")

@Serializable
data class EdidList(val edidList: List<String>? = null)

@Serializable
data class MouseJigglerState(val enabled: Boolean = false, val mode: String? = null)

@Serializable
data class AccountState(val username: String = "")

/** Thrown when the device returns `code != 0` or an HTTP-level failure. */
class ApiException(val errorCode: Int, message: String) : Exception(message)