<?php
declare(strict_types=1);

/** 公共：配置 / 数据库 / JSON 输出 / 日志 / 易支付签名 / 过期订单清理。 */

function cfg(?string $key = null)
{
    static $config = null;
    if ($config === null) {
        $config = require __DIR__ . '/config.php';
    }
    if ($key === null) {
        return $config;
    }
    return array_key_exists($key, $config) ? $config[$key] : null;
}

function db(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        $pdo = new PDO(cfg('dsn'), cfg('db_user'), cfg('db_password'), [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
    }
    return $pdo;
}

function json_out(array $data): void
{
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function plog(string $message): void
{
    try {
        @file_put_contents(cfg('log'), '[' . date('Y-m-d H:i:s') . '] ' . $message . "\n", FILE_APPEND);
    } catch (Throwable $e) {
    }
}

/**
 * 易支付签名（MD5）：
 * 参数按键名 ksort 升序 → 拼成 k=v&k=v（排除 sign / sign_type 以及空值）→ 末尾拼商户 KEY → md5()
 */
function epay_sign(array $params): string
{
    unset($params['sign'], $params['sign_type']);
    ksort($params);
    $pairs = [];
    foreach ($params as $key => $value) {
        if ($value === null || $value === '') {
            continue;
        }
        $pairs[] = $key . '=' . $value;
    }
    return md5(implode('&', $pairs) . cfg('epay_key'));
}

/** 把超过有效期的未支付订单标记为 expired。 */
function expire_orders(): void
{
    try {
        db()->exec("UPDATE pay_orders SET status='expired' WHERE status='pending' AND expires_at<=NOW()");
    } catch (Throwable $e) {
        plog('expire_orders 失败: ' . $e->getMessage());
    }
}

/**
 * 固定窗口限流（和主站共用 dekksql.dekk_limits 表，pay 站与主站同库）。
 * 用于没有鉴权的页面（p.php / return.php），避免被枚举订单号刷爆。
 * 限流表异常时不阻断正常业务。
 */
function plimit(string $key, int $seconds, int $maximum): void
{
    try {
        $pdo = db();
        $bucket = hash('sha256', $key);
        $now = time();
        $pdo->beginTransaction();
        $pdo->prepare('INSERT IGNORE INTO dekk_limits(bucket,window_start,hits) VALUES(?,?,0)')
            ->execute([$bucket, $now]);
        $stmt = $pdo->prepare('SELECT window_start,hits FROM dekk_limits WHERE bucket=? FOR UPDATE');
        $stmt->execute([$bucket]);
        $row = $stmt->fetch();
        if (!$row || $now - (int) $row['window_start'] >= $seconds) {
            $row = ['window_start' => $now, 'hits' => 0];
        }
        if ((int) $row['hits'] >= $maximum) {
            $pdo->rollBack();
            http_response_code(429);
            header('Content-Type: application/json; charset=utf-8');
            echo '{"ok":false,"message":"操作频繁，请稍后重试"}';
            exit;
        }
        $pdo->prepare('UPDATE dekk_limits SET window_start=?,hits=? WHERE bucket=?')
            ->execute([$row['window_start'], (int) $row['hits'] + 1, $bucket]);
        $pdo->commit();
    } catch (Throwable $e) {
        try {
            if (db()->inTransaction()) {
                db()->rollBack();
            }
        } catch (Throwable $ignored) {
        }
    }
}

/** 内部接口密钥校验（机器人调用 create/pending/role_done 时用）。 */
function require_internal_key(): void
{
    $key = isset($_REQUEST['key']) ? (string) $_REQUEST['key'] : '';
    if ($key === '' || !hash_equals((string) cfg('internal_key'), $key)) {
        json_out(['ok' => false, 'msg' => '内部密钥错误']);
    }
}

/** 当前站点对外地址（拼接回调/跳转链接用）。 */
function site_url(string $path): string
{
    return rtrim((string) cfg('site'), '/') . '/' . ltrim($path, '/');
}

/**
 * 规范化账号：
 *   · 纯数字（5~12 位）→ 当作注册邮箱的 QQ 号，补成 123456@qq.com
 *   · 含 @ 的 → 原样当作邮箱
 *   · 其它 → null（调用方返回错误提示）
 */
function normalize_account(string $raw): ?string
{
    $raw = trim($raw);
    if ($raw === '') {
        return null;
    }
    if (preg_match('/^[0-9]{5,12}$/', $raw)) {
        return $raw . '@qq.com';
    }
    if (filter_var($raw, FILTER_VALIDATE_EMAIL)) {
        return $raw;
    }
    return null;
}

/** 只用于展示：QQ 邮箱只显示 QQ 号，其它邮箱脱敏。 */
function account_show(string $email): string
{
    if (preg_match('/^([0-9]{5,12})@qq\.com$/i', $email, $match)) {
        return $match[1];
    }
    $at = strpos($email, '@');
    if ($at === false || $at <= 0) {
        return $email;
    }
    $name = substr($email, 0, $at);
    $shown = strlen($name) <= 2 ? substr($name, 0, 1) : substr($name, 0, 2);
    return $shown . '***' . substr($email, $at);
}

/** 由订单动态构造易支付「页面跳转支付」链接（含 MD5 签名）。 */
function epay_pay_url(array $order): string
{
    $params = [
        'pid' => (string) cfg('epay_pid'),
        'type' => (string) cfg('epay_type'),
        'out_trade_no' => (string) $order['out_trade_no'],
        'name' => (string) cfg('goods_name'),
        'money' => (string) $order['money'],
        'notify_url' => site_url('notify.php'),
        'return_url' => site_url('return.php?out_trade_no=' . urlencode((string) $order['out_trade_no'])),
    ];
    $params['sign'] = epay_sign($params);
    $params['sign_type'] = 'MD5';
    return rtrim((string) cfg('epay_api'), '/') . '/submit.php?' . http_build_query($params);
}

/** 订单短链（发给用户复制的那条）。 */
function order_short_url(string $outTradeNo): string
{
    return site_url('p.php?id=' . urlencode($outTradeNo));
}

// ─────────────────────── 可配置项（pay_settings 表）───────────────────────

/** 读设置项（失败/不存在时返回默认值）。 */
function setting_get(string $key, ?string $default = null): ?string
{
    try {
        $stmt = db()->prepare('SELECT v FROM pay_settings WHERE k=? LIMIT 1');
        $stmt->execute([$key]);
        $row = $stmt->fetch();
        return $row ? (string) $row['v'] : $default;
    } catch (Throwable $e) {
        return $default;
    }
}

/** 写设置项。 */
function setting_set(string $key, string $value): void
{
    db()->prepare('INSERT INTO pay_settings(k,v) VALUES(?,?) ON DUPLICATE KEY UPDATE v=VALUES(v)')
        ->execute([$key, $value]);
}

/** 金额是否合法：0.01 ~ 99999，最多两位小数。 */
function money_valid(string $money): bool
{
    if (!preg_match('/^[0-9]{1,5}(\.[0-9]{1,2})?$/', $money)) {
        return false;
    }
    $value = (float) $money;
    return $value >= 0.01 && $value <= 99999;
}

/** 金额归一化：保留两位小数字符串。 */
function money_normalize(string $money): string
{
    return number_format((float) $money, 2, '.', '');
}

/** 当前自助赞助金额（数据库有覆盖就用覆盖值，否则用 config 里的默认值）。 */
function current_money(): string
{
    $money = setting_get('money');
    if ($money === null || $money === '' || !money_valid($money)) {
        $money = (string) cfg('money');
    }
    return money_normalize($money);
}