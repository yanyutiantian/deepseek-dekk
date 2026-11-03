<?php
/**
 * 聊天记录迁移表（可重复执行）：
 * dekk_backups 保存加密后的备份密文，服务端不持有明文与授权码本身（只存授权码 SHA-256）。
 * 备份超过 24 小时在读取时清理。
 * 用法：php migrate_backups.php
 */
require __DIR__ . '/bootstrap.php';
sql('CREATE TABLE IF NOT EXISTS dekk_backups (
        id CHAR(32) PRIMARY KEY,
        user_id BIGINT UNSIGNED NOT NULL,
        `blob` MEDIUMTEXT NOT NULL,
        blob_hash CHAR(64) NOT NULL,
        code_hash CHAR(64) NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        expires_at DATETIME NOT NULL,
        INDEX(code_hash),
        INDEX(expires_at),
        FOREIGN KEY(user_id) REFERENCES dekk_users(id) ON DELETE CASCADE
     ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
echo "table ready: dekk_backups\n";
$row=sql("SHOW COLUMNS FROM dekk_backups")->fetchAll();
echo "columns: ".implode(',',array_map(function($c){return $c['Field'];},$row))."\n";