<?php
/**
 * 用户中心迁移（可重复执行）：
 * 1) dekk_users 增加 avatar / follows / fans / personas / likes 字段；
 * 2) 把指定邮箱的四个展示计数设为 1000。
 * 用法：php migrate_profile.php [邮箱]
 */
require __DIR__ . '/bootstrap.php';
$email=$argv[1]??'owner@example.com';
$columns=[];
foreach(sql('SHOW COLUMNS FROM dekk_users')->fetchAll() as $row)$columns[$row['Field']]=true;
$add=[
    'avatar'=>"varchar(120) NOT NULL DEFAULT ''",
    'follows'=>'int NOT NULL DEFAULT 0',
    'fans'=>'int NOT NULL DEFAULT 0',
    'personas'=>'int NOT NULL DEFAULT 0',
    'likes'=>'int NOT NULL DEFAULT 0',
];
foreach($add as $name=>$ddl){
    if($columns[$name]??false){echo "exists: $name\n";continue;}
    sql("ALTER TABLE dekk_users ADD COLUMN $name $ddl");
    echo "added: $name\n";
}
$user=sql('SELECT id,email,nickname FROM dekk_users WHERE email=?',[$email])->fetch();
if(!$user){echo "account not found: $email\n";exit(1);}
sql('UPDATE dekk_users SET follows=?,fans=?,personas=?,likes=? WHERE id=?',[1000,1000,1000,1000,$user['id']]);
// 关注关系表（详情页的「关注」按钮）
sql('CREATE TABLE IF NOT EXISTS dekk_follows (
        follower_id BIGINT UNSIGNED NOT NULL,
        target_id BIGINT UNSIGNED NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY(follower_id,target_id),
        INDEX(target_id)
     ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
echo "table ready: dekk_follows\n";
$row=sql('SELECT id,email,nickname,follows,fans,personas,likes,avatar FROM dekk_users WHERE id=?',[$user['id']])->fetch();
echo "updated: id={$row['id']} email={$row['email']} nick={$row['nickname']} "
    ."follows={$row['follows']} fans={$row['fans']} personas={$row['personas']} likes={$row['likes']}\n";