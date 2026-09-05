package com.nanokvm.app.ui.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 实时桌面缩略图 — cua 服务 `/desktop-snapshot`(代理设备 MJPEG,mixed-replace)。
 * 自绘 OkHttp 流解析(FFD8…FFD9 切帧)+ 缩放解码,不依赖 WebView。
 * 任务轮次的截图见消息气泡;这里是一直在跑的"监视器"。
 */
@Composable
fun LiveDesktopThumb(
    host: String,
    retryTick: Int,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var stateText by remember { mutableStateOf("连接中…") }
    var live by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }

    // 服务刚启动或 token 领取晚于预览:每 2s 自动重启流,直到成功
    LaunchedEffect(host, retryTick) {
        bitmap = null
        live = false
        stateText = "连接中…"
        generation++
        while (true) {
            val ok = runViewer(host) { b -> bitmap = b }
            if (ok) {
                live = true
                stateText = ""
                break
            } else {
                live = false
                stateText = "实时画面暂不可用(服务未就绪或会话被占用),2 秒后自动重试…"
                delay(2000)
            }
        }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        bitmap?.let { b ->
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "实时桌面",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
        if (!live) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(12.dp),
            ) {
                CircularProgressIndicator(Modifier.padding(bottom = 6.dp), strokeWidth = 2.dp)
                Text(
                    stateText,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                )
            }
        } else {
            // 实时角标:画面=当前被控桌面的直播证据
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF45E07A)))
                Text("实时", color = Color.White, fontSize = 10.sp)
            }
        }
    }
}

/**
 * 阻塞式 MJPEG 读取循环。返回 true=成功收到过帧,false=流结束/出错。
 * 帧回调 [onFrame] 会在任意线程被调用(内部切主线程)。
 */
private suspend fun runViewer(host: String, onFrame: (Bitmap) -> Unit): Boolean {
    val token = AssistSession.token()
    val url = "http://$host:5000/desktop-snapshot" + (if (token != null) "?token=$token" else "")
    val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长流
        .build()
    val req = Request.Builder().url(url).build()
    return withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    false
                } else {
                    val stream = resp.body?.byteStream()
                    if (stream == null) false else readFrames(stream, onFrame)
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}

/** MJPEG 字节流切帧循环:FFD8…FFD9;返回是否收到过至少一帧。 */
private suspend fun readFrames(stream: java.io.InputStream, onFrame: (Bitmap) -> Unit): Boolean {
    val buffer = ByteArrayOutputStream(1 shl 20)
    val chunk = ByteArray(64 * 1024)
    var sawFrame = false
    var lastShown = 0L
    while (true) {
        val n = stream.read(chunk)
        if (n < 0) return sawFrame
        buffer.write(chunk, 0, n)
        val bytes = buffer.toByteArray()
        val start = indexOf(bytes, 0, 0xff, 0xd8)
        if (start < 0) {
            if (bytes.size > (16 shl 20)) buffer.reset()
            continue
        }
        val end = indexOf(bytes, start + 2, 0xff, 0xd9)
        if (end < 0) {
            if (bytes.size > (16 shl 20)) buffer.reset()
            continue
        }
        val frameLen = end - start + 2
        val frame = bytes.copyOfRange(start, start + frameLen)
        buffer.reset()
        if (start + frameLen < bytes.size) buffer.write(bytes, start + frameLen, bytes.size - start - frameLen)
        sawFrame = true

        // 节流:显示帧间隔 ≥400ms,避免 4K 源解码过载
        val now = System.currentTimeMillis()
        if (now - lastShown >= 400) {
            lastShown = now
            val bmp = decodeScaled(frame, 1440)
            if (bmp != null) {
                withContext(Dispatchers.Main) { onFrame(bmp) }
            }
        }
    }
}

/** 在 frame 中从 from 起找 ff d8 / ff d9 序列 */
private fun indexOf(bytes: ByteArray, from: Int, b1: Int, b2: Int): Int {
    var i = from
    while (i < bytes.size - 1) {
        if ((bytes[i].toInt() and 0xff) == b1 && (bytes[i + 1].toInt() and 0xff) == b2) return i
        i++
    }
    return -1
}

/** 先读尺寸再按目标宽度缩放解码。 */
private fun decodeScaled(frame: ByteArray, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(frame, 0, frame.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    return BitmapFactory.decodeByteArray(frame, 0, frame.size, BitmapFactory.Options().apply {
        inSampleSize = sample
    })
}
