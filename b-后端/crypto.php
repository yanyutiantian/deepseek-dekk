<?php
declare(strict_types=1);

/**
 * DEKK API 加密层（V2协议）。
 *
 * 请求：GET  ?action=xx&ver=v2&env=<urlencode(base64(信封JSON))>
 *       POST body: {"v":2,"env":"<base64(信封JSON)>"}
 * 信封：{"v":2,"ts":…,"nonce":…,"key":RSA(master),"iv":…,"data":AES-CBC(正文),"mac":HMAC(iv|data)}
 * 响应：{"v":2,"iv":…,"data":…,"mac":…}
 *
 * 兼容：
 * - 不带 ver=v2（老版本客户端）继续走明文，直到 V1 截止时间；
 * - 截止时间之后，所有非 V2 请求都返回同一个「请更新」响应，不再区分来源。
 */

/** V1（明文）截止时间：北京时间 2026-10-08 12:00 = UTC 04:00。 */
const DEKK_V1_DEADLINE_UTC = '2026-10-08 04:00:00';

/** 公开资源端点：图片直链等没法套信封，不受 V1 截止时间影响。 */
const DEKK_PUBLIC_ACTIONS = ['avatar', 'health'];

/** 当前请求是不是公开资源端点（action 白名单 / 非 API 静态文件）。 */
function dekk_crypto_public_endpoint(): bool
{
    $action = (string) ($_GET['action'] ?? '');
    if ($action !== '' && in_array($action, DEKK_PUBLIC_ACTIONS, true)) {
        return true;
    }
    $uri = (string) ($_SERVER['REQUEST_URI'] ?? '');
    return $uri !== '' && strpos($uri, '/API/') === false;
}

/** 允许的信封最大长度（加密后体积约为明文的 1.4 倍，头像上传需要放大）。 */
const DEKK_ENVELOPE_MAX = 600000;

/** 允许的时间偏差（秒），超出视为重放。 */
const DEKK_TS_WINDOW = 300;

/** 私钥路径：放在能写日志的私有目录之外不可访问的位置，权限 600。 */
function dekk_crypto_key_path(): string
{
    $path = __DIR__ . '/dekk_rsa_private.pem';
    return is_readable($path) ? $path : '';
}

final class DekkCrypto
{
    public static bool $v2 = false;
    public static ?string $payload = null;
    public static ?string $master = null;
}

/** 截止时间后所有非 V2 请求的统一响应（内容固定，不泄露任何请求信息）。 */
function dekk_crypto_outdated(): never
{
    // 加密层没建立起来，这里只能返回明文；客户端 V2 会做明文回退，能正常提示用户
    http_response_code(426);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo '{"ok":false,"code":"CLIENT_OUTDATED","message":"客户端版本过低，请更新到最新版本后继续使用"}';
    exit;
}

/** 解密失败：返回固定内容，不把细节回给客户端。 */
function dekk_crypto_reject(string $why): never
{
    error_log('[DEKK CRYPTO] '.$why);
    http_response_code(400);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo '{"ok":false,"code":"BAD_ENVELOPE","message":"请求校验失败"}';
    exit;
}

/** 入口：判断是否 V2、解密、注入参数；V1 到期后直接拒绝。 */
function dekk_crypto_init(): void
{
    $raw = '';
    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
        $length = (int) ($_SERVER['CONTENT_LENGTH'] ?? 0);
        if ($length > DEKK_ENVELOPE_MAX) {
            http_response_code(413);
            header('Content-Type: application/json; charset=utf-8');
            echo '{"ok":false,"message":"请求过大"}';
            exit;
        }
        $raw = (string) file_get_contents('php://input', false, null, 0, DEKK_ENVELOPE_MAX + 1);
        if (strlen($raw) > DEKK_ENVELOPE_MAX) {
            http_response_code(413);
            header('Content-Type: application/json; charset=utf-8');
            echo '{"ok":false,"message":"请求过大"}';
            exit;
        }
    }

    $envelope = null;
    if (isset($_GET['env']) && is_string($_GET['env']) && $_GET['env'] !== '') {
        // GET 信封同样限长：否则超长 env 会把 base64/json 解码拖垮
        if (strlen($_GET['env']) > DEKK_ENVELOPE_MAX) {
            http_response_code(413);
            header('Content-Type: application/json; charset=utf-8');
            echo '{"ok":false,"message":"请求过大"}';
            exit;
        }
        $envelope = $_GET['env'];
    } elseif ($raw !== '') {
        $outer = json_decode($raw, true);
        if (is_array($outer) && isset($outer['env']) && is_string($outer['env'])) {
            $envelope = $outer['env'];
        }
    }

    // 没有信封：老版本客户端；到点之后一律返回「请更新」
    if ($envelope === null) {
        if (!dekk_crypto_public_endpoint()
                && time() >= (int) strtotime(DEKK_V1_DEADLINE_UTC.' UTC')) {
            dekk_crypto_outdated();
        }
        return;
    }

    $opened = dekk_crypto_open($envelope);
    if ($opened === null) {
        dekk_crypto_reject('envelope open failed');
    }
    DekkCrypto::$v2 = true;
    DekkCrypto::$payload = $opened['payload'];
    DekkCrypto::$master = $opened['master'];

    // GET 类接口在服务端是直接读 $_GET 的，这里把解密出来的参数注进去
    $params = json_decode($opened['payload'], true);
    if (is_array($params)) {
        foreach ($params as $key => $value) {
            if (is_string($key) && (is_string($value) || is_int($value) || is_float($value) || is_bool($value))) {
                $text = is_bool($value) ? ($value ? '1' : '0') : (string) $value;
                $_GET[$key] = $text;
                $_POST[$key] = $text;
                $_REQUEST[$key] = $text;
            }
        }
    }
}

/** 解密请求信封，成功返回 ['payload'=>明文, 'master'=>会话密钥]。 */
function dekk_crypto_open(string $envelope): ?array
{
    $json = base64_decode($envelope, true);
    if ($json === false || $json === '') {
        return null;
    }
    $env = json_decode($json, true);
    if (!is_array($env) || (int) ($env['v'] ?? 0) !== 2) {
        return null;
    }
    foreach (['ts', 'nonce', 'key', 'iv', 'data', 'mac'] as $field) {
        if (!isset($env[$field]) || !is_string((string) $env[$field])) {
            return null;
        }
    }
    // 防重放：时间戳必须在允许窗口内
    $ts = (int) $env['ts'];
    if (abs(time() - $ts) > DEKK_TS_WINDOW) {
        return null;
    }
    $keyPath = dekk_crypto_key_path();
    if ($keyPath === '') {
        error_log('[DEKK CRYPTO] private key missing');
        return null;
    }
    $privateKey = openssl_pkey_get_private((string) file_get_contents($keyPath));
    if ($privateKey === false) {
        error_log('[DEKK CRYPTO] private key invalid');
        return null;
    }
    $master = '';
    if (!openssl_private_decrypt(base64_decode($env['key'], true) ?: '', $master, $privateKey)) {
        return null;
    }
    if (strlen($master) !== 32) {
        return null;
    }
    $encKey = hash('sha256', $master.'enc', true);
    $macKey = hash('sha256', $master.'mac', true);
    $iv = base64_decode($env['iv'], true);
    $data = base64_decode($env['data'], true);
    if ($iv === false || $data === false || strlen($iv) !== 16) {
        return null;
    }
    // 完整性：HMAC 不匹配一定不是我们客户端发的
    $expect = base64_encode(hash_hmac('sha256', $iv.$data, $macKey, true));
    if (!hash_equals($expect, $env['mac'])) {
        return null;
    }
    // ── 防重放（nonce 计数）──
    // 位置很关键：必须在 HMAC 校验通过之后才计数。
    // 否则任何人都能拿随机 nonce 把 dekk_replay 表灌满（等于免费 DoS）。
    // 策略：第 1 次放行 / 第 2 次容忍（网络重发）/ 第 3 次拦截并封 IP 30 分钟。
    if (function_exists('dekk_replay_guard')) {
        dekk_replay_guard((string) $env['nonce']);
    }
    $plain = openssl_decrypt($data, 'aes-256-cbc', $encKey, OPENSSL_RAW_DATA, $iv);
    if ($plain === false) {
        return null;
    }
    return ['payload' => $plain, 'master' => $master];
}

/** input() 用：V2 时返回解密后的 JSON 字符串，否则 null（走原来的读正文逻辑）。 */
function dekk_crypto_payload(): ?string
{
    return DekkCrypto::$payload;
}

/**
 * respond() 用：V2 时把响应加密后输出，返回 true 表示已经输出完毕；
 * 否则返回 false，让调用方走原来的明文输出。
 */
function dekk_crypto_respond(array $data, int $status = 200): bool
{
    if (!DekkCrypto::$v2 || DekkCrypto::$master === null) {
        return false;
    }
    try {
        $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
    } catch (Throwable $e) {
        return false;
    }
    $encKey = hash('sha256', DekkCrypto::$master.'enc', true);
    $macKey = hash('sha256', DekkCrypto::$master.'mac', true);
    $iv = random_bytes(16);
    $cipher = openssl_encrypt($json, 'aes-256-cbc', $encKey, OPENSSL_RAW_DATA, $iv);
    if ($cipher === false) {
        return false;
    }
    $body = json_encode([
        'v' => 2,
        'iv' => base64_encode($iv),
        'data' => base64_encode($cipher),
        'mac' => base64_encode(hash_hmac('sha256', $iv.$cipher, $macKey, true)),
    ], JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    echo $body;
    return true;
}