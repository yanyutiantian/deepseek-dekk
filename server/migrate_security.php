<?php
declare(strict_types=1);
/**
 * 安全增强迁移：蜜罐 / 防重放 / IP 封禁 三张表。
 *
 * 访问：https://dekk.example.com/API/?action=... 之外，
 * 直接跑：php <后端配置目录>/migrate_security.php
 * 可重复执行（IF NOT EXISTS / 已存在列会跳过）。
 */

require_once __DIR__ . '/bootstrap.php';

$steps = [];
try {
    // ── 1. 蜜罐命中记录 ──
    // 正常客户端永远不会请求这些 action / 路径，命中即恶意，零误伤。
    sql("CREATE TABLE IF NOT EXISTS dekk_honeypot (
        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        ip VARCHAR(45) NOT NULL,
        kind VARCHAR(24) NOT NULL COMMENT 'action / path / replay / unknown_action',
        target VARCHAR(190) NOT NULL,
        uri VARCHAR(500) NOT NULL DEFAULT '',
        ua VARCHAR(255) NOT NULL DEFAULT '',
        created_at DATETIME NOT NULL,
        PRIMARY KEY (id),
        KEY idx_ip_time (ip, created_at),
        KEY idx_created (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='蜜罐命中'");
    $steps[] = 'dekk_honeypot';

    // ── 2. 防重放：同一 nonce 计数 ──
    // nonce 存 sha256（统一 64 字符，避免超长/异常字符撑表）。
    // 首次 hits=1；第 2 次容忍（网络重发）；第 3 次起拦截并封 IP。
    sql("CREATE TABLE IF NOT EXISTS dekk_replay (
        nonce CHAR(64) NOT NULL,
        ip VARCHAR(45) NOT NULL,
        hits INT UNSIGNED NOT NULL DEFAULT 1,
        first_at DATETIME NOT NULL,
        last_at DATETIME NOT NULL,
        PRIMARY KEY (nonce),
        KEY idx_last (last_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信封 nonce 计数（防重放）'");
    $steps[] = 'dekk_replay';

    // ── 3. IP 封禁 ──
    sql("CREATE TABLE IF NOT EXISTS dekk_bans (
        ip VARCHAR(45) NOT NULL,
        until DATETIME NOT NULL,
        reason VARCHAR(64) NOT NULL,
        created_at DATETIME NOT NULL,
        PRIMARY KEY (ip),
        KEY idx_until (`until`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP 封禁'");
    $steps[] = 'dekk_bans';

    // ── 4. 人机验证 challenge 单次使用 ──
    // 防「算一次 PoW 就无限刷 cookie」：verify 时插主键，冲突即已用过。
    sql("CREATE TABLE IF NOT EXISTS dekk_gate_used (
        ckey CHAR(64) NOT NULL,
        ip VARCHAR(45) NOT NULL,
        used_at DATETIME NOT NULL,
        PRIMARY KEY (ckey),
        KEY idx_used (used_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人机验证 challenge 单次使用'");
    $steps[] = 'dekk_gate_used';

    // ── 5. 图片验证码答案（答案只存服务端，图片本身不含任何明文/可逆编码）──
    sql("CREATE TABLE IF NOT EXISTS dekk_gate_captcha (
        ckey CHAR(64) NOT NULL,
        answer CHAR(4) NOT NULL,
        tries TINYINT UNSIGNED NOT NULL DEFAULT 0,
        ip VARCHAR(45) NOT NULL,
        created_at DATETIME NOT NULL,
        PRIMARY KEY (ckey),
        KEY idx_created (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图片验证码答案'");
    $steps[] = 'dekk_gate_captcha';

    // 自检：确认可写
    sql("INSERT INTO dekk_replay(nonce,ip,hits,first_at,last_at)
         VALUES(?,?,1,UTC_TIMESTAMP(),UTC_TIMESTAMP())
         ON DUPLICATE KEY UPDATE hits=hits+1", [hash('sha256', '__selftest__'), '127.0.0.1']);
    $selftest = sql("SELECT hits FROM dekk_replay WHERE nonce=?", [hash('sha256', '__selftest__')])->fetchColumn();
    sql("DELETE FROM dekk_replay WHERE nonce=?", [hash('sha256', '__selftest__')]);

    echo json_encode([
        'ok' => true,
        'created' => $steps,
        'replay_selftest_hits' => (int) $selftest,
        'note' => 'selftest_hits 应为 1（INSERT）或 2（重复执行时 ON DUPLICATE 自增）',
    ], JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT), "\n";
} catch (Throwable $e) {
    echo json_encode(['ok' => false, 'msg' => $e->getMessage(), 'steps' => $steps], JSON_UNESCAPED_UNICODE), "\n";
}