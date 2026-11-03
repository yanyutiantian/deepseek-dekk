<?php
/**
 * 用户中心：资料读取、头像上传（服务端再压一次）、头像读取。
 * 头像文件放在 <后端配置目录>/uploads/，只能通过 ?action=avatar&id= 读取，
 * 不暴露真实路径，也不依赖 web 目录权限。
 */
/**
 * 头像地址：数据库里没记录、或文件已经不存在时返回空串，
 * 客户端会直接用内置默认头像（不会出现裂图）。
 */
/**
 * 管理员判定：只看 dekk_users.admin（0=普通用户，1=管理员），不再看邮箱。
 * 一个请求内缓存一次，多个地方用也不会反复查库。
 */
function dekk_admin_ids(): array {
    static $ids=null;
    if($ids===null){
        $ids=[];
        foreach(sql('SELECT id FROM dekk_users WHERE admin=1 ORDER BY id ASC')->fetchAll() as $row){
            $ids[]=(int)$row['id'];
        }
    }
    return $ids;
}

/** 头像原图允许的最大边长与总像素：用于防「压缩炸弹」。 */
const DEKK_AVATAR_MAX_SIDE = 2048;
const DEKK_AVATAR_MAX_PIXELS = 2048 * 2048;

/** 这个 uid 是不是管理员。 */
function dekk_is_admin($userId): bool {
    $id=(int)$userId;
    return $id>0 && in_array($id,dekk_admin_ids(),true);
}

/**
 * 站长 = id 最小的那个管理员：所有人默认关注 TA，且不允许取关（界面上的「站长」特例）。
 * 管理员可以有多个，站长的语义只影响默认关注与计数特例。
 */
function dekk_owner_id(): int {
    $ids=dekk_admin_ids();
    return $ids?$ids[0]:0;
}

function dekk_avatar_url(int $id,string $avatar): string {
    if($avatar!==''&&preg_match('/^avatar_[0-9]+_[0-9a-f]{8}\.jpg$/',$avatar)
            &&is_file(__DIR__ . '/uploads/'.$avatar)){
        return 'https://dekk.example.com/API/?action=avatar&id='.$id.'&v='.substr(md5($avatar),0,8);
    }
    // 没设头像或文件不在了：由服务端下发内置默认头像，客户端就不会出现空白头像
    return 'https://dekk.example.com/API/?action=avatar&id='.$id.'&default=1';
}

/** 关注数 / 粉丝数以关注关系表为准（四个数字里的前两个）。 */
function dekk_sync_counts(int $id): array {
    $follows=(int)sql('SELECT COUNT(*) FROM dekk_follows WHERE follower_id=?',[$id])->fetchColumn();
    $fans=(int)sql('SELECT COUNT(*) FROM dekk_follows WHERE target_id=?',[$id])->fetchColumn();
    sql('UPDATE dekk_users SET follows=?,fans=? WHERE id=?',[$follows,$fans,$id]);
    return [$follows,$fans];
}

function profileRoute(string $action,array $a): void {
    if ($action==='profile_get') {
        $u=user();
        $row=sql('SELECT id,email,nickname,admin,sponsor,avatar,follows,fans,personas,likes,created_at FROM dekk_users WHERE id=?',[$u['id']])->fetch();
        if(!$row)fail('账号不存在',404);
        $row['admin']=dekk_is_admin((int)$row['id']);
        // 赞助状态：客户端据此决定是否显示「登录」分类、是否让强开生效
        $row['sponsor']=(int)$row['sponsor'];
        // 「人设」以服务端真实数据为准，历史账号也没问题
        $real=(int)sql('SELECT COUNT(*) FROM dekk_personas WHERE user_id=?',[$u['id']])->fetchColumn();
        if($real>(int)$row['personas']&&(int)$row['id']!==dekk_owner_id()){
            sql('UPDATE dekk_users SET personas=? WHERE id=?',[$real,$u['id']]);
            $row['personas']=$real;
        }
        $row['avatar_url']=dekk_avatar_url((int)$row['id'],(string)$row['avatar']);
        list($row['follows'],$row['fans'])=dekk_sync_counts((int)$row['id']);
        respond(['ok'=>true,'profile'=>$row]);
    }
    if ($action==='profile_avatar') {
        $u=user();
        // 【收紧】上传头像单独计数（前缀 avatar_up:），与读取直链的 avatar:<ip> 桶分开。
        // 阈值从 60 秒 10 次收紧到 60 秒 3 次：
        // 每次解码最坏要吃约 16MB 内存（2048x2048），10 次/分钟的余量对「正常改头像」
        // 毫无意义，却是「压缩炸弹 / 内存耗尽」最便宜的放大器。3 次足够覆盖
        // 「选错图 → 重选 → 再试一次」的真实使用。
        limit('avatar_up:'.$u['id'],60,3);
        $data=text($a,'image',1,3000000);
        $comma=strpos($data,',');
        if($comma!==false)$data=substr($data,$comma+1);
        $raw=base64_decode($data,true);
        if($raw===false||strlen($raw)<64)fail('图片数据无效');
        if(strlen($raw)>2*1024*1024)fail('图片过大，请重新选择');
        // 防「压缩炸弹」：先只读文件头拿到尺寸，超限直接拒，绝不交给 GD 解码。
        // 一张几 KB 的高压缩 PNG 能解出上亿像素，imagecreatefromstring 会把内存吃光。
        $info=@getimagesizefromstring($raw);
        if($info===false||empty($info[0])||empty($info[1]))fail('图片格式不支持');
        // 【格式白名单】只接受 JPEG / PNG / WebP。
        // 排除 GIF（多帧）、BMP、ICO、SVG 等边缘格式：这些格式的解析路径更不可预测，
        // 历史上是「无上限内存分配 / 解析器崩溃」的常见来源，而头像根本不需要它们。
        $type=(int)($info[2]??0);
        if(!in_array($type,[IMAGETYPE_JPEG,IMAGETYPE_PNG,IMAGETYPE_WEBP],true)){
            fail('仅支持 JPG / PNG / WebP 图片');
        }
        $sw=(int)$info[0];$sh=(int)$info[1];
        if($sw<=0||$sh<=0||$sw>DEKK_AVATAR_MAX_SIDE||$sh>DEKK_AVATAR_MAX_SIDE
                ||$sw*$sh>DEKK_AVATAR_MAX_PIXELS)fail('图片尺寸过大，请重新选择');
        $image=@imagecreatefromstring($raw);
        if($image===false)fail('图片格式不支持');
        // 客户端已压过一次，这里再兜一次（最长边 512、JPEG 86）
        $w=imagesx($image);$h=imagesy($image);$max=max($w,$h);
        if($max>512){
            $scale=512/$max;$nw=max(1,(int)round($w*$scale));$nh=max(1,(int)round($h*$scale));
            $scaled=imagecreatetruecolor($nw,$nh);
            imagecopyresampled($scaled,$image,0,0,0,0,$nw,$nh,$w,$h);
            imagedestroy($image);$image=$scaled;
        }
        $dir=__DIR__ . '/uploads';
        if(!is_dir($dir)&&!mkdir($dir,0755,true)&&!is_dir($dir))fail('存储不可用',500);
        $name='avatar_'.$u['id'].'_'.substr(md5((string)microtime(true)),0,8).'.jpg';
        if(!imagejpeg($image,$dir.'/'.$name,86)){imagedestroy($image);fail('保存失败',500);}
        imagedestroy($image);
        $old=(string)sql('SELECT avatar FROM dekk_users WHERE id=?',[$u['id']])->fetchColumn();
        sql('UPDATE dekk_users SET avatar=? WHERE id=?',[$name,$u['id']]);
        if($old!==''&&$old!==$name&&preg_match('/^avatar_[0-9]+_[0-9a-f]{8}\.jpg$/',$old))@unlink($dir.'/'.$old);
        respond(['ok'=>true,'avatar'=>$name]);
    }
    if ($action==='avatar') {
        // 图片直链也要限流：否则可被拿来高频刷（每次请求都会查一次库）。
        // 阈值放宽，正常滚动列表不会触发。
        limit('avatar:'.($_SERVER['REMOTE_ADDR']??'unknown'),60,600);
        $id=(int)($_GET['id']??0);
        $name=(string)sql('SELECT avatar FROM dekk_users WHERE id=?',[$id])->fetchColumn();
        $path=__DIR__ . '/uploads/'.$name;
        if($name===''||!preg_match('/^avatar_[0-9]+_[0-9a-f]{8}\.jpg$/',$name)||!is_file($path)){
            // 没设头像 / 文件不存在：下发内置默认头像
            $fallback=__DIR__ . '/assets/default_avatar.png';
            if(!is_file($fallback))fail('头像不存在',404);
            header('Content-Type: image/png');
            header('Cache-Control: public, max-age=3600');
            header('Content-Length: '.filesize($fallback));
            readfile($fallback);
            exit;
        }
        header('Content-Type: image/jpeg');
        header('Cache-Control: public, max-age=86400');
        header('Content-Length: '.filesize($path));
        readfile($path);
        exit;
    }
    if ($action==='profile_delete') {
        $u=user();
        $old=(string)sql('SELECT avatar FROM dekk_users WHERE id=?',[$u['id']])->fetchColumn();
        sql('UPDATE dekk_users SET avatar=? WHERE id=?',['',$u['id']]);
        if($old!==''&&preg_match('/^avatar_[0-9]+_[0-9a-f]{8}\.jpg$/',$old))@unlink(__DIR__ . '/uploads/'.$old);
        respond(['ok'=>true]);
    }
    // 公开的创作者主页：任何人可看（登录后额外返回是否已关注）
    if ($action==='user_profile') {
        $id=(int)($_GET['id']??0);
        if($id<=0)fail('用户不存在',404);
        $row=sql('SELECT id,nickname,avatar,follows,fans,personas,likes,sponsor,created_at FROM dekk_users WHERE id=?',[$id])->fetch();
        if(!$row)fail('用户不存在',404);
        $row['avatar_url']=dekk_avatar_url((int)$row['id'],(string)$row['avatar']);
        list($row['follows'],$row['fans'])=dekk_sync_counts((int)$row['id']);
        $mine=user(false);
        // 站长账号：所有人默认已关注，且不能取关
        $following=$id===dekk_owner_id();
        $row['admin']=dekk_is_admin($id);
        // 赞助状态：客户端据此把创作者昵称标成灿金
        $row['sponsor']=(int)$row['sponsor'];
        if(!$following&&$mine){
            $following=(bool)sql('SELECT 1 FROM dekk_follows WHERE follower_id=? AND target_id=?',[$mine['id'],$id])->fetch();
        }
        // 该创作者的人设与插件：含未公开的（客户端会标注「未公开」），只给名称与简介
        // 列表带上作者信息，详情页从创作者主页点进去也不会显示“未知”
        $myId=$mine?(int)$mine['id']:0;
        // 只给 public；本人与管理员可以看全量。
        // 旧实现没有过滤，等于把别人「未公开」人设/插件的名称、简介、存在性都暴露了。
        $visSql=($myId===$id||dekk_is_admin($myId))?'':" AND p.visibility='public'";
        $personas=sql("SELECT p.id,p.user_id,p.name,p.description,p.visibility,p.created_at,u.nickname AS author,u.avatar AS author_avatar FROM dekk_personas p JOIN dekk_users u ON u.id=p.user_id WHERE p.user_id=?$visSql ORDER BY p.id DESC LIMIT 50",[$id])->fetchAll();
        $plugins=sql("SELECT p.id,p.user_id,p.name,p.description,p.visibility,p.created_at,u.nickname AS author,u.avatar AS author_avatar FROM dekk_plugins p JOIN dekk_users u ON u.id=p.user_id WHERE p.user_id=?$visSql ORDER BY p.id DESC LIMIT 50",[$id])->fetchAll();
        // 计数给真实条数（站长仍按之前的要求保持 0），否则创作者主页会显示 0 个人设
        $row['personas_count']=count($personas);
        $row['plugins_count']=count($plugins);
        if($id!==dekk_owner_id()){
            $row['personas']=count($personas);
        }
        respond(['ok'=>true,'profile'=>$row,'following'=>$following,'personas'=>$personas,'plugins'=>$plugins]);
    }
    // 关注 / 取关（自己的关注数与他人粉丝数同步增减）
    if ($action==='follow') {
        $u=user();limit('follow:'.$u['id'],60,30);
        $target=(int)($a['id']??0);
        $on=(int)($a['on']??0)===1;
        if($target<=0||$target===(int)$u['id'])fail('不能关注这个账号');
        if($target===dekk_owner_id()&&!$on)fail('无法取消关注该账号',403);
        if(!sql('SELECT id FROM dekk_users WHERE id=?',[$target])->fetch())fail('用户不存在',404);
        $exists=(bool)sql('SELECT 1 FROM dekk_follows WHERE follower_id=? AND target_id=?',[$u['id'],$target])->fetch();
        if($on&&$target===dekk_owner_id()){
            // 站长账号：所有人的关注都强制生效，但不改动展示计数
            if(!$exists)sql('INSERT INTO dekk_follows(follower_id,target_id) VALUES(?,?)',[$u['id'],$target]);
            respond(['ok'=>true,'following'=>true]);
        }
        if($on&&!$exists){
            // INSERT IGNORE + rowCount：并发下两个请求都通过 $exists 检查时，
            // 只有真正插进去的那个才计数 —— 既不 500，也不虚增粉丝数。
            $ins=sql('INSERT IGNORE INTO dekk_follows(follower_id,target_id) VALUES(?,?)',[$u['id'],$target]);
            if($ins->rowCount()>0){
                sql('UPDATE dekk_users SET follows=follows+1 WHERE id=?',[$u['id']]);
                sql('UPDATE dekk_users SET fans=fans+1 WHERE id=?',[$target]);
            }
        }elseif(!$on&&$exists){
            sql('DELETE FROM dekk_follows WHERE follower_id=? AND target_id=?',[$u['id'],$target]);
            sql('UPDATE dekk_users SET follows=GREATEST(follows-1,0) WHERE id=?',[$u['id']]);
            sql('UPDATE dekk_users SET fans=GREATEST(fans-1,0) WHERE id=?',[$target]);
        }
        respond(['ok'=>true,'following'=>$on]);
    }
}