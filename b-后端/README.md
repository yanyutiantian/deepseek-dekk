# b-后端 · PHP API + 管理面板 + 自助赞助

模块的服务端：账号体系、人设/插件市场、评论、通知、备份、封禁、人机验证、赞助发货。**本目录内自带 `panel/` 与 `pay/` 两个子站，可整体部署。**

---

## 环境

- PHP **8.2+**，扩展：`pdo_mysql`、`mbstring`、`gd`、`openssl`、`curl`
- MySQL 5.7 / 8.0，字符集 `utf8mb4`
- Nginx / Apache 均可；建议把本目录放在 **Web 根目录之外**（只有 `API/` 与静态资源对外）

---

## 部署步骤

```bash
# 1. 建库建表
mysql -uroot -p -e "CREATE DATABASE dekk CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -uroot -p dekk < schema.sql
mysql -uroot -p dekk < plugins-schema.sql

# 2. 配置（三份都要复制）
cp config.example.php config.php
cp pay/config.example.php pay/config.php
cp panel/config.example.php panel/config.php
$EDITOR config.php            # DSN / 数据库账号 / 各种密钥

# 3. RSA 密钥对（V2 加密层）
openssl genrsa -out dekk_rsa_private.pem 2048
openssl rsa -in dekk_rsa_private.pem -pubout -out dekk_rsa_public.pem
chmod 600 dekk_rsa_private.pem
# 公钥填进 a-完整包/app/.../ApiCrypto.java 的 PUBLIC_KEY_BASE64

# 4. 权限
chown -R www:www .
chmod 640 config.php pay/config.php panel/config.php
```

> `config.php` 已在 `.gitignore` 里，不会被提交。`bootstrap.php` 用 `__DIR__` 定位同目录下的 `config.php`。

---

## Nginx 参考

```nginx
server {
    listen 443 ssl http2;
    server_name dekk.example.com;
    root /var/www/dekk.example.com;
    index index.php;

    location /API/ {
        fastcgi_pass unix:/run/php/php8.2-fpm.sock;
        include fastcgi_params;
        fastcgi_param SCRIPT_FILENAME $document_root/API/api_index.php;
    }

    # 私有代码目录不对外
    location ~ ^/(config|bootstrap|auth|review|personas|plugins|profile|comments|notices|items|backups|binds|crypto)\.php$ { deny all; }
}
```

`API/` 目录放一个 `index.php` 转发到 `api_index.php`，或用 `try_files` 直接指过去。

---

## 配置项

### `config.php`

| 键 | 说明 |
|---|---|
| `dsn` / `db_user` / `db_password` | 数据库连接 |
| `module_secret` | 客户端 ↔ 服务端对称密钥，须与模块内常量一致 |
| `gate_secret` | 人机验证关卡签名密钥 |
| `smtp_user` / `smtp_password` | 发验证码的 SMTP（QQ 邮箱 + 授权码） |
| `review_apikey` | 公开内容 AI 审核 Key，留空则跳过审核 |

### `pay/config.php`（自助赞助）

| 键 | 说明 |
|---|---|
| `dsn` / `db_user` / `db_password` | 与主库同一套；pay 站开了 `open_basedir`，所以单独配一份 |
| `epay_api` / `epay_pid` / `epay_key` / `epay_type` | 易支付参数，自行申请 |
| `money` / `goods_name` / `expire_seconds` | 赞助金额、商品名、链接有效期 |
| `site` | 本站对外地址 |
| `internal_key` | 机器人 ↔ 本站内部接口密钥，自行随机生成 |

### `panel/config.php`（管理面板）

| 键 | 说明 |
|---|---|
| `dsn` / `db_user` / `db_password` | 同上 |
| `internal_key` | 面板内部接口密钥 |

---

## 文件地图

```
b-后端/
├── api_index.php        # 单一入口：/API/?action=xxx
├── bootstrap.php        # PDO、JSON 封装、鉴权、限流
├── crypto.php           # V2 信封（RSA + AES-256-CBC + HMAC-SHA256）
├── auth.php             # 注册 / 登录 / 改密 / 验证码
├── profile.php          # 资料、关注、头像
├── personas.php         # 人设增删改查
├── plugins.php          # 插件市场
├── comments.php         # 评论 / 置顶
├── notices.php          # 通知
├── items.php            # 通用条目读取
├── backups.php          # 对话备份（PBKDF2 + AES）
├── binds.php            # KOOK 账号绑定
├── sponsor.php          # 赞助身份查询（客户端唯一判定入口）
├── review.php           # 公开内容 AI 审核
├── gate*.php            # 人机验证关卡（图片验证码）
├── migrate_*.php        # 一次性迁移脚本
├── setup_owner.php      # 站长账号初始化
├── schema.sql           # 主库建表
├── plugins-schema.sql   # 插件表增量
├── panel/               # 管理面板（兑换码、用户检索、封禁）
└── pay/                 # 自助赞助（易支付建单、回调、发身份）
```

---

## API V2 加密协议

```
请求  POST /API/?action=xxx&ver=v2
      body: {"v":2,"env":"<base64(信封JSON)>"}
信封  {"v":2,"ts":<秒>,"nonce":<hex>,
       "key": RSA_OAEP(master32),
       "iv":  base64(16B),
       "data": base64(AES-256-CBC(master 派生)),
       "mac": HMAC-SHA256(macKey, iv||data)}
响应  {"v":2,"iv":…,"data":…,"mac":…}
```

- `master` 每次请求随机生成，用服务端 RSA 公钥加密；
- `enc = SHA256(master||"enc")`、`mac = SHA256(master||"mac")` 派生两把子密钥；
- 时间戳窗口 300 秒，nonce 落库去重，超出视为重放；
- 不带 `ver=v2` 的老客户端走明文，V1 截止时间后统一返回「请更新」。

### 接口清单

- **GET**：`health`、`plugin_market`、`plugin_mine`、`plugin_get`、`plugin_download`、`market`、`mine`、`me`、`profile_get`、`avatar`、`user_profile`、`comment_list`、`notice_list`、`notice_unread`、`item_get`、`backup_get`
- **POST**：`save_plugin`、`delete_plugin`、`send_code`、`register`、`login`、`password`、`nickname`、`logout`、`save_persona`、`delete_persona`、`profile_avatar`、`profile_delete`、`follow`、`comment_add`、`comment_delete`、`comment_pin`、`notice_read`、`backup_put`、`backup_delete`、`bind_code`、`bind_status`

蜜罐词表（`action=sql`、`phpinfo`、`admin_export` 等）命中即记录并封禁 24h。

---

## AI 审核

`review.php` 只审 `visibility=public` 的人设 / 插件，按 OpenAI Chat Completions 格式调用，一次审完「名称 + 简介 + 提示词」：

```php
const DEKK_REVIEW_ENDPOINT = 'https://your-review-api.example.com/v1/chat/completions';
const DEKK_REVIEW_MODEL    = 'your-model-name';
```

返回第一行必须是 `SAFE` / `UNSAFE`，`UNSAFE` 时第二行给不超过 20 字原因。Key 从 `config.php` 的 `review_apikey` 读取。不想用 AI 审核就把 key 留空。

---

## 自助赞助流程

```
用户扫码 → pay/create.php 建单 → 易支付跳转
        → pay/notify.php 异步回调验签 → 写 dekk_users 赞助到期时间
        → pay/p.php / role_done.php 结果页
```

- 金额只在服务端 `config.php` 的 `money` 决定，不接受客户端传参（防篡改）；
- 回调必须验签 + 校验 `trade_status`；
- 兑换码：`panel/create.php` 生成，`pay/code.php` 核销；
- 机器人通过 `internal_key` 调 `pay/pending.php`、`pay/mark_message.php` 发身份。
