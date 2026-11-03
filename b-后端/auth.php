<?php
// 站长/管理员判定在 profile.php 里（dekk_owner_id / dekk_is_admin）
require_once __DIR__ . '/profile.php';
function authRoute(string $action,array $a): void {
    global $db,$config;
    $ip=$_SERVER['REMOTE_ADDR']??'unknown';
    if ($action==='send_code') {
        $email=email($a); $purpose=text($a,'purpose',1,20);
        if (!in_array($purpose,['register','password'],true)) fail('验证码用途错误');
        limit('mail-ip:'.$ip,60,1); limit('mail-email:'.$email,60,1);
        limit('mail-day:'.$ip,86400,30);
        // 注册场景：邮箱已存在时不发码，但返回与成功完全一致的响应，
        // 避免用 register 的「邮箱已注册」错误批量枚举哪些 QQ 邮箱已注册。
        if ($purpose==='register'
                && sql('SELECT id FROM dekk_users WHERE email=? LIMIT 1',[$email])->fetch()) {
            respond(['ok'=>true,'expires_in'=>300,'retry_after'=>60]);
        }
        $code=(string)random_int(100000,999999); $hash=password_hash($code,PASSWORD_DEFAULT);
        sql('INSERT INTO dekk_codes(email,purpose,code_hash,expires_at,attempts) VALUES(?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 MINUTE),0) ON DUPLICATE KEY UPDATE code_hash=VALUES(code_hash),expires_at=VALUES(expires_at),attempts=0',[$email,$purpose,$hash]);
        try {
            require_once __DIR__ . '/vendor/autoload.php';
            $mail=new PHPMailer\PHPMailer\PHPMailer(true);
            $mail->isSMTP();$mail->Host='smtp.qq.com';$mail->SMTPAuth=true;
            $mail->Username=$config['smtp_user'];$mail->Password=$config['smtp_password'];
            $mail->SMTPSecure=PHPMailer\PHPMailer\PHPMailer::ENCRYPTION_SMTPS;$mail->Port=465;
            $mail->Timeout=15;$mail->CharSet='UTF-8';
            $mail->setFrom($config['smtp_user'],'DEKK');$mail->addAddress($email);
            $mail->Subject='DEKK 邮箱验证码';
            $mail->Body='您的'.($purpose==='register'?'注册':'修改密码').'验证码：'.$code.'，5分钟内有效。请勿向他人提供。如非本人操作请忽略。';
            $mail->send();
        } catch(Throwable $e) {
            sql('DELETE FROM dekk_codes WHERE email=? AND purpose=? AND code_hash=?',[$email,$purpose,$hash]);
            fail('邮件发送失败，请稍后重试',503);
        }
        respond(['ok'=>true,'expires_in'=>300,'retry_after'=>60]);
    }
    if ($action==='register') {
        limit('register:'.$ip,60,10);
        $email=email($a); $pw=password($a);$nick=trim(text($a,'nickname',1,30));$code=text($a,'code',6,6);
        if ($nick==='') fail('昵称不能为空');
        $hash=password_hash($pw,PASSWORD_DEFAULT);
        $db->beginTransaction();verifyCode($email,'register',$code);
        if(sql('SELECT id FROM dekk_users WHERE email=?',[$email])->fetch()) {$db->rollBack();fail('邮箱已注册',409);}
        sql('INSERT INTO dekk_users(email,password_hash,nickname) VALUES(?,?,?)',[$email,$hash,$nick]);$db->commit();
        // 新注册用户默认关注站长（id 最小的管理员）
        try {
            $owner=dekk_owner_id();
            $uid=(int)sql('SELECT id FROM dekk_users WHERE email=?',[$email])->fetchColumn();
            if($owner>0&&$uid>0&&$uid!==$owner){
                sql('INSERT IGNORE INTO dekk_follows(follower_id,target_id) VALUES(?,?)',[$uid,$owner]);
            }
        } catch(Throwable $e) { /* 关注失败不影响注册 */ }
        respond(['ok'=>true]);
    }
    if ($action==='login') {
        limit('login-ip:'.$ip,60,15);$email=email($a);$pw=text($a,'password',1,64);limit('login-email:'.$email,60,10);
        $u=sql('SELECT * FROM dekk_users WHERE email=?',[$email])->fetch();
        if (!$u || !password_verify($pw,$u['password_hash'])) fail('邮箱或密码错误',401);
        // 封禁检查：封禁中的账号直接拒绝登录，并顺手清掉它遗留的 Token。
        // 注意：banned_until 是 MySQL 的本地时间（+08:00），而 PHP 侧是 UTC，
        // 所以这里必须用 SQL 里的 NOW() 比较，不能用 strtotime()。
        if (!empty($u['banned_until'])) {
            $b=sql('SELECT (banned_until>NOW()) AS banned, banned_until FROM dekk_users WHERE id=?',[$u['id']])->fetch();
            if ($b && (int)$b['banned']===1) {
                sql('DELETE FROM dekk_tokens WHERE user_id=?',[$u['id']]);
                fail('该账号已被封禁，解封时间：'.$b['banned_until'].'，如有疑问请联系管理员',403);
            }
        }
        $token=bin2hex(random_bytes(32));
        sql('INSERT INTO dekk_tokens(token_hash,user_id,expires_at) VALUES(?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 7 DAY))',[hash('sha256',$token),$u['id']]);
        respond(['ok'=>true,'token'=>$token,'expires_in'=>604800,'user'=>['id'=>$u['id'],'email'=>$u['email'],'nickname'=>$u['nickname']]]);
    }
    if ($action==='password') {
        limit('password:'.$ip,60,10);$email=email($a);$pw=password($a);$code=text($a,'code',6,6);$hash=password_hash($pw,PASSWORD_DEFAULT);
        $db->beginTransaction();verifyCode($email,'password',$code);
        $u=sql('SELECT id FROM dekk_users WHERE email=? FOR UPDATE',[$email])->fetch();
        if(!$u){$db->rollBack();fail('账号不存在');}
        sql('UPDATE dekk_users SET password_hash=? WHERE id=?',[$hash,$u['id']]);
        sql('DELETE FROM dekk_tokens WHERE user_id=?',[$u['id']]);$db->commit();respond(['ok'=>true]);
    }
    if ($action==='me') respond(['ok'=>true,'user'=>user()]);
    if ($action==='nickname') {
        $u=user();limit('nickname:'.$u['id'],60,20);
        $name=trim(text($a,'nickname',1,30));if($name==='')fail('昵称不能为空');
        sql('UPDATE dekk_users SET nickname=? WHERE id=?',[$name,$u['id']]);respond(['ok'=>true]);
    }
    if ($action==='logout') {
        user();$token=substr($_SERVER['HTTP_AUTHORIZATION'],7);
        sql('DELETE FROM dekk_tokens WHERE token_hash=?',[hash('sha256',$token)]);respond(['ok'=>true]);
    }
}