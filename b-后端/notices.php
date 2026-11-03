<?php
declare(strict_types=1);
// dekk_owner_id() / dekk_avatar_url() 在 profile.php 里（独立引入，避免依赖入口的加载顺序）
require_once __DIR__ . '/profile.php';

/**
 * 消息中心（抖音式红点）：别人评论了我的人设/插件、或回复了我的评论，就给我记一条。
 *
 * - 表 dekk_notices：user_id（收件人）、actor_id（谁）、kind（comment/reply）、
 *   target_kind/target_id/target_name（哪个人设/插件）、comment_id、content（评论摘要）、read_at。
 * - 接口：notice_list（列表，看完自动标记已读）、notice_unread（只取未读数，用于红点）、
 *   notice_read（手动全部已读）。
 */
const DEKK_NOTICE_MAX = 100;

/** 建表（幂等，由 migrate_comments.php 调用）。 */
function dekk_notice_migrate(): void {
    sql('CREATE TABLE IF NOT EXISTS dekk_notices (
        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        user_id BIGINT UNSIGNED NOT NULL COMMENT "收件人",
        actor_id BIGINT UNSIGNED NOT NULL COMMENT "谁触发的",
        kind VARCHAR(10) NOT NULL COMMENT "comment / reply",
        target_kind VARCHAR(10) NOT NULL COMMENT "persona / plugin",
        target_id BIGINT UNSIGNED NOT NULL,
        target_name VARCHAR(120) NOT NULL DEFAULT "",
        comment_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
        content VARCHAR(200) NOT NULL DEFAULT "",
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        read_at TIMESTAMP NULL DEFAULT NULL,
        PRIMARY KEY (id),
        KEY idx_user (user_id, read_at, id),
        KEY idx_actor (actor_id, id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
}

/** 记一条消息（自己评论自己、或收件人就是操作者时跳过）。 */
function dekk_notice_push(int $userId, int $actorId, string $kind, string $targetKind,
                          int $targetId, string $targetName, int $commentId, string $content): void {
    if ($userId <= 0 || $userId === $actorId) return;
    $brief = mb_substr(trim($content), 0, 60, 'UTF-8');
    sql('INSERT INTO dekk_notices(user_id,actor_id,kind,target_kind,target_id,target_name,comment_id,content)
         VALUES(?,?,?,?,?,?,?,?)',
        [$userId, $actorId, $kind, $targetKind, $targetId, mb_substr($targetName, 0, 60, 'UTF-8'), $commentId, $brief]);
}

function dekk_notice_unread_count(int $userId): int {
    if ($userId <= 0) return 0;
    return (int)sql('SELECT COUNT(*) FROM dekk_notices WHERE user_id=? AND read_at IS NULL', [$userId])->fetchColumn();
}

/** 内容标题（生成消息时快照下来，避免列表里再查一次）。 */
function dekk_notice_target_name(string $kind, int $id): string {
    $table = $kind === 'persona' ? 'dekk_personas' : 'dekk_plugins';
    $row = sql('SELECT name FROM '.$table.' WHERE id=?', [$id])->fetch();
    return $row ? (string)$row['name'] : '内容';
}

function noticeRoute(string $action, array $a): void {
    // 未读数：只给登录用户（未登录返回 0，不报错，方便个人中心直接调用）
    if ($action === 'notice_unread') {
        $u = user(false);
        $count = $u ? dekk_notice_unread_count((int)$u['id']) : 0;
        respond(['ok' => true, 'unread' => $count]);
    }

    // 列表：拉取后把未读全部标成已读（抖音逻辑：点进消息中心红点就没了）
    if ($action === 'notice_list') {
        $u = user();
        $limit = max(1, min(50, (int)($_GET['limit'] ?? 30)));
        $rows = sql('SELECT n.id,n.actor_id,n.kind,n.target_kind,n.target_id,n.target_name,
                        n.comment_id,n.content,n.created_at,n.read_at,
                        u.nickname,u.avatar,u.sponsor
                     FROM dekk_notices n JOIN dekk_users u ON u.id=n.actor_id
                     WHERE n.user_id=? ORDER BY n.id DESC LIMIT '.$limit, [$u['id']])->fetchAll();
        $unread = dekk_notice_unread_count((int)$u['id']);

        $items = [];
        foreach ($rows as $row) {
            $items[] = [
                'id' => (int)$row['id'],
                'kind' => (string)$row['kind'],
                'target_kind' => (string)$row['target_kind'],
                'target_id' => (int)$row['target_id'],
                'target_name' => (string)$row['target_name'],
                'comment_id' => (int)$row['comment_id'],
                'content' => (string)$row['content'],
                'created_at' => (string)$row['created_at'],
                'read' => $row['read_at'] !== null,
                'actor_id' => (int)$row['actor_id'],
                'nickname' => (string)$row['nickname'],
                'sponsor' => (int)$row['sponsor'],
                'avatar_url' => dekk_avatar_url((int)$row['actor_id'], (string)$row['avatar']),
            ];
        }
        sql('UPDATE dekk_notices SET read_at=NOW() WHERE user_id=? AND read_at IS NULL', [$u['id']]);
        respond(['ok' => true, 'items' => $items, 'unread' => $unread, 'max' => DEKK_NOTICE_MAX]);
    }

    // 手动全部已读
    if ($action === 'notice_read') {
        $u = user();
        sql('UPDATE dekk_notices SET read_at=NOW() WHERE user_id=? AND read_at IS NULL', [$u['id']]);
        respond(['ok' => true, 'unread' => 0]);
    }
}