<?php
declare(strict_types=1);
ini_set('display_errors','0');
try {
    require_once '<后端配置目录>/bootstrap.php';
    require_once '<后端配置目录>/auth.php';
    require_once '<后端配置目录>/review.php';
    require_once '<后端配置目录>/personas.php';
    require_once '<后端配置目录>/plugins.php';
    require_once '<后端配置目录>/profile.php';
    require_once '<后端配置目录>/comments.php';
    require_once '<后端配置目录>/notices.php';
    require_once '<后端配置目录>/items.php';
    require_once '<后端配置目录>/crypto.php';
     require_once '<后端配置目录>/backups.php';
    require_once '<后端配置目录>/binds.php';
    require_once '<后端配置目录>/sponsor.php';   // 赞助身份（sponsor_check）
    // ── 封禁闸门：命中封禁直接 403，连解密都不做（省 CPU）──
    // 关于 IP：本机架构是「系统 nginx(443) → 宝塔 nginx(442) → PHP」，
    // 实测后端拿到的 REMOTE_ADDR 已经是真实客户端 IP，所以只读 REMOTE_ADDR。
    // **绝不能**改读 X-Forwarded-For / X-Real-IP：实测客户端伪造 X-Real-IP: 8.8.8.8
    // 会原样透传到后端，靠它就等于把封禁交给攻击者控制。
    dekk_ban_gate();
    dekk_security_gc();   // 0.5% 概率清理过期 nonce / 过期封禁

    // ── 蜜罐（第一道）──
    // 必须放在 dekk_crypto_init() **之前**：非 v2 请求会在 crypto_init 处以 426
    // 直接退出，蜜罐放后面就永远执行不到（实测踩过这个坑）。
    //
    // 放在这里也天然零误伤：
    //   · 扫描器用的是普通 GET ?action=sql 这种写法 → URL 里能读到，命中；
    //   · 真实客户端的 action 藏在加密信封里，URL 里没有（或只是占位）→ 不命中；
    //   · 而词表里全是攻击者字典词，正常客户端不可能发出。
    $DEKK_HONEYPOT_ACTIONS=[
        'admin','admin_export','admin_login','admin_panel','all_users',
        'backup_all','backup_sql','config','config_get','db','db_backup','db_dump',
        'debug','dump','dump_users','env','export','export_users',
        'get_all_users','get_config','get_users','internal','internal_sync',
        'list_users','logs','mysql','phpinfo','query','shell','sql','sql_query',
        'system','test','user_dump','user_list','users','sqlmap','wp_admin',
    ];
    $dekkRawAction = (isset($_GET['action']) && is_string($_GET['action'])) ? $_GET['action'] : '';
    if ($dekkRawAction !== '' && in_array($dekkRawAction,$DEKK_HONEYPOT_ACTIONS,true)) {
        dekk_honeypot_hit('action',$dekkRawAction);   // 记录 → 封禁 24h → 404
    }

    dekk_crypto_init();
    $action=$_GET['action']??'health';
    if(!is_string($action))fail('接口不存在',404);
    $get=['plugin_market','plugin_mine','plugin_get','plugin_download','plugin_fetch','health','market','mine','download','me','profile_get','avatar','user_profile','comment_list','notice_list','notice_unread','item_get','backup_get','sponsor_check'];
    $post=['save_plugin','delete_plugin','send_code','register','login','password','nickname','logout','save_persona','delete_persona','profile_avatar','profile_delete','follow','comment_add','comment_delete','comment_pin','notice_read','backup_put','backup_delete','bind_code','bind_status'];
    if(!in_array($action,array_merge($get,$post),true))fail('接口不存在',404);
    $method=in_array($action,$get,true)?'GET':'POST';
    // V2 协议下客户端统一用 POST（参数已在加密层注入 $_GET/$_POST），
    // 所以带信封的请求即使命中老版本 GET 接口也放行，避免 405。
    $v2Post=(($_SERVER['REQUEST_METHOD']??'')==='POST')
        && class_exists('DekkCrypto') && DekkCrypto::$v2;
    if($_SERVER['REQUEST_METHOD']!==$method && !$v2Post){header('Allow: '.$method);fail('请求方法错误',405);}

    // ── 全局鉴权闸门 ──
    // 除「登录 / 注册 / 发验证码 / 改密码 / 健康检查 / 头像」外，所有接口都必须带有效 token。
    // 头像放行的原因：客户端用 <img> 直接加载 ?action=avatar&id=，图片请求带不了 Authorization 头。
    // 这些例外都自带限流，且不返回用户隐私字段。
    $DEKK_PUBLIC_ACTIONS = ['health','login','register','send_code','password','avatar'];
    if (!in_array($action, $DEKK_PUBLIC_ACTIONS, true)) {
        user();   // 无效 → 401「请登录」；过期 → 401「登录已过期」
    }
    if($action==='health')respond(['ok'=>true,'version'=>1]);
    $a=($method==='POST'||$v2Post)?input():[];
        authRoute($action,$a);personaRoute($action,$a);pluginRoute($action,$a);profileRoute($action,$a);commentRoute($action,$a);noticeRoute($action,$a);itemRoute($action,$a);backupRoute($action,$a);bindRoute($action,$a);sponsorRoute($action,$a);fail('接口不存在',404);
} catch(Throwable $e) {
    if(isset($db)&&$db->inTransaction())$db->rollBack();
    error_log('[DEKK API] '.get_class($e).' code='.$e->getCode());
    http_response_code(500);header('Content-Type: application/json; charset=utf-8');header('Cache-Control: no-store');
    echo '{"ok":false,"message":"服务暂时不可用"}';
}