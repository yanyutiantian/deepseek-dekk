<?php
declare(strict_types=1);

/**
 * 后端主配置示例。
 *
 * 部署时复制为 config.php 并填写：
 *     cp config.example.php config.php
 * config.php 已在 .gitignore 中，不会被提交。
 *
 * config.php 建议放在 Web 根目录之外（线上原始部署位置是 <后端配置目录>/），
 * bootstrap.php 通过 __DIR__ 定位它，两者放在同一目录即可。
 */
return [
    // 数据库
    'dsn'         => 'mysql:host=127.0.0.1;port=3306;dbname=dekk;charset=utf8mb4',
    'db_user'     => 'dekk_user',
    'db_password' => 'YOUR_DB_PASSWORD',

    // 客户端 <-> 服务端对称加密密钥，必须与模块里的 module_secret 一致
    'module_secret' => 'CHANGE_ME_MODULE_SECRET',

    // 人机验证关卡（gate）签名密钥，自行随机生成
    'gate_secret' => 'CHANGE_ME_GATE_SECRET',

    // 发验证码用的 SMTP（QQ 邮箱 + 授权码）
    'smtp_user'     => 'you@qq.com',
    'smtp_password' => 'YOUR_SMTP_AUTH_CODE',

    // 公开内容 AI 审核接口的 API Key（不填则跳过 AI 审核）
    'review_apikey' => 'YOUR_REVIEW_API_KEY',
];
