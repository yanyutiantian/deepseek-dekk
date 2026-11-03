<?php
declare(strict_types=1);

/**
 * 自助赞助站点配置（https://pay.example.com → /var/www/pay.example.com）。
 * 凭据只写在这一个文件里，其它文件统一用 cfg() 读取。
 */
return [
    // ── dekk 用户库（与 <后端配置目录>/ 用同一个库）──
    'dsn' => 'mysql:host=127.0.0.1;port=3306;dbname=dekk;charset=utf8mb4',
    'db_user' => 'dekk_user',
    'db_password' => 'YOUR_DB_PASSWORD',

    // ── 易支付（ypay.net.cn）──
    'epay_api' => 'https://ypay.net.cn/',
    'epay_pid' => 'YOUR_EPAY_PID',
    'epay_key' => 'YOUR_EPAY_KEY',
    'epay_type' => 'alipay',              // 目前只支持支付宝

    // ── 赞助参数 ──
    // 金额只在服务端决定，不接受外部传参（防篡改）
    'money' => '20.00',
    'goods_name' => 'dekk 赞助支持（20 元）',
    'expire_seconds' => 300,              // 充值链接 5 分钟有效

    // ── 站点 ──
    'site' => 'https://pay.example.com',
    'internal_key' => 'YOUR_INTERNAL_KEY',   // 机器人 ↔ 本站内部接口密钥

    'log' => __DIR__ . '/pay.log',
];
