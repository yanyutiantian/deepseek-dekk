<?php
/**
 * 站长账号数据初始化（可重复执行，不影响其它账号的计数）：
 * 1) 所有用户头像还原成默认头像（清空 avatar 字段 + 删除已上传文件）；
 * 2) 站长账号（owner@example.com）的关注/粉丝/人设/点赞四个计数全部归零；
 * 3) 让现有所有用户都关注站长（站长不能取消关注由 profile.php 的 follow 逻辑保证）。
 * 用法：php setup_owner.php
 */
require __DIR__ . '/bootstrap.php';
$email='owner@example.com';
$owner=(int)sql('SELECT id FROM dekk_users WHERE email=?',[$email])->fetchColumn();
if($owner<=0){echo "owner not found: $email\n";exit(1);}

// 1) 头像归零
$files=glob(__DIR__ . '/uploads/avatar_*.jpg');
$removed=0;
foreach($files as $file){if(@unlink($file))$removed++;}
$cleared=sql("UPDATE dekk_users SET avatar='' WHERE avatar<>''")->rowCount();
echo "avatars cleared=$cleared files removed=$removed\n";

// 2) 站长四个计数归零
sql('UPDATE dekk_users SET follows=0,fans=0,personas=0,likes=0 WHERE id=?',[$owner]);
echo "owner counters zeroed (id=$owner)\n";

// 3) 所有人都关注站长（不改动任何展示计数）
$added=sql('INSERT IGNORE INTO dekk_follows(follower_id,target_id) SELECT id,? FROM dekk_users WHERE id<>?',[$owner,$owner])->rowCount();
$total=(int)sql('SELECT COUNT(*) FROM dekk_follows WHERE target_id=?',[$owner])->fetchColumn();
echo "followers added=$added total=$total\n";

$row=sql('SELECT id,email,nickname,avatar,follows,fans,personas,likes FROM dekk_users WHERE id=?',[$owner])->fetch();
echo json_encode($row,JSON_UNESCAPED_UNICODE)."\n";