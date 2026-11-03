<?php
declare(strict_types=1);
/** 评论表迁移（可重复跑）：抖音式评论需要 parent_id（回复）与 pinned（置顶）。 */
require __DIR__ . '/bootstrap.php';

function dekk_has_column(string $table, string $column): bool {
    $row = sql('SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?',
        [$table, $column])->fetchColumn();
    return (int)$row > 0;
}

sql('CREATE TABLE IF NOT EXISTS dekk_comments (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    kind VARCHAR(10) NOT NULL COMMENT "persona / plugin",
    target_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    content VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_target (kind, target_id, id),
    KEY idx_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');

if (!dekk_has_column('dekk_comments', 'parent_id')) {
    sql('ALTER TABLE dekk_comments ADD COLUMN parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER target_id');
    sql('ALTER TABLE dekk_comments ADD KEY idx_parent (parent_id, id)');
    echo "added parent_id\n";
}
if (!dekk_has_column('dekk_comments', 'pinned')) {
    sql('ALTER TABLE dekk_comments ADD COLUMN pinned TINYINT(1) NOT NULL DEFAULT 0 AFTER user_id');
    sql('ALTER TABLE dekk_comments ADD KEY idx_pinned (kind, target_id, pinned)');
    echo "added pinned\n";
}

if (!dekk_has_column('dekk_comments', 'reply_to_user')) {
    sql('ALTER TABLE dekk_comments ADD COLUMN reply_to_user BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER parent_id');
    echo "added reply_to_user\n";
}

// 消息中心表
require_once __DIR__ . '/notices.php';
dekk_notice_migrate();
echo "dekk_notices ready\n";

$rows = (int)sql('SELECT COUNT(*) FROM dekk_comments')->fetchColumn();
$cols = sql('SHOW COLUMNS FROM dekk_comments')->fetchAll();
$names = [];
foreach ($cols as $col) $names[] = $col['Field'];
echo "dekk_comments ready, rows={$rows}, columns=".implode(',', $names)."\n";