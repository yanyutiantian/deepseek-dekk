<?php
declare(strict_types=1);

/**
 * 订单短链（发给用户复制/点击的就是它）：
 *   https://pay.example.com/p.php?id=<订单号>
 *
 *   · 未支付且未过期 → 302 到易支付收银台
 *   · 已支付         → 302 到结果页（显示「支付成功，已升级为赞助用户」）—— 不允许二次支付
 *   · 已过期/不存在  → 302 到结果页（提示重新 /pay）
 *
 * 这样频道里只出现短链，不会暴露 submit.php 那一长串带签名的参数。
 */
require __DIR__ . '/lib.php';

plimit('pay-p:'.($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 60, 60);

$outTradeNo = isset($_REQUEST['id']) ? trim((string) $_REQUEST['id']) : '';
if ($outTradeNo === '') {
    header('Location: ' . site_url('return.php'));
    exit;
}

expire_orders();

$stmt = db()->prepare('SELECT * FROM pay_orders WHERE out_trade_no=? LIMIT 1');
$stmt->execute([$outTradeNo]);
$order = $stmt->fetch();

if ($order && $order['status'] === 'pending') {
    // 每次点击都用最新的订单信息现算支付链接（签名不落库、不暴露）
    header('Location: ' . epay_pay_url($order), true, 302);
    exit;
}

header('Location: ' . site_url('return.php?out_trade_no=' . urlencode($outTradeNo)), true, 302);
exit;