<?php
declare(strict_types=1);

/**
 * 一次性迁移：建 panel_tokens 表 + 给 dekk_users 加封禁字段。
 *
 * 访问：https://dekk.example.com/panel/migrate_panel.php?key=<内部密钥>
 * 可重复执行（已存在的表/列会跳过）。
 */
require __DIR__ . '/lib.php';

$key = $_POST['key'] ?? $_GET['key'] ?? '';
if (!hash_equals(PANEL_INTERNAL_KEY, (string) $key)) {
    pfail('内部密钥错误', 403);
}

$steps = [];

try {
    // ── 1. 面板 token 表 ──
    pdb()->exec("CREATE TABLE IF NOT EXISTS panel_tokens (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        token_hash CHAR(64) NOT NULL,
        kook_user_id VARCHAR(20) NOT NULL DEFAULT '',
        kook_name VARCHAR(60) NOT NULL DEFAULT '',
        channel_id VARCHAR(20) NOT NULL DEFAULT '',
        created_at DATETIME NOT NULL,
        expires_at DATETIME NOT NULL,
        revoked TINYINT(1) NOT NULL DEFAULT 0,
        UNIQUE KEY uniq_panel_token (token_hash),
        KEY idx_panel_kook (kook_user_id),
        KEY idx_panel_exp (expires_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $steps[] = 'panel_tokens: 已就绪';

    // ── 2. dekk_users 封禁字段 ──
    $columns = [];
    foreach (pdb()->query('SHOW COLUMNS FROM dekk_users')->fetchAll() as $column) {
        $columns[$column['Field']] = true;
    }
    $add = [];
    if (!isset($columns['banned_until'])) {
        $add[] = 'ADD COLUMN banned_until DATETIME NULL';
    }
    if (!isset($columns['banned_at'])) {
        $add[] = 'ADD COLUMN banned_at DATETIME NULL';
    }
    if (!isset($columns['ban_reason'])) {
        $add[] = "ADD COLUMN ban_reason VARCHAR(255) NOT NULL DEFAULT ''";
    }
    if ($add) {
        pdb()->exec('ALTER TABLE dekk_users ' . implode(', ', $add));
        $steps[] = 'dekk_users: ' . implode(' | ', $add);
    } else {
        $steps[] = 'dekk_users: 封禁字段已存在，跳过';
    }

    plog_panel('MIGRATE ' . json_encode($steps, JSON_UNESCAPED_UNICODE));
    pjson(['ok' => true, 'steps' => $steps]);
} catch (Throwable $e) {
    plog_panel('MIGRATE 失败 ' . $e->getMessage());
    pjson(['ok' => false, 'message' => $e->getMessage(), 'steps' => $steps], 500);
}