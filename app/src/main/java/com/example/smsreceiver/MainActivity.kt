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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
        val cfg = ConfigStore.get(this)
        if (cfg.topic.isNotBlank()) startService(cfg.server, cfg.topic)

        setContent { AppUi() }
    }

    private fun startService(server: String, topic: String) {
        val intent = Intent(this, ReceiverService::class.java)
            .putExtra(ReceiverService.EXTRA_SERVER, Endpoint.normalizeServer(server))
            .putExtra(ReceiverService.EXTRA_TOPIC, Endpoint.normalizeTopic(topic))
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

    /** 是否已被加入电池优化白名单 */
    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** 引导用户去关闭电池优化（长连接保活的关键一步） */
    private fun requestIgnoreBatteryOptimization() {
        @Suppress("BatteryLife")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 部分设备不支持该页面，跳转到通用电池设置
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {}
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
        var templateField by remember {
            mutableStateOf(TextFieldValue(initial.template, TextRange(initial.template.length)))
        }
        var testing by remember { mutableStateOf(false) }
        var testResult by remember { mutableStateOf("") }
        val ctx = LocalContext.current

        // 在光标处插入占位符（无光标时追加到末尾），并保持光标在插入内容之后
        fun insertPlaceholder(token: String) {
            val v = templateField
            val start = if (v.selection.start >= 0 && v.selection.start <= v.text.length)
                v.selection.start else v.text.length
            val end = if (v.selection.end in start..v.text.length) v.selection.end else start
            val newText = v.text.substring(0, start) + token + v.text.substring(end)
            val pos = start + token.length
            templateField = TextFieldValue(newText, TextRange(pos))
            ConfigStore.saveTemplate(ctx, newText)
        }

        val normServer = Endpoint.normalizeServer(serverText)
        val normTopic = Endpoint.normalizeTopic(topicText)
        val webhook = Endpoint.webhookUrl(serverText, topicText)

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

            // 测试结果显示
            if (testResult.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(
                        testResult,
                        Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("① ntfy 服务器地址", fontWeight = FontWeight.Bold)
            Text(
                "保持默认 ntfy.sh 即可。地址不用带主题名、不用带 /json，填错会自动纠正",
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
            if (normServer != serverText.trim()) {
                Text("将按 $normServer 连接", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { serverText = ConfigStore.DEFAULT_SERVER }) {
                Text("恢复默认 ntfy.sh")
            }

            Spacer(Modifier.height(8.dp))
            Text("② 主题名（就是密码，要复杂、别人猜不到）", fontWeight = FontWeight.Bold)
            Text(
                "只能用英文字母、数字、横线。中文和空格会自动转换；两台手机填同一个主题即可互通",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = topicText,
                onValueChange = { topicText = it },
                label = { Text("主题名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (normTopic != topicText.trim() && topicText.isNotBlank()) {
                Text("将按 $normTopic 订阅", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(10.dp))
            Row {
                Button(
                    onClick = {
                        val t = Endpoint.normalizeTopic(topicText)
                        if (t.isBlank()) {
                            Toast.makeText(ctx, "请先填主题名", Toast.LENGTH_SHORT).show()
                        } else {
                            ConfigStore.save(this@MainActivity, serverText, t)
                            startService(normServer, t)
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

            TextButton(
                onClick = {
                    testing = true
                    testResult = "正在测试连接…"
                    ConnectionTester.test(serverText, topicText) { ok, msg ->
                        testing = false
                        testResult = (if (ok) "✓ " else "✗ ") + msg
                    }
                },
                enabled = !testing
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (testing) "测试中…" else "测试连接（连不上先点这里看原因）")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "连不上怎么办：先点上面「测试连接」。若提示 DNS/解析失败，switch 一下 WiFi 和流量；" +
                    "若提示连接被重置，说明当前网络对 ntfy.sh 不稳定，可换网络或稍后重试。" +
                    "ntfy.sh 在国内时好时坏，不是 App 坏了。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                        Text("Webhook URL（发消息到这里）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        webhook,
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
                        Text("消息模板（可自定义，改完点复制）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点下面的按钮，把占位符插到光标处，不用自己打（避免格式出错）。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    // 占位符快捷按钮：像 SmsForwarder 一样点一下即插入
                    val tokens = listOf(
                        "短信正文" to "[[msg]]",
                        "发件人" to "[[from]]",
                        "设备名" to "[[device_name]]",
                        "时间" to "[[timestamp]]"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tokens.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { (label, token) ->
                                    Button(
                                        onClick = { insertPlaceholder(token) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            horizontal = 4.dp, vertical = 4.dp
                                        )
                                    ) {
                                        Text(label, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = templateField,
                        onValueChange = {
                            templateField = it
                            ConfigStore.saveTemplate(ctx, it.text)   // 输入即保存，下次打开还在
                        },
                        label = { Text("消息模板（点上方按钮插入占位符，也可直接改）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 14,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        TextButton(onClick = { copyText("消息模板", templateField.text) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制模板")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val def = ConfigStore.DEFAULT_TEMPLATE
                            templateField = TextFieldValue(def, TextRange(def.length))
                            ConfigStore.saveTemplate(ctx, def)
                            Toast.makeText(ctx, "已恢复默认模板", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("恢复默认")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "保存并订阅后，状态栏显示「已连接」即成功。只要两台手机都能上网，无论多远都能实时收到。",
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
        // 点某条消息进入详情：长短信在列表里看不全，详情页可以完整阅读和单独复制
        var selected by remember { mutableStateOf<SmsMessage?>(null) }
        val detail = selected
        if (detail != null) {
            MessageDetail(detail, onBack = { selected = null })
            return
        }
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
                                .clickable { selected = m }
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
                                // 列表里限制行数，完整内容点进去看
                                Text(m.msg, fontSize = 15.sp, maxLines = 3)
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
                                    Icon(
                                        Icons.Filled.KeyboardArrowRight,
                                        contentDescription = "查看详情",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 消息详情：长短信完整展示，可单独复制验证码或复制全文 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MessageDetail(m: SmsMessage, onBack: () -> Unit) {
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        val code = remember(m.msg) { CodeExtractor.extract(m.msg) }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            TopAppBar(
                title = { Text("短信详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
            Column(Modifier.padding(16.dp)) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("发件人", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(m.from.ifBlank { "未知号码" }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "时间：${fmt.format(Date(if (m.timestamp > 0) m.timestamp else m.receivedAt))}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "设备：${m.device.ifBlank { "-" }}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("短信内容（完整）", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        m.msg,
                        Modifier.padding(16.dp),
                        fontSize = 16.sp,
                        lineHeight = 26.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { copyText("短信全文", m.msg) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("复制全文")
                    }
                    if (code != null) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { copyText("验证码", code) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("复制验证码")
                        }
                    }
                }
                if (code != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "识别到验证码：$code",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    /** Tab 3：设置 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsTab() {
        val ctx = LocalContext.current
        var autofillOn by remember { mutableStateOf(isAutofillEnabled()) }
        var batteryIgnored by remember { mutableStateOf(isBatteryOptimizationIgnored()) }
        var ringtoneIndex by remember { mutableStateOf(ConfigStore.get(ctx).ringtone) }
        var showRingtoneDialog by remember { mutableStateOf(false) }
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
                // 保活提示卡：锁屏断连的最关键一步
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (batteryIgnored) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (batteryIgnored) Icons.Filled.CheckCircle else Icons.Filled.BatteryChargingFull,
                                contentDescription = null,
                                tint = if (batteryIgnored) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (batteryIgnored) "后台保活已开启" else "⚠ 后台保活未开启",
                                fontWeight = FontWeight.Bold, fontSize = 14.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (batteryIgnored)
                                "长连接会一直保持，锁屏、退到桌面都能实时收。"
                            else
                                "请点下面的「忽略电池优化」并选择「允许」，否则锁屏几分钟后连接会被系统掐断。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!batteryIgnored) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                requestIgnoreBatteryOptimization()
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    batteryIgnored = isBatteryOptimizationIgnored()
                                }, 800)
                            }) {
                                Text("一键开启后台保活", fontSize = 12.sp)
                            }
                        }
                    }
                }
                SettingsRow(
                    icon = Icons.Filled.MusicNote,
                    title = "新短信提示音",
                    subtitle = "当前：${RingtonePlayer.nameOf(ringtoneIndex)} · 内置 10 种，点此试听/更换",
                    onClick = { showRingtoneDialog = true }
                )
                SettingsRow(
                    icon = Icons.Filled.Smartphone,
                    title = "验证码自动填充",
                    subtitle = if (autofillOn) "已开启 · 点此前往系统设置管理" else "未开启 · 点此前往系统设置开启",
                    onClick = openAutofill
                )
                SettingsRow(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = "忽略电池优化",
                    subtitle = if (batteryIgnored) "已加入白名单 · 点此管理" else "未开启 · 锁屏后会断连，点此开启",
                    onClick = {
                        requestIgnoreBatteryOptimization()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            batteryIgnored = isBatteryOptimizationIgnored()
                        }, 800)
                    }
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
                    subtitle = "安卓短信接收 v1.8 · 安卓→安卓短信转发接收端",
                    onClick = {}
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        if (showRingtoneDialog) {
            RingtonePickerDialog(
                current = ringtoneIndex,
                onPreview = { RingtonePlayer.preview(ctx, it) },
                onSelect = { index ->
                    ringtoneIndex = index
                    ConfigStore.saveRingtone(ctx, index)
                    showRingtoneDialog = false
                    Toast.makeText(
                        ctx, "已切换为「${RingtonePlayer.nameOf(index)}」", Toast.LENGTH_SHORT
                    ).show()
                },
                onDismiss = { showRingtoneDialog = false }
            )
        }
    }

    /** 10 个内置铃声选择弹窗：整行点选保存，右侧播放键可先试听 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RingtonePickerDialog(
        current: Int,
        onPreview: (Int) -> Unit,
        onSelect: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("新短信提示音 · 内置 10 种") },
            text = {
                Column {
                    Text(
                        "点右侧 ▶ 试听，点整行即选中并自动保存",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(RingtonePlayer.RINGTONES.size) { index ->
                            val selected = index == current
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(index) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = { onSelect(index) })
                                Text(
                                    RingtonePlayer.RINGTONES[index].name,
                                    Modifier.weight(1f),
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                                IconButton(onClick = { onPreview(index) }) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = "试听",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        )
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
