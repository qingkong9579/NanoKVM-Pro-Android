package com.nanokvm.app.ui.assistant

import android.util.Log
import com.nanokvm.app.data.api.NanoKvmApi
import com.nanokvm.app.data.net.Tls
import com.nanokvm.app.ui.AppSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 客户端侧"会话接管门"——不改设备端任何代码:
 *
 * 设备端 cua 是单用户会话(first GET 领 token,期间任何新客户端 403),
 * 且服务端只在 socket 断开时自杀(self-exit)。旧会话僵死(进程活着但
 * 没人持会话)时,新客户端会被永久锁在外面,以前只能重启设备。
 *
 * 本门利用 NanoKVM 自带的 /api/vm/terminal(设备 shell pty):
 * 1. JWT 登录设备 API;
 * 2. 终端 WS 发 `pkill -f cua_webapp.py` —— 断开"先前的 cua";
 * 3. TCP 探测 5000 端口确认旧进程已退出;
 * 4. 调 /api/extensions/assistant/start 拉起全新 cua —— 开启"新的 cua";
 * 5. 等 5000 端口就绪返回,调用方重新领 token。
 *
 * 只在用户主动刷新/进入对话页时触发(VM 传入 allowRelease),后台自动重连
 * 不触发,避免两个客户端互踢乒乓。
 */
object CuaSessionGate {
    private const val TAG = "CuaGate"
    private const val CUA_PORT = 5000

    @Volatile
    private var inflight = false

    @Volatile
    private var lastReleaseAt = 0L

    /** 最小接管间隔:防自动重连风暴时双端互踢。 */
    private const val MIN_INTERVAL_MS = 30_000L

    /** 杀掉旧 cua 进程、拉起新进程并**立即以本客户端身份领 token**;
     *  返回新 token(调用方直接使用),失败返回 null。
     *  内部领 token 是为了防止"刚释放完,其他旧客户端自动重试抢先 GET 认领"。 */
    suspend fun releaseStaleSession(host: String): String? {
        val now = System.currentTimeMillis()
        if (inflight || now - lastReleaseAt < MIN_INTERVAL_MS) return null
        inflight = true
        return try {
            withContext(Dispatchers.IO) {
                val api = NanoKvmApi("https://$host", Tls.okHttpBuilder().build())
                api.login(AppSession.username, AppSession.password)

                // 1) 终端 pty 杀旧 cua;2) TCP 探测确认退出(最多 3 轮)
                var dead = false
                repeat(3) {
                    runCatching { killCuaViaTerminal(api, host) }
                    delay(600)
                    if (!tcpAlive(host, CUA_PORT)) {
                        dead = true
                        return@repeat
                    }
                    delay(700)
                }
                if (!dead) {
                    Log.w(TAG, "cua process still alive after 3 pkill rounds")
                    return@withContext null
                }

                // 3) 起新进程(start.go:检测不到 cua_webapp.py 才启动)
                api.assistantStart()

                // 4) 等端口就绪后立即以本客户端身份 GET / 认领 token(最多 ~15s)
                val claimClient = Tls.okHttpBuilder().build()
                repeat(25) {
                    delay(600)
                    if (!tcpAlive(host, CUA_PORT)) return@repeat
                    val body = try {
                        claimClient.newCall(
                            Request.Builder().url("http://$host:$CUA_PORT/").build(),
                        ).execute().use { it.body?.string().orEmpty() }
                    } catch (_: Exception) {
                        return@repeat
                    }
                    val m = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").find(body)
                    val token = m?.value
                    if (token != null) {
                        AssistSession.save(token)
                        Log.i(TAG, "session released & new cua started, claimed token")
                        lastReleaseAt = System.currentTimeMillis()
                        return@withContext token
                    }
                }
                Log.w(TAG, "new cua up but claim failed")
                lastReleaseAt = System.currentTimeMillis()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "release failed: ${e.message}")
            null
        } finally {
            inflight = false
        }
    }

    /** 终端 WS 连接 → 发 pkill → 关 shell(pty 随 ws 关闭被回收)。 */
    private fun killCuaViaTerminal(api: NanoKvmApi, host: String) {
        val client = Tls.okHttpBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder()
            .url("wss://$host/api/vm/terminal")
            .addHeader("Cookie", "nano-kvm-token=${api.token.orEmpty()}")
            .addHeader("Authorization", "Bearer ${api.token.orEmpty()}")
            .build()
        val opened = CountDownLatch(1)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.countDown()
                webSocket.send("pkill -f cua_webapp.py\nexit\n")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.countDown()
            }
        }
        val ws = client.newWebSocket(req, listener)
        opened.await(6, TimeUnit.SECONDS)
        // 留给 shell 执行 pkill;随后关闭连接回收 pty
        Thread.sleep(800)
        runCatching { ws.close(1000, "done") }
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    /** TCP 探测端口(不做 HTTP,避免抢先领到 cua token)。 */
    private fun tcpAlive(host: String, port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), 700)
        }
        true
    } catch (_: Exception) {
        false
    }
}
