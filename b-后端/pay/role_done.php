<?php
declare(strict_types=1);

/**
 * 机器人回执：KOOK「赞助用户」角色是否发放成功。
 * GET/POST：key、out_trade_no、ok（1=成功 / 0=失败）、msg（失败原因，可选）
 */
require __DIR__ . '/lib.php';

require_internal_key();

$outTradeNo = isset($_REQUEST['out_trade_no']) ? trim((string) $_REQUEST['out_trade_no']) : '';
$granted = isset($_REQUEST['ok']) && (string) $_REQUEST['ok'] === '1';
$message = isset($_REQUEST['msg']) ? (string) $_REQUEST['msg'] : '';

if ($outTradeNo === '') {
    json_out(['ok' => false, 'msg' => '缺少订单号']);
}

try {
    if ($granted) {
        db()->prepare('UPDATE pay_orders SET role_granted=1, role_granted_at=NOW() WHERE out_trade_no=?')
            ->execute([$outTradeNo]);
        plog('role_done: 已发放角色 order=' . $outTradeNo);
        json_out(['ok' => true]);
    }
    // 失败：不标记已发放，机器人下轮会继续重试
    plog('role_done: 发放失败 order=' . $outTradeNo . ' msg=' . $message);
    json_out(['ok' => true, 'retry' => true]);
} catch (Throwable $e) {
    plog('role_done: 异常 ' . $e->getMessage());
    json_out(['ok' => false, 'msg' => '处理失败']);
}