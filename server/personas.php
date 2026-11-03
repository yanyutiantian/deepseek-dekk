<?php
// 审核依赖：放在这里保证任何入口文件（含网站根目录那份 index.php）都不会漏加载
require_once __DIR__ . '/review.php';

function personaRoute(string $action,array $a): void {
    if ($action==='market' || $action==='mine') {
        $page=max(1,min(10000,(int)($_GET['page']??1)));$offset=($page-1)*20;
        $args=[];$where="p.visibility='public'";
        if($action==='mine'){$u=user();$where='p.user_id=?';$args[]=$u['id'];}
        $search=$_GET['search']??'';
        if(!is_string($search)||mb_strlen($search)>100)fail('搜索过长');
        if($search!==''){$where.=' AND p.name LIKE ?';$args[]='%'.$search.'%';}
        // 公开人设列表：带上作者的 user_id 与头像，卡片/详情页要显示创作者
        $rows=sql("SELECT p.id,p.user_id,p.name,p.description,p.visibility,p.created_at,p.updated_at,u.nickname AS author,u.avatar AS author_avatar,u.sponsor AS author_sponsor FROM dekk_personas p JOIN dekk_users u ON u.id=p.user_id WHERE $where ORDER BY p.id DESC LIMIT 20 OFFSET $offset",$args)->fetchAll();
        respond(['ok'=>true,'items'=>$rows,'page'=>$page]);
    }
    if ($action==='download') {
        $id=(int)($_GET['id']??0);$u=user(false);$uid=$u?(int)$u['id']:0;
        $row=sql("SELECT p.*,u.nickname AS author,u.sponsor AS author_sponsor FROM dekk_personas p JOIN dekk_users u ON u.id=p.user_id WHERE p.id=? AND (p.visibility='public' OR p.user_id=?)",[$id,$uid])->fetch();
        if(!$row)fail('人设不存在或不可访问',404);
        respond(['ok'=>true,'persona'=>$row]);
    }
    if ($action==='save_persona') {
        $u=user();limit('upload:'.$u['id'],60,20);
        $name=trim(text($a,'name',1,100));$desc=text($a,'description',0,2000);$prompt=text($a,'prompt',1,20000);$visibility=text($a,'visibility',1,10);
        if($name===''||trim($prompt)===''||!in_array($visibility,['public','private'],true))fail('人设字段错误');
        if($visibility==='public')reviewOrFail('人设',$name,$desc,$prompt);
        $id=(int)($a['id']??0);
        if($id>0){
            if(!sql('SELECT id FROM dekk_personas WHERE id=? AND user_id=?',[$id,$u['id']])->fetch())fail('人设不存在',404);
            sql('UPDATE dekk_personas SET name=?,description=?,prompt=?,visibility=? WHERE id=? AND user_id=?',[$name,$desc,$prompt,$visibility,$id,$u['id']]);
        }else{
            sql('INSERT INTO dekk_personas(user_id,name,description,prompt,visibility) VALUES(?,?,?,?,?)',[$u['id'],$name,$desc,$prompt,$visibility]);
            global $db;$id=(int)$db->lastInsertId();
        }
        respond(['ok'=>true,'id'=>$id]);
    }
    if ($action==='delete_persona') {
        $u=user();$id=(int)($a['id']??0);
        sql('DELETE FROM dekk_personas WHERE id=? AND user_id=?',[$id,$u['id']]);respond(['ok'=>true]);
    }
}