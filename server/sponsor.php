<?php
declare(strict_types=1);
/**
 * 赞助身份接口（客户端所有「赞助限定」功能的唯一判定入口）。
 *
 * 规格文档：工作区记忆 topics/sponsor-api.md
 *
 *   action=sponsor_check   需要登录（Bearer token）
 *   可选参数：feature=force_login,email_login   （逗号分隔，只回这几项能不能用）
 *
 *   200 {"ok":true,"sponsor":1,"features":["force_login","email_login","gold_name"]}
 *
 * 赞助只有「是 / 不是」，不分等级、不设到期时间。
 * 以后新增赞助功能：只改 dekk_sponsor_features() 里那张表，客户端协议不用改。
 */

/** 赞助用户可用的功能名。加功能就在这里加一个字符串。 */
function dekk_sponsor_features(): array
{
    return ['force_login', 'email_login', 'gold_name'];
}

/** GET/POST 都取一遍（V2 信封把参数注入 $_GET/$_POST）。 */
function dekk_sponsor_param(string $key): string
{
    $value = $_GET[$key] ?? $_POST[$key] ?? '';
    return is_string($value) ? trim($value) : '';
}

function sponsorRoute(string $action, array $a): void
{
    if ($action !== 'sponsor_check') return;

    $u = user();                                   // 必须带有效 token
    limit('sponsor_check:' . (int)$u['id'], 60, 30);

    $row = sql('SELECT id,sponsor FROM dekk_users WHERE id=?', [(int)$u['id']])->fetch();
    if (!$row) fail('账号不存在', 404);

    $sponsor = (int)$row['sponsor'] === 1;
    $features = $sponsor ? dekk_sponsor_features() : [];

    // 可选：只关心某几个功能时传 feature=force_login,email_login
    $want = dekk_sponsor_param('feature');
    if ($want !== '') {
        $list = [];
        foreach (explode(',', $want) as $name) {
            $name = trim($name);
            if ($name !== '' && preg_match('/^[a-z_]{1,32}$/', $name)) $list[] = $name;
        }
        if ($list) $features = array_values(array_intersect($features, $list));
    }

    respond([
        'ok' => true,
        'sponsor' => $sponsor ? 1 : 0,
        'features' => $features,
    ]);
}
