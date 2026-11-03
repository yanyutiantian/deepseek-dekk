<?php
declare(strict_types=1);

/**
 * 机器人调用：生成一个临时管理面板（1 小时有效）→ 返回面板链接。
 *
 * GET/POST：key（内部密钥）、kook_user_id、kook_name、channel_id
 */
require __DIR__ . '/lib.php';

$key = $_POST['key'] ?? $_GET['key'] ?? '';
if (!hash_equals(panel_internal_key(), (string) $key)) {
    pfail('内部密钥错误', 403);
}

$kookUserId = trim((string) ($_POST['kook_user_id'] ?? $_GET['kook_user_id'] ?? ''));
$kookName = trim((string) ($_POST['kook_name'] ?? $_GET['kook_name'] ?? ''));
$channelId = trim((string) ($_POST['channel_id'] ?? $_GET['channel_id'] ?? ''));

if ($kookUserId === '' || !preg_match('/^[0-9]{1,20}$/D', $kookUserId)) {
    pfail('KOOK 用户 ID 不正确');
}

// 同一管理员 1 小时内重复申请：撤销旧的，只留最新的一个（避免链接满天飞）
try {
    pdb()->prepare('UPDATE panel_tokens SET revoked=1 WHERE kook_user_id=? AND revoked=0')
        ->execute([$kookUserId]);
} catch (Throwable $e) {
}

$token = bin2hex(random_bytes(24));   // 48 位 hex
$ttl = PANEL_TTL_MINUTES;

pdb()->prepare('INSERT INTO panel_tokens(token_hash,kook_user_id,kook_name,channel_id,created_at,expires_at,revoked) '
    . 'VALUES(?,?,?,?,NOW(),DATE_ADD(NOW(), INTERVAL ' . (int) $ttl . ' MINUTE),0)')
    ->execute([hash('sha256', $token), $kookUserId, $kookName, $channelId]);

plog_panel('CREATE panel kook=' . $kookUserId . '(' . $kookName . ') channel=' . $channelId);

pjson([
    'ok' => true,
    'url' => PANEL_BASE_URL . '?t=' . $token,
    'expires_in' => $ttl * 60,
    'kook_name' => $kookName,
]);