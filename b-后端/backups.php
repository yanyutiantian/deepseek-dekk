<?php
declare(strict_types=1);

/** Encrypted conversation backup blobs. The server never decrypts payload. */
function backupRoute(string $action, array $a): void {
    if ($action === 'backup_put') {
        $u = user();
        limit('backup:'.$u['id'], 3600, 5); // 防刷：单小时最多 5 次（正常的业务限制见下）
        // 业务限制：每个账号每个自然日最多 3 个备份。
        // 用 DATE(created_at)=CURDATE() 与写入时区保持一致（created_at 由 MySQL 默认值填充）。
        $today = sql('SELECT COUNT(*) AS c FROM dekk_backups WHERE user_id=? AND DATE(created_at)=CURDATE()', [$u['id']])->fetch();
        if ($today && (int)$today['c'] >= 3) {
            fail('每个账号每天最多上传 3 个备份，请明天再试', 429);
        }
        $blob = text($a, 'blob', 1, 500000);
        $hash = text($a, 'blob_hash', 64, 64);
        $codeHash = text($a, 'code_hash', 64, 64);
        if (!hash_equals(hash('sha256', $blob), $hash)) fail('备份校验失败');
        $id = bin2hex(random_bytes(16));
        $expires = gmdate('Y-m-d H:i:s', time() + 86400);
        sql('INSERT INTO dekk_backups(id,user_id,`blob`,blob_hash,code_hash,expires_at) VALUES(?,?,?,?,?,?)',
            [$id, $u['id'], $blob, $hash, $codeHash, $expires]);
        respond(['ok'=>true,'id'=>$id,'expires_at'=>$expires]);
    }
    if ($action === 'backup_get') {
        limit('backup-get:'.($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 60, 30);
        $id = text($a, 'id', 32, 32);
        $codeHash = text($a, 'code_hash', 64, 64);
        sql('DELETE FROM dekk_backups WHERE expires_at<=UTC_TIMESTAMP()');
        $row = sql('SELECT `blob`,blob_hash,expires_at FROM dekk_backups WHERE id=? AND code_hash=? AND expires_at>UTC_TIMESTAMP()', [$id,$codeHash])->fetch();
        // 用 400 而不是 404：nginx 会把 PHP 的 404 替换成自己的 HTML 错误页，
        // 客户端就拿不到 JSON 提示了（会误报成“网络请求失败”）。
        if (!$row) fail('备份链接或授权码无效，或备份已过期', 400);
        respond(['ok'=>true,'blob'=>$row['blob'],'blob_hash'=>$row['blob_hash'],'expires_at'=>$row['expires_at']]);
    }
    if ($action === 'backup_delete') {
        $u = user();
        $id = text($a, 'id', 32, 32);
        sql('DELETE FROM dekk_backups WHERE id=? AND user_id=?', [$id,$u['id']]);
        respond(['ok'=>true]);
    }
}