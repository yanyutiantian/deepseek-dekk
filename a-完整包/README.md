# a-完整包 · DeepSeek 增强模块源码

Xposed 模块主工程，宿主为 DeepSeek 安卓客户端（`com.deepseek.chat`）。这里是**全部功能代码**，改完功能打出来的就是「完整包」。

---

## 编译

只兼容 **Java 7 语法**，别用 lambda / try-with-resources / 接口默认方法。

```bash
# 本目录就是 Gradle 工程根目录
./gradlew :app:assembleDebug
# 产物：app/build/bin/app.apk
```

**手机 AIDE**：直接导入本目录（AIDE 认这里的 `build.gradle` + `settings.gradle`），`local.properties` 不随仓库提供，AIDE 会自动生成。

**依赖说明**

- `app/libs/compile_only/xposed-api-82_compileonly.jar`：仅编译期用，不打包；
- `app/jniLibs/arm64-v8a/libdekkcore.so` 与 `app/assets/dekkcore_arm64.bin`：同一 native 载具的两种投递方式；
- 未提供 `local.properties`、`build/`、`.gradle/`，都是本机产物。

---

## 目录

```
a-完整包/
├── build.gradle / settings.gradle / gradle.properties
├── gradlew / gradlew.bat
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/dekk/yanyu/     # 200+ 个类
        ├── assets/                  # xposed_init、native 载具
        ├── jniLibs/                 # libdekkcore.so
        ├── res/                     # Miuix 风格资源
        └── web/dekk/                # 模块内置 Web UI（js 分片，build.sh 合并）
```

---

## 类簇索引

| 类簇 | 主要类 | 职责 |
|---|---|---|
| 入口 / 框架 | `DekkEntry`、`HookInit`、`MainActivity`、`AntiDetect`、`SignatureMask` | LSPosed 入口、模块自检、反检测 |
| UI 基础 | `MiuixUi`、`MiuixIcon`、`MiuixGlassBar`、`MiuixBlur`、`ModuleDialog`、`ModuleNavigation`、`ModuleTheme`、`TabLiquid`、`CollapsibleSection` | HyperOS / Miuix 风格控件库 |
| 页面 | `DekkSettingsPage`、`CenterPage`、`BeautifyPage`、`AntiRiskPage`、`SponsorPage`、`UserCenterPage`、`AccountPages`、`SafetyPage`、`BackupPage`、`TransferPage` | 设置与各功能页 |
| 账号 | `AccountStore`、`AccountCenterCard`、`PersonaApi`、`SponsorApi`、`BackupApi` | 登录态、接口调用 |
| 人设 / 插件 | `PersonaEditor`、`PersonaPages`、`PersonaLocalStore`、`PluginEditor`、`PluginPages`、`PluginStore` | 本地编辑 + 市场 |
| 对话增强 | `ConversationInjector`、`ContextSummary*`、`ReplySegments`、`ConversationSegments`、`MultiReply*`、`ProactiveMessages`、`PromptTags`、`CurrentTurnContext` | 上下文、分段、多回复、主动发言 |
| 聊天美化 | `ChatBubbleHook`、`ChatBackgroundHook`、`ChatEffectsHook`、`BubbleStyle*`、`BeautifyStore`、`EffectPlayer`、`LiquidGlassNavigation` | 气泡、背景、特效 |
| 反撤回 / 风控 | `AntiRecallHook`、`FakeBan*`、`AiBanControl`、`SafetyPage` | 消息留存、伪封禁、AI 风控提示 |
| ADB 远程控制 | `AdbBridge`、`AdbProtocol`、`AdbControl*`、`AdbAiHook`、`AdbUsageBlock` | 有线/无线 ADB 通道与命令面板 |
| 音乐 | `Music*`、`Netease*`、`QqMusic*` | 网易云 / QQ 音乐登录、歌词、迷你条 |
| 语音通话 | `VoiceCallManager`、`VoiceCallHook`、`VoiceCallTts`、`VoiceCallUi` | 通话界面与 TTS |
| 协议 / 加密 | `ApiCrypto`、`BackupCrypto`、`TransferProtocol`、`VirtualTransferProtocol` | V2 信封、备份加密、转账协议 |
| 数美过检 | `ShumeiBypass`、`ShumeiTrace`、`SmsNative` | 加载 `libdekkcore.so`、风控探针处理 |
| 热更新桥 | `HotApp`、`HotBridge`、`HotHook*` | 与 `c-热更新` 壳对接的门面 |

---

## 改之前必须做的一件事：换 RSA 公钥

`app/src/main/java/com/dekk/yanyu/ApiCrypto.java` 里的 `PUBLIC_KEY_BASE64` 是原项目的公钥，**只有配上原服务端私钥才能通信**。自建后端请生成自己的密钥对：

```bash
openssl genrsa -out dekk_rsa_private.pem 2048
openssl rsa -in dekk_rsa_private.pem -pubout -out dekk_rsa_public.pem
# 把 public.pem 的头尾去掉、去掉换行，粘进 PUBLIC_KEY_BASE64
```

服务端 `crypto.php` 读私钥的方式见 [`../b-后端/README.md`](../b-后端/README.md)。

---

## 与其它目录的关系

- 后端接口地址：`PersonaApi.java` / `BackupApi.java` / `SponsorApi.java` 里的 `BASE` 常量，当前是 `https://dekk.example.com/API/?action=`，换成你自己的域名；
- 热更新：功能代码也走 `c-热更新` 的 payload 通道，改功能只需发 dex，不必让用户重装 APK；
- 机器人绑定：`BindGate.java` 与 `d-机器人` 的 `/code` 指令配合完成 KOOK 账号绑定。
