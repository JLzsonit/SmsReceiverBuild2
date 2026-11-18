package com.example.smsreceiver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局共享状态：前台服务（后台收消息）与 Activity（界面）之间的桥梁。
 */
object AppBus {
    /** 收到的消息列表（最新的在最前） */
    val messages = MutableStateFlow<List<SmsMessage>>(emptyList())

    /** 连接状态文案 */
    val status = MutableStateFlow("未连接")

    /** 是否处于连接状态 */
    val connected = MutableStateFlow(false)

    fun addMessage(context: Context, sms: SmsMessage) {
        val list = messages.value.toMutableList()
        list.add(0, sms)
        messages.value = list
        SmsStore.save(context, list)
    }

    fun clear(context: Context) {
        messages.value = emptyList()
        SmsStore.save(context, emptyList())
    }
}
