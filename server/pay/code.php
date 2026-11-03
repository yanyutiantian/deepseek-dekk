<?php
declare(strict_types=1);

/**
 * 机器人调用：KOOK 账号绑定（用户在「账号绑定」频道发 /code XXXXXX）。
 *
 * 部署：/var/www/pay.example.com/code.php（pay 站无 WAF，机器人可直接调）
 * 入参：key（内部密钥）、code（6 位绑定码）、kook_user_id、kook_name
 */
require __DIR__ . '/panel/lib.php';

// 内部密钥：接受两种（① 面板密钥 ② pay 站点 config.php 的 internal_key），
// 这样机器人无论配置哪一个都能调通（历史上机器人侧用的是 PAY_INTERNAL_KEY）。
$key = $_POST['key'] ?? $_GET['key'] ?? '';
$keyOk = hash_equals(panel_internal_key(), (string) $key);
if (!$keyOk) {
    try {
        $payConfig = require __DIR__ . '/config.php';
        if (isset($payConfig['internal_key']) && hash_equals((string) $payConfig['internal_key'], (string) $key)) {
            $keyOk = true;
        }
    } catch (Throwable $e) {
    }
}
if (!$keyOk) {
    pfail('内部密钥错误', 403);
}

$code = strtoupper(trim((string) ($_POST['code'] ?? $_GET['code'] ?? '')));
$kookUserId = trim((string) ($_POST['kook_user_id'] ?? $_GET['kook_user_id'] ?? ''));
$kookName = trim((string) ($_POST['kook_name'] ?? $_GET['kook_name'] ?? ''));

if (!preg_match('/^[A-Z0-9]{6}$/D', $code)) {
    pfail('绑定码格式不正确（应为 6 位字母或数字）');
}
if (!preg_match('/^[0-9]{1,20}$/D', $kookUserId)) {
    pfail('KOOK 用户 ID 不正确');
}

// ── ① 找绑定码 ──
$stmt = pdb()->prepare('SELECT * FROM dekk_kook_codes WHERE code=? LIMIT 1');
$stmt->execute([$code]);
$row = $stmt->fetch();
if (!$row) {
    pfail('绑定码不存在，请在 App 里重新获取');
}
if ((int) $row['used'] === 1) {
    pfail('该绑定码已失效，请在 App 里重新获取');
}
if (strtotime((string) $row['expires_at']) < time()) {
    pfail('绑定码已过期（有效期 1 小时），请在 App 里重新获取');
}

// ── ② 该 KOOK 账号是否已绑定别的 dekk 账号 ──
$stmt = pdb()->prepare('SELECT id,email FROM dekk_users WHERE kook_user_id=? AND id<>? LIMIT 1');
$stmt->execute([$kookUserId, (int) $row['user_id']]);
$other = $stmt->fetch();
if ($other) {
    pfail('该 KOOK 账号已经绑定了其它 DEKK 账号（' . (string) $other['email'] . '）');
}

// ── ③ 该 dekk 账号是否已绑定别的 KOOK ──
$stmt = pdb()->prepare('SELECT kook_user_id FROM dekk_users WHERE id=? LIMIT 1');
$stmt->execute([(int) $row['user_id']]);
$userRow = $stmt->fetch();
if ($userRow && (string) $userRow['kook_user_id'] !== '' && (string) $userRow['kook_user_id'] !== $kookUserId) {
    pfail('该 DEKK 账号已绑定其它 KOOK 账号，如需更换请联系管理员');
}

// ── ④ 写库 ──
pdb()->beginTransaction();
try {
    pdb()->prepare('UPDATE dekk_users SET kook_user_id=?, kook_name=?, kook_bound_at=NOW() WHERE id=?')
        ->execute([$kookUserId, $kookName, (int) $row['user_id']]);
    // 当前码标记为已使用
    pdb()->prepare('UPDATE dekk_kook_codes SET used=1, kook_user_id=?, kook_name=? WHERE id=?')
        ->execute([$kookUserId, $kookName, (int) $row['id']]);
    // 同一 dekk 账号名下「其它」还没用掉的绑定码，全部作废（消除），
    // 保证一个账号同一时间最多只有一个有效绑定码。
    pdb()->prepare('UPDATE dekk_kook_codes SET used=1 WHERE user_id=? AND id<>? AND used=0')
        ->execute([(int) $row['user_id'], (int) $row['id']]);
    pdb()->commit();
} catch (Throwable $e) {
    if (pdb()->inTransaction()) {
        pdb()->rollBack();
    }
    pfail('绑定失败：' . $e->getMessage(), 500);
}

// ── ⑤ 返回账号信息 ──
$stmt = pdb()->prepare('SELECT id,email,nickname,kook_user_id,kook_name,kook_bound_at FROM dekk_users WHERE id=?');
$stmt->execute([(int) $row['user_id']]);
$u = $stmt->fetch();

plog_panel('BIND kook=' . $kookUserId . '(' . $kookName . ') -> dekk id=' . (int) $row['user_id']
    . ' email=' . (string) $u['email'] . ' code=' . $code);

pjson([
    'ok' => true,
    'dekk_user' => [
        'id' => (int) $u['id'],
        'email' => (string) $u['email'],
        'nickname' => (string) $u['nickname'],
    ],
    'kook_user_id' => $kookUserId,
    'kook_name' => $kookName,
    'kook_bound_at' => (string) $u['kook_bound_at'],
]);