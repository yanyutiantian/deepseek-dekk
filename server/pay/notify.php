<?php
declare(strict_types=1);

/**
 * 易支付异步回调：
 * 验签（MD5）→ 标记订单已支付 → 把 dekk 账号升级为赞助用户 → 原样输出 success。
 *
 * 注意：即使回调晚于「链接 5 分钟有效期」，只要付款成功也照样升级（避免钱付了不到账）。
 * KOOK 频道角色不由这里发放：机器人会轮询 pending.php 去发放，Bot Token 不落在 web 侧。
 */
require __DIR__ . '/lib.php';

$params = array_merge($_GET, $_POST);
$sign = isset($params['sign']) ? (string) $params['sign'] : '';
$outTradeNo = isset($params['out_trade_no']) ? (string) $params['out_trade_no'] : '';
$tradeNo = isset($params['trade_no']) ? (string) $params['trade_no'] : '';
$tradeStatus = isset($params['trade_status']) ? (string) $params['trade_status'] : '';

if ($sign === '' || !hash_equals(epay_sign($params), $sign)) {
    plog('notify: 验签失败 order=' . $outTradeNo . ' ' . json_encode($params, JSON_UNESCAPED_UNICODE));
    echo 'fail';
    exit;
}

if ($tradeStatus !== 'TRADE_SUCCESS') {
    plog('notify: 非成功状态 ' . $tradeStatus . ' order=' . $outTradeNo);
    echo 'success';
    exit;
}

try {
    $stmt = db()->prepare('SELECT * FROM pay_orders WHERE out_trade_no=? LIMIT 1');
    $stmt->execute([$outTradeNo]);
    $order = $stmt->fetch();
    if (!$order) {
        plog('notify: 订单不存在 ' . $outTradeNo);
        echo 'success';
        exit;
    }
    if ($order['status'] !== 'paid') {
        // 金额必须与建单时一致：易支付回调里带实际支付金额，
        // 不校验的话「付更少的钱也能升级赞助」。
        $paidMoney = isset($params['money']) ? (float) $params['money'] : -1.0;
        if ($paidMoney < 0 || $paidMoney + 0.005 < (float) $order['money']) {
            plog('notify: 金额不符 order=' . $outTradeNo . ' paid=' . $paidMoney
                . ' expect=' . $order['money']);
            echo 'fail';
            exit;
        }
        db()->prepare("UPDATE pay_orders SET status='paid', paid_at=NOW(), trade_no=? WHERE out_trade_no=?")
            ->execute([$tradeNo, $outTradeNo]);
        db()->prepare('UPDATE dekk_users SET sponsor=1, sponsor_at=NOW() WHERE id=?')
            ->execute([$order['user_id']]);
        plog('notify: 支付成功 order=' . $outTradeNo . ' email=' . $order['email']
            . ' kook=' . $order['kook_user_id'] . ' trade_no=' . $tradeNo);
    } else {
        plog('notify: 重复回调（已处理）order=' . $outTradeNo);
    }
} catch (Throwable $e) {
    plog('notify: 处理异常 ' . $e->getMessage());
}

echo 'success';