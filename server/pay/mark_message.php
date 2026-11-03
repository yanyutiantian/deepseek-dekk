<?php
declare(strict_types=1);

/**
 * 机器人回写：它发出的「充值链接卡片」在哪个频道、哪条消息。
 * 支付成功后机器人要撤回那条卡片，所以需要 channel_id / message_id。
 *
 * GET/POST：key、out_trade_no、channel_id、message_id
 */
require __DIR__ . '/lib.php';

require_internal_key();

$outTradeNo = isset($_REQUEST['out_trade_no']) ? trim((string) $_REQUEST['out_trade_no']) : '';
$channelId = isset($_REQUEST['channel_id']) ? trim((string) $_REQUEST['channel_id']) : '';
$messageId = isset($_REQUEST['message_id']) ? trim((string) $_REQUEST['message_id']) : '';

if ($outTradeNo === '') {
    json_out(['ok' => false, 'msg' => '缺少订单号']);
}

try {
    db()->prepare('UPDATE pay_orders SET channel_id=?, message_id=? WHERE out_trade_no=?')
        ->execute([$channelId, $messageId, $outTradeNo]);
    json_out(['ok' => true]);
} catch (Throwable $e) {
    plog('mark_message: 失败 ' . $e->getMessage());
    json_out(['ok' => false, 'msg' => '写入失败']);
}