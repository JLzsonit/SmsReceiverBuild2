package com.example.smsreceiver

import android.content.Context

/**
 * App 配置：ntfy 服务器地址 + 主题。
 * 集中持久化，供界面 / 前台服务 / 开机自启共用，避免各处读取不一致。
 */
data class AppConfig(
    val server: String = ConfigStore.DEFAULT_SERVER,
    val topic: String = "",
    val template: String = ConfigStore.DEFAULT_TEMPLATE,
    val ringtone: Int = 0
)

object ConfigStore {
    const val DEFAULT_SERVER = "https://ntfy.sh"

    /** 默认消息模板，SmsForwarder 占位符见其文档 */
    const val DEFAULT_TEMPLATE = """
{
  "type": "sms",
  "from": "[[from]]",
  "msg": "[[msg]]",
  "device": "[[device_name]]",
  "time": [[timestamp]]
}
"""

    private const val PREF = "config"
    private const val KEY_SERVER = "server"
    private const val KEY_TOPIC = "topic"
    private const val KEY_TEMPLATE = "template"
    private const val KEY_RINGTONE = "ringtone"

    fun get(context: Context): AppConfig {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return AppConfig(
            server = Endpoint.normalizeServer(p.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER),
            topic = p.getString(KEY_TOPIC, "") ?: "",
            template = p.getString(KEY_TEMPLATE, null) ?: DEFAULT_TEMPLATE,
            ringtone = p.getInt(KEY_RINGTONE, 0)
        )
    }

    fun save(context: Context, server: String, topic: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVER, Endpoint.normalizeServer(server))
            .putString(KEY_TOPIC, Endpoint.normalizeTopic(topic))
            .apply()
    }

    /** 单独保存用户可编辑的消息模板，不影响服务器/主题配置 */
    fun saveTemplate(context: Context, template: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_TEMPLATE, template)
            .apply()
    }

    /** 保存用户选择的内置提示音序号 */
    fun saveRingtone(context: Context, index: Int) {
        val safe = index.coerceIn(0 until RingtonePlayer.RINGTONES.size)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_RINGTONE, safe)
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
