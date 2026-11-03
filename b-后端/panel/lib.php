<?php
declare(strict_types=1);

/**
 * 临时管理面板 —— 公共库（DB / token 鉴权 / JSON / 日志 / 分页）。
 *
 * 注意：面板是给**浏览器**看的，所以这里刻意<b>不复用</b> dekk 的 respond()/fail()
 * （它们会走 V2 加密层，浏览器解不开），自己输出明文 JSON。
 *
 * 部署位置：/var/www/dekk.example.com/panel/
 */

date_default_timezone_set('Asia/Shanghai');

// 面板自带配置（pay 站点有 open_basedir，不能跨目录读 后端配置/config.php）
$PANEL_CONFIG = require __DIR__ . '/config.php';

/**
 * 机器人创建面板时用的内部密钥。
 * 改从 panel/config.php 读取，不再硬编码在源码里。
 */
function panel_internal_key(): string
{
    static $key = null;
    if ($key === null) {
        global $PANEL_CONFIG;
        $key = (is_array($PANEL_CONFIG) && isset($PANEL_CONFIG['internal_key']))
            ? (string) $PANEL_CONFIG['internal_key'] : '';
    }
    return $key;
}

/** 面板对外地址（发给用户点击的那个域名）。 */
const PANEL_BASE_URL = 'https://pay.example.com/panel/';

/** 面板有效期（分钟）。 */
const PANEL_TTL_MINUTES = 60;

function pdb(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        global $PANEL_CONFIG;
        $pdo = new PDO($PANEL_CONFIG['dsn'], $PANEL_CONFIG['db_user'], $PANEL_CONFIG['db_password'], [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
        // created_at / expires_at 存的是服务器本地时间（与 dekk 主站一致）
        $pdo->exec("SET time_zone = '+08:00'");
    }
    return $pdo;
}

/** 输出明文 JSON 并结束。 */
function pjson(array $data, int $status = 200): void
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function pfail(string $message, int $status = 400): void
{
    pjson(['ok' => false, 'message' => $message], $status);
}

/** panel.log：所有管理动作都记这里（搜索/查看不记）。 */
function plog_panel(string $line): void
{
    try {
        @file_put_contents(__DIR__ . '/panel.log',
            '[' . date('Y-m-d H:i:s') . '] ' . $line . "\n", FILE_APPEND);
    } catch (Throwable $e) {
    }
}

/** 取当前有效面板（?t= 或 POST t）。 */
function panel_token(bool $required = true): ?array
{
    $token = $_POST['t'] ?? $_GET['t'] ?? '';
    if (!is_string($token) || !preg_match('/^[a-f0-9]{48}$/D', $token)) {
        if ($required) {
            pfail('面板链接无效', 401);
        }
        return null;
    }
    $stmt = pdb()->prepare('SELECT * FROM panel_tokens WHERE token_hash=? AND revoked=0 AND expires_at>NOW() LIMIT 1');
    $stmt->execute([hash('sha256', $token)]);
    $panel = $stmt->fetch();
    if (!$panel) {
        if ($required) {
            pfail('面板已过期或已失效', 401);
        }
        return null;
    }
    return $panel;
}

/** 操作者显示名（左上角也用这个）。 */
function panel_actor(array $panel): string
{
    $name = trim((string) $panel['kook_name']);
    return $name !== '' ? $name : ('kook:' . $panel['kook_user_id']);
}

/** 分页：每页固定 10 行。 */
function pager(): array
{
    $page = max(1, (int) ($_POST['page'] ?? $_GET['page'] ?? 1));
    $size = 10;
    return [$page, $size, ($page - 1) * $size];
}

/** 管理动作日志。 */
function audit(string $actor, string $action, string $detail = ''): void
{
    plog_panel('ACTION actor=' . $actor . ' action=' . $action . ($detail !== '' ? ' ' . $detail : ''));
}

/** 封禁到期时间（NOW() 之后的日期字符串）。 */
function ban_until(string $duration): ?string
{
    $map = [
        '12h' => '12 HOUR',
        '24h' => '24 HOUR',
        '7d' => '7 DAY',
        '30d' => '30 DAY',
        'forever' => '100 YEAR',
    ];
    if (!isset($map[$duration])) {
        return null;
    }
    $stmt = pdb()->prepare('SELECT DATE_ADD(NOW(), INTERVAL ' . $map[$duration] . ') AS until_at');
    $stmt->execute();
    return (string) $stmt->fetchColumn();
}