<?php
declare(strict_types=1);

/**
 * 一次性迁移：给 dekk_users 加赞助字段 + 建 pay_orders 表。
 * 访问：https://pay.example.com/migrate_pay.php?key=<内部密钥>
 * 可重复执行（已存在的列/表会跳过）。
 */
require __DIR__ . '/lib.php';

require_internal_key();

$steps = [];

try {
    // ── 1. dekk_users 加 sponsor / sponsor_at ──
    $existing = [];
    foreach (db()->query('SHOW COLUMNS FROM dekk_users')->fetchAll() as $column) {
        $existing[$column['Field']] = true;
    }
    if (!isset($existing['sponsor'])) {
        db()->exec('ALTER TABLE dekk_users ADD COLUMN sponsor TINYINT(1) NOT NULL DEFAULT 0, ADD COLUMN sponsor_at DATETIME NULL');
        $steps[] = 'dekk_users: 已新增 sponsor, sponsor_at';
    } else {
        $steps[] = 'dekk_users: sponsor 已存在，跳过';
    }

    // ── 2. pay_orders 订单表 ──
    db()->exec("CREATE TABLE IF NOT EXISTS pay_orders (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        out_trade_no CHAR(32) NOT NULL,
        kook_user_id VARCHAR(20) NOT NULL DEFAULT '',
        email VARCHAR(100) NOT NULL,
        user_id BIGINT UNSIGNED NOT NULL,
        money DECIMAL(10,2) NOT NULL,
        status ENUM('pending','paid','expired') NOT NULL DEFAULT 'pending',
        trade_no VARCHAR(64) NOT NULL DEFAULT '',
        channel_id VARCHAR(20) NOT NULL DEFAULT '',
        message_id VARCHAR(64) NOT NULL DEFAULT '',
        role_granted TINYINT(1) NOT NULL DEFAULT 0,
        role_granted_at DATETIME NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        expires_at DATETIME NOT NULL,
        paid_at DATETIME NULL,
        UNIQUE KEY uniq_out_trade_no (out_trade_no),
        KEY idx_email (email),
        KEY idx_kook_status (kook_user_id, status),
        KEY idx_role (status, role_granted)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $steps[] = 'pay_orders: 已就绪';

    // ── 4. pay_settings 键值表（目前存 money：可在 KOOK 里用 /setpay 修改）──
    db()->exec("CREATE TABLE IF NOT EXISTS pay_settings (
        k VARCHAR(32) NOT NULL PRIMARY KEY,
        v VARCHAR(255) NOT NULL DEFAULT ''
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $steps[] = 'pay_settings: 已就绪';

    // ── 3. 旧表补 channel_id / message_id（老库创建时没有这两列）──
    $orderColumns = [];
    foreach (db()->query('SHOW COLUMNS FROM pay_orders')->fetchAll() as $column) {
        $orderColumns[$column['Field']] = true;
    }
    if (!isset($orderColumns['channel_id'])) {
        db()->exec("ALTER TABLE pay_orders ADD COLUMN channel_id VARCHAR(20) NOT NULL DEFAULT '', "
            . "ADD COLUMN message_id VARCHAR(64) NOT NULL DEFAULT ''");
        $steps[] = 'pay_orders: 已补 channel_id, message_id';
    } else {
        $steps[] = 'pay_orders: channel_id/message_id 已存在，跳过';
    }

    json_out(['ok' => true, 'steps' => $steps]);
} catch (Throwable $e) {
    plog('migrate_pay: 失败 ' . $e->getMessage());
    json_out(['ok' => false, 'msg' => $e->getMessage(), 'steps' => $steps]);
}