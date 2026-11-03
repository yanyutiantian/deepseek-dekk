<?php
declare(strict_types=1);
// This file and config.php are deployed outside the web root.
ini_set('display_errors', '0');
// PHP 侧保持 UTC：token / 验证码的 expires_at 是 UTC 语义（见下面的 UTC_TIMESTAMP()），
// strtotime() 也按这个时区解释，不能改。
date_default_timezone_set('UTC');
$config = require __DIR__ . '/config.php';
$db = new PDO($config['dsn'], $config['db_user'], $config['db_password'], [PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC, PDO::ATTR_EMULATE_PREPARES=>false]);
// 数据库会话用北京时间：created_at / updated_at / read_at 存的就是本地时间，
// 客户端直接展示这个字符串，所以要跟服务器一致（Asia/Shanghai）。
// 注意：expires_at 仍然用 UTC_TIMESTAMP() 写入和比较，不受这里影响。
$db->exec("SET time_zone = '+08:00'");
function respond(array $data, int $status=200): never {
    // V2：把响应也加密后再输出（加密层没启用时返回 false，走下面的明文逻辑）
    if (function_exists('dekk_crypto_respond') && dekk_crypto_respond($data, $status)) { exit; }
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    echo json_encode($data, JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR); exit;
}
function fail(string $message, int $status=400): never { respond(['ok'=>false,'message'=>$message],$status); }
function sql(string $query, array $args=[]): PDOStatement { global $db; $s=$db->prepare($query); $s->execute($args); return $s; }
function input(): array {
    // V2：正文已经在 crypto.php 里解密好了，直接用；同时绕开了原来的 100KB 上限
    if (function_exists('dekk_crypto_payload')) {
        $payload = dekk_crypto_payload();
        if ($payload !== null) {
            try { $data = json_decode($payload, true, 32, JSON_THROW_ON_ERROR); }
            catch (Throwable $e) { fail('JSON格式错误'); }
            if (!is_array($data)) fail('请求格式错误');
            return $data;
        }
    }
    if ((int)($_SERVER['CONTENT_LENGTH']??0)>100000) fail('请求过大',413);
    $raw=file_get_contents('php://input',false,null,0,100001);
    if (strlen($raw)>100000) fail('请求过大',413);
    try { $data=json_decode($raw,true,32,JSON_THROW_ON_ERROR); } catch (Throwable $e) { fail('JSON格式错误'); }
    if (!is_array($data)) fail('请求格式错误'); return $data;
}
function text(array $a,string $key,int $min,int $max): string {
    $v=$a[$key]??null;
    if (!is_string($v)||!mb_check_encoding($v,'UTF-8')||mb_strlen($v)<$min||mb_strlen($v)>$max) fail('字段不合法：'.$key);
    return $v;
}
function email(array $a): string {
    $e=strtolower(trim(text($a,'email',6,100)));
    if (!preg_match('/^[1-9][0-9]{4,19}@qq\.com$/D',$e)) fail('仅支持数字QQ邮箱'); return $e;
}
function password(array $a): string { return text($a,'password',8,12); }
function user(bool $required=true): ?array {
    $h=$_SERVER['HTTP_AUTHORIZATION']??'';
    if (!preg_match('/^Bearer ([a-f0-9]{64})$/D',$h,$m)) { if ($required) fail('请登录',401); return null; }
    $u=sql('SELECT u.id,u.email,u.nickname FROM dekk_tokens t JOIN dekk_users u ON u.id=t.user_id WHERE t.token_hash=? AND t.expires_at>UTC_TIMESTAMP()',[hash('sha256',$m[1])])->fetch();
    if (!$u) { if ($required) fail('登录已过期',401); return null; } return $u;
}
// Atomic fixed-window counter; use only the actual peer IP, never client-supplied forwarding headers.
function limit(string $key,int $seconds,int $maximum): void {
    global $db; $key=hash('sha256',$key); $now=time();
    $db->beginTransaction();
    try {
        sql('INSERT IGNORE INTO dekk_limits(bucket,window_start,hits) VALUES(?,?,0)',[$key,$now]);
        $r=sql('SELECT window_start,hits FROM dekk_limits WHERE bucket=? FOR UPDATE',[$key])->fetch();
        if ($now-(int)$r['window_start'] >= $seconds) { $r=['window_start'=>$now,'hits'=>0]; }
        if ((int)$r['hits'] >= $maximum) { $db->rollBack(); fail('操作频繁，请稍后重试',429); }
        sql('UPDATE dekk_limits SET window_start=?,hits=? WHERE bucket=?',[$r['window_start'],(int)$r['hits']+1,$key]); $db->commit();
    } catch(Throwable $e) {
        if($db->inTransaction())$db->rollBack();
        // MySQL 40001 = 死锁 / 序列化失败（SQLSTATE 40001）。
        // 场景：App 一次加载多个头像时并发命中同一限流桶，每个请求都要写 dekk_limits，
        // 而 INSERT IGNORE 的重复键 S 锁与 SELECT ... FOR UPDATE 的 X 锁会交叉等待 → 死锁。
        // 这是限流表自身带来的副作用，绝不应该让正常业务请求变成 500。
        // 处理：本次放行（等效「这一秒不计次」），限流精度损失一次可忽略；
        //       其它异常（连接失败、SQL 错误等）照旧抛出，不掩盖真实问题。
        if($e instanceof PDOException && (string)$e->getCode()==='40001') return;
        throw $e;
    }
}
// Caller owns transaction. Wrong attempts are committed before returning an error.
function verifyCode(string $email,string $purpose,string $code): void {
    global $db;
    $r=sql('SELECT * FROM dekk_codes WHERE email=? AND purpose=? FOR UPDATE',[$email,$purpose])->fetch();
    if (!$r || strtotime($r['expires_at'])<=time() || (int)$r['attempts']>=5) { $db->rollBack(); fail('验证码无效或已过期'); }
    if (!password_verify($code,$r['code_hash'])) {
        sql('UPDATE dekk_codes SET attempts=attempts+1 WHERE email=? AND purpose=?',[$email,$purpose]);$db->commit();fail('验证码错误');
    }
    sql('DELETE FROM dekk_codes WHERE email=? AND purpose=?',[$email,$purpose]);
}

// ═══════════════════ 安全增强：IP 封禁 / 蜜罐 / 防重放 ═══════════════════

/**
 * 真实客户端 IP。只信任 TCP 对端地址，绝不读 X-Forwarded-For 之类转发头
 * （否则任何人加一个头就能伪造来源、栽赃别人、或绕过封禁）。
 */
function dekk_client_ip(): string {
    return (string)($_SERVER['REMOTE_ADDR'] ?? 'unknown');
}

/**
 * 豁免列表：本机、服务器自身公网 IP。
 * 这些不参与封禁，避免把内部 cron、健康检查、监控或自己的运维请求打死。
 */
function dekk_ip_exempt(string $ip): bool {
    if ($ip === '' || $ip === 'unknown' || $ip === '127.0.0.1' || $ip === '::1') return true;
    return $ip === '203.0.113.10';
}

/** 该 IP 的剩余封禁秒数；未封禁返回 0。 */
function dekk_ban_remaining(string $ip): int {
    if (dekk_ip_exempt($ip)) return 0;
    try {
        $row = sql('SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), `until`) AS s FROM dekk_bans WHERE ip=? LIMIT 1',[$ip])->fetch();
        if ($row && (int)($row['s'] ?? 0) > 0) return (int)$row['s'];
    } catch (Throwable $e) { /* 表可能还没建，静默 */ }
    return 0;
}

/** 封禁一个 IP，$seconds 秒（多次命中取较长者）。 */
function dekk_ban(string $ip, int $seconds, string $reason): void {
    if ($ip === '' || dekk_ip_exempt($ip)) return;
    $seconds = max(1, min($seconds, 7 * 86400)); // 上限 7 天，防止误配
    try {
        sql('INSERT INTO dekk_bans(ip,`until`,reason,created_at)
             VALUES(?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL ? SECOND),?,UTC_TIMESTAMP())
             ON DUPLICATE KEY UPDATE
               `until`=GREATEST(`until`,DATE_ADD(UTC_TIMESTAMP(),INTERVAL ? SECOND)),
               reason=VALUES(reason)',[$ip,$seconds,$reason,$seconds]);
        error_log('[DEKK BAN] ip='.$ip.' seconds='.$seconds.' reason='.$reason);
    } catch (Throwable $e) { error_log('[DEKK BAN] '.$e->getMessage()); }
}

/**
 * 入口封禁闸门：命中封禁直接 403 + Retry-After，连路由都不进。
 * 放在 index.php require 之后的第一件事。
 */
function dekk_ban_gate(): void {
    $left = dekk_ban_remaining(dekk_client_ip());
    if ($left <= 0) return;
    http_response_code(403);
    header('Retry-After: '.$left);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode(['ok'=>false,'message'=>'访问已临时限制，请稍后再试'],JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * 蜜罐命中：记录 + 封禁 + 404。
 *
 * 这里能做到「零误伤」的原因：命中词表里的 action 全都是攻击者字典里的词
 * （admin / sql / dump / shell / phpinfo …），正常客户端根本不会请求，
 * 未授权 action 也一律能在 index.php 的白名单处被拦下。
 *
 * @param string $kind   action / path / replay
 * @param string $target 命中的具体名字
 * @param int    $banSeconds 默认封 24 小时（蜜罐比防重放更确定，可以直接重罚）
 */
function dekk_honeypot_hit(string $kind, string $target, int $banSeconds = 86400): void {
    $ip  = dekk_client_ip();
    $uri = substr((string)($_SERVER['REQUEST_URI'] ?? ''), 0, 500);
    $ua  = substr((string)($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 255);
    try {
        sql('INSERT INTO dekk_honeypot(ip,kind,target,uri,ua,created_at)
             VALUES(?,?,?,?,?,UTC_TIMESTAMP())',
            [substr($ip,0,45), substr($kind,0,24), substr($target,0,190), $uri, $ua]);
    } catch (Throwable $e) { error_log('[DEKK HONEYPOT] '.$e->getMessage()); }
    error_log('[DEKK HONEYPOT] hit ip='.$ip.' kind='.$kind.' target='.$target.' uri='.$uri);
    dekk_ban($ip, $banSeconds, 'honeypot:'.$kind);
    fail('接口不存在', 404);
}

/**
 * 防重放。
 *
 * 设计（按需求）：同一 nonce
 *   第 1 次 → 正常处理
 *   第 2 次 → 容忍（网络超时重发 / 客户端重试都可能发生），只记日志
 *   第 3 次起 → 拦截 + 封禁该 IP 30 分钟
 *
 * 只在信封 HMAC 校验通过后调用，避免攻击者用随机 nonce 把表撑爆。
 * 计数失败（表异常/死锁）绝不能拖垮业务，一律放行。
 */
function dekk_replay_guard(string $nonce, string $ip = ''): void {
    if ($nonce === '') return;
    if ($ip === '') $ip = dekk_client_ip();

    $limit      = 3;      // 第 3 次重放即拦截
    $banSeconds = 1800;   // 封 30 分钟
    $key        = hash('sha256', $nonce); // 统一长度，防超长 nonce 撑表

    $hits = 0;
    try {
        // 单条原子语句：并发同 nonce 只会串行等待，不会像事务内多语句那样死锁
        sql('INSERT INTO dekk_replay(nonce,ip,hits,first_at,last_at)
             VALUES(?,?,1,UTC_TIMESTAMP(),UTC_TIMESTAMP())
             ON DUPLICATE KEY UPDATE hits=hits+1,last_at=UTC_TIMESTAMP()',[$key,substr($ip,0,45)]);
        $row  = sql('SELECT hits FROM dekk_replay WHERE nonce=? LIMIT 1',[$key])->fetch();
        $hits = $row ? (int)$row['hits'] : 1;
    } catch (Throwable $e) {
        error_log('[DEKK REPLAY] counted failed: '.$e->getMessage());
        return; // 不能因为计数失败而拒绝正常业务
    }

    if ($hits < $limit) {
        if ($hits > 1) {
            error_log('[DEKK REPLAY] 疑似重放(第'.$hits.'次，容忍) ip='.$ip);
        }
        return;
    }

    // 第 3 次起：记一笔蜜罐 + 封禁 + 拒绝
    try {
        sql('INSERT INTO dekk_honeypot(ip,kind,target,uri,ua,created_at)
             VALUES(?,?,?,?,?,UTC_TIMESTAMP())',
            [substr($ip,0,45),'replay',substr($key,0,190),
             substr((string)($_SERVER['REQUEST_URI'] ?? ''),0,500),
             substr((string)($_SERVER['HTTP_USER_AGENT'] ?? ''),0,255)]);
    } catch (Throwable $e) { /* 记录失败不影响拦截 */ }

    error_log('[DEKK REPLAY] 重放拦截 ip='.$ip.' hits='.$hits.' 封禁'.$banSeconds.'s');
    dekk_ban($ip, $banSeconds, 'replay');

    http_response_code(403);
    header('Retry-After: '.$banSeconds);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode(['ok'=>false,'message'=>'请求异常，已临时限制'],JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * 顺手做的清理（概率执行，不需要 cron）：
 *  - nonce 记录：时间窗只有 300 秒，留 1 天足够
 *  - 过期封禁：留 3 天便于回溯
 */
function dekk_security_gc(): void {
    if (random_int(1, 200) !== 1) return; // 0.5% 概率
    try {
        sql('DELETE FROM dekk_replay WHERE last_at < DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) LIMIT 2000');
        sql('DELETE FROM dekk_bans WHERE `until` < DATE_SUB(UTC_TIMESTAMP(),INTERVAL 3 DAY) LIMIT 500');
        // 人机验证用过的一次性 challenge：留 1 天足够（有效期只有 5 分钟）
        sql('DELETE FROM dekk_gate_used WHERE used_at < DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) LIMIT 1000');
        // 未完成的图片验证码答案（用户中途关页面留下的）
        sql('DELETE FROM dekk_gate_captcha WHERE created_at < DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) LIMIT 1000');
    } catch (Throwable $e) { /* 忽略 */ }
}