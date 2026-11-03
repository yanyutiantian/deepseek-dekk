# deepseek-dekk

DeepSeek 安卓客户端的 Xposed 增强模块，含完整的前端模块工程、热更新壳工程、PHP 后端、KOOK 机器人与自助赞助站点。

> 本仓库为**脱敏后的开源版本**：所有服务器地址、数据库口令、支付商户号与密钥、AI 审核接口与 API Key、内部通信密钥、管理账号均已替换为占位符。部署前请按下文自行填写，代码逻辑保持完整。

---

## 一、这个项目是什么

`deepseek-dekk` 是一套围绕 DeepSeek 安卓官方客户端（`com.deepseek.chat`）的扩展方案，由五个可独立部署的部分组成：

| 部分 | 目录 | 技术栈 | 作用 |
|---|---|---|---|
| 主模块 | `app/` | Java 7 / Xposed | 注入宿主进程，提供 UI、人设、美化、音乐、语音、ADB 等全部功能 |
| 热更新壳 | `hotupdate/` | Java 7 / Xposed | 独立的小体积宿主入口，用 DexClassLoader 从远端拉取逻辑 dex 并下发 Hook |
| 后端 API | `server/` | PHP 8.x + MySQL | 账号、人设/插件市场、评论、通知、备份、绑定、封禁 |
| 管理面板 | `server/panel/` | PHP | 兑换码生成、用户检索、封禁/解封 |
| 自助赞助 | `server/pay/` | PHP | 易支付对接、回调发货、给账号加赞助标记 |
| KOOK 机器人 | `kookbot/` | Python 3 | 网络工具箱（ping / whois / SSL / 端口 / DNS 等），附赞助码发放 |

模块与后端通过自造的 **API V2 加密信封**通信（见第六节），不依赖第三方 SDK。

---

## 二、目录结构

```
deepseek-dekk/
├── app/                      # 主模块（完整包工程）
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dekk/yanyu/     # 200+ 个类，见「类簇说明」
│       ├── assets/                  # xposed_init、native 载具
│       ├── jniLibs/                 # libdekkcore.so
│       ├── res/                     # Miuix 风格资源
│       └── web/dekk/                # 模块内置的 Web UI（js 分片）
├── hotupdate/                # 热更新壳（独立 Gradle 工程，可直接用 AIDE 编译）
│   ├── build.gradle
│   ├── app/src/main/java/com/dekk/yanyu/   # Hot* 系列
│   └── tools/                # dex 打包/校验脚本
├── server/                   # PHP 后端
│   ├── api_index.php         # 单一入口：/API/?action=xxx
│   ├── bootstrap.php         # DB、鉴权、限流、错误封装
│   ├── crypto.php            # V2 信封（RSA + AES-CBC + HMAC）
│   ├── auth.php / profile.php / personas.php / plugins.php
│   ├── comments.php / notices.php / items.php / backups.php / binds.php
│   ├── review.php            # 公开内容 AI 审核（可关）
│   ├── gate*.php             # 人机验证关卡
│   ├── panel/                # 管理面板
│   ├── pay/                  # 自助赞助（易支付）
│   ├── schema.sql            # 主库建表
│   ├── plugins-schema.sql    # 插件表增量
│   └── config.example.php    # ← 复制成 config.php 再填
├── kookbot/                  # KOOK 机器人
├── LICENSE
└── README.md
```

---

## 三、模块类簇说明

按职责分组，方便定位：

- **入口 / 框架**：`DekkEntry`、`HookInit`、`MainActivity`、`Hot*`（热更新）、`AntiDetect`、`SignatureMask`
- **UI 基础**：`MiuixUi`、`MiuixIcon`、`MiuixGlassBar`、`MiuixBlur`、`ModuleDialog`、`ModuleNavigation`、`ModuleTheme`、`TabLiquid`、`CollapsibleSection`
- **设置 / 页面**：`DekkSettingsPage`、`CenterPage`、`BeautifyPage`、`AntiRiskPage`、`SponsorPage`、`UserCenterPage`、`AccountPages`、`SafetyPage`、`BackupPage`、`TransferPage`、`VoiceCallCard`
- **人设 / 插件**：`PersonaApi`、`PersonaEditor`、`PersonaPages`、`PluginEditor`、`PluginPages`、`PluginStore`
- **对话增强**：`ConversationInjector`、`ContextSummary*`、`ReplySegments`、`ConversationSegments`、`MultiReply*`、`ProactiveMessages`、`PromptTags`
- **聊天美化**：`ChatBubbleHook`、`ChatBackgroundHook`、`ChatEffectsHook`、`BubbleStyle*`、`BeautifyStore`、`EffectPlayer`、`LiquidGlassNavigation`
- **反撤回 / 反检测**：`AntiRecallHook`、`FakeBan*`、`AiBanControl`、`SafetyPage`
- **ADB 远程控制**：`AdbBridge`、`AdbProtocol`、`AdbControl*`、`AdbAiHook`
- **音乐**：`Music*`（网易云 / QQ 音乐登录、歌词、迷你条）、`Netease*`、`QqMusic*`
- **语音通话**：`VoiceCallManager`、`VoiceCallHook`、`VoiceCallTts`
- **协议 / 加密**：`ApiCrypto`、`BackupCrypto`、`TransferProtocol`、`VirtualTransferProtocol`
- **数美过检**：`ShumeiBypass`、`ShumeiTrace`、`SmsNative`（加载 `libdekkcore.so`）

---

## 四、编译

模块只兼容 **Java 7 语法**，别用 lambda / try-with-resources。

**Android Studio / Gradle**

```bash
# 主模块
./gradlew :app:assembleDebug

# 热更新壳（独立工程）
cd hotupdate && ./gradlew :app:assembleDebug
```

**手机 AIDE**

1. 把 `app/` 所在目录整个导入（AIDE 认根目录的 `build.gradle` + `settings.gradle`）；
2. `local.properties` 不随仓库提供，AIDE 会自行生成；
3. 编译产物输出在 `app/build/bin/app.apk`。

依赖说明：`libs/compile_only/xposed-api-82_compileonly.jar` 仅编译期使用，不打包；`jniLibs/arm64-v8a/libdekkcore.so` 与 `assets/dekkcore_arm64.bin` 是同一 native 载具的两种投递方式。

---

## 五、后端部署

### 5.1 环境

- PHP **8.2+**，扩展：`pdo_mysql`、`mbstring`、`gd`、`openssl`、`curl`
- MySQL 5.7 / 8.0，字符集 `utf8mb4`
- Nginx 或 Apache 均可；建议把 `server/` 放在**Web 根目录之外**

### 5.2 步骤

```bash
# 1. 建库建表
mysql -uroot -p -e "CREATE DATABASE dekk CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -uroot -p dekk < server/schema.sql
mysql -uroot -p dekk < server/plugins-schema.sql

# 2. 配置
cd server
cp config.example.php config.php
cp pay/config.example.php pay/config.php
cp panel/config.example.php panel/config.php
$EDITOR config.php                 # 填 DSN / 账号 / 密钥

# 3. 生成 RSA 密钥对（V2 加密层）
openssl genrsa -out dekk_rsa_private.pem 2048
openssl rsa -in dekk_rsa_private.pem -pubout -out dekk_rsa_public.pem
chmod 600 dekk_rsa_private.pem
# 把 public.pem 去掉头尾换行，填进 app/src/main/java/com/dekk/yanyu/ApiCrypto.java
# 的 PUBLIC_KEY_BASE64 常量（换成你自己的！）

# 4. 权限
chown -R www:www /var/www/dekk.example.com
chmod 600 config.php pay/config.php panel/config.example.php
```

### 5.3 Nginx 参考

```nginx
server {
    listen 443 ssl http2;
    server_name dekk.example.com;
    root /var/www/dekk.example.com;
    index index.php;

    location /API/ {
        try_files $uri /API/api_index.php?$query_string;
        fastcgi_pass unix:/run/php/php8.2-fpm.sock;
        include fastcgi_params;
        fastcgi_param SCRIPT_FILENAME $document_root/API/api_index.php;
    }

    # 私有代码目录不对外
    location ~ ^/(config|bootstrap|auth|review|personas|plugins|profile|comments|notices|items|backups|binds|crypto)\.php$ { deny all; }
}
```

### 5.4 配置项

`server/config.php`

| 键 | 说明 |
|---|---|
| `dsn` / `db_user` / `db_password` | 数据库连接 |
| `module_secret` | 客户端 ↔ 服务端对称密钥，须与模块内常量一致 |
| `gate_secret` | 人机验证关卡签名密钥 |
| `smtp_user` / `smtp_password` | 发验证码的 SMTP（QQ 邮箱 + 授权码） |
| `review_apikey` | 公开内容 AI 审核的 Key，留空则跳过审核 |

`server/pay/config.php`

| 键 | 说明 |
|---|---|
| `dsn` / `db_user` / `db_password` | 与主库同一套（pay 站开 `open_basedir`，所以单独配一份） |
| `epay_api` / `epay_pid` / `epay_key` / `epay_type` | 易支付参数，自行申请 |
| `money` / `goods_name` / `expire_seconds` | 赞助金额、商品名、链接有效期 |
| `site` | 本站对外地址 |
| `internal_key` | 机器人 ↔ 本站内部接口密钥，自行随机生成 |

`server/panel/config.php`

| 键 | 说明 |
|---|---|
| `dsn` / `db_user` / `db_password` | 同上 |
| `internal_key` | 面板内部接口密钥 |

---

## 六、API V2 加密协议

```
请求  POST /API/?action=xxx&ver=v2
      body: {"v":2,"env":"<base64(信封JSON)>"}
信封  {"v":2,"ts":<秒>,"nonce":<hex>,
       "key": RSA_OAEP(master32),
       "iv":  base64(16B),
       "data": base64(AES-256-CBC(master 派生), 
       "mac": HMAC-SHA256(macKey, iv||data)}
响应  {"v":2,"iv":…,"data":…,"mac":…}
```

- `master` 由客户端每次请求随机生成，用服务端 RSA 公钥加密；
- `enc = SHA256(master||"enc")`、`mac = SHA256(master||"mac")` 派生两把子密钥；
- 时间戳窗口 300 秒，超出视为重放；nonce 落库去重；
- 不带 `ver=v2` 的老客户端走明文，服务端设有 V1 截止时间，过期统一返回「请更新」。

接口清单（`?action=`）：

- **GET**：`health`、`plugin_market`、`plugin_mine`、`plugin_get`、`plugin_download`、`market`、`mine`、`me`、`profile_get`、`avatar`、`user_profile`、`comment_list`、`notice_list`、`notice_unread`、`item_get`、`backup_get`
- **POST**：`save_plugin`、`delete_plugin`、`send_code`、`register`、`login`、`password`、`nickname`、`logout`、`save_persona`、`delete_persona`、`profile_avatar`、`profile_delete`、`follow`、`comment_add`、`comment_delete`、`comment_pin`、`notice_read`、`backup_put`、`backup_delete`、`bind_code`、`bind_status`

另有蜜罐词表（`action=sql`、`phpinfo` 等）命中即记录并封禁 24h，扫描器会自动踩雷。

---

## 七、AI 内容审核

`server/review.php` 只审核 `visibility=public` 的人设 / 插件，一次请求同时审「名称 + 简介 + 提示词」，接口按 OpenAI Chat Completions 格式调用：

```php
const DEKK_REVIEW_ENDPOINT = 'https://your-review-api.example.com/v1/chat/completions';
const DEKK_REVIEW_MODEL    = 'your-model-name';
```

返回正文第一行必须是 `SAFE` 或 `UNSAFE`，`UNSAFE` 时第二行给不超过 20 字的原因。密钥从 `config.php` 的 `review_apikey` 读取，**不再硬编码**。

不想用 AI 审核：把 `config.php` 里 `review_apikey` 留空即可，`dekk_review_*` 会直接放行。也可以换成任意兼容 OpenAI 格式的接口。

---

## 八、自助赞助（易支付）

流程：

```
用户扫码 → pay/create.php 下单 → 易支付跳转支付
        → notify.php 异步回调验签 → 写 dekk_users 赞助到期时间
        → role_done.php / p.php 展示结果页
```

- 金额只在服务端 `config.php` 的 `money` 决定，不接受客户端传参（防篡改）；
- 回调必须验签（`epay_key`）+ 校验 `trade_status`；
- 支持「纯数字账号 → 自动补 `@qq.com`」的快捷查询；
- 兑换码路径：`panel/create.php` 生成、`pay/code.php` 核销。

---

## 九、KOOK 机器人

`kookbot/` 是一个基于 KOOK（开黑啦）WebSocket 的网络工具箱机器人。

```bash
cd kookbot
pip install -r requirements.txt
# 编辑 config.py，填入 BOT_TOKEN
python3 main.py
```

| 指令 | 功能 |
|---|---|
| `/ping` | ICMP 连通性 |
| `/tcp` `/port` | 端口探测 |
| `/http` `/https` `/webping` | 站点可用性 |
| `/ssl` | 证书链与到期时间 |
| `/dns` `/whois` | 解析记录 / 域名信息 |
| `/ip` `/bgp` | IP 归属 / 路由 |
| `/github` | 仓库信息预览 |
| `/tr` `/xt` | 翻译 / 短链等杂项 |

`config.py` 里的第三方 API（`api.tangdouz.com`、`oiapi.net`）是原作者的公共接口，长期可用性不保证，建议换成自己的。

---

## 十、脱敏清单（与原版的差异）

| 原内容 | 仓库中 |
|---|---|
| 站点域名 `dekk.yaxs.cn` / `pay.yaxs.cn` | `dekk.example.com` / `pay.example.com` |
| 服务器 IP | `203.0.113.10` |
| 数据库账号口令 | `dekk_user` / `YOUR_DB_PASSWORD` |
| 易支付商户号、密钥 | `YOUR_EPAY_PID` / `YOUR_EPAY_KEY` |
| 机器人 / 面板内部密钥 | `YOUR_INTERNAL_KEY` / `YOUR_PANEL_INTERNAL_KEY` |
| AI 审核接口与 Key（`missqiu.icu`） | 换成通用占位地址，Key 走 `config.php` |
| 管理员 / 站长 QQ 邮箱与 QQ 号 | `owner@example.com` / `10000` |
| KOOK 服务器 ID | `0000000000` |
| QQ 音乐 OAuth 的 client_id | `YOUR_QQ_APPID` |
| 赞助页署名与图片直链 | 通用占位 |
| 硬编码部署路径 `/www/...` | `__DIR__` 相对定位，可直接搬目录 |

**没有脱敏、需要你注意的**：`ApiCrypto.java` 里的 RSA **公钥**仍是原项目的。它是公钥（本身不保密），但只有配上原服务端私钥才能通信。自建服务时请按 5.2 第 3 步生成自己的密钥对并替换，否则你的客户端只会打到原服务端。

---

## 十一、免责声明

本项目仅用于**学习与研究 Android 逆向、Xposed 框架与后端架构**。

- 修改、注入第三方客户端可能违反其用户协议，请自行评估风险；
- 请勿用于绕过付费、批量注册、传播违规内容等用途；
- 仓库内不含任何真实用户数据、密钥或可用的线上服务地址；
- 使用者自行承担因部署、运行产生的全部责任。

---

## License

[MIT](LICENSE)
