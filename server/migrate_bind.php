<?php
declare(strict_types=1);

/**
 * 一次性迁移：dekk_users 增加 KOOK 绑定字段 + 建 dekk_kook_codes 表。
 *
 * 部署：<后端配置目录>/migrate_bind.php（与 migrate_backups.php 同级）
 * 访问：https://dekk.example.com/API/?action=__migrate_bind
 */

require_once __DIR__ . '/bootstrap.php';

header('Content-Type: application/json; charset=utf-8');
$steps = [];

try {
    // ── 1. dekk_users 绑定字段 ──
    $columns = [];
    foreach (sql('SHOW COLUMNS FROM dekk_users')->fetchAll() as $column) {
        $columns[$column['Field']] = true;
    }
    $add = [];
    if (!isset($columns['kook_user_id'])) {
        $add[] = "ADD COLUMN kook_user_id VARCHAR(20) NOT NULL DEFAULT ''";
    }
    if (!isset($columns['kook_name'])) {
        $add[] = "ADD COLUMN kook_name VARCHAR(60) NOT NULL DEFAULT ''";
    }
    if (!isset($columns['kook_bound_at'])) {
        $add[] = 'ADD COLUMN kook_bound_at DATETIME NULL';
    }
    if ($add) {
        sql('ALTER TABLE dekk_users ' . implode(', ', $add));
        $steps[] = 'dekk_users: ' . implode(' | ', $add);
    } else {
        $steps[] = 'dekk_users: KOOK 字段已存在，跳过';
    }

    // ── 2. 绑定码表 ──
    sql("CREATE TABLE IF NOT EXISTS dekk_kook_codes (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        code CHAR(6) NOT NULL,
        user_id BIGINT UNSIGNED NOT NULL,
        kook_user_id VARCHAR(20) NOT NULL DEFAULT '',
        kook_name VARCHAR(60) NOT NULL DEFAULT '',
        created_at DATETIME NOT NULL,
        expires_at DATETIME NOT NULL,
        used TINYINT(1) NOT NULL DEFAULT 0,
        UNIQUE KEY uniq_kook_code (code),
        KEY idx_kook_user (user_id),
        KEY idx_kook_exp (expires_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $steps[] = 'dekk_kook_codes: 已就绪';

    echo json_encode(['ok' => true, 'steps' => $steps], JSON_UNESCAPED_UNICODE);
} catch (Throwable $e) {
    http_response_code(500);
    echo json_encode(['ok' => false, 'message' => $e->getMessage(), 'steps' => $steps], JSON_UNESCAPED_UNICODE);
}