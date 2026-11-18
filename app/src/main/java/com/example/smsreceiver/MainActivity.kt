package com.example.smsreceiver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 给 SmsForwarder 复制用的消息模板 */
private const val WEBHOOK_TEMPLATE = """
{
  "type": "sms",
  "from": "[[from]]",
  "msg": "[[msg]]",
  "device": "[[device_name]]",
  "time": [[timestamp]]
}
"""

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
        val cfg = ConfigStore.get(this)
        if (cfg.topic.isNotBlank()) startService(cfg.server, cfg.topic)

        setContent { AppUi() }
    }

    private fun startService(server: String, topic: String) {
        val intent = Intent(this, ReceiverService::class.java)
            .putExtra(ReceiverService.EXTRA_SERVER, server)
            .putExtra(ReceiverService.EXTRA_TOPIC, topic)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopServiceNow() {
        stopService(Intent(this, ReceiverService::class.java))
        AppBus.status.value = "已断开"
        AppBus.connected.value = false
    }

    /** 判断本 App 是否已被用户设为系统自动填充服务 */
    private fun isAutofillEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val enabled = Settings.Secure.getString(contentResolver, "autofill_service") ?: return false
        return enabled.contains(packageName)
    }

    /** 跳转到系统自动填充服务设置页 */
    private fun openAutofillSettings() {
        try {
            val intent = Intent("android.settings.AUTOFILL_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
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

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 界面

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppUi() {
        val msgs by AppBus.messages.collectAsState()
        val status by AppBus.status.collectAsState()
        val connected by AppBus.connected.collectAsState()
        var tab by remember { mutableStateOf(0) }

        MaterialTheme {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                            label = { Text("服务器") }
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Message, contentDescription = null) },
                            label = { Text("消息") }
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("设置") }
                        )
                    }
                }
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (tab) {
                        0 -> ServerTab(status, connected)
                        1 -> MessagesTab(msgs)
                        2 -> SettingsTab()
                    }
                }
            }
        }
    }

    /** Tab 1：服务器配置 —— 打开就能拿到地址去 SmsForwarder 配置 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ServerTab(status: String, connected: Boolean) {
        val initial = remember { ConfigStore.get(this@MainActivity) }
        var serverText by remember { mutableStateOf(initial.server) }
        var topicText by remember { mutableStateOf(initial.topic) }
        val webhook = "${ConfigStore.normalize(serverText)}/${topicText.trim()}"

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            TopAppBar(title = { Text("服务器配置") })

            // 连接状态卡片
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (connected) Icons.Filled.CheckCircle else Icons.Filled.PowerSettingsNew,
                        contentDescription = null,
                        tint = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (connected) "接收服务运行中" else "接收服务未连接",
                            fontWeight = FontWeight.Bold
                        )
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("① 这里填 ntfy 服务器地址", fontWeight = FontWeight.Bold)
            Text(
                "默认 ntfy.sh 免费公共服务器；若自建 ntfy，把地址改成你的域名即可（要带 https://）",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = serverText,
                onValueChange = { serverText = it },
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { serverText = ConfigStore.DEFAULT_SERVER }) {
                Text("恢复默认 ntfy.sh")
            }

            Spacer(Modifier.height(8.dp))
            Text("② 主题名（相当于密码，要复杂，别人猜不到）", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = topicText,
                onValueChange = { topicText = it },
                label = { Text("主题名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Button(
                    onClick = {
                        val s = ConfigStore.normalize(serverText)
                        val t = topicText.trim()
                        if (t.isBlank()) {
                            Toast.makeText(this@MainActivity, "请先填主题名", Toast.LENGTH_SHORT).show()
                        } else {
                            ConfigStore.save(this@MainActivity, s, t)
                            startService(s, t)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存并订阅")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { stopServiceNow() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("断开")
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("③ 复制下面的地址去 SmsForwarder 配置", fontWeight = FontWeight.Bold)
            Text(
                "备用机 SmsForwarder → 发送通道 → Webhook → URL 填下面这个，请求类型选 POST",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Webhook URL", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        webhook.ifBlank { "（先填服务器地址和主题名）" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { copyText("Webhook 地址", webhook) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制地址")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Message, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("消息模板（JSON）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(WEBHOOK_TEMPLATE.trimIndent(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    TextButton(onClick = { copyText("消息模板", WEBHOOK_TEMPLATE.trimIndent()) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制模板")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "提示：主题名就是密码，设得越长越安全；只要备用机和这台手机都能上网， anywhere 都能实时收到。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    /** Tab 2：消息历史 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MessagesTab(msgs: List<SmsMessage>) {
        val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("消息") },
                actions = {
                    TextButton(onClick = { AppBus.clear(this@MainActivity) }) {
                        Text("清空")
                    }
                }
            )
            if (msgs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Message,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("还没有收到消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(msgs) { m ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 5.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row {
                                    Text(m.from.ifBlank { "未知号码" }, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        fmt.format(Date(if (m.timestamp > 0) m.timestamp else m.receivedAt)),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(m.msg, fontSize = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "设备：${m.device.ifBlank { "-" }}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    CodeExtractor.extract(m.msg)?.let { code ->
                                        TextButton(onClick = { copyText("验证码", code) }) {
                                            Icon(
                                                Icons.Filled.ContentCopy,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("复制 $code", fontSize = 12.sp)
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

    /** Tab 3：设置 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsTab() {
        var autofillOn by remember { mutableStateOf(isAutofillEnabled()) }
        val openAutofill = {
            openAutofillSettings()
            autofillOn = isAutofillEnabled()
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            TopAppBar(title = { Text("设置") })
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingsRow(
                    icon = Icons.Filled.Smartphone,
                    title = "验证码自动填充",
                    subtitle = if (autofillOn) "已开启 · 点此前往系统设置管理" else "未开启 · 点此前往系统设置开启",
                    onClick = openAutofill
                )
                SettingsRow(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = "忽略电池优化",
                    subtitle = "防止息屏后长连接被杀，保证实时接收",
                    onClick = { requestIgnoreBatteryOptimization() }
                )
                SettingsRow(
                    icon = Icons.Filled.Refresh,
                    title = "开机自动启动",
                    subtitle = "手机重启后自动恢复接收（需系统允许自启动）",
                    onClick = {
                        Toast.makeText(
                            this@MainActivity,
                            "请在系统设置中允许本 App 自启动",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                SettingsRow(
                    icon = Icons.Filled.Delete,
                    title = "清空消息历史",
                    subtitle = "删除本机保存的所有接收记录",
                    onClick = {
                        AppBus.clear(this@MainActivity)
                        Toast.makeText(this@MainActivity, "已清空", Toast.LENGTH_SHORT).show()
                    }
                )
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "关于",
                    subtitle = "短信接收端 v1.1 · 配合 SmsForwarder 使用",
                    onClick = {}
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun SettingsRow(
        icon: ImageVector,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clickable(onClick = onClick)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
