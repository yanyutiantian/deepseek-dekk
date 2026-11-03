<?php
declare(strict_types=1);

/**
 * 临时管理面板 —— 数据与管理接口。
 *
 * 所有请求都必须带有效面板 token（t）；
 * 「搜索 / 查看」不写日志，「管理动作」（封禁/解封/改密/建号）写入 panel.log。
 */
require __DIR__ . '/lib.php';

$panel = panel_token(true);
$actor = panel_actor($panel);
$action = trim((string) ($_POST['action'] ?? $_GET['action'] ?? ''));
$keyword = trim((string) ($_POST['q'] ?? $_GET['q'] ?? ''));
list($page, $size, $offset) = pager();

/** 关键字查询的 LIKE 参数。 */
$like = '%' . $keyword . '%';

switch ($action) {

    // ─────────────────────────── 人设 ───────────────────────────

    case 'personas': {
        $where = "p.visibility='public'";
        $args = [];
        if ($keyword !== '') {
            $where .= ' AND (p.name LIKE ? OR p.description LIKE ?)';
            $args[] = $like;
            $args[] = $like;
        }
        $stmt = pdb()->prepare("SELECT COUNT(*) FROM dekk_personas p WHERE {$where}");
        $stmt->execute($args);
        $total = (int) $stmt->fetchColumn();

        $stmt = pdb()->prepare("SELECT p.id,p.user_id,p.name,p.description,p.visibility,p.created_at,p.updated_at,"
            . "u.nickname,u.email FROM dekk_personas p LEFT JOIN dekk_users u ON u.id=p.user_id "
            . "WHERE {$where} ORDER BY p.id DESC LIMIT {$size} OFFSET {$offset}");
        $stmt->execute($args);
        pjson(['ok' => true, 'total' => $total, 'page' => $page, 'size' => $size, 'items' => $stmt->fetchAll()]);
    }

    case 'persona': {
        $id = (int) ($_POST['id'] ?? $_GET['id'] ?? 0);
        $stmt = pdb()->prepare('SELECT p.*,u.nickname,u.email FROM dekk_personas p '
            . 'LEFT JOIN dekk_users u ON u.id=p.user_id WHERE p.id=? LIMIT 1');
        $stmt->execute([$id]);
        $row = $stmt->fetch();
        if (!$row) {
            pfail('人设不存在', 404);
        }
        pjson(['ok' => true, 'item' => $row]);
    }

    // ─────────────────────────── 插件 ───────────────────────────

    case 'plugins': {
        $where = "p.visibility='public'";
        $args = [];
        if ($keyword !== '') {
            $where .= ' AND (p.name LIKE ? OR p.description LIKE ?)';
            $args[] = $like;
            $args[] = $like;
        }
        $stmt = pdb()->prepare("SELECT COUNT(*) FROM dekk_plugins p WHERE {$where}");
        $stmt->execute($args);
        $total = (int) $stmt->fetchColumn();

        $stmt = pdb()->prepare("SELECT p.id,p.user_id,p.name,p.description,p.visibility,p.created_at,p.updated_at,"
            . "u.nickname,u.email FROM dekk_plugins p LEFT JOIN dekk_users u ON u.id=p.user_id "
            . "WHERE {$where} ORDER BY p.id DESC LIMIT {$size} OFFSET {$offset}");
        $stmt->execute($args);
        pjson(['ok' => true, 'total' => $total, 'page' => $page, 'size' => $size, 'items' => $stmt->fetchAll()]);
    }

    case 'plugin': {
        $id = (int) ($_POST['id'] ?? $_GET['id'] ?? 0);
        $stmt = pdb()->prepare('SELECT p.*,u.nickname,u.email FROM dekk_plugins p '
            . 'LEFT JOIN dekk_users u ON u.id=p.user_id WHERE p.id=? LIMIT 1');
        $stmt->execute([$id]);
        $row = $stmt->fetch();
        if (!$row) {
            pfail('插件不存在', 404);
        }
        pjson(['ok' => true, 'item' => $row]);
    }

    // ─────────────────────────── 用户 ───────────────────────────

    case 'users': {
        $where = '1=1';
        $args = [];
        if ($keyword !== '') {
            $where .= ' AND (u.email LIKE ? OR u.nickname LIKE ? OR CAST(u.id AS CHAR) LIKE ?)';
            $args[] = $like;
            $args[] = $like;
            $args[] = $like;
        }
        $stmt = pdb()->prepare("SELECT COUNT(*) FROM dekk_users u WHERE {$where}");
        $stmt->execute($args);
        $total = (int) $stmt->fetchColumn();

        $stmt = pdb()->prepare("SELECT u.id,u.email,u.nickname,u.admin,u.sponsor,u.sponsor_at,u.created_at,"
            . "u.kook_user_id,u.kook_name,u.kook_bound_at,"
            . "u.banned_until,u.banned_at,u.ban_reason,"
            . "(SELECT COUNT(*) FROM dekk_tokens t WHERE t.user_id=u.id AND t.expires_at>UTC_TIMESTAMP()) AS tokens,"
            . "(SELECT COUNT(*) FROM dekk_personas p WHERE p.user_id=u.id) AS personas,"
            . "(SELECT COUNT(*) FROM dekk_plugins g WHERE g.user_id=u.id) AS plugins "
            . "FROM dekk_users u WHERE {$where} ORDER BY u.id DESC LIMIT {$size} OFFSET {$offset}");
        $stmt->execute($args);
        pjson(['ok' => true, 'total' => $total, 'page' => $page, 'size' => $size, 'items' => $stmt->fetchAll()]);
    }

    case 'user': {
        $id = (int) ($_POST['id'] ?? $_GET['id'] ?? 0);
        $stmt = pdb()->prepare("SELECT u.id,u.email,u.nickname,u.admin,u.sponsor,u.sponsor_at,u.created_at,u.updated_at,"
            . "u.kook_user_id,u.kook_name,u.kook_bound_at,"
            . "u.banned_until,u.banned_at,u.ban_reason,"
            . "(SELECT COUNT(*) FROM dekk_tokens t WHERE t.user_id=u.id AND t.expires_at>UTC_TIMESTAMP()) AS tokens,"
            . "(SELECT COUNT(*) FROM dekk_personas p WHERE p.user_id=u.id) AS personas,"
            . "(SELECT COUNT(*) FROM dekk_plugins g WHERE g.user_id=u.id) AS plugins "
            . "FROM dekk_users u WHERE u.id=? LIMIT 1");
        $stmt->execute([$id]);
        $row = $stmt->fetch();
        if (!$row) {
            pfail('用户不存在', 404);
        }
        pjson(['ok' => true, 'item' => $row]);
    }

    // ─────────────────────────── 管理动作 ───────────────────────────

    case 'ban': {
        $id = (int) ($_POST['id'] ?? 0);
        $duration = trim((string) ($_POST['duration'] ?? ''));
        $reason = trim((string) ($_POST['reason'] ?? ''));
        if ($id <= 0) {
            pfail('缺少用户 ID');
        }
        $until = ban_until($duration);
        if ($until === null) {
            pfail('封禁时长不合法（12h / 24h / 7d / 30d / forever）');
        }
        if (mb_strlen($reason) > 100) {
            pfail('备注过长');
        }
        // ① 写入封禁时间
        pdb()->prepare('UPDATE dekk_users SET banned_until=?, banned_at=NOW(), ban_reason=? WHERE id=?')
            ->execute([$until, $reason, $id]);
        // ② 立刻清除该账号所有 Token（已登录的会话立刻失效）
        $stmt = pdb()->prepare('DELETE FROM dekk_tokens WHERE user_id=?');
        $stmt->execute([$id]);
        $cleared = $stmt->rowCount();

        audit($actor, 'BAN', 'user_id=' . $id . ' duration=' . $duration . ' until=' . $until
            . ' tokens_cleared=' . $cleared . ($reason !== '' ? ' reason=' . $reason : ''));
        pjson(['ok' => true, 'until' => $until, 'tokens_cleared' => $cleared]);
    }

    case 'unban': {
        $id = (int) ($_POST['id'] ?? 0);
        if ($id <= 0) {
            pfail('缺少用户 ID');
        }
        pdb()->prepare("UPDATE dekk_users SET banned_until=NULL, banned_at=NULL, ban_reason='' WHERE id=?")
            ->execute([$id]);
        audit($actor, 'UNBAN', 'user_id=' . $id);
        pjson(['ok' => true]);
    }

    case 'set_password': {
        $id = (int) ($_POST['id'] ?? 0);
        $pw = (string) ($_POST['password'] ?? '');
        if ($id <= 0) {
            pfail('缺少用户 ID');
        }
        if (strlen($pw) < 8 || strlen($pw) > 12) {
            pfail('密码长度需在 8~12 位之间');
        }
        pdb()->prepare('UPDATE dekk_users SET password_hash=? WHERE id=?')
            ->execute([password_hash($pw, PASSWORD_DEFAULT), $id]);
        // 改密后让旧登录全部失效
        $stmt = pdb()->prepare('DELETE FROM dekk_tokens WHERE user_id=?');
        $stmt->execute([$id]);
        // 日志里绝不写密码原文
        audit($actor, 'SET_PASSWORD', 'user_id=' . $id . ' tokens_cleared=' . $stmt->rowCount());
        pjson(['ok' => true, 'tokens_cleared' => $stmt->rowCount()]);
    }

    case 'set_sponsor': {
        $id = (int) ($_POST['id'] ?? 0);
        $sponsor = (string) ($_POST['sponsor'] ?? '');
        if ($id <= 0) {
            pfail('缺少用户 ID');
        }
        if ($sponsor !== '0' && $sponsor !== '1') {
            pfail('参数不合法');
        }
        if ($sponsor === '1') {
            pdb()->prepare('UPDATE dekk_users SET sponsor=1, sponsor_at=NOW() WHERE id=?')->execute([$id]);
        } else {
            pdb()->prepare('UPDATE dekk_users SET sponsor=0, sponsor_at=NULL WHERE id=?')->execute([$id]);
        }
        audit($actor, $sponsor === '1' ? 'SET_SPONSOR' : 'UNSET_SPONSOR', 'user_id=' . $id);
        pjson(['ok' => true]);
    }

    // ─────────────────────────── 评论 ───────────────────────────

    case 'comments': {
        $kind = strtolower(trim((string) ($_POST['kind'] ?? $_GET['kind'] ?? '')));
        if ($kind !== 'persona' && $kind !== 'plugin') {
            pfail('kind 不合法');
        }
        $id = (int) ($_POST['id'] ?? $_GET['id'] ?? 0);
        if ($id <= 0) {
            pfail('缺少内容 ID');
        }
        $stmt = pdb()->prepare('SELECT COUNT(*) FROM dekk_comments WHERE kind=? AND target_id=?');
        $stmt->execute([$kind, $id]);
        $total = (int) $stmt->fetchColumn();

        // 顶层评论（置顶在前，最多 100 条）
        $stmt = pdb()->prepare('SELECT c.id,c.user_id,c.parent_id,c.pinned,c.reply_to_user,c.content,c.created_at,'
            . 'u.nickname FROM dekk_comments c LEFT JOIN dekk_users u ON u.id=c.user_id '
            . 'WHERE c.kind=? AND c.target_id=? AND c.parent_id=0 ORDER BY c.pinned DESC, c.id DESC LIMIT 100');
        $stmt->execute([$kind, $id]);
        $tops = $stmt->fetchAll();

        // 回复：挂到各自的顶层评论下（每条最多 20 条）
        $replies = [];
        if ($tops) {
            $ids = [];
            foreach ($tops as $t) {
                $ids[] = (int) $t['id'];
            }
            $marks = implode(',', array_fill(0, count($ids), '?'));
            $stmt = pdb()->prepare('SELECT c.id,c.user_id,c.parent_id,c.reply_to_user,c.content,c.created_at,'
                . 'u.nickname FROM dekk_comments c LEFT JOIN dekk_users u ON u.id=c.user_id '
                . "WHERE c.parent_id IN ({$marks}) ORDER BY c.id ASC");
            $stmt->execute($ids);
            foreach ($stmt->fetchAll() as $r) {
                $p = (int) $r['parent_id'];
                if (count($replies[$p] ?? []) < 20) {
                    $replies[$p][] = $r;
                }
            }
        }

        $items = [];
        foreach ($tops as $t) {
            $tid = (int) $t['id'];
            $items[] = [
                'id' => $tid,
                'user_id' => (int) $t['user_id'],
                'nickname' => (string) $t['nickname'],
                'content' => (string) $t['content'],
                'created_at' => (string) $t['created_at'],
                'pinned' => (int) $t['pinned'] === 1,
                'replies' => array_map(static function (array $r): array {
                    return [
                        'id' => (int) $r['id'],
                        'nickname' => (string) $r['nickname'],
                        'content' => (string) $r['content'],
                        'created_at' => (string) $r['created_at'],
                    ];
                }, $replies[$tid] ?? []),
            ];
        }

        $stmt = pdb()->prepare('SELECT COUNT(*) FROM dekk_comments WHERE kind=? AND target_id=? AND pinned=1');
        $stmt->execute([$kind, $id]);
        pjson([
            'ok' => true,
            'total' => $total,
            'items' => $items,
            'pinned_count' => (int) $stmt->fetchColumn(),
            'max_pin' => 3,
        ]);
    }

    case 'comment_pin': {
        $cid = (int) ($_POST['id'] ?? 0);
        $on = ((string) ($_POST['on'] ?? '1')) === '1' ? 1 : 0;
        if ($cid <= 0) {
            pfail('缺少评论 ID');
        }
        $stmt = pdb()->prepare('SELECT kind,target_id,parent_id,pinned FROM dekk_comments WHERE id=? LIMIT 1');
        $stmt->execute([$cid]);
        $row = $stmt->fetch();
        if (!$row) {
            pfail('评论不存在', 404);
        }
        if ($on === 1 && (int) $row['parent_id'] !== 0) {
            pfail('只能置顶顶层评论');
        }
        if ($on === 1 && (int) $row['pinned'] !== 1) {
            $stmt = pdb()->prepare('SELECT COUNT(*) FROM dekk_comments WHERE kind=? AND target_id=? AND pinned=1');
            $stmt->execute([$row['kind'], (int) $row['target_id']]);
            if ((int) $stmt->fetchColumn() >= 3) {
                pfail('最多置顶 3 条评论');
            }
        }
        pdb()->prepare('UPDATE dekk_comments SET pinned=? WHERE id=?')->execute([$on, $cid]);
        audit($actor, $on === 1 ? 'PIN_COMMENT' : 'UNPIN_COMMENT',
            'comment_id=' . $cid . ' kind=' . $row['kind'] . ' target_id=' . (int) $row['target_id']);
        pjson(['ok' => true, 'pinned' => $on === 1]);
    }

    case 'comment_delete': {
        $cid = (int) ($_POST['id'] ?? 0);
        if ($cid <= 0) {
            pfail('缺少评论 ID');
        }
        $stmt = pdb()->prepare('SELECT kind,target_id,content FROM dekk_comments WHERE id=? LIMIT 1');
        $stmt->execute([$cid]);
        $row = $stmt->fetch();
        if (!$row) {
            pfail('评论不存在', 404);
        }
        // 顶层评论连它的回复一起删（通知也一起清）
        $stmt = pdb()->prepare('SELECT id FROM dekk_comments WHERE id=? OR parent_id=?');
        $stmt->execute([$cid, $cid]);
        $kids = $stmt->fetchAll();
        foreach ($kids as $kid) {
            pdb()->prepare('DELETE FROM dekk_notices WHERE comment_id=?')->execute([(int) $kid['id']]);
        }
        pdb()->prepare('DELETE FROM dekk_comments WHERE id=? OR parent_id=?')->execute([$cid, $cid]);
        audit($actor, 'DELETE_COMMENT', 'comment_id=' . $cid . ' kind=' . $row['kind']
            . ' target_id=' . (int) $row['target_id'] . ' deleted=' . count($kids)
            . ' content=' . mb_substr((string) $row['content'], 0, 30));
        pjson(['ok' => true, 'deleted' => count($kids)]);
    }

    // ─────────────────────── 上下架（可见性） ───────────────────────

    case 'set_visibility': {
        $kind = strtolower(trim((string) ($_POST['kind'] ?? $_GET['kind'] ?? '')));
        $id = (int) ($_POST['id'] ?? 0);
        $visibility = (string) ($_POST['visibility'] ?? '');
        if ($kind !== 'persona' && $kind !== 'plugin') {
            pfail('kind 不合法');
        }
        if ($id <= 0) {
            pfail('缺少内容 ID');
        }
        if ($visibility !== 'public' && $visibility !== 'private') {
            pfail('visibility 不合法');
        }
        $table = $kind === 'persona' ? 'dekk_personas' : 'dekk_plugins';
        pdb()->prepare("UPDATE {$table} SET visibility=? WHERE id=?")->execute([$visibility, $id]);
        audit($actor, $visibility === 'private' ? 'UNPUBLISH' : 'PUBLISH',
            'kind=' . $kind . ' id=' . $id);
        pjson(['ok' => true, 'visibility' => $visibility]);
    }

    case 'create_user': {
        $email = strtolower(trim((string) ($_POST['email'] ?? '')));
        $nick = trim((string) ($_POST['nickname'] ?? ''));
        $pw = (string) ($_POST['password'] ?? '');
        if (!preg_match('/^[1-9][0-9]{4,19}@qq\.com$/D', $email)) {
            pfail('邮箱格式：仅支持数字 QQ 邮箱，例如 123456@qq.com');
        }
        if ($nick === '' || mb_strlen($nick) > 30) {
            pfail('昵称需为 1~30 个字符');
        }
        if (strlen($pw) < 8 || strlen($pw) > 12) {
            pfail('密码长度需在 8~12 位之间');
        }
        $stmt = pdb()->prepare('SELECT id FROM dekk_users WHERE email=? LIMIT 1');
        $stmt->execute([$email]);
        if ($stmt->fetch()) {
            pfail('该邮箱已注册', 409);
        }
        pdb()->prepare('INSERT INTO dekk_users(email,password_hash,nickname) VALUES(?,?,?)')
            ->execute([$email, password_hash($pw, PASSWORD_DEFAULT), $nick]);
        $newId = (int) pdb()->lastInsertId();
        audit($actor, 'CREATE_USER', 'user_id=' . $newId . ' email=' . $email . ' nickname=' . $nick);
        pjson(['ok' => true, 'id' => $newId]);
    }

    default:
        pfail('未知操作', 404);
}