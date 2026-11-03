<?php
declare(strict_types=1);

/**
 * KOOK 账号绑定（模块侧接口）。
 *
 * - bind_code   （需登录）：生成/复用 6 位绑定码（字母+数字，1 小时有效）
 * - bind_status （需登录）：查询当前 dekk 账号是否已绑定 KOOK
 *
 * 绑定动作本身由机器人在 KOOK「账号绑定」频道执行 /code XXXXXX，
 * 机器人调用 pay 站点的 code.php 写库（见 /var/www/pay.example.com/code.php）。
 */

/** 绑定码字符集：大写字母 + 数字，去掉容易混淆的 0/O/1/I。 */
function bind_code_alphabet(): string
{
    return 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
}

/** 生成一个 6 位绑定码。 */
function bind_code_generate(): string
{
    $alphabet = bind_code_alphabet();
    $max = strlen($alphabet) - 1;
    $code = '';
    for ($i = 0; $i < 6; $i++) {
        $code .= $alphabet[random_int(0, $max)];
    }
    return $code;
}

/** 把用户信息 + 绑定状态打包返回。 */
function bind_user_payload(array $u): array
{
    return [
        'id' => (int) $u['id'],
        'email' => (string) $u['email'],
        'nickname' => (string) $u['nickname'],
        'kook_user_id' => (string) ($u['kook_user_id'] ?? ''),
        'kook_name' => (string) ($u['kook_name'] ?? ''),
        'kook_bound_at' => (string) ($u['kook_bound_at'] ?? ''),
        'bound' => ($u['kook_user_id'] ?? '') !== '',
    ];
}

/** 绑定码有效期（秒）。 */
const DEKK_BIND_TTL = 3600;

/** 绑定频道（机器人侧提示用）。 */
const DEKK_BIND_CHANNEL_ID = '000000000084876';
const DEKK_BIND_INVITE = 'https://kook.vip/YOUR_INVITE';

function bindRoute(string $action, array $a): void
{
    global $db;

    // ── 生成 / 复用绑定码 ──
    if ($action === 'bind_code') {
        $u = user();
        limit('bind_code:' . $u['id'], 60, 20);

        // 已经绑定了就不需要再生成。
        // 注意：user() 只返回 id/email/nickname，绑定信息必须自己查库，
        // 否则这个判断永远不成立（旧实现就是这个 bug）。
        $bound = sql('SELECT kook_user_id,kook_name FROM dekk_users WHERE id=? LIMIT 1',
            [(int) $u['id']])->fetch();
        if ($bound && (string) $bound['kook_user_id'] !== '') {
            respond(['ok' => true, 'already' => true, 'bound' => true,
                'kook_user_id' => (string) $bound['kook_user_id'],
                'kook_name' => (string) $bound['kook_name']]);
        }

        // 1 小时内已有的未用码：直接复用（避免用户连点生成一堆）。
        // 同时保证「一个 dekk 账号同一时间只有一个有效码」：
        // 无论复用还是新建，都会把该账号名下其它未用码全部作废。
        $row = sql('SELECT code,expires_at FROM dekk_kook_codes WHERE user_id=? AND used=0 AND expires_at>NOW() ORDER BY id DESC LIMIT 1',
            [(int) $u['id']])->fetch();
        if ($row) {
            // 保留这一个有效码，作废同账号其它所有未用码
            sql('UPDATE dekk_kook_codes SET used=1 WHERE user_id=? AND used=0 AND code<>?',
                [(int) $u['id'], (string) $row['code']]);
        } else {
            // 先作废所有历史未用码（含已过期的），再生成新码
            sql('UPDATE dekk_kook_codes SET used=1 WHERE user_id=? AND used=0', [(int) $u['id']]);
            $code = bind_code_generate();
            sql('INSERT INTO dekk_kook_codes(code,user_id,kook_user_id,kook_name,created_at,expires_at,used) '
                . 'VALUES(?,?,?,?,NOW(),DATE_ADD(NOW(), INTERVAL ' . DEKK_BIND_TTL . ' SECOND),0)',
                [$code, (int) $u['id'], '', '']);
            $row = sql('SELECT code,expires_at FROM dekk_kook_codes WHERE user_id=? AND used=0 ORDER BY id DESC LIMIT 1',
                [(int) $u['id']])->fetch();
        }

        respond([
            'ok' => true,
            'code' => (string) $row['code'],
            'expires_at' => (string) $row['expires_at'],
            'expires_in' => (int) DEKK_BIND_TTL,
            'command' => '/code ' . (string) $row['code'],
            'channel_id' => DEKK_BIND_CHANNEL_ID,
            'invite' => DEKK_BIND_INVITE,
        ]);
    }

    // ── 查询绑定状态 ──
    if ($action === 'bind_status') {
        $u = user();
        $row = sql('SELECT id,email,nickname,kook_user_id,kook_name,kook_bound_at FROM dekk_users WHERE id=?',
            [(int) $u['id']])->fetch();
        if (!$row) fail('账号不存在', 404);
        respond(['ok' => true] + bind_user_payload($row));
    }
}