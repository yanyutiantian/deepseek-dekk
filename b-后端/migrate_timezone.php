<?php
declare(strict_types=1);
/**
 * 时区迁移（可重复跑，只会生效一次）：
 *
 * 背景：以前 bootstrap.php 把 MySQL 会话时区设成 +00:00，所以
 * dekk_personas / dekk_plugins / dekk_users 的 created_at、updated_at 存的是 UTC，
 * 比北京时间慢 8 小时（客户端直接展示这个字符串，看起来就是「时间不对」）。
 *
 * 现在：MySQL 会话改成 +08:00（存的就是北京时间），
 * 而 token / 验证码的 expires_at 仍然用 UTC_TIMESTAMP() 判定（不受影响）。
 * 这里把历史数据一次性 +8 小时，改完打标记，重复执行不会再加。
 *
 * 用法：php <后端配置目录>/migrate_timezone.php
 */
require __DIR__ . '/bootstrap.php';

sql('CREATE TABLE IF NOT EXISTS dekk_meta (
    name VARCHAR(64) NOT NULL,
    value VARCHAR(255) NOT NULL DEFAULT "",
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');

$flag = sql('SELECT value FROM dekk_meta WHERE name=?', ['tz_shift_plus8'])->fetchColumn();

if ($flag === false) {
    // 只补「客户端会展示时间」的几张表的历史数据。
    // expires_at（token / 验证码）是 UTC 语义，绝对不能动。
    $total = 0;
    foreach (['dekk_personas', 'dekk_plugins', 'dekk_users', 'dekk_comments', 'dekk_notices'] as $table) {
        $cols = [];
        foreach (sql('SHOW COLUMNS FROM '.$table)->fetchAll() as $col) {
            if ($col['Field'] === 'created_at' || $col['Field'] === 'updated_at') $cols[] = $col['Field'];
        }
        if (!$cols) continue;
        $sets = [];
        foreach ($cols as $col) $sets[] = $col.'='.$col.' + INTERVAL 8 HOUR';
        $n = sql('UPDATE '.$table.' SET '.implode(',', $sets))->rowCount();
        echo $table.' 平移 +8 小时：'.$n." 行\n";
        $total += $n;
    }
    sql('INSERT INTO dekk_meta(name,value) VALUES(?,?) ON DUPLICATE KEY UPDATE value=VALUES(value)',
        ['tz_shift_plus8', date('c')]);
    echo "合计 {$total} 行（已打标记 tz_shift_plus8，重复执行不会再加）\n";
} else {
    echo "已迁移过（标记时间 {$flag}），跳过\n";
}

echo "当前会话时区=".sql('SELECT @@session.time_zone t')->fetch()['t']
    ."  NOW()=".sql('SELECT NOW() n')->fetch()['n']
    ."  UTC_TIMESTAMP()=".sql('SELECT UTC_TIMESTAMP() u')->fetch()['u']."\n";