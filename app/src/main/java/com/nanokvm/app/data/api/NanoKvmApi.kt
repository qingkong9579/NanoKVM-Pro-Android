package com.nanokvm.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import com.nanokvm.app.data.net.Tls
import java.util.concurrent.TimeUnit
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin typed wrapper over the NanoKVM-Pro REST surface.
 *
 * All calls are `POST` with a JSON body (the server binds with Gin `ShouldBind`,
 * `application/json` is accepted) and return the `{code, msg, data}` envelope.
 * REST auth uses `Authorization: Bearer <JWT>`; the WebSocket upgrade re-uses the
 * same JWT via the `Cookie: nano-kvm-token=<JWT>` header (see the ws package).
 */
class NanoKvmApi(
    private val baseUrl: String,
    private val okHttp: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** JWT from login; consumed by the REST interceptor and the WS handshakes. */
    @Volatile
    var token: String? = null
        private set

    suspend fun login(username: String, password: String): String {
        val envelope = post<AuthResponse>(
            "/api/auth/login",
            buildJsonObject {
                put("username", username)
                put("password", password)
            },
        )
        if (envelope.code != 0) throw ApiException(envelope.code, "login: ${envelope.msg ?: "failed"}")
        val t = envelope.data?.token ?: throw ApiException(envelope.code, "login response is missing token")
        token = t
        return t
    }

    suspend fun logout() {
        try {
            post<JsonObject>("/api/auth/logout", buildJsonObject { })
        } catch (_: Exception) {
            // Best-effort; always clear the local token.
        }
        token = null
    }

    suspend fun setStreamMode(mode: String) = configure("/api/stream/mode", JsonPrimitive(mode), "mode")
    suspend fun setRateControl(mode: String) = configure("/api/stream/rate-control", JsonPrimitive(mode), "mode")
    suspend fun setQuality(quality: Int) = configure("/api/stream/quality", JsonPrimitive(quality), "quality")
    suspend fun setGop(gop: Int) = configure("/api/stream/gop", JsonPrimitive(gop), "gop")
    suspend fun setFps(fps: Int) = configure("/api/stream/fps", JsonPrimitive(fps), "fps")

    /** Applies the web client's full entry sequence before opening a stream. */
    suspend fun configureStream(mode: String, webDefaults: Boolean = true) {
        setStreamMode(mode)
        if (webDefaults) {
            setRateControl(StreamDefaults.RATE_CONTROL)
            setQuality(StreamDefaults.BITRATE)
            setGop(StreamDefaults.GOP)
            setFps(StreamDefaults.FPS)
        }
    }

    suspend fun setHidMode(mode: String) = configure("/api/hid/mode", JsonPrimitive(mode), "mode")

    suspend fun getHidMode(): HidModeState = data("/api/hid/mode") ?: HidModeState()

    /** 服务端逐字符 USB HID 回放(web paste.tsx,≤1024 字符)。 */
    suspend fun hidPaste(content: String) = configure("/api/hid/paste", buildJsonObject { put("content", content) }, "")

    /** 重置整个 USB HID gadget(先断输入 WS 再调,web reset-hid.tsx)。 */
    suspend fun hidReset() = configure("/api/hid/reset", buildJsonObject { }, "")

    // ---- 电源 (web menu/power) ----
    suspend fun gpioPower(type: String, durationMs: Int) =
        configure("/api/vm/gpio", buildJsonObject { put("type", type); put("duration", durationMs) }, "")

    suspend fun gpioState(): GpioState = data("/api/vm/gpio") ?: GpioState()

    /** 重启 NanoKVM 设备系统本身(web settings/device/reboot)。 */
    suspend fun rebootSystem() = configure("/api/vm/system/reboot", buildJsonObject { }, "")

    // ---- 屏幕 (web menu/screen) ----
    suspend fun edidCurrent(): EdidState = data("/api/vm/edid") ?: EdidState()

    suspend fun edidCustomList(): EdidList = data("/api/vm/edid/custom") ?: EdidList()

    suspend fun switchEdid(edid: String) = configure("/api/vm/edid", buildJsonObject { put("edid", edid) }, "")

    suspend fun mouseJiggler(): MouseJigglerState = data("/api/vm/mouse-jiggler") ?: MouseJigglerState()

    suspend fun setMouseJiggler(enabled: Boolean, mode: String) =
        configure("/api/vm/mouse-jiggler", buildJsonObject { put("enabled", enabled); put("mode", mode) }, "")

    // ---- 镜像 (web menu/image) ----
    suspend fun storageImages(): FileList = data("/api/storage/image") ?: FileList()

    suspend fun storageMounted(): MountedImage = data("/api/storage/image/mounted") ?: MountedImage()

    /** file 为空 = 卸载(web 约定)。 */
    suspend fun storageMount(file: String, cdrom: Boolean = false, readOnly: Boolean = true) =
        configure("/api/storage/image/mount", buildJsonObject {
            put("file", file); put("cdrom", cdrom); put("readOnly", readOnly)
        }, "")

    // ---- 脚本 (web menu/script) ----
    suspend fun scripts(): FileList = data("/api/vm/script") ?: FileList()

    suspend fun runScript(name: String, type: String): ScriptRunResult? {
        val envelope = post<JsonObject>("/api/vm/script/run", buildJsonObject { put("name", name); put("type", type) })
        if (envelope.code != 0) throw ApiException(envelope.code, "/api/vm/script/run: ${envelope.msg ?: "rejected"}")
        val element = envelope.data ?: return null
        return json.decodeFromString<ScriptRunResult>(json.encodeToString(JsonObject.serializer(), element))
    }

    // ---- WOL (web menu/wol) ----
    suspend fun wolMacs(): WolMacList = data("/api/network/wol/mac") ?: WolMacList()

    suspend fun wolWake(mac: String) = configure("/api/network/wol", buildJsonObject { put("mac", mac) }, "")

    suspend fun wolRename(mac: String, name: String) =
        configure("/api/network/wol/mac/name", buildJsonObject { put("mac", mac); put("name", name) }, "")

    suspend fun wolDelete(mac: String) = delete("/api/network/wol/mac", buildJsonObject { put("mac", mac) })

    // ---- 设备信息 (web settings/about + menu) ----
    suspend fun deviceInfo(): DeviceInfo = data("/api/vm/info") ?: DeviceInfo()

    suspend fun account(): AccountState = data("/api/auth/account") ?: AccountState()

    // ---- 终端 & 智能助手 (web #terminal + menu/assistant) ----
    /** Basic(SSH) 校验终端权限:GET /api/vm/terminal w/ Basic;服务端同时要求 token。 */
    suspend fun terminalAuthBasic(username: String, password: String) {
        val basic = "Basic " + android.util.Base64.encodeToString("$username:$password".toByteArray(), android.util.Base64.NO_WRAP)
        val envelope = call<JsonObject>("GET", "/api/vm/terminal", extraHeader = "Authorization" to basic)
        if (envelope.code != 0) throw ApiException(envelope.code, "terminal auth: ${envelope.msg ?: "rejected"}")
    }

    suspend fun assistantInstall() = configure("/api/extensions/assistant/install", buildJsonObject { }, "")
    suspend fun assistantStart() = configure("/api/extensions/assistant/start", buildJsonObject { }, "")

    // --- internals ---

    private suspend fun configure(path: String, value: JsonElement, key: String) {
        val envelope = post<JsonObject>(path, if (key.isEmpty()) value as JsonObject else buildJsonObject { put(key, value) })
        if (envelope.code != 0) throw ApiException(envelope.code, "$path: ${envelope.msg ?: "rejected"}")
    }

    private suspend inline fun <reified R : Any> data(path: String): R? {
        val envelope = call<JsonObject>("GET", path)
        if (envelope.code != 0) throw ApiException(envelope.code, "$path: ${envelope.msg ?: "rejected"}")
        val element = envelope.data ?: return null
        return json.decodeFromString<R>(json.encodeToString(JsonObject.serializer(), element))
    }

    private suspend fun delete(path: String, body: JsonObject) {
        val envelope = call<JsonObject>("DELETE", path, body)
        if (envelope.code != 0) throw ApiException(envelope.code, "$path: ${envelope.msg ?: "rejected"}")
    }

    private suspend inline fun <reified R : Any> post(path: String, body: JsonElement): ApiEnvelope<R> =
        call("POST", path, body)

    private suspend inline fun <reified R : Any> call(
        method: String,
        path: String,
        body: JsonElement? = null,
        extraHeader: Pair<String, String>? = null,
    ): ApiEnvelope<R> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(baseUrl + path)
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer ${token.orEmpty()}")
            extraHeader?.let { builder.header(it.first, it.second) }
            val request = when (method) {
                "GET" -> builder.get().build()
                "DELETE" -> builder.delete(body.toString().toRequestBody(jsonMedia)).build()
                else -> builder.post((body ?: buildJsonObject { }).toString().toRequestBody(jsonMedia)).build()
            }

            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(response.code, "HTTP ${response.code} from $path")
                }
                json.decodeFromString<ApiEnvelope<R>>(response.body?.string().orEmpty())
            }
        }

    // --- 设备基础设置(web 设置: about/device/network) ---

    suspend fun getHostname(): String = data<HostnameState>("/api/vm/hostname")?.hostname.orEmpty()

    suspend fun setHostname(hostname: String) =
        configure("/api/vm/hostname", buildJsonObject { put("hostname", hostname) }, "")

    suspend fun getSshState(): Boolean = data<BoolState>("/api/vm/ssh")?.enabled ?: false

    suspend fun setSshState(on: Boolean) = if (on) {
        configure("/api/vm/ssh/enable", buildJsonObject { }, "")
    } else {
        configure("/api/vm/ssh/disable", buildJsonObject { }, "")
    }

    suspend fun getMdnsState(): Boolean = data<BoolState>("/api/vm/mdns")?.enabled ?: false

    suspend fun setMdnsState(on: Boolean) = if (on) {
        configure("/api/vm/mdns/enable", buildJsonObject { }, "")
    } else {
        configure("/api/vm/mdns/disable", buildJsonObject { }, "")
    }

    suspend fun getHdmiCapture(): Boolean = data<BoolState>("/api/vm/hdmi/capture")?.enabled ?: false

    suspend fun setHdmiCapture(on: Boolean) =
        configure("/api/vm/hdmi/capture", buildJsonObject { put("enabled", on) }, "")

    suspend fun getHdmiPassthrough(): Boolean = data<BoolState>("/api/vm/hdmi/passthrough")?.enabled ?: false

    suspend fun setHdmiPassthrough(on: Boolean) =
        configure("/api/vm/hdmi/passthrough", buildJsonObject { put("enabled", on) }, "")

    suspend fun getStaticIp(): StaticIpState = data<StaticIpState>("/api/network/static-ip") ?: StaticIpState()

    suspend fun setStaticIp(ip: String) {
        val enabled = ip.isNotBlank()
        configure(
            "/api/network/static-ip",
            buildJsonObject {
                put("enabled", enabled)
                put("ip", ip)
            },
            "",
        )
    }

    suspend fun getWifiState(): WifiState = data<WifiState>("/api/network/wifi") ?: WifiState()

    suspend fun scanWifi(): List<WifiInfo> = data<WifiScan>("/api/network/wifi/scan")?.wifiList.orEmpty()

    suspend fun connectWifi(ssid: String, password: String) =
        configure("/api/network/wifi/connect", buildJsonObject { put("ssid", ssid); put("password", password) }, "")

    suspend fun disconnectWifi() = configure("/api/network/wifi/disconnect", buildJsonObject { }, "")

    // --- 显示 / 外设 / 时间 / 账户(web settings device+datetime+account) ---

    suspend fun getOled(): OledState = data<OledState>("/api/vm/oled") ?: OledState()

    suspend fun setOledSleep(sleep: Int) =
        configure("/api/vm/oled", buildJsonObject { put("sleep", sleep) }, "")

    suspend fun getLcdTimeFormat(): String = data<FormatState>("/api/vm/lcd/time/format")?.format.orEmpty()

    suspend fun setLcdTimeFormat(format: String) =
        configure("/api/vm/lcd/time/format", buildJsonObject { put("format", format) }, "")

    suspend fun getLcdScreenOff(): ScreenOffState = data<ScreenOffState>("/api/vm/lcd/screen-off") ?: ScreenOffState()

    suspend fun setLcdScreenOff(enabled: Boolean, startMinute: Int, endMinute: Int) =
        configure(
            "/api/vm/lcd/screen-off",
            buildJsonObject {
                put("enabled", enabled)
                put("startMinute", startMinute)
                put("endMinute", endMinute)
            },
            "",
        )

    suspend fun getLedConfig(): LedState = data<LedState>("/api/vm/ledstrip/get") ?: LedState()

    suspend fun setLedConfig(on: Boolean, hor: Int, ver: Int, brightness: Int) =
        configure(
            "/api/vm/ledstrip/set",
            buildJsonObject {
                put("on", on)
                put("hor", hor)
                put("ver", ver)
                put("brightness", brightness)
            },
            "",
        )

    suspend fun getLowPower(): Boolean = data<BoolState>("/api/vm/low-power")?.enabled ?: false

    suspend fun setLowPower(on: Boolean) =
        configure("/api/vm/low-power", buildJsonObject { put("enable", on) }, "")

    suspend fun getTimeZone(): String = data<TimeZoneState>("/api/vm/timezone")?.timezone.orEmpty()

    suspend fun setTimeZone(timezone: String) =
        configure("/api/vm/timezone", buildJsonObject { put("timezone", timezone) }, "")

    suspend fun getTimeStatus(): TimeStatusState = data<TimeStatusState>("/api/vm/time/status") ?: TimeStatusState()

    suspend fun syncTime() = configure("/api/vm/time/sync", buildJsonObject { }, "")

    suspend fun getAccount(): String = data<AccountState>("/api/auth/account")?.username.orEmpty()

    suspend fun changePassword(username: String, password: String) =
        configure(
            "/api/auth/password",
            buildJsonObject {
                put("username", username)
                put("password", password)
            },
            "",
        )

    // --- 虚拟设备 / Tailscale ---

    suspend fun getVirtualDevices(): VirtualDevState = data<VirtualDevState>("/api/vm/device/virtual") ?: VirtualDevState()

    suspend fun toggleVirtualDevice(device: String, type: String? = null) {
        val body = buildJsonObject {
            put("device", device)
            type?.let { put("type", it) }
        }
        configure("/api/vm/device/virtual", body, "")
    }

    suspend fun refreshVirtualDevice(device: String) =
        configure("/api/vm/device/virtual/refresh", buildJsonObject { put("device", device) }, "")

    suspend fun getTailscale(): TailscaleStatus = data<TailscaleStatus>("/api/extensions/tailscale/status") ?: TailscaleStatus()

    suspend fun tailscaleAction(action: String): String? {
        val envelope = call<LoginTailscaleRsp>("POST", "/api/extensions/tailscale/$action", buildJsonObject { })
        return envelope.data?.url
    }

    // --- 上传 / 校验 / 更新(web storage + application) ---

    /** 上传自定义 EDID(.bin),multipart 字段 file。 */
    suspend fun uploadEdidFile(file: java.io.File) = postMultipart("/api/vm/edid/upload", file)

    suspend fun edidDelete(edid: String) =
        configure("/api/vm/edid/delete", buildJsonObject { put("edid", edid) }, "")

    /** 镜像列表 / 删除 / 校验。 */
    suspend fun storageDeleteImage(file: String) =
        configure("/api/storage/image/delete", buildJsonObject { put("file", file) }, "")

    suspend fun storageChecksum(file: String, algorithm: String = "sha256"): String? {
        val envelope = call<JsonElement>(
            "POST",
            "/api/storage/image/checksum",
            buildJsonObject { put("file", file); put("algorithm", algorithm) },
        )
        return envelope.data?.toString()
    }

    /** 分片上传镜像(与 web 端一致:chunkIndex/chunkSize/totalChunks + file)。 */
    suspend fun uploadImageChunked(file: java.io.File, onProgress: (doneChunks: Int, totalChunks: Int) -> Unit) {
        val chunkSize = 8 * 1024 * 1024
        val total = ((file.length() + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
        val buffer = ByteArray(chunkSize)
        file.inputStream().use { input ->
            repeat(total) { idx ->
                val read = input.read(buffer)
                if (read <= 0) throw ApiException(-1, "文件读取失败(空文件或已截断)")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chunkIndex", idx.toString())
                    .addFormDataPart("chunkSize", chunkSize.toString())
                    .addFormDataPart("totalChunks", total.toString())
                    .addFormDataPart(
                        "file",
                        file.name,
                        buffer.copyOf(read).toRequestBody("application/octet-stream".toMediaType()),
                    )
                    .build()
                val envelope = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(baseUrl + "/api/storage/image/upload")
                        .addHeader("Authorization", "Bearer ${token.orEmpty()}")
                        .post(body)
                        .build()
                    okHttp.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code} from /api/storage/image/upload")
                        json.decodeFromString<ApiEnvelope<JsonObject>>(resp.body?.string().orEmpty())
                    }
                }
                if (envelope.code != 0) throw ApiException(envelope.code, "upload: ${envelope.msg ?: "rejected"}")
                onProgress(idx + 1, total)
            }
        }
    }

    /** 应用版本 / 预览通道 / 在线更新(下载安装,可能耗时数分钟)。 */
    suspend fun appVersion(): AppVersion = data("/api/application/version") ?: AppVersion()

    suspend fun appPreviewEnabled(): Boolean = data<BoolState>("/api/application/preview")?.enabled ?: false

    suspend fun setAppPreview(on: Boolean) =
        configure("/api/application/preview", buildJsonObject { put("enable", on) }, "")

    suspend fun updateApplication() {
        withContext(Dispatchers.IO) {
            val longClient = Tls.okHttpBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.MINUTES)
                .build()
            val request = Request.Builder()
                .url(baseUrl + "/api/application/update")
                .addHeader("Authorization", "Bearer ${token.orEmpty()}")
                .post(buildJsonObject { }.toString().toRequestBody(jsonMedia))
                .build()
            longClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code} from /api/application/update")
                val envelope = json.decodeFromString<ApiEnvelope<JsonObject>>(resp.body?.string().orEmpty())
                if (envelope.code != 0) throw ApiException(envelope.code, "update: ${envelope.msg ?: "rejected"}")
            }
        }
    }

    private suspend fun postMultipart(path: String, file: java.io.File) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl + path)
                .addHeader("Authorization", "Bearer ${token.orEmpty()}")
                .post(body)
                .build()
            okHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code} from $path")
                val envelope = json.decodeFromString<ApiEnvelope<JsonObject>>(resp.body?.string().orEmpty())
                if (envelope.code != 0) throw ApiException(envelope.code, "$path: ${envelope.msg ?: "rejected"}")
            }
        }
    }

    /**
     * 设备健康采样(CPU/内存/温度/负载)——设备无现成 API,经 /api/vm/terminal root shell
     * 读 /proc/stat、/proc/meminfo、thermal_zone;每次请求独立 pty,返回后即断开。
     * CPU 占用由调用方对连续两次 [DeviceStats.cpuTotal]/[cpuIdle] 差分计算。
     */
    suspend fun fetchDeviceStats(): DeviceStats? = withContext(Dispatchers.IO) {
        val host = baseUrl.removePrefix("https://").removePrefix("http://")
        val client = Tls.okHttpBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val out = java.io.ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        val cmd = "echo __S__; head -1 /proc/stat; grep -E 'MemTotal|MemAvailable' /proc/meminfo; cat /proc/loadavg; " +
            "for z in /sys/class/thermal/thermal_zone*/temp; do if [ -r \"\$z\" ]; then echo TEMP \$(cat \"\$z\"); break; fi; done; echo __E__\nexit\n"
        var failed: Throwable? = null
        val ws = client.newWebSocket(
            Request.Builder()
                .url("wss://$host/api/vm/terminal")
                .addHeader("Cookie", "nano-kvm-token=${token.orEmpty()}")
                .addHeader("Authorization", "Bearer ${token.orEmpty()}")
                .build(),
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.send(cmd)
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    out.write(text.toByteArray())
                    if (text.contains("__E__")) done.countDown()
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                    out.write(bytes.toByteArray())
                    if (bytes.utf8().contains("__E__")) done.countDown()
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    failed = t
                    done.countDown()
                }
            },
        )
        try {
            if (!done.await(8, TimeUnit.SECONDS)) failed = Exception("stats timeout")
            if (failed == null) {
                // pty 会先回显整条命令(含 __E__),真正输出随后才到:多等一拍收尾
                Thread.sleep(600)
            }
        } finally {
            runCatching { ws.close(1000, "done") }
            runCatching { client.dispatcher.executorService.shutdown() }
        }
        if (failed != null) {
            android.util.Log.w("NanoKvmApi", "stats fetch failed: ${failed?.message}")
            null
        } else {
            parseDeviceStats(out.toString(Charsets.UTF_8))
        }
    }

    private fun parseDeviceStats(text: String): DeviceStats? {
        var cpuTotal = 0L
        var cpuIdle = -1L
        var memTotal = 0L
        var memAvail = 0L
        var tempC = Float.NaN
        var load1 = Float.NaN
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("cpu ") -> {
                    val nums = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (nums.size >= 4) {
                        cpuTotal = nums.sum()
                        cpuIdle = nums[3] + (nums.getOrElse(4) { 0L }) // idle + iowait
                    }
                }
                line.startsWith("MemTotal:") -> memTotal = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                line.startsWith("MemAvailable:") -> memAvail = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                line.startsWith("TEMP ") -> {
                    val milli = line.split(Regex("\\s+")).getOrNull(1)?.toFloatOrNull()
                    if (milli != null) tempC = milli / 1000f
                }
                else -> {
                    val f = line.trim().split(Regex("\\s+")).firstOrNull()?.toFloatOrNull()
                    if (f != null && load1.isNaN()) load1 = f
                }
            }
        }
        if (cpuTotal <= 0) return null
        return DeviceStats(cpuTotal, cpuIdle, memTotal, memAvail, tempC, load1)
    }
}

@kotlinx.serialization.Serializable
data class DeviceStats(
    val cpuTotal: Long = 0,
    val cpuIdle: Long = 0,
    val memTotalKb: Long = 0,
    val memAvailKb: Long = 0,
    val tempC: Float = Float.NaN,
    val load1: Float = Float.NaN,
)

@kotlinx.serialization.Serializable
data class AppVersion(val current: String = "", val latest: String = "")

@kotlinx.serialization.Serializable
data class VirtualDevState(
    val isNetworkEnabled: Boolean = false,
    val isMicEnabled: Boolean = false,
    val mountedDisk: String = "",
    val isEmmcExist: Boolean = false,
    val isSdCardExist: Boolean = false,
)

@kotlinx.serialization.Serializable
data class TailscaleStatus(
    val state: String = "notInstall",
    val name: String = "",
    val ip: String = "",
    val account: String = "",
)

@kotlinx.serialization.Serializable
data class LoginTailscaleRsp(val url: String = "")

@kotlinx.serialization.Serializable
data class FormatState(val format: String = "")

@kotlinx.serialization.Serializable
data class TimeZoneState(val timezone: String = "")

@kotlinx.serialization.Serializable
data class HostnameState(val hostname: String = "")

@kotlinx.serialization.Serializable
data class BoolState(val enabled: Boolean = false)

@kotlinx.serialization.Serializable
data class StaticIpState(val enabled: Boolean = false, val ip: String = "")

@kotlinx.serialization.Serializable
data class WifiState(
    val supported: Boolean = false,
    val apMode: Boolean = false,
    val connected: Boolean = false,
    val wifi: WifiInfo? = null,
)

@kotlinx.serialization.Serializable
data class WifiInfo(
    val ssid: String = "",
    val bssid: String = "",
    val signal: Int = 0,
    val frequency: Int = 0,
    val security: String = "",
)

@kotlinx.serialization.Serializable
data class WifiScan(val wifiList: List<WifiInfo> = emptyList())

@kotlinx.serialization.Serializable
data class OledState(val exist: Boolean = false, val type: String = "", val sleep: Int = 0)

@kotlinx.serialization.Serializable
data class ScreenOffState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val startMinute: Int = 0,
    val endMinute: Int = 0,
)

@kotlinx.serialization.Serializable
data class LedState(val on: Boolean = false, val hor: Int = 0, val ver: Int = 0, val brightness: Int = 0)

@kotlinx.serialization.Serializable
data class TimeStatusState(val isSynchronized: Boolean = false, val lastSyncTime: Long = 0)

