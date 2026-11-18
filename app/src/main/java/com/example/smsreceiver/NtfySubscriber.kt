package com.example.smsreceiver

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * ntfy 订阅端：与 ntfy.sh 建立一条长连接（HTTP 流式响应，不做轮询），
 * 服务器一有新消息立刻推过来，延迟通常在 1 秒以内。
 *
 * 连接断开会自动重连（5 秒后），重连时用 since 参数补拉断线期间的消息，
 * 并按消息 id 去重，不会出现重复提醒。
 *
 * 消息内容解析：
 *   1. 优先尝试把 message 字段当 JSON 解析（SmsForwarder 的模板格式）；
 *   2. 解析失败则按纯文本处理。
 */
class NtfySubscriber(
    private val topic: String,
    private val onMessage: (SmsMessage) -> Unit,
    private val onStatus: (text: String, connected: Boolean) -> Unit,
    private val server: String = ConfigStore.DEFAULT_SERVER
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接：读超时设为无限
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var running = false
    private var thread: Thread? = null
    private val seenIds = LinkedHashSet<String>() // 重连补拉时按 id 去重

    fun start() {
        running = true
        thread = Thread { loop() }.apply {
            name = "ntfy-subscriber"
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        onStatus("正在连接 $server …", false)
        while (running) {
            try {
                // since=all：首次连接补拉缓存中的历史消息；后续重连也由此补齐断线期间的消息
                val request = Request.Builder()
                    .url("$server/$topic/json?since=all")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onStatus("连接失败：HTTP ${response.code}，5 秒后重试", false)
                        sleepBeforeRetry()
                        return@use
                    }
                    onStatus("已连接，等待消息（实时推送）", true)
                    val source = response.body!!.source()
                    while (running) {
                        val line = source.readUtf8Line() ?: break
                        if (!running) break
                        handleEvent(line)
                    }
                    onStatus("连接已断开，5 秒后重连", false)
                }
            } catch (e: Exception) {
                if (!running) return
                onStatus("网络异常：${e.message ?: "未知"}，5 秒后重连", false)
            }
            sleepBeforeRetry()
        }
    }

    private fun handleEvent(line: String) {
        val obj = try {
            JsonParser.parseString(line).asJsonObject
        } catch (e: Exception) {
            return
        }

        val event = obj.get("event")?.asString ?: return
        if (event != "message") return // 忽略 open/keepalive 等控制事件

        val id = obj.get("id")?.asString ?: return
        // 重连后 since=all 会重发旧消息，按 id 去重
        synchronized(seenIds) {
            if (!seenIds.add(id)) return
            if (seenIds.size > 1000) {
                val iter = seenIds.iterator()
                repeat(200) { if (iter.hasNext()) { iter.next(); iter.remove() } }
            }
        }

        val text = obj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val ntfyTime = obj.get("time")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val sms = parseSms(text, ntfyTime) ?: SmsMessage(msg = text, timestamp = ntfyTime * 1000)
        onMessage(sms)
    }

    /** 尝试按 SmsForwarder 模板 JSON 解析，失败返回 null */
    private fun parseSms(text: String, ntfyTime: Long): SmsMessage? = try {
        val obj = JsonParser.parseString(text).asJsonObject
        if (!obj.has("msg")) null else {

        fun str(name: String): String =
            obj.get(name)?.takeIf { !it.isJsonNull }?.asString ?: ""

        fun num(name: String): Long {
            val v = obj.get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            // SmsForwarder 的 [[timestamp]] 是秒级时间戳，转成毫秒
            return if (v in 1..9_999_999_999L) v * 1000 else v
        }

        SmsMessage(
            type = str("type").ifBlank { "sms" },
            from = str("from"),
            msg = str("msg"),
            device = str("device"),
            timestamp = num("time").takeIf { it > 0 } ?: ntfyTime * 1000
        )
        } // end else
    } catch (e: Exception) {
        null
    }

    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(5000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
