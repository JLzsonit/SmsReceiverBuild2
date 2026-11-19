package com.example.smsreceiver

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 一键测试：用短超时单独连一次订阅地址，拿到明确的成功/失败原因，
 * 帮用户判断是配置错、DNS 问题还是网络被拦。
 */
object ConnectionTester {

    fun test(server: String, topic: String, onResult: (ok: Boolean, message: String) -> Unit) {
        Thread {
            val url = Endpoint.subscribeUrl(server, topic)
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        // 读第一行确认流真的通（open 事件）
                        val line = resp.body?.source()?.readUtf8Line()
                        if (line != null && line.contains("\"event\":\"open\"", true)) {
                            onResult(true, "连接成功！服务器正常，可以收到消息了")
                        } else {
                            onResult(true, "已连上服务器（HTTP ${resp.code}），订阅正常")
                        }
                    } else {
                        onResult(false, "服务器返回 HTTP ${resp.code}。请检查地址是否正确")
                    }
                }
            } catch (e: Exception) {
                onResult(false, Endpoint.friendlyError(e))
            }
        }.apply { name = "connection-test" }.start()
    }
}
