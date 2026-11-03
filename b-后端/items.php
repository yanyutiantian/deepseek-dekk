<?php
declare(strict_types=1);
// dekk_owner_id() 在 profile.php 里
require_once __DIR__ . '/profile.php';

/**
 * 单条内容的详情数据（消息中心点进去要打开对应的人设/插件详情页）。
 *
 * - item_get&kind=persona|plugin&id=：返回和列表一致的行字段（含作者昵称/头像），
 *   可见性规则和 download 一致：公开的谁都能看，非公开的只有作者本人和管理员能看。
 */
function itemRoute(string $action, array $a): void {
    if ($action !== 'item_get') return;

    $kind = strtolower(trim((string)($_GET['kind'] ?? '')));
    if ($kind !== 'persona' && $kind !== 'plugin') fail('参数错误');
    $id = (int)($_GET['id'] ?? 0);
    if ($id <= 0) fail('参数错误');

    $u = user(false);
    $uid = $u ? (int)$u['id'] : 0;
    $isAdmin = dekk_is_admin($uid) ? 1 : 0;
    $table = $kind === 'persona' ? 'dekk_personas' : 'dekk_plugins';
    $row = sql("SELECT p.id,p.user_id,p.name,p.description,p.visibility,p.created_at,p.updated_at,
                       u.nickname AS author,u.avatar AS author_avatar,u.sponsor AS author_sponsor
                FROM $table p JOIN dekk_users u ON u.id=p.user_id
                WHERE p.id=? AND (p.visibility='public' OR p.user_id=? OR ?=1)",
        [$id, $uid, $isAdmin])->fetch();
    if (!$row) fail('内容不存在或不可访问', 404);

    $row['kind'] = $kind;
    $row['author_sponsor'] = (int)$row['author_sponsor'];
    respond(['ok' => true, 'item' => $row]);
}