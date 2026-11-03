<?php
// CLI only. Creates and removes its own isolated test account and plugin.
if (PHP_SAPI !== 'cli') exit;
require __DIR__ . '/bootstrap.php';
$uid=0;
function checkApi($action,$body=null,$token='') {
 $c=curl_init('https://dekk.example.com/API/?action='.$action);
 $headers=['Content-Type: application/json'];if($token!=='')$headers[]='Authorization: Bearer '.$token;
 curl_setopt_array($c,[CURLOPT_RETURNTRANSFER=>true,CURLOPT_TIMEOUT=>15,CURLOPT_HTTPHEADER=>$headers]);
 if($body!==null){curl_setopt($c,CURLOPT_POST,true);curl_setopt($c,CURLOPT_POSTFIELDS,json_encode($body));}
 $raw=curl_exec($c);$code=curl_getinfo($c,CURLINFO_RESPONSE_CODE);curl_close($c);
 $data=json_decode($raw,true);if(!is_array($data))throw new Exception('Invalid response');return [$code,$data];
}
function verify($ok,$name){if(!$ok)throw new Exception('FAIL '.$name);echo 'PASS '.$name.PHP_EOL;}
try {
 $email='plugin-test-'.bin2hex(random_bytes(8)).'@invalid.local';
 sql('INSERT INTO dekk_users(email,password_hash,nickname) VALUES(?,?,?)',[$email,password_hash(bin2hex(random_bytes(16)),PASSWORD_DEFAULT),'插件接口自动测试']);$uid=(int)$db->lastInsertId();
 $token=bin2hex(random_bytes(32));sql('INSERT INTO dekk_tokens(token_hash,user_id,expires_at) VALUES(?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))',[hash('sha256',$token),$uid]);
 $body=['name'=>'DEKK_TEST_'.bin2hex(random_bytes(4)),'description'=>'test','prompt'=>'test prompt','visibility'=>'private'];
 [$code,$r]=checkApi('save_plugin',$body,$token);verify($code===200&&($r['id']??0)>0,'upload private');$id=$r['id'];
 [$code,$r]=checkApi('plugin_download&id='.$id);verify($code===404,'guest denied private');
 [$code,$r]=checkApi('plugin_download&id='.$id,null,$token);verify($code===200&&$r['plugin']['prompt']==='test prompt','owner download');
 [$code,$r]=checkApi('plugin_mine',null,$token);verify($code===200&&count($r['items'])===1,'my uploads');
 $body['id']=$id;$body['visibility']='public';[$code,$r]=checkApi('save_plugin',$body,$token);verify($code===200,'edit public');
 [$code,$r]=checkApi('plugin_market&search='.rawurlencode($body['name']));verify($code===200&&count($r['items'])===1&&!isset($r['items'][0]['prompt']),'search and prompt privacy');
 [$code,$r]=checkApi('plugin_download&id='.$id);verify($code===200,'guest public download');
 [$code,$r]=checkApi('delete_plugin',['id'=>(string)$id],$token);verify($code===200,'delete');
 [$code,$r]=checkApi('plugin_download&id='.$id);verify($code===404,'deleted unavailable');
} finally {
 if($uid){sql('DELETE FROM dekk_plugins WHERE user_id=?',[$uid]);sql('DELETE FROM dekk_limits WHERE bucket=?',[hash('sha256','upload:'.$uid)]);sql('DELETE FROM dekk_users WHERE id=?',[$uid]);}
}
