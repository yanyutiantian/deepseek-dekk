<?php
declare(strict_types=1);
/**
 * 人机验证网关端到端测试。直接跑：php <后端配置目录>/gate_test.php
 * 只读验证，不写入任何业务数据。
 */

// config.php 是 `return [...]` 形式，必须接返回值；只 require 的话 $config 是空的
// （踩过：导致算出的 module_secret 与服务端不一致，签名校验一直 401）
$config = require __DIR__ . '/config.php';

$BASE = 'https://dekk.example.com';
$UA   = 'Mozilla/5.0 (Linux; Android 13; Test) AppleWebKit/537.36';

// 复算网关密钥（与 gate.php 的派生方式一致），用于验证模块签名头
$gateSecret   = hash('sha256', 'dekk-gate|' . (string)($config['db_password'] ?? '') . '|v1');
$moduleSecret = (string)($config['module_secret'] ?? '') !== ''
    ? (string)$config['module_secret']
    : substr($gateSecret, 0, 32);

function req(string $url, array $opt = []): array {
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => 1,
        CURLOPT_HEADER         => 1,
        CURLOPT_TIMEOUT        => 20,
        CURLOPT_SSL_VERIFYPEER => 0,
    ] + $opt);
    $raw  = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $hsz  = (int)curl_getinfo($ch, CURLINFO_HEADER_SIZE);
    curl_close($ch);
    $head = substr((string)$raw, 0, $hsz);
    $body = substr((string)$raw, $hsz);
    $cookies = [];
    foreach (explode("\r\n", $head) as $line) {
        if (stripos($line, 'Set-Cookie:') === 0) {
            $kv = trim(substr($line, 11));
            $p  = explode(';', $kv)[0];
            $pp = explode('=', $p, 2);
            if (count($pp) === 2) $cookies[$pp[0]] = $pp[1];
        }
    }
    return ['code' => $code, 'head' => $head, 'body' => $body, 'cookies' => $cookies];
}

$pass = 0; $fail = 0;
function chk(string $name, bool $ok, string $extra = ''): void {
    global $pass, $fail;
    if ($ok) { $pass++; echo "  [PASS] $name" . ($extra ? "  ($extra)" : '') . "\n"; }
    else     { $fail++; echo "  [FAIL] $name" . ($extra ? "  ($extra)" : '') . "\n"; }
}

echo "===== T1) 取挑战（JSON）=====\n";
$r = req($BASE . '/_gate/?r=api', [CURLOPT_USERAGENT => $UA]);
$j = json_decode($r['body'], true);
chk('r=api 返回 200', $r['code'] === 200, 'http=' . $r['code']);
chk('challenge 字段存在', is_array($j) && !empty($j['c']), 'bits=' . ($j['bits'] ?? '?'));
$c = (string)($j['c'] ?? '');
$bits = (int)($j['bits'] ?? 0);

echo "\n===== T2) 计算 PoW =====\n";
$t0 = microtime(true);
$n = 0; $zero = str_repeat('0', $bits);
while ($bits > 0 && strncmp(hash('sha256', $c . $n), $zero, $bits) !== 0) {
    $n++;
    if ($n > 20000000) break;
}
$dt = round((microtime(true) - $t0) * 1000);
chk('找到满足条件的 nonce', $bits > 0 && $n <= 20000000, "n=$n 用时{$dt}ms");

// 从挑战里解出目标位置（前端画缺口也用它）
$p = explode('|', (string)base64_decode($c, true));
$target = isset($p[2]) ? (int)$p[2] : 50;

echo "\n===== T3) 不带轨迹提交（应被拒）=====\n";
$jsTok = hash('sha256', 'dekk-js|' . $c);   // JS 挑战令牌
$r = req($BASE . '/_gate/?r=verify', [
    CURLOPT_USERAGENT   => $UA,
    CURLOPT_POST        => 1,
    CURLOPT_POSTFIELDS  => http_build_query([
        'c' => $c, 'n' => $n, 'x' => 100, 'j' => $jsTok, 'e' => 800,
    ]),
]);
$j = json_decode($r['body'], true);
chk('无轨迹被拒 400', $r['code'] === 400, 'msg=' . ($j['msg'] ?? ''));

echo "\n===== T4) 滑到底 + 人类轨迹 + JS令牌（应通过并下发 cookie）=====\n";
$trace = [];
for ($t = 0; $t <= 700; $t += 60) $trace[] = [$t, (int)round($t / 700 * 100)];
$r = req($BASE . '/_gate/?r=verify', [
    CURLOPT_USERAGENT  => $UA,
    CURLOPT_POST       => 1,
    CURLOPT_POSTFIELDS => http_build_query([
        'c' => $c, 'n' => $n, 'x' => 100,
        'j' => $jsTok, 'e' => 720,
        't' => json_encode($trace), 'd' => 720,
    ]),
]);
$j = json_decode($r['body'], true);
chk('verify 通过', $r['code'] === 200 && !empty($j['ok']), 'msg=' . ($j['msg'] ?? 'ok'));
$ck = $r['cookies']['dekk_ck'] ?? '';
chk('下发 dekk_ck cookie', $ck !== '', 'len=' . strlen($ck));

echo "\n===== T4b) 缺少 JS 令牌（应被拒）=====\n";
$r = req($BASE . '/_gate/?r=verify', [
    CURLOPT_USERAGENT  => $UA,
    CURLOPT_POST       => 1,
    CURLOPT_POSTFIELDS => http_build_query([
        'c' => $c, 'n' => $n, 'x' => 100, 'e' => 800,
        't' => json_encode($trace), 'd' => 720,
    ]),
]);
$j = json_decode($r['body'], true);
chk('缺 j 被拒 400', $r['code'] === 400, 'msg=' . ($j['msg'] ?? ''));

echo "\n===== T4c) JS 令牌错误（应被拒）=====\n";
$r = req($BASE . '/_gate/?r=verify', [
    CURLOPT_USERAGENT  => $UA,
    CURLOPT_POST       => 1,
    CURLOPT_POSTFIELDS => http_build_query([
        'c' => $c, 'n' => $n, 'x' => 100, 'j' => 'deadbeef', 'e' => 800,
        't' => json_encode($trace), 'd' => 720,
    ]),
]);
$j = json_decode($r['body'], true);
chk('错误 j 被拒 400', $r['code'] === 400, 'msg=' . ($j['msg'] ?? ''));

echo "\n===== T4d) 停留时间过短（应被拒）=====\n";
$r = req($BASE . '/_gate/?r=verify', [
    CURLOPT_USERAGENT  => $UA,
    CURLOPT_POST       => 1,
    CURLOPT_POSTFIELDS => http_build_query([
        'c' => $c, 'n' => $n, 'x' => 100, 'j' => $jsTok, 'e' => 10,
        't' => json_encode($trace), 'd' => 720,
    ]),
]);
$j = json_decode($r['body'], true);
chk('过短 e 被拒 400', $r['code'] === 400, 'msg=' . ($j['msg'] ?? ''));

echo "\n===== T5) auth：无 cookie（应 401）=====\n";
$r = req($BASE . '/_gate/?r=auth', [CURLOPT_USERAGENT => $UA]);
chk('无 cookie 返回 401', $r['code'] === 401, 'http=' . $r['code']);

echo "\n===== T6) auth：带 cookie（应 200）=====\n";
$r = req($BASE . '/_gate/?r=auth', [
    CURLOPT_USERAGENT => $UA,
    CURLOPT_COOKIE    => 'dekk_ck=' . $ck,
]);
chk('带 cookie 返回 200', $r['code'] === 200, 'http=' . $r['code']);

echo "\n===== T7) auth：伪造 cookie（应 401）=====\n";
$r = req($BASE . '/_gate/?r=auth', [
    CURLOPT_USERAGENT => $UA,
    CURLOPT_COOKIE    => 'dekk_ck=' . (time() + 3600) . '.deadbeef',
]);
chk('伪造 cookie 返回 401', $r['code'] === 401, 'http=' . $r['code']);

echo "\n===== T8) auth：Dalvik UA（过渡期放行，应 200）=====\n";
$r = req($BASE . '/_gate/?r=auth', [
    CURLOPT_USERAGENT => 'Dalvik/2.1.0 (Linux; U; Android 16; PYR110)',
]);
chk('Dalvik UA 返回 200', $r['code'] === 200, 'http=' . $r['code']);

echo "\n===== T9) auth：模块签名头（浏览器 UA + 有效签名，应 200）=====\n";
$ts = (string)time();
// 与 gate.php 一致：签名只绑 ts，不绑 URI
$sign = base64_encode(hash_hmac('sha256', $ts, $moduleSecret, true));
// 用浏览器 UA，确保不会被「非浏览器放行」这条旁路掩盖，真正走到签名校验
$r = req($BASE . '/_gate/?r=auth', [
    CURLOPT_USERAGENT => $UA,
    CURLOPT_HTTPHEADER => ['X-Dekk-Ts: ' . $ts, 'X-Dekk-Sign: ' . $sign],
]);
chk('有效签名头返回 200', $r['code'] === 200, 'http=' . $r['code']);

echo "\n===== T10) auth：浏览器 UA + 错误签名头（应 401）=====\n";
$r = req($BASE . '/_gate/?r=auth', [
    CURLOPT_USERAGENT => $UA,
    CURLOPT_HTTPHEADER => ['X-Dekk-Ts: ' . $ts, 'X-Dekk-Sign: ' . base64_encode('wrong')],
]);
chk('错误签名头返回 401', $r['code'] === 401, 'http=' . $r['code']);

echo "\n===== T10b) auth：非浏览器 UA（过渡期放行，应 200）=====\n";
$r = req($BASE . '/_gate/?r=auth', [CURLOPT_USERAGENT => 'okhttp/4.12.0']);
chk('okhttp UA 返回 200', $r['code'] === 200, 'http=' . $r['code']);

echo "\n===== T11) 挑战页 HTML =====\n";
$r = req($BASE . '/_gate/?r=challenge&to=/', [CURLOPT_USERAGENT => $UA]);
chk('挑战页 200 且含标题', $r['code'] === 200 && strpos($r['body'], '访问验证') !== false, 'http=' . $r['code']);

echo "\n===== T12) 开放重定向防护（to=//evil.com 应归一到 /）=====\n";
$r = req($BASE . '/_gate/?r=challenge&to=//evil.com', [CURLOPT_USERAGENT => $UA]);
chk('to 被归一', strpos($r['body'], '//evil.com') === false, 'http=' . $r['code']);

echo "\n===== 结果：PASS={$pass}  FAIL={$fail} =====\n";
exit($fail > 0 ? 1 : 0);