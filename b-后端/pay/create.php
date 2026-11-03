<?php
declare(strict_types=1);

/**
 * 机器人调用：校验账号 → 建单 → 返回「短链」充值链接。
 *
 * GET/POST：key（内部密钥）、account（QQ 号或完整邮箱；兼容旧参数 qq / email）、kook_user_id
 *   · account 为纯数字时按注册邮箱的 QQ 号处理：123456 → 123456@qq.com
 *   · 也接受完整邮箱
 * 金额完全由服务端决定，外部传的金额一律忽略。
 */
require __DIR__ . '/lib.php';

require_internal_key();

$raw = '';
foreach (['account', 'qq', 'email'] as $field) {
    if (isset($_REQUEST[$field]) && trim((string) $_REQUEST[$field]) !== '') {
        $raw = (string) $_REQUEST[$field];
        break;
    }
}
$kookUserId = isset($_REQUEST['kook_user_id']) ? trim((string) $_REQUEST['kook_user_id']) : '';

$email = normalize_account($raw);
if ($email === null) {
    json_out(['ok' => false, 'msg' => '请填写注册邮箱对应的 QQ 号（纯数字）。用法：/pay 123456']);
}
if (!preg_match('/^[0-9]{1,20}$/', $kookUserId)) {
    json_out(['ok' => false, 'msg' => 'KOOK 用户 ID 不正确，请重新发送指令']);
}

expire_orders();

$stmt = db()->prepare('SELECT id, email, nickname, sponsor FROM dekk_users WHERE email=? LIMIT 1');
$stmt->execute([$email]);
$user = $stmt->fetch();
if (!$user) {
    json_out(['ok' => false, 'msg' => '这个 QQ/邮箱没有注册 dekk 账号，请先到 dekk.example.com 注册后再赞助']);
}

$money = current_money();   // 金额可在 KOOK 里用 /setpay 调整，默认取 config 里的 20.00
$seconds = (int) cfg('expire_seconds');

// 5 分钟内同一 KOOK 用户还有未支付的单：直接复用那一张，避免刷单
$stmt = db()->prepare("SELECT out_trade_no FROM pay_orders WHERE kook_user_id=? AND status='pending' AND expires_at>NOW() ORDER BY id DESC LIMIT 1");
$stmt->execute([$kookUserId]);
$pending = $stmt->fetch();

$reused = false;
if ($pending) {
    $outTradeNo = (string) $pending['out_trade_no'];
    $reused = true;
} else {
    $outTradeNo = 'DEKK' . bin2hex(random_bytes(12));
    $sql = "INSERT INTO pay_orders(out_trade_no, kook_user_id, email, user_id, money, status, created_at, expires_at) "
        . "VALUES(?, ?, ?, ?, ?, 'pending', NOW(), DATE_ADD(NOW(), INTERVAL {$seconds} SECOND))";
    db()->prepare($sql)->execute([$outTradeNo, $kookUserId, $email, $user['id'], $money]);
}

plog('create: account=' . $email . ' kook=' . $kookUserId . ' order=' . $outTradeNo . ' reused=' . ($reused ? '1' : '0'));

json_out([
    'ok' => true,
    'out_trade_no' => $outTradeNo,
    'money' => $money,
    // ★ 发给用户的就是这条短链（点击后 302 跳到支付宝收银台）
    'url' => order_short_url($outTradeNo),
    'expires_seconds' => $seconds,
    'account' => $email,
    'account_show' => account_show($email),
    'nickname' => (string) $user['nickname'],
]);