package com.example.smsreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 开机自启：如果之前订阅过主题，开机后自动恢复接收服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val topic = context.getSharedPreferences("config", Context.MODE_PRIVATE)
            .getString("topic", "") ?: ""
        if (topic.isBlank()) return

        val service = Intent(context, ReceiverService::class.java)
            .putExtra(ReceiverService.EXTRA_TOPIC, topic)
        ContextCompat.startForegroundService(context, service)
    }
}
