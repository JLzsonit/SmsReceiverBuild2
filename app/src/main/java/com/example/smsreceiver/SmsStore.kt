package com.example.smsreceiver

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 一条被转发过来的短信。
 * 字段与 SmsForwarder Webhook 请求体模板中的占位符一一对应，
 * 你可以在 SmsForwarder 模板里增减字段，只要 JSON 键名能对上就能解析。
 */
data class SmsMessage(
    val type: String = "sms",        // [[type]] 固定为 sms
    val from: String = "",           // [[from]] 发件人号码
    val msg: String = "",            // [[msg]] 短信内容
    val device: String = "",         // [[device_name]] 转发来源设备名
    val timestamp: Long = 0,         // [[timestamp]] 短信到达时间（秒，App 内自动转毫秒）
    val receivedAt: Long = System.currentTimeMillis() // 本机收到推送的时间
)

/**
 * 本地持久化：消息保存为 JSON 文件，App 重启后依然能看到历史记录。
 * 如果以后消息量大了，可以换成 Room 数据库。
 */
object SmsStore {
    private const val FILE_NAME = "messages.json"
    private val gson = Gson()

    fun load(context: Context): MutableList<SmsMessage> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<SmsMessage>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, list: List<SmsMessage>) {
        try {
            File(context.filesDir, FILE_NAME).writeText(gson.toJson(list))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
