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
        val url = Endpoint.subscribeUrl(server, topic)
        onStatus("正在连接 $server …", false)
        while (running) {
            try {
                // since=all：首次连接补拉缓存中的历史消息；后续重连也由此补齐断线期间的消息
                val request = Request.Builder()
                    .url(url)
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
                onStatus("${Endpoint.friendlyError(e)}，5 秒后重连", false)
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

    /**
     * 尝试按 SmsForwarder 模板 JSON 解析。字段名可自定义（比如把 msg 改成 content、正文、
     * code 等都能识别）：先按常见字段名匹配，找不到就取最长的字符串字段当正文。
     * 解析失败（例如纯文本）返回 null，由调用方按纯文本处理。
     */
    private fun parseSms(text: String, ntfyTime: Long): SmsMessage? {
      return try {
        val obj = JsonParser.parseString(text).asJsonObject

        // 在候选字段名里按优先级取第一个非空字符串
        fun firstStr(vararg names: String): String {
            for (n in names) {
                val v = obj.get(n) ?: continue
                if (v.isJsonPrimitive && !v.isJsonNull) {
                    val s = v.asString
                    if (s.isNotBlank() && s != "null") return s
                }
            }
            return ""
        }

        // 正文：优先常见字段名；都没有则扫描所有字段，取最长的字符串（短信正文通常最长）
        var body = firstStr(
            "msg", "message", "content", "text", "body", "sms", "smsContent", "sms_body",
            "短信", "正文", "内容", "短信内容", "code", "验证码"
        )
        if (body.isBlank()) {
            var longest = ""
            for ((k, v) in obj.entrySet()) {
                if (!v.isJsonPrimitive || v.isJsonNull) continue
                val kl = k.lowercase()
                if (kl.contains("time") || kl.contains("date") || k.contains("时间")) continue
                val s = runCatching { v.asString }.getOrDefault("")
                if (s.length >= 10 && s.all { it.isDigit() }) continue // 跳过长数字（时间戳）
                if (s != "null" && s.length > longest.length) longest = s
            }
            if (longest.length >= 4) body = longest
        }
        if (body.isBlank()) return null // 没有可识别正文，交给纯文本兜底

        // 时间戳：秒级转毫秒
        fun firstTime(vararg names: String): Long {
            for (n in names) {
                val v = obj.get(n) ?: continue
                if (v.isJsonPrimitive && !v.isJsonNull) {
                    val t = runCatching { v.asLong }.getOrDefault(0L)
                    if (t > 0) return if (t in 1..9_999_999_999L) t * 1000 else t
                }
            }
            return 0L
        }

        SmsMessage(
            type = firstStr("type").ifBlank { "sms" },
            from = firstStr("from", "sender", "phone", "number", "address", "发件人", "手机号"),
            msg = body,
            device = firstStr("device", "device_name", "deviceName", "设备", "设备名"),
            timestamp = firstTime("time", "timestamp", "date", "时间").takeIf { it > 0 } ?: ntfyTime * 1000
        )
      } catch (e: Exception) {
        null
      }
    }

    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(5000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
