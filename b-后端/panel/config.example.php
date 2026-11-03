<?php
declare(strict_types=1);

/**
 * 面板自带的数据库配置。
 *
 * 为什么不直接 require <后端配置目录>/config.php：
 * pay 站点开启了 open_basedir（限制在本站点目录内），跨目录会被拒绝。
 */
return [
    'dsn' => 'mysql:host=127.0.0.1;port=3306;dbname=dekk;charset=utf8mb4',
    'db_user' => 'dekk_user',
    'db_password' => 'YOUR_DB_PASSWORD',

    // 机器人创建面板时用的内部密钥（原先硬编码在 lib.php 里）
    'internal_key' => 'YOUR_PANEL_INTERNAL_KEY',
];