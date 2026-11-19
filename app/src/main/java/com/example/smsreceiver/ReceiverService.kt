package com.example.smsreceiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 前台服务：承载 ntfy 订阅长连接。
 * 前台服务 + 常驻通知可以大幅降低被系统杀掉的概率，保证低延迟实时接收。
 */
class ReceiverService : Service() {

    companion object {
        const val EXTRA_TOPIC = "topic"
        const val EXTRA_SERVER = "server"
        private const val CHANNEL_SERVICE = "service_channel"   // 常驻通知（低优先级）
        private const val CHANNEL_MESSAGE = "sms_channel"      // 短信通知（高优先级）
        private const val NOTIF_SERVICE_ID = 1
    }

    private var subscriber: NtfySubscriber? = null

    // 保活：锁屏后阻止 CPU 休眠和 WiFi 休眠，长连接才不会 2-3 分钟后被掐断
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfg = ConfigStore.get(this)
        val topic = intent?.getStringExtra(EXTRA_TOPIC)?.takeIf { it.isNotBlank() } ?: cfg.topic
        val server = ConfigStore.normalize(
            intent?.getStringExtra(EXTRA_SERVER)?.takeIf { it.isNotBlank() } ?: cfg.server
        )

        if (topic.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 保存配置，开机自启和下次启动都能恢复
        ConfigStore.save(this, server, topic)

        createChannels()
        startForeground(NOTIF_SERVICE_ID, serviceNotification(server, topic))
        acquireLocks()

        subscriber?.stop()
        subscriber = NtfySubscriber(
            server = server,
            topic = topic,
            onMessage = { sms -> onSms(sms) },
            onStatus = { text, connected ->
                AppBus.status.value = text
                AppBus.connected.value = connected
            }
        ).also { it.start() }

        return START_STICKY // 被系统杀掉后自动重启
    }

    override fun onDestroy() {
        subscriber?.stop()
        subscriber = null
        releaseLocks()
        super.onDestroy()
    }

    /** 锁屏/退到后台时持有唤醒锁，保证 CPU 与 WiFi 不休眠，长连接持续在线 */
    @Suppress("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsReceiver:keepalive").apply {
                setReferenceCounted(false)
                acquire() // 常驻持有，服务销毁时释放
            }
        } catch (e: Exception) { /* 部分机型不支持，忽略 */ }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock?.let { if (it.isHeld) it.release() }
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(
                if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SmsReceiver:wifi"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) { /* 忽略 */ }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun onSms(sms: SmsMessage) {
        AppBus.addMessage(applicationContext, sms)
        showNotification(sms)
    }

    // ---------------- 通知 ----------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "接收服务", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGE, "转发短信", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun serviceNotification(server: String, topic: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("短信接收服务运行中")
            .setContentText("正在订阅 $server/$topic")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun showNotification(sms: SmsMessage) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // 自动识别验证码：通知标题直接亮出验证码 + 一键复制按钮
        val code = CodeExtractor.extract(sms.msg)

        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_MESSAGE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(
                if (code != null) "验证码 $code · 来自 ${sms.from}"
                else sms.from.ifBlank { "收到转发短信" }
            )
            .setContentText(sms.msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sms.msg))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (code != null) {
            val copyIntent = Intent(this, CopyCodeReceiver::class.java)
                .putExtra(CopyCodeReceiver.EXTRA_CODE, code)
            val copyPending = PendingIntent.getBroadcast(
                this, code.hashCode(), copyIntent, PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "复制验证码", copyPending)
        }

        getSystemService(NotificationManager::class.java)
            .notify(sms.receivedAt.hashCode(), builder.build())
    }
}
