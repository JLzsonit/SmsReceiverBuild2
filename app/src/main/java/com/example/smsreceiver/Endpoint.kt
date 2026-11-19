package com.example.smsreceiver

import java.net.URI
import java.util.Locale

/**
 * 服务器地址与主题名的规范化 + 诊断。
 * 用户经常填错地址（漏 https://、带空格、把主题写进地址里），这里统一纠正，
 * 避免 OkHttp 拼 URL 时抛异常导致「网络异常」却不知所措。
 */
object Endpoint {

    /** 把用户输入的服务器地址规范成 https://host[:port]，无尾斜杠 */
    fun normalizeServer(raw: String): String {
        var s = raw.trim().replace("\\s+".toRegex(), "")
        if (s.isEmpty()) return ConfigStore.DEFAULT_SERVER

        // 用户把完整订阅链接贴进来了：https://ntfy.sh/mytopic/json?... → 只取 host
        if (s.contains("/")) {
            val hostPart = runCatching { URI(s).host }.getOrNull()
            if (!hostPart.isNullOrBlank()) {
                val scheme = if (s.startsWith("http://", true)) "http" else "https"
                val port = runCatching { URI(s).port }.getOrDefault(-1)
                return if (port > 0) "$scheme://$hostPart:$port" else "$scheme://$hostPart"
            }
            // 形如 ntfy.sh/topic ：取第一段当 host
            val first = s.substringBefore("/")
            if (first.contains(".")) {
                var h = first
                if (h.startsWith("http://", true)) h = h.removePrefix("http://")
                if (h.startsWith("https://", true)) h = h.removePrefix("https://")
                return "https://$h"
            }
        }

        var hasScheme = false
        if (s.startsWith("https://", true)) { s = "https://" + s.substring(8); hasScheme = true }
        else if (s.startsWith("http://", true)) { s = "http://" + s.substring(7); hasScheme = true }
        if (!hasScheme) s = "https://$s"
        return s.trimEnd('/')
    }

    /** 把主题名规范成 URL 安全的小写 slug：去空格、中文转拼音式占位、只留 [a-z0-9_-] */
    fun normalizeTopic(raw: String): String {
        var s = raw.trim().lowercase(Locale.US)
        // 中文等非 ASCII 字符无法直接放进主题，转成十六进制避免拼 URL 出错
        val sb = StringBuilder()
        for (c in s) {
            when {
                c.isAsciiLetterOrDigit() || c == '-' || c == '_' -> sb.append(c)
                c.isWhitespace() -> sb.append('-')
                else -> sb.append(c.code.toString(16))
            }
        }
        s = sb.toString().trim('-')
        if (s.isEmpty()) s = "sms-" + System.currentTimeMillis().toString(36)
        return s.take(64)
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in '0'..'9'

    /** 把 OkHttp/网络异常转成用户看得懂的诊断文案 */
    fun friendlyError(e: Throwable): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("Expected URL scheme", true) ||
                msg.contains("no colon", true) ||
                msg.contains("Illegal URL", true) ->
                "地址格式不对。请只填服务器地址，如 https://ntfy.sh（不要带主题名）"
            msg.contains("Unable to resolve host", true) ||
                msg.contains("UnknownHost", true) ||
                e is java.net.UnknownHostException ->
                "DNS 解析失败：当前网络无法解析该地址。换个网络（如开/关 WiFi、切 4G）再试"
            msg.contains("failed to connect", true) ||
                msg.contains("Connection refused", true) ||
                e is java.net.ConnectException ->
                "连接被拒绝：服务器可达但端口连不上，可能被网络拦截，请换网络重试"
            msg.contains("timeout", true) ||
                msg.contains("SocketTimeout", true) ->
                "连接超时：网络较慢或被限速，请换网络重试"
            msg.contains("SSL", true) ||
                msg.contains("Certificate", true) ||
                msg.contains("Handshake", true) ->
                "HTTPS 证书校验失败：系统时间不对或网络被劫持，请校正手机时间后重试"
            msg.contains("ECT") || msg.contains("connection reset") ->
                "连接被重置：该服务器在当前网络下可能不稳定，建议换网络"
            else -> "网络异常：${msg.take(60)}"
        }
    }

    /** 完整订阅地址：heartbeat=30 让服务器每 30 秒发一次心跳，穿透 NAT 并便于检测断线 */
    fun subscribeUrl(server: String, topic: String): String =
        "${normalizeServer(server)}/${normalizeTopic(topic)}/json?since=all&heartbeat=30"

    /** 给 SmsForwarder 用的 Webhook 地址 */
    fun webhookUrl(server: String, topic: String): String =
        "${normalizeServer(server)}/${normalizeTopic(topic)}"
}
