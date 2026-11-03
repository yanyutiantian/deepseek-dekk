<?php
declare(strict_types=1);

/**
 * 给机器人轮询用：取出「已支付但还没发 KOOK 角色」的订单。
 * GET/POST：key（内部密钥）、limit（可选，默认 20）
 */
require __DIR__ . '/lib.php';

require_internal_key();

$limit = isset($_REQUEST['limit']) ? (int) $_REQUEST['limit'] : 20;
if ($limit < 1 || $limit > 50) {
    $limit = 20;
}

try {
    $sql = "SELECT out_trade_no, kook_user_id, email, money, paid_at, channel_id, message_id FROM pay_orders "
        . "WHERE status='paid' AND role_granted=0 AND kook_user_id<>'' ORDER BY id ASC LIMIT {$limit}";
    $rows = db()->query($sql)->fetchAll();
    json_out(['ok' => true, 'items' => $rows]);
} catch (Throwable $e) {
    plog('pending: 查询失败 ' . $e->getMessage());
    json_out(['ok' => false, 'msg' => '查询失败']);
}