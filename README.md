# deepseek-dekk

DeepSeek 安卓客户端（`com.deepseek.chat`）的 Xposed 增强模块，连同它配套的后端、热更新壳和 KOOK 机器人。

**仓库按「用途」分目录，每个目录都能独立拉取、独立部署**，你只想要哪一块就只下哪一块。

> 本仓库是**脱敏后的开源版本**：服务器地址、数据库口令、支付商户号与密钥、AI 审核接口与 Key、内部通信密钥、管理账号、KOOK 频道/身份 ID 全部换成了占位符。代码逻辑完整，部署时按各目录 README 自行填写。

---

## 目录

| 目录 | 用途 | 技术栈 | 单独部署 |
|---|---|---|---|
| [`a-完整包/`](a-完整包/) | 模块主工程，全部功能代码，打出来就是完整包 APK | Java 7 / Xposed | 需要 Android SDK 或 AIDE |
| [`b-后端/`](b-后端/) | PHP API + 管理面板 + 自助赞助站（含 `panel/`、`pay/`） | PHP 8.2 + MySQL | 独立，需域名 + HTTPS |
| [`c-热更新/`](c-热更新/) | 热更新壳工程，从远端拉 dex 跑，改功能不用重装 APK | Java 7 / Xposed | 需要 Android SDK 或 AIDE |
| [`d-机器人/`](d-机器人/) | KOOK 机器人：AI 客服、`/code` 账号绑定、`/pay` 自助赞助、`/panel` 管理面板 | Python 3 (khl.py) | 独立，任意能联网的机器 |

---

## 它们怎么连起来

```
                    ┌──────────────────────────────┐
                    │  b-后端  PHP + MySQL          │
                    │  账号 / 人设 / 插件 / 评论     │
                    │  V2 加密信封 / 封禁 / 备份     │
                    │  ┌────────┐  ┌─────────────┐ │
                    │  │ panel/ │  │   pay/      │ │
                    │  │ 管理面板│  │ 自助赞助站   │ │
                    │  └────────┘  └─────────────┘ │
                    └───────▲──────────────▲───────┘
                            │ HTTPS+V2     │ internal_key
        ┌───────────────────┴──┐        ┌──┴─────────────────┐
        │  a-完整包  Xposed 模块│        │  d-机器人           │
        │  注入 DeepSeek 客户端 │◄──────►│  /code 绑定账号     │
        │        ▲              │ 绑定码  │  /pay 发起赞助      │
        │        │ 门面          │        │  /panel 管理面板    │
        │  c-热更新  壳 + dex   │        └────────────────────┘
        └───────────────────────┘
```

- 模块的所有网络请求走 **API V2 加密信封**（RSA + AES-CBC + HMAC，见 `b-后端/README.md`）；
- 热更新：功能代码打成 dex 放服务器，壳冷启动拉取后加载，**不需要重新发 APK**；
- 绑定：机器人 `/code` 出码 → 客户端「账号中心」输码 → 后端 `bind_code` / `bind_status` 落库；
- 赞助：机器人 `/pay` 建单 → 易支付回调 → 后端标记 → 机器人轮询发放「赞助用户」身份。

---

## 只拉某一个目录

**方式 1：网页下载**（最省事）
在 GitHub 上点进目标目录 → 右上角 `⋯` → 没有整目录下载按钮时，用下面的 sparse-checkout。

**方式 2：稀疏检出**（推荐）

```bash
git clone --filter=blob:none --sparse https://github.com/yanyutiantian/deepseek-dekk.git
cd deepseek-dekk
git sparse-checkout set b-后端          # 只要后端
# git sparse-checkout set a-完整包 c-热更新   # 要多个就一起列
```

**方式 3：只拿压缩包**

```bash
curl -L https://github.com/yanyutiantian/deepseek-dekk/archive/refs/heads/main.tar.gz \
  | tar xz --wildcards 'deepseek-dekk-main/b-后端/*'
```

---

## 快速开始

| 我想… | 去哪 |
|---|---|
| 编译模块 / 看有哪些功能 | [`a-完整包/README.md`](a-完整包/README.md) |
| 搭后端、配数据库、看接口清单 | [`b-后端/README.md`](b-后端/README.md) |
| 让用户不用重装就能更新功能 | [`c-热更新/README.md`](c-热更新/README.md) |
| 起 KOOK 机器人、配 `/code` 和 `/pay` | [`d-机器人/README.md`](d-机器人/README.md) |

三件必须自己生成的东西：

1. **RSA 密钥对**：`b-后端` 放私钥，`a-完整包` 的 `ApiCrypto.java` 填公钥；
2. **`module_secret` / `gate_secret` / `internal_key`**：后端三份配置里各自随机生成；
3. **AI 审核与 AI 客服的接口地址 + Key**：任意 OpenAI 风格接口都能接。

---

## 脱敏清单（与原版的差异）

| 原内容 | 仓库中 |
|---|---|
| 原站主域名 / 支付站域名 | `dekk.example.com` / `pay.example.com` |
| 服务器 IP | `203.0.113.10` |
| 数据库账号口令 | `dekk_user` / `YOUR_DB_PASSWORD` |
| 易支付商户号、密钥 | `YOUR_EPAY_PID` / `YOUR_EPAY_KEY` |
| 机器人 / 面板内部密钥 | `YOUR_INTERNAL_KEY` / `YOUR_PANEL_INTERNAL_KEY` |
| AI 审核接口地址与 Key | 通用占位地址，Key 走 `config.php` |
| AI 客服接口与 Key | `YOUR_AI_API_KEY` |
| KOOK Bot Token | `YOUR_KOOK_BOT_TOKEN` |
| KOOK 服务器 / 频道 / 身份 ID 与邀请链接 | `0` / 空串 / `YOUR_INVITE` |
| 管理员 / 站长 QQ 邮箱与 QQ 号 | `owner@example.com` / `10000` |
| QQ 音乐 OAuth 的 client_id | `YOUR_QQ_APPID` |
| 赞助页署名与图片直链 | 通用占位 |
| 硬编码部署路径 `/www/...` | `__DIR__` 相对定位，可整体搬目录 |

**唯一没换的**：`ApiCrypto.java` 里的 RSA **公钥**。公钥本身不保密，但它只配得上原服务端私钥，自建服务请务必换成自己的（见 `a-完整包/README.md`）。

---

## 免责声明

本项目仅用于**学习与研究 Android 逆向、Xposed 框架与后端架构**。

- 修改、注入第三方客户端可能违反其用户协议，请自行评估风险；
- 请勿用于绕过付费、批量注册、传播违规内容等用途；
- 仓库内不含任何真实用户数据、密钥或可用的线上服务地址；
- 使用者自行承担因部署、运行产生的全部风险与责任。

## License

[MIT](LICENSE)
