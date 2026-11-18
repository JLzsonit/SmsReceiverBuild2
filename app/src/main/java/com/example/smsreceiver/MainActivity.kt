package com.example.smsreceiver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 用户拒绝不影响接收，只是不弹通知，App 内列表仍然可见
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppBus.messages.value = SmsStore.load(this)

        // Android 13+ 申请通知权限
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 之前订阅过则自动恢复服务
        val savedTopic = prefs().getString("topic", "") ?: ""
        if (savedTopic.isNotBlank()) {
            startService(savedTopic)
        }

        setContent { Ui() }
    }

    private fun prefs() = getSharedPreferences("config", Context.MODE_PRIVATE)

    private fun startService(topic: String) {
        val intent = Intent(this, ReceiverService::class.java)
            .putExtra(ReceiverService.EXTRA_TOPIC, topic)
        ContextCompat.startForegroundService(this, intent)
    }

    /** 判断本 App 是否已被用户设为系统自动填充服务 */
    private fun isAutofillEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        // 用字符串字面量，避免不同 API 版本常量差异
        val enabled = Settings.Secure.getString(contentResolver, "autofill_service")
            ?: return false
        return enabled.contains(packageName)
    }

    /** 跳转到系统自动填充服务设置页，让用户选择本 App */
    private fun openAutofillSettings() {
        try {
            val intent = Intent(Settings.ACTION_AUTOFILL_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // 极少数设备无此设置页，降级到应用详情
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (e2: Exception) {
                // 忽略
            }
        }
    }

    /** 引导用户去关闭电池优化（长连接保活的关键一步） */
    private fun requestIgnoreBatteryOptimization() {
        @Suppress("BatteryLife")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 部分设备不支持该页面，忽略
        }
    }

    @Composable
    private fun Ui() {
        val msgs by AppBus.messages.collectAsState()
        val status by AppBus.status.collectAsState()
        val connected by AppBus.connected.collectAsState()
        var topicText by remember { mutableStateOf(prefs().getString("topic", "") ?: "") }
        var autofillOn by remember { mutableStateOf(isAutofillEnabled()) }
        val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

        MaterialTheme {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text("短信接收端", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))

                Text(
                    text = status,
                    fontSize = 14.sp,
                    color = if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = topicText,
                    onValueChange = { topicText = it },
                    label = { Text("ntfy 主题名（相当于密码，要复杂）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val topic = topicText.trim()
                            if (topic.isNotBlank()) startService(topic)
                        }
                    ) { Text("订阅") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { stopService(Intent(this@MainActivity, ReceiverService::class.java)) }) {
                        Text("断开")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { AppBus.clear(applicationContext) }) { Text("清空") }
                }

                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { requestIgnoreBatteryOptimization() }) {
                    Text("点此忽略电池优化（保证息屏也能实时收到）")
                }

                TextButton(onClick = {
                    openAutofillSettings()
                    autofillOn = isAutofillEnabled()
                }) {
                    Text(
                        if (autofillOn) "✓ 自动填充已开启（点此可关闭）"
                        else "点此开启验证码自动填充（系统设置）"
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "备用机 SmsForwarder 的 Webhook URL 填：https://ntfy.sh/${topicText.trim()}",
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.weight(1f)) {
                    items(msgs) { m ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row {
                                    Text(m.from, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        fmt.format(Date(if (m.timestamp > 0) m.timestamp else m.receivedAt)),
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(m.msg, fontSize = 15.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "设备：${m.device} · 送达时间：${fmt.format(Date(m.receivedAt))}",
                                        fontSize = 11.sp
                                    )
                                    Spacer(Modifier.weight(1f))
                                    CodeExtractor.extract(m.msg)?.let { code ->
                                        TextButton(onClick = {
                                            val cm = applicationContext
                                                .getSystemService(Context.CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                            cm.setPrimaryClip(
                                                android.content.ClipData.newPlainText("code", code)
                                            )
                                            android.widget.Toast.makeText(
                                                applicationContext, "已复制验证码：$code",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }) { Text("复制 $code", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
