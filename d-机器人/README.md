# d-机器人 · DEKK KOOK 机器人

DEKK 官方 KOOK 机器人：**AI 客服 + KOOK 账号绑定 + 自助赞助 + 管理面板**。基于 [khl.py](https://github.com/TWT233/khl.py)（KOOK WebSocket SDK）。

---

## 指令一览

| 指令 | 可用频道 | 作用 |
|---|---|---|
| `/ai 你的问题` | 全服 | 带 DEKK 常见问题人设的 AI 问答，按 KOOK 用户 ID 分开记忆 |
| `/code` | `#账号绑定` | 生成绑定码，把 KOOK 账号绑到 DEKK 账号 |
| `/pay 注册邮箱` | `#自助赞助` | 校验邮箱 → 建单 → 返回支付宝充值链接，支付成功后自动加「赞助用户」身份 |
| `/setpay 金额` | 任意（需管理员身份） | 改自助赞助金额（只改服务端，客户端无法篡改） |
| `/panel` | `#管理员频道` | 申请一次性管理面板链接，1 小时有效 |

### 绑定流程（`/code`）

```
用户 /code → 机器人向 pay 站点 code.php 申请绑定码
           → 用户到客户端「账号中心」输入绑定码
           → 客户端 bind_code / bind_status 接口完成绑定
           → 机器人轮询状态并回执卡片
```

### 赞助流程（`/pay`）

```
/pay 邮箱 → 调 pay/create.php 建单 → 返回充值链接
          → 用户支付 → pay 站点回调标记赞助
          → sponsor_poller 每 15s 轮询 pending.php
          → 给下单的 KOOK 用户加「赞助用户」身份
```

---

## 部署

```bash
pip install -r requirements.txt
$EDITOR config.py          # 填 BOT_TOKEN、接口地址、各种 ID
python3 main.py
```

systemd 示例：

```ini
[Unit]
Description=dekk-bot
After=network-online.target

[Service]
WorkingDirectory=/opt/dekk-bot
ExecStart=/usr/bin/python3 main.py
Restart=always
User=www

[Install]
WantedBy=multi-user.target
```

---

## 配置项

| 键 | 说明 |
|---|---|
| `BOT_TOKEN` | KOOK 开发者后台的 Bot Token（WebSocket） |
| `AI_ENDPOINT` / `AI_APIKEY` / `AI_MODEL` | 任意 OpenAI 风格问答接口；`AI_QQ` 是拿不到用户 ID 时的兜底记忆标识 |
| `PERSONA_PROMPT` | 客服人设 + FAQ 提示词，改回答口径就改这里 |
| `MEMORY_ID_PREFIX` | 记忆标识前缀，最终值 = 前缀 + KOOK 用户 ID |
| `PAY_API_BASE` / `PAY_INTERNAL_KEY` | 赞助站点地址与内部密钥，须与 `b-后端/pay/config.php` 的 `internal_key` 一致 |
| `PAY_ALLOWED_CHANNEL_ID` | 只允许该频道用 `/pay`，留空不限 |
| `KOOK_GUILD_ID` / `KOOK_SPONSOR_ROLE_ID` / `KOOK_ADMIN_ROLE_ID` | 频道 ID、赞助身份 ID、管理员身份 ID |
| `PANEL_API_BASE` / `PANEL_INTERNAL_KEY` | 管理面板地址与密钥，对应 `b-后端/panel/config.php` |
| `BIND_INVITE` | 新用户引导用的 KOOK 邀请链接 |

> 所有频道 / 身份 / 服务器 ID 都已清空（填 `0` 或空串），自建频道后在 KOOK 里右键「复制 ID」填回来。

---

## 目录

```
d-机器人/
├── config.py               # 全部配置
├── main.py                 # 指令注册与卡片渲染
├── handlers/
│   ├── ai_handler.py       # AI 问答（人设拼接、超长截断、错误兜底）
│   └── pay_handler.py      # 赞助建单
└── utils/
    ├── ai_client.py        # AI 接口调用与返回解析
    ├── pay_client.py       # pay 站点 / 面板 / KOOK API 封装
    ├── card_utils.py       # KMD 卡片片段
    └── sponsor_poller.py   # 待发身份订单轮询
```
