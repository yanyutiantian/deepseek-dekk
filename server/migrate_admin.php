<?php
declare(strict_types=1);
/**
 * 管理员字段迁移（可重复跑）：
 *
 * - dekk_users 增加 admin：0=普通用户，1=管理员；
 * - 首次建列时把两个初始管理员邮箱（owner@example.com / admin@example.com）置为 1；
 *   之后**所有管理员判定都只看这一列**（看代码里的 dekk_is_admin()），不再查邮箱。
 *
 * 用法：php <后端配置目录>/migrate_admin.php
 */
require __DIR__ . '/bootstrap.php';

/** 初始管理员邮箱：只在建列那一次用来打标，跑过之后就不再依赖邮箱了。 */
const DEKK_INITIAL_ADMIN_EMAILS = ['owner@example.com', 'admin@example.com'];

function dekk_admin_has_column(): bool {
    $row = sql('SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?',
        ['dekk_users', 'admin'])->fetchColumn();
    return (int)$row > 0;
}

if (!dekk_admin_has_column()) {
    sql('ALTER TABLE dekk_users ADD COLUMN admin TINYINT(1) NOT NULL DEFAULT 0 AFTER nickname');
    sql('ALTER TABLE dekk_users ADD KEY idx_admin (admin, id)');
    echo "added dekk_users.admin\n";
    $marks = implode(',', array_fill(0, count(DEKK_INITIAL_ADMIN_EMAILS), '?'));
    $n = sql('UPDATE dekk_users SET admin=1 WHERE email IN ('.$marks.')', DEKK_INITIAL_ADMIN_EMAILS)->rowCount();
    echo "initial admins flagged: {$n}\n";
}

$rows = sql('SELECT id,nickname,email,admin FROM dekk_users WHERE admin=1 ORDER BY id ASC')->fetchAll();
echo "管理员（admin=1）共 ".count($rows)." 个：\n";
foreach ($rows as $row) {
    echo "  #{$row['id']}  {$row['nickname']}  {$row['email']}\n";
}
$owner = (int)sql('SELECT id FROM dekk_users WHERE admin=1 ORDER BY id ASC LIMIT 1')->fetchColumn();
echo "站长（id 最小的管理员，默认关注对象）= {$owner}\n";