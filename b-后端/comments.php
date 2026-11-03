<?php
declare(strict_types=1);
// dekk_owner_id() / dekk_avatar_url() 在 profile.php 里（独立引入，避免依赖入口的加载顺序）
require_once __DIR__ . '/profile.php';
require_once __DIR__ . '/notices.php';
/**
 * 人设 / 插件的评论（抖音式：支持回复、置顶、按权限删除、敏感词过滤）。
 *
 * 头衔只看「这条内容的作者」：
 *   - 管理员账号（dekk_users.admin=1）→ 管理员（任何评论区都是）；
 *   - 在**自己的**人设/插件评论区 → 创作者（相当于楼主）；
 *   - 其他人 → 用户（哪怕他自己也是创作者）。
 *
 * 权限：
 *   - 删评论：评论本人 / 内容作者 / 管理员；
 *   - 置顶：内容作者 / 管理员，且每个内容最多置顶 3 条。
 */
const DEKK_COMMENT_MAX_CHARS = 30;
const DEKK_COMMENT_MAX_PIN = 3;

/** 站点根目录 / 站点地址：特效文件就放在站点下的 /effect/ 里。 */
const DEKK_WEB_ROOT = '/var/www/dekk.example.com';
const DEKK_SITE_ORIGIN = 'https://dekk.example.com';

/** 不允许出现的脏话（命中即拒，可自行增删）。 */
const DEKK_COMMENT_BANNED = [
    '操你妈', '草泥马', '草你妈', '操你全家', '你妈死了', '死全家', '全家死光',
    '傻逼', '傻b', '煞笔', '沙比', '脑残', '脑瘫', '智障', '弱智',
    '贱人', '婊子', '母狗', '狗东西', '滚你妈', '尼玛', '你麻痹', '妈的', '他妈的',
    '去死吧', 'cnm', 'nmsl', 'sb东西',
];

/** 去掉空白/标点/符号并转小写，避免「傻 逼」「傻-逼」这类绕过。 */
function dekk_comment_flatten(string $text): string {
    $flat = preg_replace('/[\s\p{P}\p{S}]+/u', '', $text);
    return mb_strtolower($flat === null ? $text : $flat, 'UTF-8');
}

/** 命中脏话就拒掉。 */
function dekk_comment_check_words(string $text): void {
    $flat = dekk_comment_flatten($text);
    foreach (DEKK_COMMENT_BANNED as $bad) {
        $key = dekk_comment_flatten($bad);
        if ($key !== '' && mb_strpos($flat, $key) !== false) {
            fail('评论包含不当用语，请修改后再发');
        }
    }
}

/**
 * 评论输入框支持的「特效口令」：发这几个词不存评论，服务端直接下发特效地址，
 * 客户端在线播放（不落本地）。
 *
 * 每个特效可选三份文件（都在站点目录下，路径相对站点根）：
 *   anim  —— 带透明通道的动图（webp/apng），客户端叠在界面上播，不会有黑边；
 *   audio —— 音效（mp3）；
 *   video —— 整段视频（webm），老系统 / 动图解码失败时的兜底。
 * 哪个文件存在就下发哪个地址，一个都没有就当普通评论。
 */
const DEKK_COMMENT_EFFECTS = [
    'mj' => [
        'anim' => '/effect/mj.webp',
        'audio' => '/effect/mj.mp3',
        'video' => '/effect/mj.webm',
        'duration_ms' => 2773,
    ],
];

/** 命中特效口令就返回特效信息，否则返回 null。 */
function dekk_comment_effect(string $text): ?array {
    $key = strtolower(trim($text));
    if (!isset(DEKK_COMMENT_EFFECTS[$key])) return null;
    $spec = DEKK_COMMENT_EFFECTS[$key];
    $out = ['name' => $key];
    $found = false;
    foreach (['anim', 'audio', 'video'] as $slot) {
        $path = (string)($spec[$slot] ?? '');
        if ($path === '' || !is_file(DEKK_WEB_ROOT.$path)) continue;
        $out[$slot.'_url'] = DEKK_SITE_ORIGIN.$path;
        $found = true;
    }
    if (!$found) return null; // 文件都没上传就当普通评论处理
    // 兼容旧字段名：url 指向整段视频
    if (isset($out['video_url'])) $out['url'] = $out['video_url'];
    $out['duration_ms'] = (int)($spec['duration_ms'] ?? 3000);
    return $out;
}

/** 参数 kind 只允许 persona / plugin。 */
function dekk_comment_kind($value): string {
    $kind = strtolower(trim((string)$value));
    if ($kind !== 'persona' && $kind !== 'plugin') fail('参数错误');
    return $kind;
}

/** 这条内容的作者 id（顺带确认内容存在）。 */
function dekk_comment_target_owner(string $kind, int $id): int {
    $table = $kind === 'persona' ? 'dekk_personas' : 'dekk_plugins';
    $row = sql('SELECT user_id FROM '.$table.' WHERE id=?', [$id])->fetch();
    if (!$row) fail('内容不存在', 404);
    return (int)$row['user_id'];
}

/** 头衔：管理员（dekk_users.admin=1）> 创作者（本条内容的作者）> 用户。 */
function dekk_comment_title(int $userId, int $targetOwnerId): string {
    if (dekk_is_admin($userId)) return '管理员';
    if ($userId === $targetOwnerId) return '创作者';
    return '用户';
}

function dekk_comment_is_admin(array $user = null): bool {
    return $user !== null && dekk_is_admin((int)$user['id']);
}

const DEKK_COMMENT_SELECT = 'SELECT c.id,c.user_id,c.parent_id,c.pinned,c.reply_to_user,c.content,c.created_at,
     u.nickname,u.avatar,u.sponsor,r.nickname AS reply_to_name FROM dekk_comments c
     JOIN dekk_users u ON u.id=c.user_id LEFT JOIN dekk_users r ON r.id=c.reply_to_user';

/** 组装一条评论（含当前用户的权限标记）。 */
function dekk_comment_view(array $row, int $targetOwnerId, ?array $me, string $replyTo = ''): array {
    //回复某个人：服务端优先用 reply_to_user 的昵称
    if ($replyTo === '' && isset($row['reply_to_name']) && (int)($row['reply_to_user'] ?? 0) > 0) {
        $replyTo = (string)$row['reply_to_name'];
    }
    $userId = (int)$row['user_id'];
    $myId = $me ? (int)$me['id'] : 0;
    $isOwner = $myId > 0 && $myId === $targetOwnerId;
    $isAdmin = dekk_comment_is_admin($me);
    return [
        'id' => (int)$row['id'],
        'user_id' => $userId,
        'parent_id' => (int)$row['parent_id'],
        'nickname' => (string)$row['nickname'],
        'avatar_url' => dekk_avatar_url($userId, (string)$row['avatar']),
        'title' => dekk_comment_title($userId, $targetOwnerId),
        // 赞助用户：客户端把昵称渲染成灿金 + 扫光
        'sponsor' => (int)$row['sponsor'],
        'text' => (string)$row['content'],
        'created_at' => (string)$row['created_at'],
        'pinned' => (int)$row['pinned'] === 1,
        'reply_to' => $replyTo,
        'mine' => $myId > 0 && $userId === $myId,
        'can_delete' => $isAdmin || $isOwner || ($myId > 0 && $userId === $myId),
        'can_pin' => $isAdmin || $isOwner,
    ];
}

function commentRoute(string $action, array $a): void {
    // 列表：公开可看。顶层评论（置顶在前），每条带最多 20 条回复
    if ($action === 'comment_list') {
        $kind = dekk_comment_kind($_GET['kind'] ?? '');
        $id = (int)($_GET['id'] ?? 0);
        if ($id <= 0) fail('参数错误');
        $limit = max(1, min(50, (int)($_GET['limit'] ?? 30)));
        $ownerId = dekk_comment_target_owner($kind, $id);
        $me = user(false);
        $myId = $me ? (int)$me['id'] : 0;

        $total = (int)sql('SELECT COUNT(*) FROM dekk_comments WHERE kind=? AND target_id=?', [$kind, $id])->fetchColumn();
        $tops = sql(DEKK_COMMENT_SELECT.' WHERE c.kind=? AND c.target_id=? AND c.parent_id=0
                ORDER BY c.pinned DESC, c.id DESC LIMIT '.$limit, [$kind, $id])->fetchAll();

        $replies = [];
        $creatorReplied = [];
        if ($tops) {
            $ids = [];
            foreach ($tops as $row) $ids[] = (int)$row['id'];
            $marks = implode(',', array_fill(0, count($ids), '?'));
            $rows = sql(DEKK_COMMENT_SELECT.' WHERE c.parent_id IN ('.$marks.') ORDER BY c.id ASC', $ids)->fetchAll();
            foreach ($rows as $row) {
                $parent = (int)$row['parent_id'];
                if (count($replies[$parent] ?? []) < 20) $replies[$parent][] = $row;
                if ((int)$row['user_id'] === $ownerId) $creatorReplied[$parent] = true;
            }
        }

        $items = [];
        foreach ($tops as $row) {
            $cid = (int)$row['id'];
            $view = dekk_comment_view($row, $ownerId, $me);
            $view['creator_replied'] = isset($creatorReplied[$cid]);
            $list = [];
            foreach (($replies[$cid] ?? []) as $reply) {
                // 直接回复楼主时显示楼主昵称；回复某条回复时用 reply_to_user 对应的昵称
                $to = ((int)($reply['reply_to_user'] ?? 0) === 0) ? (string)$row['nickname'] : '';
                $list[] = dekk_comment_view($reply, $ownerId, $me, $to);
            }
            $view['replies'] = $list;
            $items[] = $view;
        }

        $pinned = (int)sql('SELECT COUNT(*) FROM dekk_comments WHERE kind=? AND target_id=? AND pinned=1', [$kind, $id])->fetchColumn();
        respond([
            'ok' => true,
            'count' => $total,
            'items' => $items,
            'pinned_count' => $pinned,
            'max_pin' => DEKK_COMMENT_MAX_PIN,
            'can_pin' => dekk_comment_is_admin($me) || ($myId > 0 && $myId === $ownerId),
        ]);
    }

    // 发表评论 / 回复：必须登录
    if ($action === 'comment_add') {
        $u = user();
        limit('comment:'.$u['id'], 10, 60);
        $kind = dekk_comment_kind($a['kind'] ?? '');
        $id = (int)($a['id'] ?? 0);
        if ($id <= 0) fail('参数错误');
        $parent = (int)($a['parent_id'] ?? 0);
        $text = trim((string)($a['text'] ?? ''));
        if ($text === '') fail('评论不能为空');
        if (mb_strlen($text, 'UTF-8') > DEKK_COMMENT_MAX_CHARS) fail('评论最多'.DEKK_COMMENT_MAX_CHARS.'字');
        dekk_comment_check_words($text);
        $ownerId = dekk_comment_target_owner($kind, $id);
        // 特效口令（如 mj / MJ）：不存评论、不发消息，直接告诉客户端播哪个特效
        $effect = dekk_comment_effect($text);
        if ($effect !== null) {
            respond(['ok' => true, 'effect' => $effect]);
        }
        // 抖音那种两级评论：回复“回复”时也平铺到同一条顶层评论下面，
        // 只把“被回复的人”记在 reply_to_user 里（这样二级回复能一直接着回复）。
        $replyToUser = 0;
        if ($parent > 0) {
            $target = sql('SELECT id,parent_id,user_id FROM dekk_comments WHERE id=? AND kind=? AND target_id=?',
                [$parent, $kind, $id])->fetch();
            if (!$target) fail('要回复的评论不存在', 404);
            if ((int)$target['parent_id'] > 0) {
                $replyToUser = (int)$target['user_id'];
                $parent = (int)$target['parent_id'];
            } else {
                $parent = (int)$target['id'];
            }
        }
        sql('INSERT INTO dekk_comments(kind,target_id,parent_id,reply_to_user,user_id,content) VALUES(?,?,?,?,?,?)',
            [$kind, $id, $parent, $replyToUser, (int)$u['id'], $text]);
        $commentId = (int)sql('SELECT LAST_INSERT_ID()')->fetchColumn();
        // 消息中心：通知内容作者；回复别人时再通知被回复的人（自己评论自己不通知）
        $targetName = dekk_notice_target_name($kind, $id);
        dekk_notice_push($ownerId, (int)$u['id'], $parent > 0 ? 'reply' : 'comment',
            $kind, $id, $targetName, $commentId, $text);
        if ($replyToUser > 0 && $replyToUser !== $ownerId) {
            dekk_notice_push($replyToUser, (int)$u['id'], 'reply', $kind, $id, $targetName, $commentId, $text);
        }
        $row = sql(DEKK_COMMENT_SELECT.' WHERE c.id=?', [$commentId])->fetch();
        respond(['ok' => true, 'comment' => dekk_comment_view($row, $ownerId, $u)]);
    }

    // 删除：评论本人 / 内容作者 / 管理员
    if ($action === 'comment_delete') {
        $u = user();
        limit('comment_del:'.$u['id'], 20, 60);
        $cid = (int)($a['id'] ?? 0);
        if ($cid <= 0) fail('参数错误');
        $row = sql('SELECT kind,target_id,user_id FROM dekk_comments WHERE id=?', [$cid])->fetch();
        if (!$row) fail('评论不存在', 404);
        $ownerId = dekk_comment_target_owner((string)$row['kind'], (int)$row['target_id']);
        $mine = (int)$row['user_id'] === (int)$u['id'];
        if (!$mine && !dekk_comment_is_admin($u) && (int)$u['id'] !== $ownerId) {
            fail('只能删除自己的评论', 403);
        }
        // 顶层评论连它的回复一起删，对应的消息也一起清掉
        $kids = sql('SELECT id FROM dekk_comments WHERE id=? OR parent_id=?', [$cid, $cid])->fetchAll();
        foreach ($kids as $kid) sql('DELETE FROM dekk_notices WHERE comment_id=?', [(int)$kid['id']]);
        sql('DELETE FROM dekk_comments WHERE id=? OR parent_id=?', [$cid, $cid]);
        respond(['ok' => true]);
    }

    // 置顶 / 取消置顶：内容作者或管理员，最多 3 条
    if ($action === 'comment_pin') {
        $u = user();
        limit('comment_pin:'.$u['id'], 30, 60);
        $cid = (int)($a['id'] ?? 0);
        $on = (int)($a['on'] ?? 0) === 1;
        if ($cid <= 0) fail('参数错误');
        $row = sql('SELECT kind,target_id,parent_id,pinned FROM dekk_comments WHERE id=?', [$cid])->fetch();
        if (!$row) fail('评论不存在', 404);
        $ownerId = dekk_comment_target_owner((string)$row['kind'], (int)$row['target_id']);
        if (!dekk_comment_is_admin($u) && (int)$u['id'] !== $ownerId) fail('只有作者或管理员可以置顶', 403);
        if ($on && (int)$row['parent_id'] !== 0) fail('只能置顶顶层评论');
        if ($on && (int)$row['pinned'] !== 1) {
            $count = (int)sql('SELECT COUNT(*) FROM dekk_comments WHERE kind=? AND target_id=? AND pinned=1',
                [$row['kind'], (int)$row['target_id']])->fetchColumn();
            if ($count >= DEKK_COMMENT_MAX_PIN) fail('最多置顶 '.DEKK_COMMENT_MAX_PIN.' 条评论');
        }
        sql('UPDATE dekk_comments SET pinned=? WHERE id=?', [$on ? 1 : 0, $cid]);
        respond(['ok' => true, 'pinned' => $on]);
    }
}