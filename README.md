# 短信接收端 App（SmsReceiver · ntfy 版）

配合备用机上的 [SmsForwarder](https://github.com/pppscn/SmsForwarder) 使用，
**两台手机无论相隔多远、是否同一网络，只要有网就能实时收到短信**。

链路：

```
备用机 SmsForwarder ──Webhook──▶ ntfy.sh（公共中转，免费）──▶ 主力机本 App（长连接实时收）
```

- 接收端 App **主动长连接** ntfy.sh 订阅主题，不需要公网 IP、不需要服务器
- **低延迟**：走流式长连接推送，不做轮询，通常 **1 秒内**弹通知
- **验证码识别 + 一键复制**：收到含验证码的短信，通知栏直接显示验证码，带「复制验证码」按钮，点一下进剪贴板；App 内每条消息也有复制按钮
- **系统级自动填充**：把本 App 设为系统自动填充服务后，在任意 App 的验证码框点一下，自动弹出「填入验证码 xxxxxx」，一键填入（见下方「开启自动填充」）
- 断网自动重连 + 按消息 id 去重，断线期间的消息重连后自动补收
- 前台服务 + 开机自启 + 忽略电池优化，保证息屏也能实时收

## 项目结构

```
SmsReceiverApp/
├── app/src/main/
│   ├── AndroidManifest.xml          # 权限：网络 / 前台服务 / 开机自启 / 通知
│   └── java/com/example/smsreceiver/
│       ├── MainActivity.kt          # 界面：主题设置、订阅/断开、消息列表、复制验证码
│       ├── ReceiverService.kt       # 前台服务：承载长连接 + 弹通知（含验证码一键复制）
│       ├── CodeExtractor.kt         # 验证码自动识别（关键词正则 + 数字兜底）
│       ├── CopyCodeReceiver.kt      # 通知栏复制按钮处理器
│       ├── SmsAutofillService.kt    # 系统自动填充服务（验证码候选气泡）
│       ├── NtfySubscriber.kt        # ntfy 长连接订阅（自动重连、去重、补拉）
│       ├── BootReceiver.kt          # 开机自启恢复连接
│       ├── AppBus.kt                # 服务 ↔ 界面的共享状态
│       └── SmsStore.kt              # 数据模型 + JSON 文件持久化
│   ├── res/layout/
│   │   └── autofill_dataset_item.xml  # 自动填充候选气泡布局
│   └── res/xml/
│       └── autofill_service.xml     # 自动填充服务元数据和设置入口
├── app/build.gradle.kts             # Kotlin + Compose + OkHttp + Gson
└── ...
```

最低支持 Android 8.0（minSdk 26）。

## 构建运行

1. 用 **Android Studio**（Hedgehog 及以上）打开 `SmsReceiverApp`，等待 Gradle 同步。
2. 连接主力机（接收端），USB 调试开启，点 Run 安装；或 `./gradlew assembleDebug` 生成 APK 手动安装。
3. 打开 App：
   - 输入一个**主题名**（如 `sms-fwd-x7k9p2q8`，要复杂、别人猜不到——主题名就是密码）
   - 点「订阅」，状态栏出现常驻通知「短信接收服务运行中」即成功
   - 首次会请求通知权限；强烈建议点「忽略电池优化」按钮

## 备用机 SmsForwarder 配置

1. 「发送通道」→ 新建 → **Webhook**：
   - **URL**：`https://ntfy.sh/你的主题名`
   - **请求类型**：POST
   - **消息模板**（消息类型选 JSON）：

   ```json
   {
     "type": "sms",
     "from": "[[from]]",
     "msg": "[[msg]]",
     "device": "[[device_name]]",
     "time": [[timestamp]]
   }
   ```

2. 「转发规则」→ 新建：匹配方式选「全部」或按发件人/关键词筛选，通道指向上面这个 Webhook。

备用机收到短信 → ntfy.sh 中转 → 主力机 1 秒内弹通知，App 内可回看历史。

## 快速验证（不等备用机）

用电脑或任何设备往主题发一条测试消息：

```bash
curl -d '{"type":"sms","from":"10086","msg":"测试短信","device":"备用机","time":1739939200}' \
  https://ntfy.sh/你的主题名
```

主力机应立即弹通知并在列表显示。

## 开启验证码自动填充（系统级）

安卓不允许 App 静默往输入框填字（安全限制），标准做法是注册成**系统自动填充服务**，
由用户在系统设置里启用，填充时弹候选气泡让用户确认——和 Gboard、密码管理器的体验一致。

1. 打开本 App，点「**点此开启验证码自动填充（系统设置）**」按钮，跳转到系统设置。
2. 在「自动填充服务」里选择本 App（名称「短信接收端」）。
3. 以后在**任意 App**（银行、社交、购物等）的验证码输入框点一下，
   键盘上方会弹出「**填入验证码 xxxxxx**」候选，点一下即自动填入。

识别逻辑（`SmsAutofillService.kt`）：
- 优先识别带 OTP/验证码/verification 语义的输入框（含 Android 标准 autofillHints）；
- 兜底识别 4~10 位的纯数字输入框；
- 验证码来源是本 App 保存的最新一条转发短信，取不到就不弹，不会误填。

> 注意：自动填充用的是**本机保存的最新验证码**，所以要先收到转发的验证码短信、
> 且本 App 在后台运行中（前台服务常驻），才能填充。不同 App 的验证码框形态各异，
> 个别识别不到的可以在通知栏点「复制验证码」手动粘贴。

## 延迟优化清单（重要）

| 项 | 作用 |
|---|---|
| App 内点「忽略电池优化」 | 防止息屏后长连接被省电机制掐断（延迟的最大杀手） |
| 允许自启动 + 常驻通知 | 前台服务保活，进程被杀概率大幅降低 |
| 备用机 SmsForwarder 保活 | 备用机侧同样要关闭电池优化，否则发端就断了 |
| 收发两端网络质量 | ntfy.sh 服务器在国外，国内网络高峰期可能有 1~3 秒波动；要求更稳可自建 ntfy |

如果对延迟要求极高或介意消息经公共服务器，可自建 ntfy（单二进制，一条 Docker
命令跑起来），把 App 里 `NtfySubscriber.kt` 的 `https://ntfy.sh` 换成自己的域名即可。

## 安全说明

- ntfy.sh 上**任何知道主题名的人都能订阅看到消息**，所以主题名务必设成足够长的随机串。
- ntfy.sh 缓存消息默认 12 小时，之后服务器上删除；App 本地历史永久保留，可随时清空。
- 验证码类短信介意经第三方服务器的，请自建 ntfy。

## License

示例代码可自由修改使用。
