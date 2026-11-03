<?php
declare(strict_types=1);

/**
 * 机器人调用：设定自助赞助金额（机器人侧已校验「管理员」身份）。
 *
 * GET/POST：key、money（如 20 / 30.5）
 * 金额范围：0.01 ~ 99999，最多两位小数。
 */
require __DIR__ . '/lib.php';

require_internal_key();

$raw = isset($_REQUEST['money']) ? trim((string) $_REQUEST['money']) : '';
if ($raw === '' || !money_valid($raw)) {
    json_out(['ok' => false, 'msg' => '金额不合法：需在 0.01 ~ 99999 之间，最多两位小数。用法：/setpay 30']);
}

$money = money_normalize($raw);
try {
    setting_set('money', $money);
    plog('setpay: money=' . $money);
    json_out(['ok' => true, 'money' => $money]);
} catch (Throwable $e) {
    plog('setpay: 失败 ' . $e->getMessage());
    json_out(['ok' => false, 'msg' => '保存失败：' . $e->getMessage()]);
}