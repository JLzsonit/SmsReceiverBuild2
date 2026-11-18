package com.example.smsreceiver

import android.content.Context

/**
 * App 配置：ntfy 服务器地址 + 主题。
 * 集中持久化，供界面 / 前台服务 / 开机自启共用，避免各处读取不一致。
 */
data class AppConfig(
    val server: String = ConfigStore.DEFAULT_SERVER,
    val topic: String = ""
)

object ConfigStore {
    const val DEFAULT_SERVER = "https://ntfy.sh"
    private const val PREF = "config"
    private const val KEY_SERVER = "server"
    private const val KEY_TOPIC = "topic"

    fun get(context: Context): AppConfig {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return AppConfig(
            server = normalize(p.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER),
            topic = p.getString(KEY_TOPIC, "") ?: ""
        )
    }

    fun save(context: Context, server: String, topic: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVER, normalize(server))
            .putString(KEY_TOPIC, topic.trim())
            .apply()
    }

    /** 去掉结尾斜杠，保证拼出来的 URL 不出现 // */
    fun normalize(server: String): String {
        var s = server.trim()
        while (s.endsWith("/")) s = s.dropLast(1)
        if (s.isBlank()) s = DEFAULT_SERVER
        return s
    }

    /** 给 SmsForwarder 用的完整 Webhook 地址 */
    fun webhookUrl(config: AppConfig): String = "${config.server}/${config.topic}"

    /** 下拉可选的常用服务器 */
    val PRESETS = listOf(DEFAULT_SERVER)
}
