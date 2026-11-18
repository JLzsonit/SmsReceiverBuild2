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
        val cfg = ConfigStore.get(context)
        if (cfg.topic.isBlank()) return

        val service = Intent(context, ReceiverService::class.java)
            .putExtra(ReceiverService.EXTRA_TOPIC, cfg.topic)
            .putExtra(ReceiverService.EXTRA_SERVER, cfg.server)
        ContextCompat.startForegroundService(context, service)
    }
}
