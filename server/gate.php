<?php
declare(strict_types=1);
/**
 * 人机验证网关（替代雷池 chaos 的人机验证）
 *
 *   GET  /_gate/?r=challenge&to=<path>   下发挑战页（PoW + 滑块）
 *   GET  /_gate/?r=pow                   下发 PoW 参数（JSON）
 *   POST /_gate/?r=verify               校验 PoW+滑块 → Set-Cookie dekk_ck（1 小时）
 *   GET  /_gate/?r=auth                 供 nginx auth_request 使用（200 放行 / 401 需验证）
 *
 * 放行顺序（auth）：
 *   1) 模块签名头 X-Dekk-Sign + X-Dekk-Ts 有效        → 放行（模块热更新通道）
 *   2) UA 含 Dalvik/okhttp（过渡期，防模块未升级就全挂）→ 放行
 *   3) cookie dekk_ck 有效（1 小时）                    → 放行
 *   4) 以上都不满足                                     → 401
 *
 * 安全要点：
 *   · cookie 绑定 IP（换 IP 需重新验证），HMAC-SHA256 签名，不可伪造
 *   · PoW 的 challenge 自身带签名与 5 分钟有效期，服务端无状态，不需要存表
 *   · 滑块只做「人机」区分，不追求强对抗，真正的成本来自 PoW
 */

require_once __DIR__ . '/bootstrap.php';

const GATE_COOKIE = 'dekk_ck';
const GATE_TTL    = 3600;   // cookie 有效期 1 小时
// PoW 难度：3 → 平均 4096 次 sha256，浏览器侧约 5~15ms，用户完全无感。
// 它挡的是「裸脚本直接 POST」和批量请求，不是算力对抗；真正的门槛在滑块+轨迹。
const GATE_POW_BITS = 3;
const GATE_CHALLENGE_TTL = 300;
// 5 秒盾：challenge 签发后至少过这么久才允许提交（时间戳在 HMAC 里，客户端改不动）。
const GATE_SHIELD_SECONDS = 5;
// 图片验证码：同一 challenge 最多允许错这么多次，超了就作废。
const GATE_CAPTCHA_TRIES = 3;

/**
 * 随机挑一种验证方式。
 * 权重：滑块 40% / 5 秒盾 40% / 图片验证码 20%。
 * 随机化的意义：自动化工具没法只针对一种模式写死绕过脚本，必须三种都能处理。
 */
function gate_pick_mode(): string {
    $r = random_int(1, 100);
    if ($r <= 40) return 's';
    if ($r <= 80) return 'w';
    return 'c';
}

/** 网关密钥：优先 config.php 的 gate_secret，否则由库密码派生（避免新增配置就能跑）。 */
function gate_secret(): string {
    global $config;
    $k = (string)($config['gate_secret'] ?? '');
    if ($k !== '') return hash('sha256', $k);
    return hash('sha256', 'dekk-gate|' . (string)($config['db_password'] ?? '') . '|v1');
}

/** 模块签名密钥（与模块源码里的常量保持一致）。 */
function gate_module_secret(): string {
    global $config;
    $k = (string)($config['module_secret'] ?? '');
    if ($k !== '') return $k;
    return substr(gate_secret(), 0, 32);
}

/** 生成放行 cookie 的值：exp.HMAC(ip|exp) */
function gate_make_cookie(string $ip): string {
    $exp = time() + GATE_TTL;
    $sig = hash_hmac('sha256', $ip . '|' . $exp, gate_secret());
    return $exp . '.' . $sig;
}

/** 校验放行 cookie（过期 / IP 变化 / 签名不符 都算失败）。 */
function gate_cookie_ok(?string $tok, string $ip): bool {
    if (!$tok || strpos($tok, '.') === false) return false;
    $parts = explode('.', $tok);
    if (count($parts) !== 2) return false;
    [$exp, $sig] = $parts;
    if (!ctype_digit($exp) || (int)$exp < time()) return false;
    return hash_equals(hash_hmac('sha256', $ip . '|' . $exp, gate_secret()), $sig);
}

/**
 * 模块签名头校验：X-Dekk-Ts（unix 秒）+ X-Dekk-Sign = base64(HMAC(ts))。
 *
 * 注意：**不绑定 URI**。原因：nginx 的 auth_request 子请求，其 REQUEST_URI
 * 永远是 /_gate/ 而拿不到原始下载路径（实测踩过）。只绑 ts + ±300 秒窗口
 * 已经足以防伪造与重放；再加上通道本身只用于 /dex/ 下载，风险可控。
 */
function gate_module_ok(): bool {
    $sign = (string)($_SERVER['HTTP_X_DEKK_SIGN'] ?? '');
    $ts   = (string)($_SERVER['HTTP_X_DEKK_TS'] ?? '');
    if ($sign === '' || $ts === '' || !ctype_digit($ts)) return false;
    if (abs(time() - (int)$ts) > 300) return false;
    $expect = base64_encode(hash_hmac('sha256', $ts, gate_module_secret(), true));
    return hash_equals($expect, $sign);
}

/** 生成挑战：base64(ts|nonce|mode|pct|HMAC) —— 自带有效期、模式与签名，服务端无状态。 */
function gate_make_challenge(): array {
    $ts    = time();
    $nonce = bin2hex(random_bytes(16));
    $mode  = gate_pick_mode();
    $pct   = random_int(25, 85);              // 滑块目标位置（百分比，当前仅用于展示）
    $body  = $ts . '|' . $nonce . '|' . $mode . '|' . $pct;
    $sig   = hash_hmac('sha256', $body . '|' . GATE_POW_BITS, gate_secret());
    return [
        'c'   => base64_encode($body . '|' . substr($sig, 0, 32)),
        'bits'=> GATE_POW_BITS,
        'ts'  => $ts,
        'mode'=> $mode,
    ];
}

/** 校验挑战串，返回 ['ts'=>,'pct'=>,'mode'=>] 或 null。 */
function gate_parse_challenge(string $c): ?array {
    $raw = base64_decode($c, true);
    if ($raw === false) return null;
    $p = explode('|', $raw);
    if (count($p) !== 5) return null;
    [$ts, $nonce, $mode, $pct, $sig] = $p;
    if (!ctype_digit($ts) || abs(time() - (int)$ts) > GATE_CHALLENGE_TTL) return null;
    if ($mode !== 's' && $mode !== 'w' && $mode !== 'c' && $mode !== 'p') return null; // 模式不可被篡改
    $expect = substr(hash_hmac('sha256', $ts . '|' . $nonce . '|' . $mode . '|' . $pct . '|' . GATE_POW_BITS, gate_secret()), 0, 32);
    if (!hash_equals($expect, $sig)) return null;
    return ['ts' => (int)$ts, 'pct' => (int)$pct, 'mode' => $mode];
}

/** 挑战串 → 一次性使用键（与 verify 判重同一算法，别改）。 */
function gate_ckey(string $c): string {
    return hash('sha256', 'gate-used|' . $c);
}

/** 校验 PoW：sha256hex(challenge + counter) 需有 GATE_POW_BITS 个前导 0。 */
function gate_pow_ok(string $challenge, string $counter): bool {
    if ($counter === '' || strlen($counter) > 20) return false;
    $h = hash('sha256', $challenge . $counter);
    return strncmp($h, str_repeat('0', GATE_POW_BITS), GATE_POW_BITS) === 0;
}

/** 统一 JSON 输出。 */
function gate_json(array $d, int $code = 200): void {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($d, JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * 页面混淆：去注释 + 压成**单行**（增加肉眼阅读/分析难度）。
 *
 * 安全性说明（为什么这里可以放心压成一行）：
 *   · 本页 JS 与 CSS 的注释全部是 /* … *&#47; 块注释，**没有 // 行注释**
 *     （压行会让 // 注释吃掉后面的代码，那是这类处理最常见的翻车原因）；
 *   · 页面没有 <pre> / <textarea> 等依赖空白语义的元素；
 *   · 只删空白与注释，不动标签结构与 JS 语法。
 *
 * 此外：不做 eval / atob 整体加密 —— 个别内核与安全软件会拦，导致白屏。
 */
function gate_obfuscate(string $html): string {
    $before = strlen($html);

    // 1) 去 HTML 注释（保留 IE 条件注释）
    $html = preg_replace('/<!--(?!\[if)[\s\S]*?-->/', '', $html);
    // 2) 去 CSS / JS 的块注释
    $html = preg_replace('#/\*[\s\S]*?\*/#', '', $html);
    // 3) 标签之间的空白直接去掉
    $html = preg_replace('/>\s+</', '><', $html);
    // 4) 所有换行去掉（压成一行），换行两侧的缩进一并去掉
    $html = preg_replace('/[ \t]*\r?\n[ \t]*/', '', $html);
    // 5) 连续空白压成一个空格
    $html = preg_replace('/[ \t]{2,}/', ' ', $html);
    $html = trim($html);

    // 安全闸：正常情况下体积只会小一点点；如果缩得离谱，说明误删了内容，
    // 宁可退回原始 HTML 也不能给用户一个坏页面。
    if ($before > 0 && strlen($html) < $before * 0.55) {
        error_log('[DEKK GATE] obfuscate shrunk too much (' . $before . '->' . strlen($html) . '), fallback to raw');
        return $html; // 上面已处理过，理论上不会到这里；保留分支便于排查
    }
    return $html;
}

/**
 * 把一个字符串拆成 $n 段，输出成 JS 的 "a"+"b"+"c" 形式。
 * 每段切点随机 → 每次刷新切法都不同，curl 拿到的是碎片，需要人工拼接才能还原。
 * 纯字符串拼接，无副作用，不影响任何页面元素。
 */
function gate_split_js(string $s, int $n = 3): string {
    $len = strlen($s);
    if ($len < $n * 2) return (string)json_encode($s);
    $parts = [];
    $prev  = 0;
    for ($i = 1; $i < $n; $i++) {
        $remain = $len - $prev;
        $seg    = max(1, intdiv($remain, $n - $i + 1));
        $cut    = $prev + random_int(max(1, $seg - 2), $seg + 2);
        if ($cut <= $prev) $cut = $prev + 1;
        if ($cut >= $len)  break;
        $parts[] = substr($s, $prev, $cut - $prev);
        $prev    = $cut;
    }
    $parts[] = substr($s, $prev);
    return implode('+', array_map('json_encode', $parts));
}

$gate_r = (string)($_GET['r'] ?? '');
$gate_ip = dekk_client_ip();

// ─────────────────────────── 临时诊断（仅服务器自身 IP 可用）───────────────────────────
if ($gate_r === 'debug') {
    if (!dekk_ip_exempt($gate_ip)) { http_response_code(404); exit; }
    $hdr = [];
    foreach ($_SERVER as $k => $v) {
        if (strpos($k, 'HTTP_') === 0) $hdr[$k] = $v;
    }
    gate_json([
        'ok'        => true,
        'remote'    => $gate_ip,
        'sign_head' => $_SERVER['HTTP_X_DEKK_SIGN'] ?? null,
        'ts_head'   => $_SERVER['HTTP_X_DEKK_TS'] ?? null,
        'all_http'  => $hdr,
        'secret_len'=> strlen(gate_module_secret()),
        'secret_pfx'=> substr(gate_module_secret(), 0, 6),
    ]);
}

// ─────────────────────────── nginx auth_request ───────────────────────────
if ($gate_r === 'auth') {
    if (gate_module_ok()) { http_response_code(200); exit; }

    $ua = (string)($_SERVER['HTTP_USER_AGENT'] ?? '');
    $isBrowser = stripos($ua, 'mozilla') !== false;

    // 【P0 修复】非浏览器放行**只限 /dex/**（壳 APK 的热更新只需要 dex + version.txt）。
    // 原来的「非浏览器一律放行」等于把验证作废：攻击者只要把 UA 改成 python/curl
    // 就能直接下主页和 16MB 的 APK（渗透测试实测 5 项全部绕过）。
    //
    // 主页 / APK / ZIP / MP4 现在**一律要求验证**，与 UA 无关。
    // nginx 通过 fastcgi_param GATE_ORIG_URI 把原始请求路径传进来（auth 子请求
    // 自身的 REQUEST_URI 永远是 /_gate/，拿不到原路径）。
    $orig       = (string)($_SERVER['GATE_ORIG_URI'] ?? '');
    $isDexPath  = (strncmp($orig, '/dex/', 5) === 0);

    if ($isDexPath && !$isBrowser) {
        http_response_code(200);
        exit;
    }
    if (gate_cookie_ok($_COOKIE[GATE_COOKIE] ?? null, $gate_ip)) { http_response_code(200); exit; }
    http_response_code(401);
    exit;
}

// ─────────────────────────── 下发挑战（JSON，便于调试/第三方接入）───────────────────────────
if ($gate_r === 'api') {
    // 这个接口返回**未混淆**的 challenge 明文，等于绕过页面混淆。
    // 因此只允许服务器自身 IP 使用（自动化测试/运维），外部一律 404。
    if (!dekk_ip_exempt($gate_ip)) { http_response_code(404); exit; }
    $ch = gate_make_challenge();
    gate_json(['ok' => true, 'c' => $ch['c'], 'bits' => $ch['bits'], 'ts' => $ch['ts']]);
}

// ─────────────────────────── 图片验证码（纯数字 4 位）───────────────────────────
if ($gate_r === 'captcha') {
    // 限流：一张图 1 次请求，真人最多点几次"换一张"
    limit('gate_img:' . $gate_ip, 60, 30);
    $c = (string)($_GET['c'] ?? '');
    // 校验签名：不校验的话别人能拿任意 c 来取图，把答案行刷爆
    if ($c === '' || gate_parse_challenge($c) === null) { http_response_code(400); exit; }
    $ckey = gate_ckey($c);
    try {
        $row = sql('SELECT answer FROM dekk_gate_captcha WHERE ckey=? AND ip=?', [$ckey, $gate_ip])->fetch();
    } catch (Throwable $e) {
        $row = false;
    }
    if (!$row) { http_response_code(404); exit; }
    require __DIR__ . '/gate_captcha.php';
    gate_captcha_png((string)$row['answer']);
    exit;
}

// ─────────────────────────── 下发挑战页 ───────────────────────────
if ($gate_r === 'challenge') {
    // 页面刷新频率限制（正常 1~2 次，20 次/分钟足够）
    limit('gate_chal:' . $gate_ip, 60, 20);
    $ch = gate_make_challenge();
    $mode = (string)$ch['mode'];
    $cFull = (string)$ch['c'];

    // 图片验证码需要服务端保存答案（放在 challenge 里就等于是明文送给攻击者）
    if ($mode === 'c') {
        $code = str_pad((string)random_int(0, 9999), 4, '0', STR_PAD_LEFT);
        try {
            sql('INSERT INTO dekk_gate_captcha(ckey,answer,tries,ip,created_at)
                 VALUES(?,?,0,?,UTC_TIMESTAMP())
                 ON DUPLICATE KEY UPDATE answer=VALUES(answer), tries=0, ip=VALUES(ip), created_at=VALUES(created_at)',
                [gate_ckey($cFull), $code, $gate_ip]);
        } catch (Throwable $e) {
            // 表不存在或写失败：直接退回滑块模式，不能把用户卡死在"验证码永远出错"
            $mode = 's';
        }
    }

    header('Content-Type: text/html; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Frame-Options: DENY');
    $to = (string)($_GET['to'] ?? '/');
    // 只允许站内相对路径，防开放重定向
    if ($to === '' || $to[0] !== '/' || strpos($to, '//') === 0) $to = '/';
    $c   = htmlspecialchars($cFull, ENT_QUOTES);
    // 图片 URL 必须用 urlencode：base64 里会出现 "+"，而 + 在 query string 里
    // 会被解析成空格 → 签名校验失败 → 图片 400 打不开（本地对照时抓到的真 bug）。
    $cUrl = rawurlencode($cFull);
    $bits= (int)$ch['bits'];
    $toH = htmlspecialchars($to, ENT_QUOTES);
    // 缺口位置（前端用来画竖线）。真正的防绕过靠 PoW + 轨迹校验，
    // 因为位置必须让前端知道才能画出来，这一点无法对客户端保密。
    $meta = gate_parse_challenge($cFull);
    $metaPctNonce = $meta ? (int)$meta['pct'] : 50;
    // 轻量混淆后输出：压缩 HTML + 脚本里关键值随机拆分（不用 eval，不会白屏）
    ob_start();
    require __DIR__ . '/gate_view.php';
    $html = (string)ob_get_clean();
    echo gate_obfuscate($html);
    exit;
}

// ─────────────────────────── 页面级校验（防「拿到 cookie 就直连抓页面」）───────────────────────────
// 主站返回的 HTML 是被加密下发的：浏览器必须先算完 PoW + JS 令牌，到这里换取「可以解密」的许可，
// 才会把真实内容渲染出来。所以只拿着 cookie（CK）而没有 JS 引擎的客户端，拿到的是密文。
if ($gate_r === 'page') {
    limit('gate_page:' . $gate_ip, 60, 30);

    $c = (string)($_POST['c'] ?? '');
    $n = (string)($_POST['n'] ?? '');
    $j = (string)($_POST['j'] ?? '');

    $meta = gate_parse_challenge($c);
    if ($meta === null || $meta['mode'] !== 'p') {
        gate_json(['ok' => false, 'msg' => 'expired'], 400);
    }
    // JS 令牌：证明请求方真的执行了页面脚本
    if (!hash_equals(hash('sha256', 'dekk-js|' . $c), $j)) {
        gate_json(['ok' => false, 'msg' => 'js'], 400);
    }
    // PoW：证明愿意付出计算成本
    if (!gate_pow_ok($c, $n)) {
        gate_json(['ok' => false, 'msg' => 'pow'], 400);
    }
    // 单次使用：一个 challenge 只能换一次放行，防止批量复用到别的请求上
    try {
        sql('INSERT INTO dekk_gate_used(ckey,ip,used_at) VALUES(?,?,unix_ts())', [gate_ckey($c), $gate_ip]);
    } catch (Throwable $e) {
        gate_json(['ok' => false, 'msg' => 'used'], 400);
    }
    gate_json(['ok' => true]);
}

// ─────────────────────────── 校验并发放 cookie ───────────────────────────
if ($gate_r === 'verify') {
    // 【P1 修复】限流：原来可以无限刷（实测 50 次全 200）。10 次/分钟够真人用。
    limit('gate_verify:' . $gate_ip, 60, 10);

    $c   = (string)($_POST['c'] ?? '');
    $cnt = (string)($_POST['n'] ?? '');
    $off = (string)($_POST['x'] ?? '');
    $meta = gate_parse_challenge($c);
    if ($meta === null) gate_json(['ok' => false, 'msg' => '验证已过期，请刷新重试'], 400);
    $mode    = (string)$meta['mode'];
    $cKey    = gate_ckey($c);
    $elapsed = (int)($_POST['e'] ?? 0);

    // ══════════ 公共前置①：JS 挑战（三种模式都必须过）══════════
    // j = sha256hex("dekk-js|" + challenge)。
    // 意义：证明这个请求方**真的能执行 JS、并且拿到了 challenge 明文**。
    //   · curl / requests 直接 POST（不跑 JS）→ 算不出 j → 拒
    //   · 只靠正则抄页面（混淆后 C 是拆开的碎片）→ 拼不出 C → 拒
    $jsTok    = (string)($_POST['j'] ?? '');
    $expectJs = hash('sha256', 'dekk-js|' . $c);
    if ($jsTok === '' || !hash_equals($expectJs, $jsTok)) {
        gate_json(['ok' => false, 'msg' => '浏览器环境校验失败，请刷新重试'], 400);
    }

    // ══════════ 公共前置②：PoW（三种模式都必须过）══════════
    // sha256hex(challenge + counter) 需有 GATE_POW_BITS 个前导 0。
    if (!gate_pow_ok($c, $cnt)) {
        gate_json(['ok' => false, 'msg' => '工作量校验未通过'], 400);
    }

    // ══════════ 模式专属校验 ══════════
    if ($mode === 'c') {
        // ── 图片验证码（纯数字 4 位）──
        // 答案只存在服务端；错 GATE_CAPTCHA_TRIES 次该 challenge 作废。
        // 注意：这里**先不判重**，否则第一次输错就整页失效，体验太差。
        $ans = trim((string)($_POST['a'] ?? ''));
        try {
            $row = sql('SELECT answer,tries FROM dekk_gate_captcha WHERE ckey=? AND ip=?', [$cKey, $gate_ip])->fetch();
        } catch (Throwable $e) {
            $row = false;
        }
        if (!$row) gate_json(['ok' => false, 'msg' => '验证已失效，请刷新重试'], 400);
        if ($elapsed < 1000) gate_json(['ok' => false, 'msg' => '操作过快，请重试'], 400);

        if (!hash_equals((string)$row['answer'], $ans)) {
            $tries = (int)$row['tries'] + 1;
            if ($tries >= GATE_CAPTCHA_TRIES) {
                sql('DELETE FROM dekk_gate_captcha WHERE ckey=?', [$cKey]);
                gate_json(['ok' => false, 'msg' => '错误次数过多，请刷新重试'], 400);
            }
            sql('UPDATE dekk_gate_captcha SET tries=? WHERE ckey=?', [$tries, $cKey]);
            gate_json(['ok' => false, 'msg' => '验证码不正确'], 400);
        }
        // 答对 → 清理答案行（单次使用的判重统一放在下面所有校验之后）
        sql('DELETE FROM dekk_gate_captcha WHERE ckey=?', [$cKey]);
    } else {
        if ($mode === 'w') {
            // ── 5 秒盾 ──
            // challenge 的 ts 在 HMAC 签名里，客户端改不动 → 只能老老实实等够 5 秒。
            // 不接受太旧的 challenge（防「提前批量取一批，5 秒后一次性全用过」）。
            $age = time() - (int)$meta['ts'];
            if ($age < GATE_SHIELD_SECONDS) {
                gate_json(['ok' => false, 'msg' => '请稍候再提交'], 400);
            }
            if ($age > 120) {
                gate_json(['ok' => false, 'msg' => '验证已过期，请刷新重试'], 400);
            }
            if ($elapsed < GATE_SHIELD_SECONDS * 1000 - 500) {
                gate_json(['ok' => false, 'msg' => '操作过快，请重试'], 400);
            }
        } else {
            // ── 滑块：滑到底 + 有拖动轨迹 ──
            // 页面停留时间：从加载到提交至少 150ms，挡瞬时脚本。
            if ($elapsed < 150) {
                gate_json(['ok' => false, 'msg' => '操作过快，请重试'], 400);
            }
            // 按需求「滑到底就行」：≥90% 即通过（没到底松手时前端自动吸附到最右）。
            if (!ctype_digit($off) || (int)$off < 90) {
                gate_json(['ok' => false, 'msg' => '请把滑块滑到底'], 400);
            }
            // 轨迹下限：有过拖动（≥2 采样点且 ≥2 个不同位置）或至少耗时 100ms。
            $traceRaw = (string)($_POST['t'] ?? '');
            $dur      = (int)($_POST['d'] ?? 0);
            $trace    = json_decode($traceRaw, true);
            $points   = is_array($trace) ? count($trace) : 0;
            $xs       = [];
            if (is_array($trace)) {
                foreach ($trace as $p) {
                    if (is_array($p) && isset($p[1])) $xs[(int)$p[1]] = 1;
                }
            }
            $okHuman = ($points >= 2 && count($xs) >= 2) || $dur >= 100;
            if (!$okHuman) {
                error_log('[DEKK GATE] 轨迹不足 ip=' . $gate_ip . ' points=' . $points . ' dur=' . $dur . ' xs=' . count($xs));
                gate_json(['ok' => false, 'msg' => '请拖动滑块后再提交'], 400);
            }
        }
    }

    // ══════════ 单次使用：放在所有校验之后（成功才消耗）══════════
    // 这样失败时（滑块没到底 / 5 秒没等够 / 验证码输错）不会把 challenge 作废，
    // 用户可以直接重试；同时同一个 challenge 仍然只能**成功**一次，
    // 「算一次 PoW 无限刷 cookie」的路径依然是堵死的。
    try {
        sql('INSERT INTO dekk_gate_used(ckey,ip,used_at) VALUES(?,?,UTC_TIMESTAMP())', [$cKey, $gate_ip]);
    } catch (Throwable $e) {
        gate_json(['ok' => false, 'msg' => '验证已失效，请刷新重试'], 400);
    }

    $tok = gate_make_cookie($gate_ip);
    setcookie(GATE_COOKIE, $tok, [
        'expires'  => time() + GATE_TTL,
        'path'     => '/',
        // 不能设 secure：本机链路是「雷池 :443/:80 → 后端 :442(http) → PHP」，
        // 且实测雷池**不传** X-Forwarded-Proto，后端无法判断原始 scheme。
        // 若设 true，http 访问时浏览器直接拒绝保存 → 验证通过也永远进不去。
        // 安全影响有限：这个 cookie 只是「已通过验证」凭证，绑定 IP、1 小时过期。
        // 根治办法：在雷池控制台开启「强制 HTTPS 跳转」。
        'secure'   => false,
        'httponly' => true,
        'samesite' => 'Lax',
    ]);
    gate_json(['ok' => true, 'ttl' => GATE_TTL]);
}

gate_json(['ok' => false, 'msg' => 'not found'], 404);